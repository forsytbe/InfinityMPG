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
import android.text.format.Time;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;

/*  This is the primary class for establishing, and maintaining the ELM327 Bluetooth Connection,
 *  As well as polling for data and the computations that need to be performed on that data.
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

	public static enum fileStates {
		START, END, INPROG
	};

	protected ArrayAdapter<String> cmdPrompt;

	protected ArrayList<String> mpgDataList = new ArrayList<String>();

	protected ArrayList<String> obdCommands;

	private BluetoothSocket mmSocket;
	private InputStream mmInStream;
	private OutputStream mmOutStream;

	private String obdCommand = "";
	private String reply = "";
	private String response = "";

	protected boolean isBound = false;
	protected boolean isRunning = false;
	protected Messenger mService = null;
	final Messenger mMessenger = new Messenger(new IncomingHandler());

	ArrayList<Messenger> mClients = new ArrayList<Messenger>(); // Keeps track
																// of all
																// current
																// registered

	// clients.
	public static enum ServiceMode {
		MPG_MODE, CONSOLE_MODE
	};

	private ServiceMode currentMode;

	protected class IncomingHandler extends Handler { // Handler of incoming
		// messages from
		// clients.
		@Override
		public void handleMessage(Message msg) {
			switch (msg.what) {
			case MSG_REGISTER_CLIENT:
				mClients.add(msg.replyTo);
				break;
			case MSG_UNREGISTER_CLIENT:
				mClients.remove(msg.replyTo);
				break;
			case SET_OBD_COMMAND:
				obdCommand = msg.getData().getString("obdCommand");

				break;
			default:
				super.handleMessage(msg);
			}
		}
	}

	int mValue = 0; // Holds last value set by a client.
	static final int MSG_REGISTER_CLIENT = 1;
	static final int MSG_UNREGISTER_CLIENT = 2;
	static final int MSG_SET_INT_VALUE = 3;
	static final int MSG_SET_STRING_VALUE = 4;
	static final int SET_OBD_COMMAND = 5;
	static final int SET_MODE = 6;

	public double vSpeed = 0; // vehicle speed in km/h
	public double MAF = 0; // mass air flow, g/s
	public double MPG = 0; // miles/gallon

	private double currMPG = 0.0;
	private long numDataPts = 0L;
	private double currSUM = 0.0;
	private long currNDP = 0L;
	private double runningMpgAvg = 0.0;
	private double currDisplayData = 0.0;
	private double currSubDispData = 0.0;

	private SharedPreferences prefs;

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

	private Time start = null;
	private boolean startTSet = false;

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

	/*
	 * This thread establishes a connection with the ELM327
	 * 
	 * Relays failure or success to MainActivity via a message to the handler
	 */
	protected obdService(Context context) {
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		parentContext = context;

		prefs = PreferenceManager.getDefaultSharedPreferences(parentContext
				.getApplicationContext());
		runningMpgAvg = prefs.getFloat("avgMpg", 0.0f);
		numDataPts = prefs.getLong("numPtsForAvg", 0l);
		currSUM = prefs.getFloat("currSUM", 0.0f);
		currNDP = prefs.getLong("currNDP", 0l);

		if (currNDP > 0l) {
			double calcedAvg = currSUM / currNDP;
			// contTrip.setVisibility(Button.VISIBLE);

			if (calcedAvg > 0f) {
				currSubDispData = prefConversion(calcedAvg);
			} else {
				currSubDispData = 0.0;
			}
			// mainText.setText(Double.toString(currSubDispData));

			DecimalFormat df = new DecimalFormat("#.00");
			double ltAVG = 0.0;
			if (numDataPts > 0L) {
				ltAVG = runningMpgAvg / numDataPts;
			}
			String temp;
			if (ltAVG < .1) {
				temp = "0.0";
			} else {

				temp = df.format(ltAVG);

			}
			// subText.setText("Lifetime AVG: " + temp);

			// unitText.setText("AVG " + prefs.getString("units_pref", "MPG")+
			// "\nFOR TRIP");

		} else {
			// contTrip.setVisibility(Button.GONE);

		}
	}

	public synchronized void connect(BluetoothDevice device) {
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();

	}

	public synchronized void setMode(ServiceMode newMode) {
		currentMode = newMode;

		switch (currentMode) {
		case MPG_MODE:
			obdCommands.clear();
			obdCommands.add("41 0D");
			obdCommands.add("41 10");

			break;
		case CONSOLE_MODE:
			break;
		}
		if (isBound) {
			if (mService != null) {
				try {
					Message msg = Message.obtain(null, SET_MODE, 0, 0);
					msg.replyTo = mMessenger;
					mService.send(msg);
				} catch (RemoteException e) {
				}
			}
		}

	}

	protected synchronized void connected(BluetoothSocket socket) {
		// mConnectThread.cancel();
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();
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

		protected class ServIncomingHandler extends Handler { // Handler of
																// incoming
			// messages from
			// clients.
			@Override
			public void handleMessage(Message msg) {
				switch (msg.what) {
				case MSG_REGISTER_CLIENT:
					mClients.add(msg.replyTo);
					break;
				case MSG_UNREGISTER_CLIENT:
					mClients.remove(msg.replyTo);
					break;
				case SET_OBD_COMMAND:
					obdCommand = msg.getData().getString("obdCommand");

					break;
				default:
					super.handleMessage(msg);
				}
			}
		}

		private void sendMessageStrToUI(String strName, String strToSend,
				int msgType) {
			for (int i = mClients.size() - 1; i >= 0; i--) {
				try {

					// Send data as a String
					Bundle b = new Bundle();
					b.putString(strName, strToSend);
					Message msg = Message.obtain(null, msgType);
					msg.setData(b);
					mClients.get(i).send(msg);

				} catch (RemoteException e) {
					// The client is dead. Remove it from the list; we are going
					// through the list from back to front so this is safe to do
					// inside the loop.
					mClients.remove(i);
				}
			}
		}

		@Override
		public void onCreate() {
			super.onCreate();
			Log.i("MyService", "Service Started.");
			showNotification();
			isRunning = true;
			timer.scheduleAtFixedRate(new TimerTask() {
				public void run() {
					onTimerTick();
				}
			}, 0, 100L);

		}

		private void showNotification() {

			nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
			// In this sample, we'll use the same text for the ticker and the
			// expanded notification
			CharSequence text = getText(R.string.service_started);
			// Set the icon, scrolling text and timestamp
			Notification notification = new Notification(R.drawable.infinity,
					text, System.currentTimeMillis());
			// The PendingIntent to launch our activity if the user selects this
			// notification
			PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
					new Intent(this, MainActivity.class), 0);
			// Set the info for the views that show in the notification panel.
			notification.setLatestEventInfo(this,
					getText(R.string.service_label), text, contentIntent);
			// Send the notification.
			// We use a layout id because it is a unique number. We use it later
			// to cancel.
			nm.notify(R.string.service_started, notification);
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

				sendMessageStrToUI("connect_failure", "Connection Failed",
						MainActivity.CONNECT_FAILURE);
				// cancel();
			}

			Log.i("MyService", "Received start id " + startId + ": " + intent);

			return START_STICKY; // run until explicitly stopped.
		}

		protected void onTimerTick() {
			Log.i("TimerTick", "Timer doing work.");

			byte[] buffer = new byte[8];
			int bytes = 0;
			try {

				String tmpStr = new String();
				int byteOne, byteTwo;
				Bundle bundle = new Bundle();

				sendMessageStrToUI("comm_data", obdCommand,
						MainActivity.WRITE_PROMPT);

				reply = "";
				try {
					mmOutStream.write(obdCommand.getBytes());
				} catch (IOException e) {
				}

				do {
					bytes += mmInStream.read(buffer);
					reply += buffer.toString();
				} while (!reply.contains(">"));

				if (reply.contains("\r")) {
					reply = reply.substring(reply.indexOf("41"),
							reply.indexOf("\r") - 1);

					if (reply.contains("41 0D")) {
						tmpStr = reply.substring(6);// this only returns one
													// byte, so this is that
													// byte
						byteOne = Integer.parseInt(tmpStr, 16);
						vSpeed = byteOne;
					} else if (reply.contains("41 10")) {
						tmpStr = reply.substring(6);
						byteOne = Integer.parseInt(
								tmpStr.substring(0, tmpStr.indexOf(" ")), 16);
						byteTwo = Integer.parseInt(
								tmpStr.substring(tmpStr.indexOf(" ") + 1), 16);
						MAF = (((double) byteOne * 256.0) + (double) byteTwo) / 100.0;

						DecimalFormat df = new DecimalFormat("#.##");

						bundle = new Bundle();
						Message calcMessage = new Message();

						if (Double.valueOf(df.format(vSpeed)) <= 0.5) {

							MPG = (MAF * obdService.stoichRatio * 3600.0)
									/ obdService.gramGasToGal; // gallons per
																// hour, MAF is
																// in
																// gram/second

							calcMessage = Message.obtain(null,
									MainActivity.WRITE_SCREEN, 1, -1);

						} else {
							// miles pergallon, vspeed is in km/hr, MAF is in
							// grams/seconds
							MPG = (vSpeed * obdService.kmToMi)
									/ ((MAF * obdService.stoichRatio * 3600.0) / obdService.gramGasToGal);

							calcMessage = Message.obtain(null,
									MainActivity.WRITE_SCREEN, 0, -1);
						}

						bundle.putDouble("mpgData", MPG);

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

					}
					reply = "";
				}

			} catch (Throwable t) { // you should always ultimately catch all
									// exceptions in timer tasks.
				Log.e("TimerTick", "Timer Tick Failed.", t);
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
