package com.devsyte.infinitympg;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;

import com.devsyte.infinitympg.obdService.ConnectedService;

import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.format.Time;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.graphics.Typeface;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.bluetooth.*;

/*This is the main display.  This is the screen the user will first encounter, 
 * and where they will spend most of their time.
 * 
 * From this activity users can start a new trip or continue the most recent trip.
 * 
 * The streaming fuel economy data is displayed as the focal point of this activity
 * 
 * */
public class MainActivity extends Activity {

	protected BluetoothAdapter mBluetoothAdapter;

	/*
	 * obdService is the class that performs all the heavy-lifting as pertaining
	 * to the ELM327. It will be instantiated once it is confirmed the user's
	 * device supports Bluetooth. From MainActivity one must only call start and
	 * stop on an obdService object, and the MainActivity handler handles all
	 * messages from the obdService threads.
	 */
	protected obdService mobdService = null;

	/*
	 * cmdPrompt is the log of the prompts and commands exchanged between the
	 * app and the ELM327 device. Used for manual debugging.
	 */


	/*
	 * These are the primary TextViews that will be used to display the fuel
	 * economy data, and units to the user
	 */
	TextView mainText;
	TextView subText;
	TextView appText;
	TextView unitText;

	/*
	 * These flags are used sent via message (from the obdService
	 * ConnectedThread) to the handler, where the data accompanying the message
	 * can be interpreted, based on aforementioned flag
	 */
	public static final int WRITE_SCREEN = 1;
	public static final int WRITE_PROMPT = 2;
	public static final int WRITE_FILE = 3;
	public static final int FINISH_IT = 4;
	public static final int CONNECT_SUCCESS = 5;
	public static final int CONNECT_FAILURE = 6;

	/*
	 * These are the primary members the hold the data to be displayed to the
	 * user, as well as tracking the overall fuel economy of the current trip.
	 * The values of these members are saved to the files: "mpg_avgs.json" and
	 * "mpg_data.json" at predetermined intervals or upon trip completion.
	 * 
	 * These members will be set to the saved value (all of which are
	 * SharedPreferences), that is, the value the member had upon completion of
	 * the most recent trip, or 0, if there was no previous trip, or the user
	 * opts to start a new trip
	 */
	private double currDisplayData = 0.0;
	private double currSubDispData = 0.0;

	Messenger mService = null;

	final Messenger mMessenger = new Messenger(new IncomingHandler());

	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			Button startOrSave = (Button) findViewById(R.id.start_or_save);
			Button contTrip = (Button) findViewById(R.id.continue_trip);
			ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
			waiting.setVisibility(ProgressBar.GONE);

			SharedPreferences prefs = PreferenceManager
					.getDefaultSharedPreferences(getApplicationContext());
			String unitOutput = "";
			DecimalFormat df = new DecimalFormat("#.00");

