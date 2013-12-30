package com.devsyte.infinitympg;


import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;





import java.util.Hashtable;

import android.media.MediaScannerConnection;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.text.format.Time;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.DialogInterface.OnClickListener;
import android.content.SharedPreferences.Editor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.bluetooth.*;

public class MainActivity extends Activity {
    public final static String EXTRA_MESSAGE = "test.example.helloworld.MESSAGE";
	protected BluetoothAdapter mBluetoothAdapter;
	protected obdService mobdService = null;
	protected ArrayAdapter<String> cmdPrompt;
	
	ArrayList<String> mpgDataList = new ArrayList<String>();
	TextView mainText;
	TextView subText;
	TextView appText;
	TextView unitText;
	
	public static final int WRITE_SCREEN = 1;	        
	public static final int WRITE_PROMPT = 2;
	public static final int WRITE_FILE = 3;
	public static final int FINISH_IT = 4;
	public static final int CONNECT_SUCCESS = 5;
	public static final int CONNECT_FAILURE = 6;
	
	public static enum fileStates {START, END, INPROG};
	
	private double currDisplayData = 0.0;
	private double currSubDispData = 0.0;
	private double currMPG = 0.0;
	private long numDataPts = 0L;
	
	private double currSUM = 0.0;
	private long currNDP = 0L;
	private double runningMpgAvg = 0.0;
	private Time start = null;
		

