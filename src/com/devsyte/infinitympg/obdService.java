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
import java.util.Timer;
import java.util.TimerTask;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ServiceConnection;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.format.Time;
import android.util.Log;
import android.widget.ArrayAdapter;

/*  This is the primary class for establishing, and maintaining the ELM327 Bluetooth Connection,
 *  As well as polling for data and the computations that need to be performed on that data.
 *  
 *  This class is essentially a wrapper around the actual Service, to allow for simple, isolated
 *  use in the MainActivity.  The user must simply create an obdService object, then obdService.connect(),
 *  then obdService.startMpgTracking(), in order to initiate a connection and begin streaming.
 *  
 *  Alternatively, calling sendOBDCommand allows the user to issue a single request and receive a single response,
 *  or by specifiying and interval period (amount of time between calls), sendOBDCommand can also begin a recurring 
 *  command to be sent to the device.
 *  
 *  *OBD COMMANDS (also known as Parameter Id's, or PID's) can be found here: http://en.wikipedia.org/wiki/OBD-II_PIDs
 *  *Not all vehicles support all commands, and there may also be manufacturer specific commands not listed.
 *  
 *  The threads involved communicate with the MainActivity via the handler that is passed to the
 *  obdService constructor
 *   
 *   */
public class obdService {
	BluetoothAdapter mBluetoothAdapter;
	ConnectThread mConnectThread;
	ConnectedThread mConnectedThread;

	Context parentContext;

	/* cmdPrompt is primarily for debugging purposes, tracks all communication between the ELM327 and the application */
	protected ArrayAdapter<String> cmdPrompt;
	/* mpgDataList is simply a temporary array to hold the data to prevent constantly writing to a file*/
	protected ArrayList<String> mpgDataList = new ArrayList<String>();

	/*This is the array the is home to the OBD2 PID's we want to send.  They will be sent in order.  This needs to be watched,
	 * currently the only support for multi-PID tracking is via startMpgTracking.  
	 * 
	 * TODO:  Find a *good* way to allow the user to submit a list of commands to issue, and then view them simultaneously,
	 *  or better, allow them to submit some kind of heuristics to combine the these arbitrary commands
	 *  */
	protected ArrayList<String> obdCommands;

	private BluetoothSocket mmSocket;
	private InputStream mmInStream;
	private OutputStream mmOutStream;

	private String obdCommand = "";
	private String reply = "";

	protected boolean isBound = false;
	protected boolean isRunning = false;
	protected Messenger mService = null;
	
	/*This is the messenger that will allow us to send interpreted data to the MainActivity */
	private Messenger uiMessenger = null;
	
	
	final Messenger mMessenger = new Messenger(new IncomingHandler());
	ArrayList<Messenger> mClients = new ArrayList<Messenger>();
	
	/*
	 * Just some useful constants for unit conversion
	 */
	public static final double gramGasToGal = 2835.0;
	public static final double gramGasToImpGal = 3410.0;
	public static final double gramGasToLiter = 750.0;
	public static final double literGasToGal = 0.264172;
	public static final double galGasToImpGal = 0.832674;
	public static final double kmToMi = .621371;
	public static final double miToKm = 1.60934;
	public static final double stoichRatio = (1.0 / 14.7);


	int mValue = 0; // Holds last value set by a client.
	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	static final int MSG_SET_INT_VALUE = 3;
	static final int MSG_SET_STRING_VALUE = 4;
	static final int SEND_OBD = 5;
	static final int SEND_SCHEDULED_OBD = 6;
	static final int SET_MODE = 7;
	static final int RETURNED_MPG = 8;
	static final int RETURNED_IDLE_MPG = 9;
	static final int RETURNED_BYTES = 10;
	static final int CONNECT_FAILED = 11;
	static final int CONNECT_INTERRUPT = 12;
	
	public double vSpeed = 0; // vehicle speed in km/h
	public double MAF = 0; // mass air flow, g/s
	public double MPG = 0; // miles/gallon

	/* currSum and currNDP are the primary means of tracking long term trips.
	 * 
	 * 
	 */
	private double currSUM = 0.0;
	private long currNDP = 0L;
	private double runningMpgAvg = 0.0;

	
	private DecimalFormat df = new DecimalFormat("#.##");


