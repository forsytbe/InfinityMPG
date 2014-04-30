package com.devsyte.infinitympg;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.devsyte.infinitympg.obdService.IncomingHandler;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.format.Time;
import android.util.Log;
import android.widget.ArrayAdapter;

public class ConnectedService extends Service {

	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	static final int REGISTER_DEVICE = 3;
	static final int CONNECT_DEVICE = 4;
	static final int SEND_OBD = 5;
	static final int SEND_SCHEDULED_OBD = 6;
	static final int ADD_COMMAND = 7;
	static final int CLEAR_COMMANDS = 8;
	static final int TRACK_MPG = 9;
	static final int CLEAR_TRIP_DATA = 10;

	public double vSpeed = 0; // vehicle speed in km/h
	public double MAF = 0; // mass air flow, g/s
	public double MPG = 0; // miles/gallon
	
	private double currSUM = 0.0;
	private long currNDP = 0L;
	private double runningMpgAvg = 0.0;
	
	private boolean trackingMPG;

	private SharedPreferences prefs;
	private Editor pedit;

	private NotificationManager mNotifyMgr = null;
	private int mNotificationId = 001;

	
	private Time startTime = new Time();
	/* cmdPrompt is primarily for debugging purposes, tracks all communication between the ELM327 and the application */
	protected ArrayList<String> cmdPrompt = new ArrayList<String>();

	protected PollingThread mpThread;
	
	private BluetoothAdapter mBluetoothAdapter;
	private BluetoothSocket mmSocket;
	private InputStream mmInStream;
	private OutputStream mmOutStream;
	
	protected JSONObject tripObj = new JSONObject();
	protected JSONArray mpgData = new JSONArray();
	
	protected Object syncToken;
	
	protected ArrayList<String> obdCommands = new ArrayList<String>();
	
	protected ArrayList<Messenger> mClients = new ArrayList<Messenger>();

	protected NotificationManager nm;
	protected Timer timer = null;
	protected Messenger servMessenger;

	private String devAddress;
	
	private long pollInterval;
	
	private static volatile boolean isRunning = false;
	
	public static boolean isRunning(){
		return isRunning;
	}	

	@Override
	public IBinder onBind(Intent intent) {

		return servMessenger.getBinder();
	}

	private class ServIncomingHandlerThread extends HandlerThread{
		
		ServIncomingHandlerThread(){
			super("ServIncomingHandlerThread");
			start();
			servMessenger =	new Messenger(new ServIncomingHandler(getLooper()));			
		}
	}

	
	public class ServIncomingHandler extends Handler { 
		
		public ServIncomingHandler(Looper looper){
			super(looper);
		}
		
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				Log.i("MyService", "Service:Client Registered!");

				mClients.add(msg.replyTo);
				Message mess = new Message();
				mess.what = obdService.SERVICE_REGISTERED;
				sendMsgToClients(mess);
				break;
			case MSG_UNREGISTER_CLIENT:
				Log.i("MyService", "Service:Client Removed!");

				mClients.remove(msg.replyTo);
				break;

			case CONNECT_DEVICE:
				devAddress = msg.getData().getString("btdev_address");
				connectDevice(devAddress);
				configDevice();
				break;
			case ADD_COMMAND:
				trackingMPG = false;
				obdCommands.add(msg.getData().getString("command"));
				break;
			case CLEAR_COMMANDS:
				trackingMPG = false;
				obdCommands.clear();
				break;
			case CLEAR_TRIP_DATA:
				pedit = prefs.edit();
				pedit.putFloat("avgMPG", 0.0f);
				pedit.putFloat("currSUM", 0.0f);
				pedit.putLong("currNDP", 1l);
				pedit.apply();
				break;
			case SEND_OBD:
				Log.i("MyService", "Service Sending OBD!");
				trackingMPG = false;
				if(mpThread != null){
					mpThread.interrupt();
					mpThread = null;
				}
				try{
					pollDevice(obdCommands.get(0));
				}catch(InterruptedException ex){}
				break;
			case TRACK_MPG:
				Log.i("MyService", "Service Starting Tracking!");

				pedit = prefs.edit();

				pedit.putBoolean("isFirstTrip", false);
				pedit.apply();
				trackingMPG = true;
				obdCommands.clear();
				obdCommands.add("01 0D\r");
				obdCommands.add("01 10\r");
				startTime.setToNow();
			case SEND_SCHEDULED_OBD:
				if(mpThread != null){
					mpThread.interrupt();
					mpThread = null;
				}
				mpThread = new PollingThread();
				
				Log.i("MyService", "Service Sending SCHEDULED_OBD!");

				pollInterval = msg.getData().getLong("poll_interval");
				mpThread.start();

