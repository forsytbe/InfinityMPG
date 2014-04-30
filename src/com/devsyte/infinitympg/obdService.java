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

	private static Context parentContext;

	public static final DecimalFormat df = new DecimalFormat("#.##");
	/*This is the array the is home to the OBD2 PID's we want to send.  They will be sent in order.  This needs to be watched,
	 * currently the only support for multi-PID tracking is via startMpgTracking.  
	 * 
	 * TODO:  Find a *good* way to allow the user to submit a list of commands to issue, and then view them simultaneously,
	 *  or better, allow them to submit some kind of heuristics to combine the these arbitrary commands
	 *  */

	protected static boolean isBound = false;
	protected Messenger mService = null;
	
	/*This is the messenger that will allow us to send interpreted data to the MainActivity */
	private Messenger uiMessenger = null;
	
	private Handler mHandler = null;
	private Messenger mMessenger = null;
	private IncomingHandlerThread mThread = null;
	
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

	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	static final int SERVICE_REGISTERED = 3;
	static final int RETURNED_MPG = 8;
	static final int RETURNED_BYTES = 10;
	static final int CONNECT_SUCCESS = 11;
	static final int CONNECT_FAILED = 12;
	static final int CONNECT_INTERRUPT = 13;
	static final int WRITE_SCREEN = 14;

	
	private double MPG;
	private double avgMPG;
	private double explicitMpgAvg;
	
	
	private static SharedPreferences prefs;

	/*  These functions are called to convert from the standard units (MPG, which everything is saved as),
	 *  and the units specified by user preferences.
	 * 
	 * */
	public static double prefConversion(double mpgData) {
		double convertedVal = 0;
		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
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
	public static double idlePrefConversion(double mpgData){
		double convertedVal = 0;
		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
		String unitOutput = prefs.getString("units_pref", "MPG");

		if(!useIdleMode()){
			if (unitOutput.contentEquals("MPG")) {
				convertedVal = 0;

			} else if (unitOutput.contentEquals("L/100KM")) {
				convertedVal =  -1;

			} else if (unitOutput.contentEquals("MPG(UK)")) {
				convertedVal = 0;

			}
		}else{
			if (unitOutput.contentEquals("MPG")) {
				convertedVal = mpgData;
	
			} else if (unitOutput.contentEquals("L/100KM")) {
				convertedVal =  mpgData * 3.7854;
	
			} else if (unitOutput.contentEquals("MPG(UK)")) {
				convertedVal = mpgData * 0.83267;
	
			}
		}

		return convertedVal;
	}
	public static String getPrefUnits(){
		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
		String unitOutput = prefs.getString("units_pref", "MPG");
		return unitOutput;
	}
	public static String getPrefIdleUnits(){
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
	
	private class IncomingHandlerThread extends HandlerThread{
		
		IncomingHandlerThread(){
			super("IncomingHandlerThread");
			start();
			mHandler = new IncomingHandler(getLooper());
			mMessenger = new Messenger(mHandler);
			
		}
		
		public void connect(String add){
			final String devAddress = add;
			mHandler.post(new Runnable(){
				@Override
				public void run(){
					if(mService == null){
						parentContext.startService(new Intent(parentContext, ConnectedService.class));
						doBindService();
					
						while(mService == null);
						Message mess =  new Message();
						mess.what = ConnectedService.CONNECT_DEVICE;
						Bundle b = new Bundle();
						b.putString("btdev_address", devAddress);
						mess.setData(b);
						try {
							mService.send(mess);
						} catch (RemoteException e) {

						}
					}

					
				}
				
			});
		}
		
	}
	
	/*
	 * This thread establishes a connection with the ELM327
	 * 
	 * Relays failure or success to MainActivity via a message to the handler
	 */
	protected class IncomingHandler extends Handler { // Handler of incoming
		// messages from
		// clients.
		public IncomingHandler(Looper looper){
			super(looper);
		}
		
		@Override
		public void handleMessage(Message msg) {
			String unitOutput = "NONE";
			Message uiMsg = new Message();
			Bundle b = new Bundle();
			
			switch (msg.what) {
			case obdService.SERVICE_REGISTERED:

			break;
			case obdService.CONNECT_SUCCESS:
				uiMsg.what = obdService.CONNECT_SUCCESS;
				try {
					uiMessenger.send(uiMsg);
				} catch (RemoteException e1) {

				}
				break;			
			case obdService.CONNECT_FAILED:
				uiMsg.what = obdService.CONNECT_FAILED;
				try {
					uiMessenger.send(uiMsg);
				} catch (RemoteException e1) {

				}
				stop();

				break;
			case obdService.CONNECT_INTERRUPT:
				
				uiMsg.what = obdService.CONNECT_FAILED;
				try {
					uiMessenger.send(uiMsg);
				} catch (RemoteException e1) {

				}

				break;
			case RETURNED_MPG:
				uiMsg.what = obdService.WRITE_SCREEN;
				uiMsg.arg1 = RETURNED_MPG;

				explicitMpgAvg = msg.getData().getDouble("avgMPG");
				MPG = msg.getData().getDouble("mpg");
				avgMPG = prefConversion(explicitMpgAvg);
				
				if(msg.getData().getBoolean("isIdle")){
					
						MPG = idlePrefConversion(MPG);
						if(useIdleMode()){
							unitOutput = getPrefIdleUnits();
						}else{
							unitOutput = getPrefUnits();

						}
						if(MPG == -1){
							b.putString("disp_str", "\u221E");

						}else{
							b.putString("disp_str", df.format(MPG));

						}
				}else{
					MPG = prefConversion(MPG);
					unitOutput = getPrefUnits();
					b.putString("disp_str", df.format(MPG));

				}
		
				b.putString("pref_units", unitOutput);
				b.putString("sub_disp_str", df.format(avgMPG));
				b.putString("sub_pref_units", getPrefUnits());
				uiMsg.setData(b);
				try {
					uiMessenger.send(uiMsg);
				} catch (RemoteException e1) {

				}
				break;
			case RETURNED_BYTES:
				uiMsg.what = obdService.WRITE_SCREEN;

				uiMsg.arg1 = RETURNED_BYTES;

				b.putString("pref_units", unitOutput);
				b.putString("disp_str", msg.getData().getString("ret_bytes"));
				
				uiMsg.setData(b);
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
	
	public boolean isRunning(){
		return ConnectedService.isRunning();
	}
	
	private static boolean useIdleMode(){
		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
		return  prefs.getBoolean("idle_stats_pref", true);
	}
	
	public obdService(Context context, Messenger tMessenger) {

		uiMessenger = tMessenger;
		parentContext = context;


		
		if(mThread == null){
			mThread = new IncomingHandlerThread();
		}
		
		if(ConnectedService.isRunning()){
			doBindService();
		}
	}

	public synchronized void clearTripData(){
		if(isBound){
			Message msg = Message.obtain(null, ConnectedService.CLEAR_TRIP_DATA,0, 0);
			try {
				mService.send(msg);
			} catch (RemoteException e) {

			}
		}

	}
	
	public synchronized void clearCommandList(){
		if(isBound){
			Message msg = Message.obtain(null, ConnectedService.CLEAR_COMMANDS,0, 0);
			try {
				mService.send(msg);
			} catch (RemoteException e) {

			}
		}
	}
	
	public synchronized void connect(String address){
		if(mThread == null){
			mThread = new IncomingHandlerThread();
		}
		mThread.connect(address);
	}
	
	/*Either of the sendObdCommand() methods will stop any current streams and poll the device
	 * with the new command.  It will stream the data, polling at the specified interval if the overloaded
	 * sendObdCommand(String, long) is used
	 * */
	public synchronized void sendCommandList(){
		if (isBound) {
			if (mService != null) {
				try {
					Message msg = Message.obtain(null, ConnectedService.SEND_OBD,0, 0);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
				}
			}
		}
	}
	
	public synchronized void sendCommandList( long pollInterval ){
		if (isBound) {
			if (mService != null) {
				try {
					Bundle b = new Bundle();
					Message msg = Message.obtain(null, ConnectedService.SEND_SCHEDULED_OBD,0, 0);
					msg.replyTo = mMessenger;
					b.putLong("poll_interval", pollInterval);
					msg.setData(b);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
					
				}
			}
		}
	}
	
	public void addCommand(String comm){
		if (isBound) {
			Bundle b = new Bundle();
			Message msg = Message.obtain(null, ConnectedService.ADD_COMMAND,0, 0);
			b.putString("command",  comm);
			msg.setData(b);
			msg.replyTo = mMessenger;
			try {
				mService.send(msg);
			} catch (RemoteException e) {
	
			}
			
		}
	
	}
	/*
	 * This function is used to begin streaming fuel economy data
	 */
	public synchronized void startMpgTracking(){
		if (isBound) {
			try {
				Log.i("MyService", "Sending Start Tracking Message");			
				Bundle b = new Bundle();
				b.putLong("poll_interval", 300);
		
				Message msg = Message.obtain(null, ConnectedService.TRACK_MPG,0, 0);
				msg.replyTo = mMessenger;
				msg.setData(b);
				mService.send(msg);
			} catch (RemoteException e) {
				Log.i("MyService", "Failed Sending Start Tracking Message");

			}
			
		}
	}
	public void unbind(){
			doUnbindService();
	}
	
	public boolean bind(){
			return doBindService();
		
	}
	
	public void stop(){
		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
		prefs.edit().putFloat("avgMPG", (float) explicitMpgAvg).apply();
		try{
			doUnbindService();
		}catch(IllegalArgumentException e){}
		
		while(isBound);
		parentContext.stopService(new Intent(parentContext, ConnectedService.class));
		
		if(mThread != null){
			mThread.interrupt();
			mThread = null;
		}
	}

	protected void AlertBox(String title, String message) {
		new AlertDialog.Builder(parentContext).setTitle(title)
				.setMessage(message)
				.setPositiveButton("OK", new OnClickListener() {
					public void onClick(DialogInterface arg0, int arg1) {
						// finish();
					}
				}).show();
	}

	private ServiceConnection mConnection = new ServiceConnection() {
		@Override
		public void onServiceConnected(ComponentName className, IBinder service) {
			Log.i("MyService", "mConnection Connected!");
			mService = new Messenger(service);
			isBound = true;
			try {
				Message msg = Message.obtain(null,
						obdService.MSG_REGISTER_CLIENT, 0, 0);
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
			Log.i("MyService", "mConnection Disconnected!");
			isBound = false;
			mService = null;

		}

	};

	protected boolean doBindService() {
		Log.i("MyService", "Service Bound!");

		if(parentContext.bindService(new Intent(parentContext,
				ConnectedService.class), mConnection,0)){
			return true;
			
		}else{
			return false;
		}

	}

	protected void doUnbindService() {
		if(isBound){
			// If we have received the service, and hence registered with it,
			// then now is the time to unregister.
				try {
					Message msg = Message.obtain(null,
							obdService.MSG_UNREGISTER_CLIENT);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
					// There is nothing special we need to do if the service has
					// crashed.
				}
			
			// Detach our existing connection.
				try{
					parentContext.unbindService(mConnection);
				}catch(Exception e){
					
				}
			isBound = false;
			mService = null;
			Log.i("MyService", "Service Unbound!");

		}
	}

	

}