			switch (msg.what) {
	
			case WRITE_SCREEN:
				Time now = new Time();
				now.setToNow();
				String curTime = "\"" + Integer.toString(now.hour) + "h"
						+ Integer.toString(now.minute) + "m"
						+ Integer.toString(now.second) + "s\" : ";

				unitOutput = prefs.getString("units_pref", "MPG");

				currMPG = msg.getData().getDouble("mpgData");// this comes back
																// as MPG
																// regardless of
																// user
																// preference
				++numDataPts;
				currDisplayData = currMPG;
				/*
				 * currMPG is what is written to the file. ** The file is ALWAYS
				 * stored as MPG, regardless of user preference **
				 * 
				 * runningavg will store the *total* number of data points for
				 * the file and the avg associated with them every iteration we
				 * just add the new point to the set
				 * 
				 * Case 0 is standard, the engine is running, vehicle is moving
				 * Case 1 is engine is running, vehicle is stopped (Theoretical)
				 * Case 2 engine is off, this flag is not actually sent or used
				 * But it's here to think about. Currently, this 'case' of the
				 * engine being off and the device still streaming would simply
				 * default to case 1.
				 */
				double calcedAvg = 0.0;
				switch (msg.arg1) {

				case 0:
					currSUM += currMPG;
					++currNDP;
					calcedAvg = currSUM / currNDP;
					runningMpgAvg += currMPG;

					if (unitOutput.equals("MPG")) {
						// this will convert km/h to mi/h
						// this converts a gram of gas/second to gallon/hour
						currDisplayData = currMPG;
						currSubDispData = calcedAvg;

					} else if (unitOutput.equals("L/100KM")) {

						currDisplayData = 235.2 / currMPG;

						currSubDispData = 235.2 / calcedAvg;
					} else if (unitOutput.equals("MPG(UK)")) {
						currDisplayData = currMPG * 1.201;

						currSubDispData = calcedAvg * 1.201;
					}

					break;
				case 1:
					currSUM += 0.0;
					++currNDP;
					calcedAvg = currSUM / currNDP;
					runningMpgAvg += 0.0;// 0.0, because thats the mpg you're
											// getting
					if (unitOutput.contentEquals("MPG")) {
						// currMPG is coming back as gal/hour if the vehicle is
						// stopped
						currDisplayData = currMPG;
						unitOutput = "G/HR";

						currSubDispData = calcedAvg;

					} else if (unitOutput.contentEquals("L/100KM")) {
						currDisplayData = currMPG * 3.7854;
						unitOutput = "L/HR";

						currSubDispData = 235.2 / calcedAvg;
					} else if (unitOutput.contentEquals("MPG(UK)")) {
						currDisplayData = currMPG * 0.83267;
						unitOutput = "G(UK)/HR";
						currSubDispData = calcedAvg * 1.201;

					}

					if (!prefs.getBoolean("idle_stats_pref", true)) {

						currDisplayData = 0.0;
						unitOutput = prefs.getString("units_pref", "MPG");
					}

					break;
				// TODO: Case 2: engine off?
				}

				mpgDataList.add("\t\t" + curTime + df.format(currMPG) + ",\r");
				if (mpgDataList.size() >= 256) {
					writeMpgData(fileStates.INPROG);
				}

				mainText.setText(df.format(currDisplayData));
				subText.setText("AVG " + prefs.getString("units_pref", "MPG")
						+ ": " + df.format(currSubDispData));
				unitText.setText(unitOutput);
				break;

			/*
			 * CONNECT_SUCCESS:
			 * 
			 * This flag is sent upon connection to and streaming from the
			 * ELM327 device
			 */
			case CONNECT_SUCCESS:

				startOrSave.setText(R.string.saveBut);
				startOrSave.setOnClickListener(new View.OnClickListener() {
					@Override
					public void onClick(View v) {
						endAndSave();
					}
				});
				contTrip.setVisibility(Button.GONE);

				break;

			/*
			 * CONNECT_FAILURE:
			 * 
			 * CASE 0: The connection could not be established CASE 1: An
			 * unexpected disconnect occured (e.g. the ELM327/Android devices
			 * have gone outside Bluetooth range)
			 */
			case CONNECT_FAILURE:
				// case 0 = the connection never happened, case 1 is a
				// disconnect

				switch (msg.arg1) {
				case 0:
					final String errMess = "Device not available.  Please find a device.";
					final String title = "Connection Failed";
					connectExceptAlert(title, errMess);

					break;
				case 1:
					waiting.setVisibility(ProgressBar.VISIBLE);

					break;
				}

				endAndSave();
				break;
			}
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_main);

		PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
		waiting.setVisibility(ProgressBar.GONE);

		appText = (TextView) findViewById(R.id.appName);
		mainText = (TextView) findViewById(R.id.mainDisplay);
		subText = (TextView) findViewById(R.id.subDisplay);
		unitText = (TextView) findViewById(R.id.unitDisplay);

		Typeface typeFace = Typeface.createFromAsset(getApplicationContext()
				.getAssets(), "font/Magenta_BBT.ttf");
		appText.setTypeface(typeFace);
		typeFace = Typeface.createFromAsset(
				getApplicationContext().getAssets(), "font/orbitron-black.otf");
		mainText.setTypeface(typeFace);
		subText.setTypeface(typeFace);
		typeFace = Typeface.createFromAsset(
				getApplicationContext().getAssets(), "font/orbitron-bold.otf");
		unitText.setTypeface(typeFace);

		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mBluetoothAdapter == null) {
			AlertBox("blue", "Bluetooth not supported");
			finish();
		} else {
			mobdService = new obdService(this);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();


		Button contTrip = (Button) findViewById(R.id.continue_trip);
		Button startOrSave = (Button) findViewById(R.id.start_or_save);

		/*
		 * Go ahead and grab the saved trip values if they exist, display the
		 * 'Continue Trip' button if they do, omit said button if they do not.
		 */
		
		if (!mobdService.isRunning()) {
			startOrSave.setText(R.string.start);
			startOrSave.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {

					startService(false);

				}
			});
		} else {

			startOrSave.setText(R.string.saveBut);
			startOrSave.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					endAndSave();
				}
			});
		}
	}

	/*
	 * mHandler essentially drives the application display, receiving relevant
	 * data from the ELM327 via messages from an obdService object, and then
	 * displaying that data based on user preferences
	 * 
	 * Here the preferences are implemented, the data conversions done, and the
	 * screen is written
	 */

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		// Handle Android menu (top right corner) selection
		Intent intent;
		switch (item.getItemId()) {
		case R.id.action_settings:
			intent = new Intent(this, Settings.class);
			startActivityForResult(intent, 1);
			return true;
		case R.id.view_prev_trips:

			intent = new Intent(this, ViewPrevActivity.class);
			startActivity(intent);
			return true;

		default:
			return super.onOptionsItemSelected(item);
		}
	}

	/*
	 * onActivityResult(int, int, android.content.Intent):
	 * 
	 * CASE 0: Result from BluetoothSettings activity, a new default device has
	 * been selected.
	 */
	@Override
	public void onActivityResult(int requestCode, int resultCode, Intent data) {

		switch (requestCode) {

		case 0:
			if (resultCode == Activity.RESULT_OK) {

				SharedPreferences.Editor prefs = PreferenceManager
						.getDefaultSharedPreferences(this).edit();
				String deviceData = data.getExtras().getString(
						BluetoothSettings.DEVICE_DATA);
				prefs.putString("bt_device", deviceData).apply();

			}
		}

	}

	/*
	 * obdService.connect() is call that initiates both connection to and
	 * streamming from the ELM327 From here on out, the application display is
	 * driven by mHandler and user input.
	 */
	private void connectDevice(String deviceData) {
		// Get the device MAC address

		String address = deviceData.substring(deviceData.indexOf('\n') + 1);
		// Get the BluetoothDevice object
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		// Attempt to connect to the device
		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
		waiting.setVisibility(ProgressBar.VISIBLE);

		mobdService.connect(device);

	}

	public void findDevice(View view) {
		Intent intent = new Intent(this, BluetoothSettings.class);

		startActivityForResult(intent, 0);

	}

	@Override
	public void onRestart() {

		super.onRestart();
	}

	@Override
	public void onStop() {
		if (mobdService.isRunning()) {
			endAndSave();
		}

		super.onStop();
	}

	public void continueTrip(View v) {

		startService(true);
	}

	/*
	 * This function is called to start the connection with the ELM327 device,
	 * and to stream data upon completion. Data is then displayed via mHandler.
	 * 
	 * the argument 'boolean cont' specifies if the user opted to: continue the
	 * last trip (true), or to start a new trip (false)
	 * 
	 * startService() will redirect the user to BluetoothSettings, and prompt
	 * them to pick a device if no default device is available
	 */
	public void startService(boolean cont) {
		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(this);

		if (!cont) {
			currSUM = 0.0;
			currNDP = 1L;
		}
		String deviceName = prefs.getString("bt_device", "None");
		if (deviceName.equals("None")) {

			Intent intent = new Intent(this, BluetoothSettings.class);

			startActivityForResult(intent, 0); // My displayMessageActivity
												// needs renamed, but this
												// allows the user to select a
												// BT device
		} else {

			connectDevice(deviceName);

		}
	}

	/*
	 * This function is called: When the application loses focus and is still
	 * connected to the ELM327 (in onStop()), When the user ends the trip
	 * (presses the 'End and Save' button, When a disconnect occurs
	 * (CONNECT_FAILURE message flag is sent from obdService to mHandler)
	 */
	public void endAndSave() {
		if (mobdService.isRunning()) {


		}
		Button startOrSave = (Button) findViewById(R.id.start_or_save);
		Button contTrip = (Button) findViewById(R.id.continue_trip);
		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);

		SharedPreferences prefs = PreferenceManager
				.getDefaultSharedPreferences(getApplicationContext());
		SharedPreferences.Editor prefEdit = prefs.edit();

		if (currNDP > 0l) {

			contTrip.setVisibility(Button.VISIBLE);

			prefEdit.putLong("numPtsForAvg", numDataPts).apply();
			prefEdit.putFloat("avgMPG", (float) runningMpgAvg).apply();

			prefEdit.putLong("currNDP", currNDP).apply();
			prefEdit.putFloat("currSUM", (float) currSUM).apply();

			if (startTSet == true) {
				writeAvgData();
				startTSet = false;
			}

			writeCommsToFile();
			writeMpgData(fileStates.END);
			File file = new File(Environment.getExternalStorageDirectory(),
					"mpg_data.json");
			MediaScannerConnection.scanFile(this,
					new String[] { file.toString() }, null,
					new MediaScannerConnection.OnScanCompletedListener() {
						public void onScanCompleted(String path, Uri uri) {

						}
					});
			mainText.setText(Double.toString(currSubDispData));
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
			subText.setText("Lifetime AVG: " + temp);

			unitText.setText("AVG " + prefs.getString("units_pref", "MPG")
					+ "\nFOR TRIP");

		} else if (prefs.getLong("numPtsForAvg", 0l) > 0l) {
			contTrip.setVisibility(Button.VISIBLE);

		} else {

			contTrip.setVisibility(Button.GONE);

		}

		startOrSave.setText(R.string.start);
		startOrSave.setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {

				startService(false);

			}
		});

		waiting.setVisibility(ProgressBar.GONE);
	}



	
	public void AlertBox(String title, String message) {
		new AlertDialog.Builder(this).setTitle(title)
				.setMessage(message + "\n Please Press OK")
				.setPositiveButton("OK", new OnClickListener() {
					public void onClick(DialogInterface arg0, int arg1) {

					}
				}).show();
	}

	public void connectExceptAlert(String title, String message) {
		new AlertDialog.Builder(this).setTitle(title).setMessage(message)
				.setPositiveButton("Find Device", new OnClickListener() {
					public void onClick(DialogInterface arg0, int arg1) {
						Intent intent = new Intent(getApplicationContext(),
								BluetoothSettings.class);

						startActivityForResult(intent, 0);
					}
				}).setNegativeButton("Cancel", new OnClickListener() {
					public void onClick(DialogInterface arg0, int arg1) {

					}
				}).show();
	}

}