				break;
			default:
				super.handleMessage(msg);
			}
		}
	}

	public void sendMsgToClients(Message msg){
		for(int i = 0; i <mClients.size(); ++i){
			try {
				mClients.get(i).send(msg);
			} catch (RemoteException e) {
				mClients.remove(i);
			}
		}
	}
	
	public void connectDevice(String address) {
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);

		BluetoothSocket tmp = null;
		try {
			Method m = device.getClass().getMethod("createRfcommSocket",
					new Class[] { int.class });
			tmp = (BluetoothSocket) m.invoke(device, 1);
			Log.i("MyService", "OBD Socket Assigned.");


		} catch (Exception e) {
		}
		
		mmSocket = tmp;
		try {
			mmSocket.connect();
			Message message = Message.obtain(null,
					obdService.CONNECT_SUCCESS, 0, 0);

			sendMsgToClients(message);

			
			Log.i("MyService", "Connected!");
			mmInStream = mmSocket.getInputStream();
			mmOutStream = mmSocket.getOutputStream();
	 

		} catch (IOException connectException) {
			Log.i("MyService", "Failed to Connect");

			Message message = Message.obtain(null,
					obdService.CONNECT_FAILED, 0, -1);

			sendMsgToClients(message);

			try {
				mmSocket.close();
				mmSocket = null;
			} catch (IOException closeException) {
			}
		}
	}
	
	@Override
	public void onCreate() {
		Log.i("MyService", "Service Started.");
		showNotification();
		syncToken = new Object();
		isRunning = true;
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		ServIncomingHandlerThread mt = new ServIncomingHandlerThread();

	}

	private void showNotification() {

		// In this sample, we'll use the same text for the ticker and the
		// expanded notification
		CharSequence text = getText(R.string.service_started);
		// Set the icon, scrolling text and timestamp
		NotificationCompat.Builder mBuilder =  
				new NotificationCompat.Builder(this).setSmallIcon(R.drawable.infinity)
				.setContentTitle(getText(R.string.service_label))
				.setContentText(text);

		// The PendingIntent to launch our activity if the user selects this
		// notification
		PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
				new Intent(this, MainActivity.class), 0);
		// Set the info for the views that show in the notification panel.
		mBuilder.addAction(R.drawable.infinity, text, contentIntent);
		// Gets an instance of the NotificationManager service
		mNotifyMgr = 
		        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		// Builds the notification and issues it.
		mNotifyMgr.notify(mNotificationId, mBuilder.build());
		// Send the notification.
		// We use a layout id because it is a unique number. We use it later
		// to cancel.
	}

	public void configDevice(){

		if(mmSocket != null){
			byte[] buffer = new byte[128];
			int bytes = 0;
			String obdCommand="", tmp = "", reply = "";
			try {
				obdCommand = "AT WS\r";
				cmdPrompt.add(obdCommand);
				
				mmOutStream.write(obdCommand.getBytes());
				try {
					Thread.sleep(200);
				} catch (InterruptedException e) {

				}
				while(reply.indexOf(">") == -1  && !reply.contains(obdCommand)){
					bytes = mmInStream.read(buffer); // this throws an exception as
					tmp = new String(buffer,0, bytes);
					reply+= tmp;
					cmdPrompt.add(reply);
				}
				Log.i("MyService", "Device Configured:"+reply);

	/*
				obdCommand = "AT SPA3\r";
				cmdPrompt.add(obdCommand);

				mmOutStream.write(obdCommand.getBytes());
	
				bytes += mmInStream.read(buffer);
				cmdPrompt.add(buffer.toString());
	
				obdCommand = "AT E0\r";
				cmdPrompt.add(obdCommand);

				mmOutStream.write(obdCommand.getBytes());
	
				bytes += mmInStream.read(buffer);
				cmdPrompt.add(buffer.toString());
	*/

	
			} catch (IOException e) {
				Message msg = Message.obtain(null, obdService.CONNECT_FAILED,0, 0);
				sendMsgToClients(msg);
			}
		}
	}
	
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.i("MyService", "Received start id " + startId + ": " + intent);
		
		prefs = PreferenceManager.getDefaultSharedPreferences(this.getApplicationContext());
		
		
		runningMpgAvg = prefs.getFloat("avgMPG", 0.0f);
		currSUM = prefs.getFloat("currSUM", 0.0f);
		currNDP = prefs.getLong("currNDP", 1l);
		return START_STICKY; // run until explicitly stopped.
	}

	protected void restartDevice(){
		
		if(mpThread != null){
			mpThread.interrupt();
			mpThread = null;
		}
		configDevice();
		mpThread = new PollingThread();
		mpThread.start();
	}
	
	protected void trackMpg(Message calcMessage, Bundle bundle, String reply){
		int byteOne, byteTwo;
		
		String tmpStr = reply.substring(3);
		Log.i("MyService", "Polling: In tracking");

		if (reply.indexOf("0D")==0) {
			byteOne = Integer.parseInt(tmpStr, 16);
			vSpeed = byteOne;
		} else if (reply.indexOf("10")==0) {
			
			byteOne = Integer.parseInt(
					tmpStr.substring(0, tmpStr.indexOf(" ")), 16);
			byteTwo = Integer.parseInt(
					tmpStr.substring(tmpStr.indexOf(" ") + 1), 16);
			MAF = (((double) byteOne * 256.0) + (double) byteTwo) / 100.0;

			if (vSpeed <= 0.5) {

				MPG = (MAF * obdService.stoichRatio * 3600.0)
						/ obdService.gramGasToGal; // gallons per hour, MAF is gram/second
				bundle.putBoolean("isIdle", true);
				currSUM += 0.0;

			} else {


				// miles pergallon, vspeed is in km/hr, MAF is in
				// grams/seconds
				MPG = (vSpeed * obdService.kmToMi)
						/ ((MAF * obdService.stoichRatio * 3600.0) / obdService.gramGasToGal);
				bundle.putBoolean("isIdle", false);
				currSUM += MPG;

			}
		
			++currNDP;
			runningMpgAvg = currSUM/currNDP;
			
			bundle.putDouble("avgMPG", runningMpgAvg);
			bundle.putDouble("mpg", MPG);


			
			Time now = new Time();
			now.setToNow();
			String timestamp = Integer.toString(now.hour)
					+":"+ Integer.toString(now.minute)
					+":"+ Integer.toString(now.second);
			
			JSONObject currData = new JSONObject();
			try {
				currData.put(timestamp, obdService.df.format(runningMpgAvg));
			} catch (JSONException e) {

			}
			mpgData.put(currData);
			Log.i("MyService", "Polling: Finished tracking");
			
			calcMessage.setData(bundle);
			sendMsgToClients(calcMessage);

		}else{
			calcMessage = Message.obtain(null,
					obdService.RETURNED_BYTES, 0, -1);
							
			bundle.putString("ret_bytes", tmpStr);
			
		}
		
	}
	
	protected void pollDevice(String command) throws InterruptedException{
		Log.i("MyService", "Polling Device");

		byte[] buffer = new byte[128];
		int bytes = 0;
		String tmpStr = new String();
		Bundle bundle = new Bundle();
		Message calcMessage = new Message();
		String reply = "";
		String tmp;
		try {
				
			mmOutStream.write(command.getBytes());
			cmdPrompt.add(command);


			Thread.sleep(100);
			do {

				bytes = mmInStream.read(buffer);
				tmp = new String(buffer,0, bytes);
				reply += tmp;
				Log.i("MyService", "Collecting Instream:" + reply);
				if(reply.indexOf("41") != -1){
					reply = reply.substring(reply.indexOf("41"));
				}
				if(reply.contains("NO DATA")){
					restartDevice();
				}

			} while (reply.indexOf(">") == -1 || reply.indexOf("41") == -1); 
			//this hardcoded 2 for the length should avoid just reading the '?>'prompt
			Log.i("MyService", "Finished Collecting Instream");

			cmdPrompt.add(reply);

			reply = reply.substring(reply.indexOf("41")+3,
					reply.indexOf(">")-3);


			Log.i("MyService", "Polling: About to Track, trackingMPG = " + trackingMPG);

			if(trackingMPG){
				calcMessage = Message.obtain(null,
						obdService.RETURNED_MPG, 0, -1);
				trackMpg(calcMessage, bundle, reply);
			}else{
				tmpStr = reply.substring(3);
				calcMessage = Message.obtain(null,
						obdService.RETURNED_BYTES, 0, -1);
								
				bundle.putString("ret_bytes", tmpStr);
				calcMessage.setData(bundle);
				sendMsgToClients(calcMessage);

			}


			Log.i("MyService", "Polling: Sent Message");

		}catch(InterruptedException ex){
			throw new InterruptedException();
		
		} catch (Exception e) { // you should always ultimately catch all
			
			Log.i("MyService", "Polling Failed: " + e.getMessage());
		}
	}
	
	
	public void writeMpgData() {
		
		File file = new File(Environment.getExternalStorageDirectory(),
				"mpg_data.json");
		try {
			
			BufferedWriter bW;
			bW = new BufferedWriter(new FileWriter(file, true));
			
			Time endTime = new Time();
			endTime.setToNow();

			try {
				tripObj.put("End_Time", endTime.format("%Y:%m:%d %H:%M:%S"));
				tripObj.put("Mpg_Data_List", mpgData);
				tripObj.put("Start_Time", startTime.format("%Y:%m:%d %H:%M:%S"));

			} catch (JSONException e) {
				
			}

			try {
				bW.write(tripObj.toString(4));
			} catch (JSONException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
			bW.newLine();
			bW.flush();
			bW.close();
			Log.i("MyService", "Wrote stream data.");
			MediaScannerConnection.scanFile(this.getApplicationContext(),
					new String[] { file.toString() }, null,
					new MediaScannerConnection.OnScanCompletedListener() {
						public void onScanCompleted(String path, Uri uri) {

						}
					});

		} catch (IOException e) {
			Log.i("MyService", "Stream Data writing failed: "+ e.getMessage());

		}

	}
	
	/*  simply write the Command Prompt to a file, "ELM327comm_data.txt" */
	public void writeCommsToFile() {

		File file = new File(Environment.getExternalStorageDirectory(),
				 "ELM327comm_data.txt");

		String str = "";
		try {
			BufferedWriter bW;

			bW = new BufferedWriter(new FileWriter(file, true));
			if (cmdPrompt.size() > 0) {
				for (int i = 0; i < cmdPrompt.size(); ++i) {
					str = str.concat(cmdPrompt.get(i));
				}
				cmdPrompt.clear();

				bW.write(str);
				bW.newLine();
				bW.flush();
				bW.close();
				Log.i("MyService", "Comms written");
				MediaScannerConnection.scanFile(this.getApplicationContext(),
						new String[] { file.toString() }, null,
						new MediaScannerConnection.OnScanCompletedListener() {
							public void onScanCompleted(String path, Uri uri) {

							}
						});
			}

		} catch (IOException e) {
			Log.i("MyService", "Comm writing failed: "+ e.getMessage());

		}

	}

	/*
	 * writeAvgData():
	 * 
	 * This function writes appends the average for the most recent trip to the
	 * file "mpg_avgs.json"
	 */
	public void writeAvgData() {
		File file = new File(Environment.getExternalStorageDirectory(),
				"mpg_avgs.json");

		try {
			BufferedWriter bW;
			runningMpgAvg = currSUM / currNDP;
			Time endTime = new Time();
			endTime.setToNow();
			
			bW = new BufferedWriter(new FileWriter(file, true));

			JSONObject avgObj = new JSONObject();
			try {
				avgObj.put("End_Time", endTime.format("%Y:%m:%d %H:%M:%S"));

				avgObj.put("Trip_Average", obdService.df.format(runningMpgAvg));
				avgObj.put("Start_Time", startTime.format("%Y:%m:%d %H:%M:%S"));

			} catch (JSONException e) {

			}


			try {
				bW.write(avgObj.toString(4));
			} catch (JSONException e) {

			}
			bW.newLine();
			bW.flush();
			bW.close();
			Log.i("MyService", "Average Mpg written");

			MediaScannerConnection.scanFile(this.getApplicationContext(),
					new String[] { file.toString() }, null,
					new MediaScannerConnection.OnScanCompletedListener() {
						public void onScanCompleted(String path, Uri uri) {

						}
					});
		} catch (IOException e) {
			Log.i("MyService", "Average writing failed: "+ e.getMessage());

		}

	}
	
	/*
	 * writeMpgData():
	 * 
	 * Writes the last X number of time-stamped data points **IN MILES PER
	 * GALLON**, to the file "mpg_data.json",
	 */

	@Override
	public void onDestroy() {
		super.onDestroy();
		pedit = prefs.edit();
		pedit.putFloat("avgMPG", (float)runningMpgAvg).apply();
		pedit.putFloat("currSUM", (float) currSUM);
		pedit.putLong("currNDP", currNDP);
		pedit.apply();
		isRunning = false;
		if(mpThread != null){
			mpThread.interrupt();
			mpThread = null;
		}
		if(mmSocket != null){
			try {
				mmSocket.close();
				mmSocket = null;
			} catch (IOException e) {
	
			}					
		}

		if(mNotifyMgr!=null){
			mNotifyMgr.cancel(mNotificationId);
		}
		if(trackingMPG){
			writeMpgData();
			writeAvgData();
			writeCommsToFile();
		}

		Log.i("MyService", "Service Stopped.");


		
	}

	protected class PollingThread extends Thread{
		
		public void run(){
			try {
				while(isRunning()){
					for(int i = 0; i < obdCommands.size(); ++i){
						pollDevice(obdCommands.get(i));
					}
					
				}
			} catch (InterruptedException e) {
				return;
			}
		}
		
	}
};



