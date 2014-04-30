package com.devsyte.infinitympg;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.app.Activity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.support.v4.app.NavUtils;

/* This Activity currently is accesssed thrrough the 'Settings' option bar
 * 
 * Currently, it simply allows for the viewing of all of the previous trips.
 * Display format could be prettied up, it currently just prints the straight JSON
 *    */
public class ViewPrevActivity extends Activity {
	public static final int FINISHED = 0;
	protected ArrayAdapter<String> mArrayAdapter;
	
	private class ReadFileThread extends Thread{
			
			String fname, currItem;
			boolean inItem = false; 

			
			public ReadFileThread(String filename){

				fname = filename;
			}
			
			
			public void run(){
				String inp = "", tmp = "";
				currItem = "\n";

				 try {
					File file = new File(Environment.getExternalStorageDirectory(),fname);
					BufferedReader buf = new BufferedReader(new FileReader(file));
					while(true){
						try {
							inp = buf.readLine();
						} catch (IOException e) {
							try {
								buf.close();
							} catch (IOException e1) {
								
							}

							break;
						}
						
						if(inp == null){
							break;
						}else{
							
							if(inp.contains("{")){
								inItem = true;
								continue;
							
							}else if(inp.contains("}")){
								inItem = false;
								mArrayAdapter.add(currItem);
								currItem = "\n";
								continue;
								
							}
							
							
							if(inItem){
								tmp = inp;

								currItem += tmp + "\n";
							}
							
						}
							
					}
					
					
					try {
						buf.close();
					} catch (IOException e1) {

					}
					Message message = mHandler.obtainMessage(ViewPrevActivity.FINISHED, -1, -1);

					message.sendToTarget();

					
				} catch (FileNotFoundException e) {
					mArrayAdapter.add("No Previous Trips");
					Message message = mHandler.obtainMessage(ViewPrevActivity.FINISHED, -1, -1);

					message.sendToTarget();
				}

				 
		

				
			}
	};
	
	 public Handler mHandler = new Handler(){
	    	@Override
	    	public void handleMessage(Message msg) {
	    		switch (msg.what) {
	    		case ViewPrevActivity.FINISHED:
	    			ListView devListView = (ListView) findViewById(R.id.trip_list);
				
	    			devListView.setAdapter(mArrayAdapter);
	    		}
	    	}
	};
	
	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		
		mArrayAdapter = new ArrayAdapter<String>(this, R.layout.custom_list_item_1);
		
		
		setContentView(R.layout.activity_view_prev);
		// Show the Up button in the action bar.
		setupActionBar();
		
		ReadFileThread rfThread = new ReadFileThread( "mpg_avgs.json");
		rfThread.run();

	}

	/**
	 * Set up the {@link android.app.ActionBar}.
	 */
	private void setupActionBar() {

		getActionBar().setDisplayHomeAsUpEnabled(false);

	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		// Inflate the menu; this adds items to the action bar if it is present.
		getMenuInflater().inflate(R.menu.view_prev, menu);
		return true;
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

}