	private SharedPreferences prefs;

	/*  These functions are called to convert from the standard units (MPG, which everything is saved as),
	 *  and the units specified by user preferences.
	 * 
	 * */
	private double prefConversion(double mpgData) {
		double convertedVal = 0;
		String unitOutput = prefs.getString("units_pref", "MPG");
		if (unitOutput.equals("MPG")) {
			convertedVal = mpgData;

		} else if (unitOutput.equals("L/100KM")) {
			convertedVal = 235.2 / mpgData;

		} else if (unitOutput.equals("MPG(UK)")) {
			convertedVal = mpgData * 1.201;

		}

		return convertedVal;
	}
	private double idlePrefConversion(double mpgData){
		double convertedVal = 0;
		String unitOutput = prefs.getString("units_pref", "MPG");
		if (unitOutput.contentEquals("MPG")) {
			convertedVal = mpgData;

		} else if (unitOutput.contentEquals("L/100KM")) {
			convertedVal =  mpgData * 3.7854;

		} else if (unitOutput.contentEquals("MPG(UK)")) {
			convertedVal = mpgData * 0.83267;

		}

		return convertedVal;
	}
	private String getPrefUnits(){
		String unitOutput = prefs.getString("units_pref", "MPG");
		return unitOutput;
	}
	private String getPrefIdleUnits(){
		String unitOutput = prefs.getString("units_pref", "MPG");

		unitOutput = prefs.getString("units_pref", "MPG");
		if (unitOutput.contentEquals("MPG")) {

			unitOutput = "G/HR";

		} else if (unitOutput.contentEquals("L/100KM")) {
			unitOutput = "L/HR";

		} else if (unitOutput.contentEquals("MPG(UK)")) {
			unitOutput = "G(UK)/HR";

		}
		return unitOutput;

	}
	

