package com.devsyte.infinitympg;


import android.os.Bundle;

import android.bluetooth.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.app.Activity;
import android.app.AlertDialog;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.support.v4.app.NavUtils;
import android.annotation.TargetApi;
import android.os.Build;
import android.preference.PreferenceManager;
import android.provider.Settings;


/*  This activity is responsible for presenting a list of available Bluetooth devices,
 *  and prompting the user to select a device as the default.  The app will attempt to connect after
 *  the user has selected a device.  Android should prompt the user to pair the ELM327 device upon 
 *  an attempted connection to an unpaired device.
 *  
 *  This activity gains focus upon either:
 *    User selection from the 'Settings' activity, or
 *    redirection upon user attempting to start a trip without an available default device
 * 
 * 
 * 
 * 
 */
public class BluetoothSettings extends Activity {
	
	protected ArrayAdapter<String> mArrayAdapter;
	protected BluetoothAdapter mBluetoothAdapter;
	
		
	private final BroadcastReceiver mReceiver = new BroadcastReceiver(){
		public void onReceive(Context context, Intent intent){
			String action = intent.getAction();
			if(BluetoothDevice.ACTION_FOUND.equals(action)){
				BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				mArrayAdapter.add(device.getName()+"\n"+ device.getAddress());
			}
		}
	};
	
	public void toSettings(View view){
		Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
			startActivity(intent);
	}
	
	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
	private void setupActionBar() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
			getActionBar().setDisplayHomeAsUpEnabled(false);
		}
	}
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setupActionBar();
		
		
		
		mArrayAdapter = new ArrayAdapter<String>(this, R.layout.custom_list_item_1);

		setContentView(R.layout.activity_bluetooth_settings);
		
		String message;
		int REQUEST_ENABLE_BT = 1;
		int ACT_RESULT = 1;	
		
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    	String deviceName = prefs.getString("bt_device", "None");
    	if(deviceName.equals("None")){
    		new AlertDialog.Builder(this)
    	    .setTitle( "No Default Device" )
    	    .setMessage( "No default Bluetooth device found.  Connect to a device.")
    	    .setPositiveButton("OK", new OnClickListener() {
    	        public void onClick(DialogInterface arg0, int arg1) {
    	            
    	        }
    	    }).show();
    		
    	}
		
		
		
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		
		if(mBluetoothAdapter == null){
			message = "Bluetooth not supported";
			finish();
		}else{
			message = "Available Devices:";

			IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
			registerReceiver(mReceiver, filter); // Don't forget to unregister during onDestroy
			
			if(!mBluetoothAdapter.isEnabled()){
				Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
				startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
				onActivityResult(REQUEST_ENABLE_BT, ACT_RESULT, enableBtIntent);
				if(ACT_RESULT == RESULT_CANCELED){
					message = "You must enable Bluetooth.";
				}
			}
			
			mBluetoothAdapter.startDiscovery();
		}

		ListView devListView = (ListView) findViewById(R.id.btDevList);
		
		devListView.setAdapter(mArrayAdapter);
		devListView.setOnItemClickListener(new OnItemClickListener(){
			
			public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
					mBluetoothAdapter.cancelDiscovery();
			      	String deviceData = mArrayAdapter.getItem(position);
			      	
		            Intent intent = new Intent();
		            intent.putExtra("DEVICE_DATA", deviceData);
		            intent.putExtra("shouldCont", getIntent().getBooleanExtra("shouldCont", false));
		            if (getParent() == null) {
		                setResult(Activity.RESULT_OK, intent);
		            } else {
		                getParent().setResult(Activity.RESULT_OK, intent);
		            }
		            
		            // Set result and finish this Activity
		            finish();
			       
			       
			       
			   }
		});
		
		TextView btStatView = (TextView) findViewById(R.id.btStatus);
		btStatView.setText(message);		
	}
	
    @Override
    public void onDestroy(){
    	unregisterReceiver(mReceiver);
    	super.onDestroy();
    	
    }
    
    @Override
    public void onStop(){
    	if(mBluetoothAdapter.isDiscovering()){
    		mBluetoothAdapter.cancelDiscovery();
    	}
    	super.onStop();
    }
	
	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case android.R.id.home:

			NavUtils.navigateUpFromSameTask(this);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
	
	public void AlertBox( String title, String message ){
	    new AlertDialog.Builder(this)
	    .setTitle( title )
	    .setMessage( message + " Press OK to exit." )
	    .setPositiveButton("OK", new OnClickListener() {
	        public void onClick(DialogInterface arg0, int arg1) {

	        }
	    }).show();
	  }
	
}