	private boolean startTSet = false;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);


        
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);
        SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        
		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
		waiting.setVisibility(ProgressBar.GONE);
        
        
        cmdPrompt = new ArrayAdapter<String>(this, android.R.layout.list_content);
        appText = (TextView) findViewById(R.id.appName);
        mainText = (TextView) findViewById(R.id.mainDisplay);
        subText = (TextView) findViewById(R.id.subDisplay);
        unitText = (TextView) findViewById(R.id.unitDisplay);
        
        //setTF(this, "font/fedserviceextrabold.TFF", mainText);
        Typeface typeFace = Typeface.createFromAsset(getApplicationContext().getAssets(), "font/Magenta_BBT.ttf");
        appText.setTypeface(typeFace);
        typeFace = Typeface.createFromAsset(getApplicationContext().getAssets(), "font/orbitron-black.otf");
        mainText.setTypeface(typeFace);
        subText.setTypeface(typeFace);
        typeFace = Typeface.createFromAsset(getApplicationContext().getAssets(), "font/orbitron-bold.otf");
        unitText.setTypeface(typeFace);

       // Bitmap bm = BitmapFactory.decodeResource(getResources(), R.drawable.infinity);

        //Drawable d = new BitmapDrawable(getResources(), bm);
        
        
        //appText.setCompoundDrawablesRelativeWithIntrinsicBounds (R.drawable.infinity, 0, 0, 0);
        
        
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		if(mBluetoothAdapter == null){
			AlertBox("blue", "Bluetooth not supported");
			finish();
		}else{
			mobdService = new obdService(this, mHandler);
		}
    }
    
    @Override
    protected void onStart(){
    	super.onStart();
    	
    	SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    	Button contTrip = (Button) findViewById(R.id.continue_trip);
    	Button startOrSave = (Button) findViewById(R.id.start_or_save);
        runningMpgAvg = prefs.getFloat("avgMpg", 0.0f);
        numDataPts = prefs.getLong("numPtsForAvg", 0l);
		currSUM = prefs.getFloat("currSUM", 0.0f);
		currNDP = prefs.getLong("currNDP", 0l);


    	if(currNDP>0l){
        	double calcedAvg = currSUM/currNDP;
    		contTrip.setVisibility(Button.VISIBLE);
        	String unitOutput = prefs.getString("units_pref", "MPG");


	    	if(calcedAvg > 0f){
				if(unitOutput.equals("MPG")){

	    			currSubDispData = calcedAvg;
	
	   			}else if(unitOutput.equals("L/100KM")){
	
	   				
					currSubDispData = 235.2/calcedAvg;
	   			}else if(unitOutput.equals("MPG(UK)")){
	
	   				
	   				currSubDispData = calcedAvg * 1.201;
	   			}
				
	    	}else{
	    		currSubDispData = 0.0;
	    	}
			mainText.setText(Double.toString(currSubDispData));
			
			DecimalFormat df = new DecimalFormat("#.00");
			double ltAVG =0.0;
			if(numDataPts >0L){
				ltAVG = runningMpgAvg/numDataPts;
			}
			String temp;
			if(ltAVG <.1 ){
				temp = "0.0";
			}else{
				
				temp = df.format(ltAVG);

			}
			subText.setText("Lifetime AVG: " + temp );

			unitText.setText("AVG " + prefs.getString("units_pref", "MPG") + "\nFOR TRIP");


    	}else{
    		contTrip.setVisibility(Button.GONE);

    	}

    	if(!mobdService.isConnected()){
    		startOrSave.setText(R.string.start);
    		startOrSave.setOnClickListener(new View.OnClickListener() {
    			@Override
                public void onClick(View v) {
               
                	startService(false);

                }
            });
    	}else{
    		startOrSave.setText(R.string.saveBut);
    		startOrSave.setOnClickListener(new View.OnClickListener() {
    			@Override
                public void onClick(View v) {
                	endAndSave();
                }
            });
    	}
    }

    //Here the preferences are implemented, the data conversions done, and the screen is written
    Handler mHandler = new Handler(){
    	@Override
    	public void handleMessage(Message msg) {
			Button startOrSave = (Button) findViewById(R.id.start_or_save);
    		Button contTrip= (Button) findViewById(R.id.continue_trip);
    		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
    		waiting.setVisibility(ProgressBar.GONE);
    		
    		SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
    		String unitOutput = ""; 
			DecimalFormat df = new DecimalFormat("#.00");

    		switch (msg.what) {
    		
    		case WRITE_PROMPT:
    			
    			cmdPrompt.add(msg.getData().getString("commData"));
    			if(cmdPrompt.getCount()>=128){
    				writeCommsToFile();
    			}
    			
    			
    			break;
    		case WRITE_FILE:
    			
        		
        		start = new Time();
        		start.setToNow();
        		startTSet = true;

				writeCommsToFile();
				writeMpgData(fileStates.START); //write_file message is only sent when the time should be appended (pass true)
    				//writeMpgData(msg.getData().getBoolean("writeTime"));
    			
    			
    			break;
    		case WRITE_SCREEN:
    			Time now = new Time();
    			now.setToNow();
    			String curTime = "\"" + Integer.toString(now.hour) + "h" + Integer.toString(now.minute) + "m" +Integer.toString(now.second)+"s\" : ";
    			
    			unitOutput = prefs.getString("units_pref", "MPG");
    			
    			
    			currMPG = msg.getData().getDouble("mpgData");//this comes back as MPG regardless of user preference
    			++numDataPts;
    			currDisplayData = currMPG;
    			//currMPG is what is written to the file.  The file is ALWAYS stored as MPG, regardless of user preference
    			//When data analysis is available, it will be converted to user spec'd  units.

    			//runningavg will store the *total* number of data points for the file and the avg associated with them
    			//every iteration we just add the new point to the set
    	
    			//Case 0 is standard, engine running displaying mpg.  1 is engine idling, 2 engine is off (mpg = 0)
    			double calcedAvg = 0.0;
    			
    			switch(msg.arg1){

	    			case 0:
	        			currSUM +=currMPG;
	        			++currNDP;
	    				calcedAvg = currSUM/currNDP;
	        			runningMpgAvg  += currMPG;

	        			if(unitOutput.equals("MPG")){
	        				//this will convert km/h to mi/h
	       				 	//this converts a gram of gas/second to gallon/hour
	        				currDisplayData = currMPG;
		        			currSubDispData = calcedAvg;

		       			}else if(unitOutput.equals("L/100KM")){

		       				currDisplayData = 235.2/currMPG;
		       				
		    				currSubDispData = 235.2/calcedAvg;
		       			}else if(unitOutput.equals("MPG(UK)")){
		       				currDisplayData =currMPG*1.201;
		       				
		       				currSubDispData = calcedAvg * 1.201;
		       			}
	        			
	        			break;
	    			case 1:
	        			currSUM +=0.0;
	        			++currNDP;
	    				calcedAvg = currSUM/currNDP;
	        			runningMpgAvg +=  0.0;//0.0 because thats the mpg youre getting
	        			if(unitOutput.contentEquals("MPG")){
	        				//currMPG is coming back as gal/hour for case1
	        				currDisplayData = currMPG;
	        				unitOutput = "G/HR";
	        				
		        			currSubDispData = calcedAvg;

		       			}else if(unitOutput.contentEquals("L/100KM")){
		       				currDisplayData = currMPG*3.7854;
		       				unitOutput = "L/HR";

		    				currSubDispData = 235.2/ calcedAvg;
		       			}else if(unitOutput.contentEquals("MPG(UK)")){
		       				currDisplayData = currMPG*0.83267;
		       				unitOutput = "G(UK)/HR";
		       				currSubDispData =  calcedAvg * 1.201;

		       			}
	        			
		        		if(!prefs.getBoolean("idle_stats_pref",true)){

	    					currDisplayData = 0.0;
	    					unitOutput = prefs.getString("units_pref", "MPG");
	    				}

	        			break;
    			}
    			
    			
    			mpgDataList.add("\t\t" + curTime+  df.format(currMPG) + ",\r");
    			if(mpgDataList.size() >=256){
    				writeMpgData(fileStates.INPROG);
    			}
				
            	

            	
    			mainText.setText(df.format(currDisplayData));
    			subText.setText("AVG "+ prefs.getString("units_pref", "MPG") + ": " + df.format(currSubDispData));
    			unitText.setText(unitOutput);
    			break;
    			
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
        		
    		case CONNECT_FAILURE:
    			//case 0 = the connection never happened, case 1 is a disconnect
    			
    			switch(msg.arg1){
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
    };
    

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        return true;
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle item selection
    	Intent intent;
        switch (item.getItemId()) {
            case R.id.action_settings:
                // Display the fragment as the main content.
            	intent = new Intent(this, Settings.class);
            	startActivityForResult(intent, 1);
                return true;
            case R.id.view_prev_trips:
                // Display the fragment as the main content.
            	intent = new Intent(this, ViewPrevActivity.class);
            	startActivity(intent);
                return true;
                
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {

    	switch(requestCode){
    	
    	case 0:
	    	if (resultCode == Activity.RESULT_OK) {
	    		
				SharedPreferences.Editor prefs = PreferenceManager.getDefaultSharedPreferences(this).edit();
		        String deviceData = data.getExtras()
		                .getString(BluetoothSettings.DEVICE_DATA);
		        prefs.putString("bt_device", deviceData).apply();
		        
			}
    	}
	    		
    		
    }

    private void connectDevice(String deviceData){
        // Get the device MAC address

    	String address = deviceData.substring(deviceData.indexOf('\n')+1);
        // Get the BluetoothDevice object
        BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
        // Attempt to connect to the device
		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);
		waiting.setVisibility(ProgressBar.VISIBLE);
        mobdService.connect(device);
        

        
        
    }
    
    public void findDevice(View view){
    	Intent intent = new Intent(this, BluetoothSettings.class);
        
		startActivityForResult(intent, 0);
    	
    }
    
    @Override
    public void onRestart(){

    	super.onRestart();
    }

    
    @Override
    public void onStop(){
    	if(mobdService.isConnected()){
    		endAndSave();
    	}

		super.onStop();
    }
    
    public void continueTrip(View v){
    	SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());


    	startService(true);
    }
    
	public void startService(boolean cont ){
		SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
    	
		if(!cont){
			currSUM = 0.0;
			currNDP = 1L;
		}
    	String deviceName = prefs.getString("bt_device", "None");
    	if(deviceName.equals("None")){
    
    	
    		Intent intent = new Intent(this, BluetoothSettings.class);
    


    		startActivityForResult(intent, 0); //My displayMessageActivity needs renamed, but this allows the user to select a BT device
    	}else{

    		connectDevice(deviceName);

    	}
    }
  
	public void endAndSave(){
		mobdService.stop();
		Button startOrSave = (Button) findViewById(R.id.start_or_save);
		Button contTrip = (Button) findViewById(R.id.continue_trip);
		ProgressBar waiting = (ProgressBar) findViewById(R.id.loadingContent);

		SharedPreferences prefs=PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
		SharedPreferences.Editor prefEdit = prefs.edit();

		if(currNDP > 0l){
			
				contTrip.setVisibility(Button.VISIBLE);

				prefEdit.putLong("numPtsForAvg", numDataPts).apply();
				prefEdit.putFloat("avgMPG", (float)runningMpgAvg).apply();
				
				prefEdit.putLong("currNDP", currNDP).apply();
				prefEdit.putFloat("currSUM", (float)currSUM).apply();
				
				if(startTSet == true){
						writeAvgData();
						startTSet = false;
				}


	
				writeCommsToFile();
				writeMpgData(fileStates.END);
				File file = new File(Environment.getExternalStorageDirectory(), "mpg_data.json");
				MediaScannerConnection.scanFile(this,  new String[] {file.toString()}, null, new MediaScannerConnection.OnScanCompletedListener() {
				      public void onScanCompleted(String path, Uri uri) {
		
				      }
				 });
				mainText.setText(Double.toString(currSubDispData));
				DecimalFormat df = new DecimalFormat("#.00");
				double ltAVG = 0.0;
				if(numDataPts >0L){
					ltAVG = runningMpgAvg/numDataPts;
				}
				
				String temp;
				if(ltAVG <.1 ){
					temp = "0.0";
				}else{
				
					temp = df.format(ltAVG);

				}
				subText.setText("Lifetime AVG: " + temp );
			
	
				unitText.setText("AVG " + prefs.getString("units_pref", "MPG") + "\nFOR TRIP");
			
		}else if(prefs.getLong("numPtsForAvg", 0l)> 0l){
			contTrip.setVisibility(Button.VISIBLE);

			
		}else{
		
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
	
    public void writeCommsToFile(){
    	
    	File file = new File(Environment.getExternalStorageDirectory(), "ELM327comm_data.txt");
		
		String str = "";
		try {
			 BufferedWriter bW;
	
	         bW = new BufferedWriter(new FileWriter(file,true));
	    	if(cmdPrompt.getCount() >0){
		    	for(int i =0; i< cmdPrompt.getCount(); ++i){
		    		str = str.concat(cmdPrompt.getItem(i));
		    	}
		    	cmdPrompt.clear();
		    	
				bW.write(str);
				bW.newLine();
	            bW.flush();
	            bW.close();
	    	}
			
		}catch (IOException e) {}
		
    }

    
        public void writeAvgData(){
    		File file = new File(Environment.getExternalStorageDirectory(), "mpg_avgs.json");
    	
    			
    			String str = "";
    			try {
    				 BufferedWriter bW;
    				 DecimalFormat df = new DecimalFormat("#.00");
    				 double calcedAvg = currSUM/currNDP;

    				 
    		         bW = new BufferedWriter(new FileWriter(file,true));
    		         str= str.concat("Session : {\r");
    		         String date = "\t\"Started\" : " + "\"" + Integer.toString(start.year)+"-"+Integer.toString(start.month+1)+"-"+Integer.toString(start.monthDay)+
    		        		 "  "+Integer.toString(start.hour)+"h"+Integer.toString(start.minute)+"m"  + "\"," +"\r";
    		        str = str.concat(date);
    		        str = str.concat("\t" + "\""+ "AverageMPG" + "\" : " +  df.format(calcedAvg)+",\r");
    				Time now = new Time();
    				now.setToNow();
    				date = "\t\"Ended\" : " +  "\"" + Integer.toString(now.year)+"-"+Integer.toString(now.month+1)+"-"+Integer.toString(now.monthDay)+
    		        		 "  "+Integer.toString(now.hour)+"h"+Integer.toString(now.minute)+ "m" +  "\"" +"\r}\r";
    				str = str.concat(date);
    		    
    		    	
    				bW.write(str);
    				bW.newLine();
    	            bW.flush();
    	            bW.close();
    	        	MediaScannerConnection.scanFile(this,  new String[] {file.toString()}, null, new MediaScannerConnection.OnScanCompletedListener() {
    	      	      public void onScanCompleted(String path, Uri uri) {
    	
    	      	      }
    	      	 });
    			}catch (IOException e) {
    				
    			}
    	    	
    	    }

    public void writeMpgData(fileStates currState){
    	
    	File file = new File(Environment.getExternalStorageDirectory(), "mpg_data.json");

		
		String str = "";
		try {
			 BufferedWriter bW;
	
	         bW = new BufferedWriter(new FileWriter(file,true));
	         
	    	if(currState == fileStates.START){
				Time now = new Time();
				now.setToNow();
				 str =str.concat("Session : {\r");
		         String date = "\t\"Started\" : " +  "\"" + Integer.toString(now.year)+"-"+Integer.toString(now.month+1)+"-"+Integer.toString(now.monthDay)+
		        		 "  "+Integer.toString(now.hour)+"h"+Integer.toString(now.minute)+ "m" +  "\""  +"\r\t"  +  "\"MpgValArr\" : [\r";
		    
				str = str.concat(date);
	    	}

	    	for(int i =0; i< mpgDataList.size(); ++i){
	    		str = str.concat(mpgDataList.get(i));
	    	}
	    	mpgDataList.clear();
	    	

	         
	    	if(currState == fileStates.END){
				Time now = new Time();
				now.setToNow();

		         String date = "\t]\r\t\"Ended\" : " +  "\"" + Integer.toString(now.year)+"-"+Integer.toString(now.month+1)+"-"+Integer.toString(now.monthDay)+
		        		 "  "+Integer.toString(now.hour)+"h"+Integer.toString(now.minute)+ "m"  +  "\""+"\r}";
		    
				str = str.concat(date);
	    	}
	    	
	    	
			bW.write(str);
			bW.newLine();
            bW.flush();
            bW.close();
			
		}catch (IOException e) {
			
		}

    }
   
    
	public void AlertBox( String title, String message ){
	    new AlertDialog.Builder(this)
	    .setTitle( title )
	    .setMessage( message + "\n Please Press OK" )
	    .setPositiveButton("OK", new OnClickListener() {
	        public void onClick(DialogInterface arg0, int arg1) {
	   
	        }
	    }).show();
	  }
	
	public void connectExceptAlert(String title, String message){
	    new AlertDialog.Builder(this)
	    .setTitle( title )
	    .setMessage( message )
	    .setPositiveButton("Find Device", new OnClickListener() {
	        public void onClick(DialogInterface arg0, int arg1) {
	    		Intent intent = new Intent(getApplicationContext(), BluetoothSettings.class);
	    	    
	    		startActivityForResult(intent, 0);
	        }
	    })
	    .setNegativeButton("Cancel", new OnClickListener() {
	        public void onClick(DialogInterface arg0, int arg1) {
	   
	        }
	    }).show();
	}
    
}