	/*
	 * This thread establishes a connection with the ELM327
	 * 
	 * Relays failure or success to MainActivity via a message to the handler
	 */
	protected class IncomingHandler extends Handler { // Handler of incoming
		// messages from
		// clients.
		@Override
		public void handleMessage(Message msg) {
			String unitOutput = "NONE";
			Message uiMsg = new Message();
			Bundle b = new Bundle();
			
			switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				mClients.add(msg.replyTo);
				break;
			case MSG_UNREGISTER_CLIENT:
				mClients.remove(msg.replyTo);
				break;
			case CONNECT_FAILED:
				uiMsg.what = CONNECT_FAILED;
				uiMsg.sendToTarget();

				break;
			case CONNECT_INTERRUPT:
				uiMsg.what = CONNECT_INTERRUPT;
				uiMsg.sendToTarget();

				break;
			case RETURNED_BYTES:
				String tmpStr = msg.getData().getString("ret_bytes");
				int byteOne, byteTwo;

				if (tmpStr.indexOf("0D")==0) {

					byteOne = Integer.parseInt(tmpStr, 16);
					vSpeed = byteOne;
				} else if (tmpStr.indexOf("10")==0) {
					byteOne = Integer.parseInt(
							tmpStr.substring(0, tmpStr.indexOf(" ")), 16);
					byteTwo = Integer.parseInt(
							tmpStr.substring(tmpStr.indexOf(" ") + 1), 16);
					MAF = (((double) byteOne * 256.0) + (double) byteTwo) / 100.0;

					if (Double.valueOf(df.format(vSpeed)) <= 0.5) {

						MPG = (MAF * obdService.stoichRatio * 3600.0)
								/ obdService.gramGasToGal; // gallons per hour, MAF is gram/second
						MPG = idlePrefConversion(MPG);
						unitOutput = getPrefUnits();
						currSUM += 0.0;
						++currNDP;
						runningMpgAvg = currSUM/currNDP;
					} else {


						// miles pergallon, vspeed is in km/hr, MAF is in
						// grams/seconds
						MPG = (vSpeed * obdService.kmToMi)
								/ ((MAF * obdService.stoichRatio * 3600.0) / obdService.gramGasToGal);
						currSUM += MPG;
						++currNDP;
						runningMpgAvg = currSUM/currNDP;
						
						MPG = prefConversion(MPG);
						unitOutput = getPrefIdleUnits();
						

						
					}
					b.putString("pref_units", unitOutput);
					b.putString("disp_str", df.format(MPG));
					b.putString("sub_disp_str", df.format(runningMpgAvg));
					b.putString("sub_pref_units", getPrefUnits());
					uiMsg.arg1 = RETURNED_MPG;
				}else{		
					uiMsg.arg1 = RETURNED_BYTES;

					b.putString("pref_units", unitOutput);

					b.putString("disp_str", msg.getData().getString("ret_bytes"));


				}
								
				uiMsg.setData(b);
				uiMsg.what = MainActivity.WRITE_SCREEN;
				try {
					uiMessenger.send(uiMsg);
				} catch (RemoteException e) {

				}

				break;
			default:
				super.handleMessage(msg);
			}
		}
	}
	
	public obdService(Context context, Messenger tMessenger) {
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		uiMessenger = tMessenger;
		parentContext = context;

		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
		runningMpgAvg = prefs.getFloat("avgMpg", 0.0f);
		currSUM = prefs.getFloat("currSUM", 0.0f);
		currNDP = prefs.getLong("currNDP", 1l);
		Message msg = new Message();
		Bundle b = new Bundle();
		if (currNDP>1) {
			msg.what = MainActivity.WRITE_SCREEN;

			runningMpgAvg = currSUM / currNDP;
			// contTrip.setVisibility(Button.VISIBLE);

			// mainText.setText(Double.toString(currSubDispData));

			b.putString("pref_units", getPrefUnits());

			b.putString("disp_str", df.format(prefConversion(runningMpgAvg)));
			// subText.setText("Lifetime AVG: " + temp);

			// unitText.setText("AVG " + prefs.getString("units_pref", "MPG")+
			// "\nFOR TRIP");

		} else {
			msg.what = MainActivity.WRITE_SCREEN;
			b.putString("pref_units", getPrefUnits());

			b.putString("disp_str", df.format(0.0));
			// contTrip.setVisibility(Button.GONE);

		}
		try {
			uiMessenger.send(msg);
		} catch (RemoteException e){
		}
	}
	
	public synchronized void clearTripData(){
		
		runningMpgAvg = 0.0;
		currSUM = 0;
		currNDP = 1;
	}

	public synchronized void connect(BluetoothDevice device) {
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();

	}
	
	protected synchronized void connected(BluetoothSocket socket) {
		// mConnectThread.cancel();
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();
	}
	
	/*Either of the sendObdCommand() methods will stop any current streams and poll the device
	 * with the new command.  It will stream the data, polling at the specified interval if the overloaded
	 * sendObdCommand(String, long) is used
	 * */
	public synchronized void sendObdCommand(String comm){
		if (isBound) {
			if (mService != null) {
				try {
					Bundle b = new Bundle();
					Message msg = Message.obtain(null, SEND_OBD,0, 0);
					b.putString("obd_command",  comm);
					msg.setData(b);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
				}
			}
		}
	}
	
	public synchronized void sendObdCommand(String comm, long pollInterval ){
		if (isBound) {
			if (mService != null) {
				try {
					Bundle b = new Bundle();

					Message msg = Message.obtain(null, SEND_SCHEDULED_OBD,0, 0);
					b.putLong("poll_interval", pollInterval);
					b.putString("obd_command",  comm);
					msg.setData(b);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
				}
			}
		}
	}
		
	
	/*
	 * This function is used to begin streaming fuel economy data
	 */
	public synchronized void startMpgTracking(){
		obdCommands.clear();
		obdCommands.add("41 0D");
		obdCommands.add("41 10");

		Bundle b = new Bundle();
		b.putLong("poll_interval", 100);

		Message msg = Message.obtain(null, SEND_SCHEDULED_OBD,0, 0);
		msg.replyTo = mMessenger;
		msg.setData(b);

		try {
			mService.send(msg);
		} catch (RemoteException e) {

		}
		
	}


	public void AlertBox(String title, String message) {
		new AlertDialog.Builder(parentContext).setTitle(title)
				.setMessage(message)
				.setPositiveButton("OK", new OnClickListener() {
					public void onClick(DialogInterface arg0, int arg1) {
						// finish();
					}
				}).show();
	}

	protected class ConnectThread extends Thread {

		public ConnectThread(BluetoothDevice device) {
			BluetoothSocket tmp = null;
			try {
				Method m = device.getClass().getMethod("createRfcommSocket",
						new Class[] { int.class });
				tmp = (BluetoothSocket) m.invoke(device, 1);

			} catch (Exception e) {
			}

			mmSocket = tmp;
		}

		public void run() {

			try {
				mmSocket.connect();
				Message message = Message.obtain(null,
						MainActivity.CONNECT_SUCCESS, -1, -1);

				for (int i = mClients.size() - 1; i >= 0; i--) {
					try {

						mClients.get(i).send(message);

					} catch (RemoteException e) {
						// The client is dead. Remove it from the list; we are
						// going through the list from back to front so this is
						// safe to do inside the loop.
						mClients.remove(i);
					}
				}

			} catch (IOException connectException) {

				Message message = Message.obtain(null,
						MainActivity.CONNECT_FAILURE, 0, -1);

				for (int i = mClients.size() - 1; i >= 0; i--) {
					try {

						mClients.get(i).send(message);

					} catch (RemoteException e) {
						// The client is dead. Remove it from the list; we are
						// going through the list from back to front so this is
						// safe to do inside the loop.
						mClients.remove(i);
					}
				}

				try {
					mmSocket.close();
				} catch (IOException closeException) {
				}

				// AlertBox("IOExcept", connectException.toString());
				return;
			}

			connected(mmSocket);

		}

		public void cancel() {
			try {
				mmInStream.close();
				mmOutStream.close();
				mmSocket.close();

			} catch (IOException e) {
			}
		}

	};

	protected class ConnectedThread extends Thread {

		public ConnectedThread(BluetoothSocket socket) {
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			try {
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e) {
			}

			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}

		@SuppressLint("NewApi")
		public void run() {
			parentContext.startService(new Intent(parentContext,
					ConnectedService.class));

		}

		public void write(byte[] data) throws IOException {

			try {
				mmOutStream.write(data);
			} catch (IOException e) {
				throw new IOException();
			}
		}

		public void cancel() {
			try {
				mmInStream.close();
				mmOutStream.close();
				mmSocket.close();

			} catch (IOException e) {
			}
		}
	};

	public boolean isRunning() {
		return isRunning;
	}

	
	public synchronized void save(){
		
		SharedPreferences.Editor prefEdit = prefs.edit();
		prefEdit.putFloat("avgMPG", (float) runningMpgAvg).apply();
		prefEdit.putLong("currNDP", currNDP).apply();
		prefEdit.putFloat("currSUM", (float) currSUM).apply();

	};
	public synchronized void stop() {
		if (mConnectThread != null) {
			mConnectThread.cancel();
			mConnectThread = null;
		}

		if (mConnectedThread != null) {
			mConnectedThread.cancel();
			mConnectedThread = null;
		}
		if (isRunning) {
			parentContext.stopService(new Intent(parentContext,
					ConnectedService.class));
			isRunning = false;
		}

	}

	private ServiceConnection mConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			mService = new Messenger(service);

			try {
				Message msg = Message.obtain(null,
						obdService.MSG_REGISTER_CLIENT);
				msg.replyTo = mMessenger;
				mService.send(msg);
			} catch (RemoteException e) {
				// In this case the service has crashed before we could even do
				// anything with it
			}
		}

		@Override
		public void onServiceDisconnected(ComponentName className) {
			// This is called when the connection with the service has been
			// unexpectedly disconnected - process crashed.
			mService = null;

		}

	};

	public void doBindService() {
		parentContext.bindService(new Intent(parentContext,
				ConnectedService.class), mConnection, Context.BIND_AUTO_CREATE);
		isBound = true;

	}

	public void doUnbindService() {
		if (isBound) {
			// If we have received the service, and hence registered with it,
			// then now is the time to unregister.
			if (mService != null) {
				try {
					Message msg = Message.obtain(null,
							obdService.MSG_UNREGISTER_CLIENT);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
					// There is nothing special we need to do if the service has
					// crashed.
				}
			}
			// Detach our existing connection.
			parentContext.unbindService(mConnection);
			isBound = false;
		}

	}

	protected class ConnectedService extends Service {

		protected NotificationManager nm;
		protected Timer timer = new Timer();
		final Messenger servMessenger = new Messenger(new ServIncomingHandler());

		@Override
		public IBinder onBind(Intent intent) {

			return servMessenger.getBinder();
		}

		protected class ServIncomingHandler extends Handler { 
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_REGISTER_CLIENT:
					mClients.add(msg.replyTo);
					break;
				case MSG_UNREGISTER_CLIENT:
					mClients.remove(msg.replyTo);
					break;
				case SEND_OBD:
					timer.cancel();

					pollDevice(msg.getData().getString("obd_command"));
					break;
				case SEND_SCHEDULED_OBD:
					timer.cancel();
					timer = new Timer();
					timer.scheduleAtFixedRate(new TimerTask() {
						public void run() {
							onTimerTick();
						}
					}, 0,  msg.getData().getLong("poll_interval"));
					break;
				default:
					super.handleMessage(msg);
				}
			}
		}


		@Override
		public void onCreate() {
			super.onCreate();
			Log.i("MyService", "Service Started.");
			showNotification();
			isRunning = true;


		}

		private void showNotification() {

			nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
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
			int mNotificationId = 001;
			// Gets an instance of the NotificationManager service
			NotificationManager mNotifyMgr = 
			        (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			// Builds the notification and issues it.
			mNotifyMgr.notify(mNotificationId, mBuilder.build());
			// Send the notification.
			// We use a layout id because it is a unique number. We use it later
			// to cancel.
		}

		@Override
		public int onStartCommand(Intent intent, int flags, int startId) {
			Log.i("MyService", "Received start id " + startId + ": " + intent);
			super.onCreate();
			byte[] buffer = new byte[8];
			int bytes = 0;

			writeTime("Started");
			String sb = new String();
			try {
				obdCommand = "AT WS\r";
				mmOutStream.write(obdCommand.getBytes());

				bytes += mmInStream.read(buffer); // this throws an exception as
													// well
				sb.concat(buffer.toString());

				obdCommand = "AT SPA3\r";
				mmOutStream.write(obdCommand.getBytes());

				bytes += mmInStream.read(buffer);
				sb.concat(buffer.toString());

				obdCommand = "AT E0\r";
				mmOutStream.write(obdCommand.getBytes());

				bytes += mmInStream.read(buffer);
				sb.concat(buffer.toString());

				if (bytes > 0) {

					writeCommsToFile();

				}

			} catch (IOException e) {
				Message msg = Message.obtain(null, CONNECT_FAILED,0, 0);
				for (int i = mClients.size() - 1; i >= 0; i--) {
					try {
					
						// Send data as a String
					
						mClients.get(i).send(msg);
					
					} catch (RemoteException e1) {
						// The client is dead. Remove it from the list;
					// we are going through the list from back to
					// front so this is safe to do inside the loop.
							mClients.remove(i);
					}
				}
			}

			Log.i("MyService", "Received start id " + startId + ": " + intent);

			return START_STICKY; // run until explicitly stopped.
		}

		protected void pollDevice(String command){

			try {

				byte[] buffer = new byte[8];
				int bytes = 0;
				
				String tmpStr = new String();
				Bundle bundle = new Bundle();
				Message calcMessage = new Message();
				reply = "";
				
				try {
					mmOutStream.write(command.getBytes());
				} catch (IOException e) {
					Message msg = Message.obtain(null, CONNECT_FAILED,0, 0);
					for (int i = mClients.size() - 1; i >= 0; i--) {
						try {
						
							// Send data as a String
						
							mClients.get(i).send(msg);
						
						} catch (RemoteException e1) {
							// The client is dead. Remove it from the list;
						// we are going through the list from back to
						// front so this is safe to do inside the loop.
								mClients.remove(i);
						}
					}
					
				}

				do {
					bytes += mmInStream.read(buffer);
					reply += buffer.toString();
				} while (!reply.contains(">"));
				reply = reply.substring(reply.indexOf("41"),
						reply.indexOf("\r") - 1);

				tmpStr = reply.substring(3);
				calcMessage = Message.obtain(null,
						RETURNED_BYTES, 0, -1);
					
				
				bundle.putString("ret_bytes", tmpStr);
				
				calcMessage.setData(bundle);
			
				for (int i = mClients.size() - 1; i >= 0; i--) {
					try {

						// Send data as a String

						mClients.get(i).send(calcMessage);

					} catch (RemoteException e) {
						// The client is dead. Remove it from the list;
						// we are going through the list from back to
						// front so this is safe to do inside the loop.
						mClients.remove(i);
					}
				}
				reply = "";

			} catch (Throwable t) { // you should always ultimately catch all
									// exceptions in timer tasks.
			}
		}
		
		protected void onTimerTick() {
			Log.i("TimerTick", "Timer doing work.");
			for(int i = 0; i < obdCommands.size(); ++i){
				pollDevice(obdCommands.get(i));
			}
			

		}

		@Override
		public void onDestroy() {
			super.onDestroy();
			if (timer != null) {
				timer.cancel();
			}

			nm.cancel(R.string.service_started); // Cancel the persistent
													// notification.
			Log.i("MyService", "Service Stopped.");
			isRunning = false;
		}

	};

	/*  simply write the Command Prompt to a file, "ELM327comm_data.txt" */
	public void writeCommsToFile() {

		File file = new File(Environment.getExternalStorageDirectory(),
				"ELM327comm_data.txt");

		String str = "";
		try {
			BufferedWriter bW;

			bW = new BufferedWriter(new FileWriter(file, true));
			if (cmdPrompt.getCount() > 0) {
				for (int i = 0; i < cmdPrompt.getCount(); ++i) {
					str = str.concat(cmdPrompt.getItem(i));
				}
				cmdPrompt.clear();

				bW.write(str);
				bW.newLine();
				bW.flush();
				bW.close();
			}

		} catch (IOException e) {
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

		String str = "";
		try {
			BufferedWriter bW;
			DecimalFormat df = new DecimalFormat("#.00");
			double calcedAvg = currSUM / currNDP;

			bW = new BufferedWriter(new FileWriter(file, true));

			str = str.concat("\t" + "\"" + "AverageMPG" + "\" : "
					+ df.format(calcedAvg) + ",\r");

			bW.write(str);
			bW.newLine();
			bW.flush();
			bW.close();
			MediaScannerConnection.scanFile(parentContext,
					new String[] { file.toString() }, null,
					new MediaScannerConnection.OnScanCompletedListener() {
						public void onScanCompleted(String path, Uri uri) {

						}
					});
		} catch (IOException e) {

		}

	}

	/*
	 * writeMpgData():
	 * 
	 * Writes the last X number of time-stamped data points **IN MILES PER
	 * GALLON**, to the file "mpg_data.json",
	 */

	public void writeTime(String prefix) {
		File file = new File(Environment.getExternalStorageDirectory(),
				"mpg_data.json");

		String str = "";
		try {
			BufferedWriter bW;

			bW = new BufferedWriter(new FileWriter(file, true));

			Time now = new Time();
			now.setToNow();

			str = str.concat("Session : {\r");

			str += "\t\"" + prefix + "\" : " + "\""
					+ Integer.toString(now.year) + "-"
					+ Integer.toString(now.month + 1) + "-"
					+ Integer.toString(now.monthDay) + "  "
					+ Integer.toString(now.hour) + "h"
					+ Integer.toString(now.minute) + "m" + "\"" + "\r";

			bW.write(str);
			bW.newLine();
			bW.flush();
			bW.close();

		} catch (IOException e) {

		}

	}

	public void writeMpgData() {

		File file = new File(Environment.getExternalStorageDirectory(),
				"mpg_data.json");

		String str = "";
		try {
			BufferedWriter bW;

			bW = new BufferedWriter(new FileWriter(file, true));

			Time now = new Time();
			now.setToNow();
			str = str.concat("Session : {\r\t\"MpgValArr\" : [\r");

			for (int i = 0; i < mpgDataList.size(); ++i) {
				str = str.concat(mpgDataList.get(i));
			}
			mpgDataList.clear();

			bW.write(str);
			bW.newLine();
			bW.flush();
			bW.close();

		} catch (IOException e) {

		}

	}
}
