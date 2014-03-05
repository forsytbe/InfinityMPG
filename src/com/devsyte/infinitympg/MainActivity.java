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
 *  This is accomplished via an instantiation of the obdService class, which 
 *  upon starting will initiate and maintain a Bluetooth Connection with an ELM327 Device
 *  as a Service.
 *  
 *  Calling 
 *  
 * */
public class MainActivity extends Activity {

	protected BluetoothAdapter mBluetoothAdapter;

	/*
	 * obdService is the class that performs all the heavy-lifting as pertaining
	 * to the ELM327. It will be instantiated once it is confirmed the user's
	 * device supports Bluetooth. From MainActivity one must only call start and
	 * stop on an obdService object, which will then send data directly to the MainActivity
	 */
	protected obdService mobdService = null;


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
	 * ) to the MainActivity handler, where the data accompanying the message
	 * can be interpreted, based on aforementioned flag
	 */
	public static final int WRITE_SCREEN = 1;
	public static final int WRITE_PROMPT = 2;
	public static final int WRITE_FILE = 3;
	public static final int FINISH_IT = 4;
	public static final int CONNECT_SUCCESS = 5;
	public static final int CONNECT_FAILURE = 6;

	/* mMessenger is where the messages from the obdService object will arrive.
	 *  obdService performs all the logic and returns only what needs to be displayed in the main actvity
	 */
	final Messenger mMessenger = new Messenger(new IncomingHandler());

	class IncomingHandler extends Handler {
		@Override
		public void handleMessage(Message msg) {
			Button startOrSave = (Button) findViewById(R.id.start_or_save);
			Button contTrip = (Button) findViewById(R.id.continue_trip);
			ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
			waiting.setVisibility(ProgressBar.GONE);

			switch (msg.what) {
	
			case WRITE_SCREEN:
		
				switch (msg.arg1) {

				case obdService.RETURNED_MPG:

					mainText.setText(msg.getData().getString("disp_str"));
					subText.setText("AVG " + msg.getData().getString("sub_pref_units")
							+ ": " + msg.getData().getString("sub_disp_str"));

					unitText.setText(msg.getData().getString("pref_units"));
					break;
				case obdService.RETURNED_BYTES:
					mainText.setText(msg.getData().getString("disp_str"));

					break;
				}

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
			mobdService = new obdService(this, mMessenger);
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
				startService(data.getBundleExtra("shouldCont").getBoolean("cont"));
				

			}
		}

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
	 * and to stream data upon completion. Data is then displayed via mMessenger.
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

		String deviceName = prefs.getString("bt_device", "None");
		
		
		if (deviceName.equals("None")) {

			Intent intent = new Intent(this, BluetoothSettings.class);
			Bundle b = new Bundle();
			b.putBoolean("cont", cont);
			intent.putExtra("shouldCont", b);
			startActivityForResult(intent, 0); 
		} else {
			String address = deviceName.substring(deviceName.indexOf('\n') + 1);
			// Get the BluetoothDevice object
			BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
			// Attempt to connect to the device
			ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
			waiting.setVisibility(ProgressBar.VISIBLE);


			if (cont) {
				mobdService.connect(device);
				mobdService.doBindService();

			}else{
				mobdService.clearTripData();
				mobdService.connect(device);
				mobdService.doBindService();
				mobdService.setMode(obdService.ServiceMode.MPG_MODE);
			}

		}
	}

	/*
	 * This function is called: When the application is still
	 * connected to the ELM327 and the app is ended by the user (When the user ends the trip
	 * (presses the 'End and Save' button, when a disconnect occurs
	 * (CONNECT_FAILURE message flag is sent from obdService to )
	 */
	public void endAndSave() {
		if (mobdService.isRunning()) {
			mobdService.save();
			mobdService.stop();
		}
		Button startOrSave = (Button) findViewById(R.id.start_or_save);
		Button contTrip = (Button) findViewById(R.id.continue_trip);
		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);



			
			File file = new File(Environment.getExternalStorageDirectory(),
					"mpg_data.json");
			MediaScannerConnection.scanFile(this,
					new String[] { file.toString() }, null,
					new MediaScannerConnection.OnScanCompletedListener() {
						public void onScanCompleted(String path, Uri uri) {

						}
					});


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
