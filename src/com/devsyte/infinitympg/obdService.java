package com.devsyte.infinitympg;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.text.DecimalFormat;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;


/*  This is the primary class for establishing, and maintaining the ELM327 Bluetooth Connection,
 *  As well as polling for data and the computations that need to be performed on that data.
 *  
 *  The threads involved communicate with the MainActivity via the handler that is passed to the
 *  obdService constructor
 *   
 *   */
public class obdService {
	
	
	Handler mHandler;
	BluetoothAdapter mBluetoothAdapter;
	ConnectThread mConnectThread;
	ConnectedThread mConnectedThread;
	
	
	Context parentContext;
	public static final int STATE_UNCONNECTED = 0;
	public static final int STATE_CONNECTED = 1;
	public int mState = 0;
	
	public double vSpeed = 0; //vehicle speed in km/h
	public double MAF = 0; //mass air flow, g/s
	public double MPG = 0; //miles/gallon
	
	
	/*
	 * Just some useful constants for unit conversion
	 * 
	 */
	public static final double  gramGasToGal = 2835.0;
	public static final double gramGasToImpGal = 3410.0;
	public static final double gramGasToLiter = 750.0;
	public static final double literGasToGal = 0.264172;
	public static final double galGasToImpGal = 0.832674;
	public static final double kmToMi = .621371;
	public static final double miToKm = 1.60934;
	public static final double stoichRatio = (1.0/14.7);
	
	

	

 	protected obdService(Context context, Handler handler){
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
		mHandler = handler;
		parentContext = context;
	}
	
	public synchronized void stop(){
	    if (mConnectThread != null) {
	        mConnectThread.cancel();
	        mConnectThread = null;
	    }
	
	    if (mConnectedThread != null) {
	        mConnectedThread.cancel();
	        mConnectedThread = null;
	    }
		mState = STATE_UNCONNECTED;


	}
	
	public Boolean isConnected(){
		if(mState == STATE_CONNECTED){
			return true;
		}else{
			return false;
		}
	
	}
	
	/*  This thread establishes a connection with the ELM327
	 * 
	 * Relays failure or success to MainActivity via a message to the handler
	 */
	private class ConnectThread extends Thread{
		
		private final BluetoothSocket mmSocket;

		
		
		public ConnectThread(BluetoothDevice device){
			BluetoothSocket tmp = null;


			try{
				Method m = device.getClass().getMethod("createRfcommSocket", new Class[] {int.class});
		        tmp = (BluetoothSocket) m.invoke(device, 1);

			}catch(Exception e){}
			
			mmSocket = tmp;
		}
		
		public void run(){
			
			try{
				mmSocket.connect();
				

				Message message = mHandler.obtainMessage(MainActivity.CONNECT_SUCCESS, -1, -1);

				message.sendToTarget();
			
				
			}catch(IOException connectException){
		
    			
				Message message = mHandler.obtainMessage(MainActivity.CONNECT_FAILURE, 0, -1);

				message.sendToTarget();
				
				
				try{
					mmSocket.close();
				}
				catch(IOException closeException){}

				//AlertBox("IOExcept", connectException.toString());
				return;
			}
			
			mState = STATE_CONNECTED;
			connected(mmSocket);
			
		}
		

		
		public void cancel(){
			try{
				mmSocket.close();
				mState = STATE_UNCONNECTED;
			}catch (IOException e){}
		}
		
	};
	
	/* This thread manages the connected ELM327 device, 
	 * and performs most of the logic necessary to display fuel economy data to the user
	 * 
	 * The commands in run() and parseResponse() are ELM327 standard commands
	 * 	(review your ELM327 device documentation)
	 * 	and standard OBD-II PID's, which are relayed from the ELM327 to the vehicles
	 *  on-board computer in a format specified by the ELM327
	 *  
	 *  A fairly comphrehensive list of standard PID's can be reviewed on the
	 *  wikipedia page for 'OBD-II PIDs'
	 */
	private class ConnectedThread extends Thread{
		
		private final BluetoothSocket mmSocket;
		private final InputStream mmInStream;
		private final OutputStream mmOutStream;
		private int numSent = 0;
		private String obdCommand = "";
		private String reply = "";
		
		public ConnectedThread(BluetoothSocket socket){
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;
			
			try{
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			}catch(IOException e){}
			
			mmInStream = tmpIn;
			mmOutStream = tmpOut;
		}
		
		@SuppressLint("NewApi")
		public void run(){

			byte[] buffer = new byte[8];
			int bytes;
			
			//first message we want to resest device, turn off echo, try to set protocal to Iso 9141
			//isConnected is API 14 or greater
			Message message = new Message();
				
			message = mHandler.obtainMessage(MainActivity.WRITE_FILE, -1, -1);
			
			Bundle bundle = new Bundle();
			bundle.putBoolean("writeTime",true);
			message.setData(bundle);
			message.sendToTarget();
			
			
			obdCommand = "AT WS\r";
			try{
				write(obdCommand.getBytes());

			
				while(mmSocket.isConnected()){
	
							bytes = mmInStream.read(buffer); //this throws an exception as well
							
							String sb = new String(buffer, 0, bytes);
						
							if(bytes > 0 ){
								message = mHandler.obtainMessage(MainActivity.WRITE_PROMPT, -1, -1);
		
								
								bundle = new Bundle();
								bundle.putString("commData", sb);
								message.setData(bundle);
								message.sendToTarget();

								parseResponse(sb, bytes);	//this throws an exception if write fails

					
							}				

				}
			
			}catch(IOException e){
			
			
		
				mState = STATE_UNCONNECTED;
				message = mHandler.obtainMessage(MainActivity.CONNECT_FAILURE, 1, -1);
	
				message.sendToTarget();
				cancel();
			}
			
		}
		
		public void parseResponse(String response, int numBytes) throws IOException{
			String tmpStr = new String();
		
			int byteOne, byteTwo;
			Bundle bundle = new Bundle();
			
			
			if(response.charAt(response.length()-1) == '>'){
				switch(numSent){
				case 0: 
					obdCommand = "AT SPA3\r";
					++numSent;
					break;
				case 1:
					obdCommand = "AT E0\r";
					++numSent;
					break;
				case 2:
					obdCommand = "01 0D\r";
					++numSent;
					break;
				case 3:
					obdCommand = "01 10\r";
					++numSent; //this is just incase i add more cases, 
					numSent =2;
					break;
				}
				Message message = mHandler.obtainMessage(MainActivity.WRITE_PROMPT, -1, -1);
				
				bundle.putString("commData", obdCommand);
				message.setData(bundle);
				message.sendToTarget();
				try{
					write(obdCommand.getBytes());
				}catch(IOException e){
					throw new IOException();
				}
			}
			
			if(response.contains("41")){
				reply = response.substring(response.indexOf("41"));
			}else if(!reply.isEmpty()){
				reply = reply.concat(response);
			}

			if(reply.contains("\r")){
				reply = reply.substring(reply.indexOf("41"), reply.indexOf("\r")-1);


				if(reply.contains("41 0D")){
					tmpStr = reply.substring(6);//this only returns one byte, so this is that byte
					byteOne = Integer.parseInt(tmpStr, 16);
					vSpeed = byteOne;
				}else if(reply.contains("41 10")){
					tmpStr = reply.substring(6);
					byteOne = Integer.parseInt(tmpStr.substring(0, tmpStr.indexOf(" ")), 16);
					byteTwo = Integer.parseInt(tmpStr.substring(tmpStr.indexOf(" ")+1), 16);
					MAF = (((double)byteOne*256.0)+(double)byteTwo)/100.0;

					DecimalFormat df = new DecimalFormat("#.##");

					
					bundle = new Bundle();
					Message calcMessage = new Message();
		
						if(Double.valueOf(df.format(vSpeed)) <= 0.5){
							
							MPG = (MAF*obdService.stoichRatio*3600.0)/obdService.gramGasToGal; //gallons per hour, MAF is in gram/second

							calcMessage = mHandler.obtainMessage(MainActivity.WRITE_SCREEN, 1, -1);
	
						}else{
							//miles pergallon, vspeed is in km/hr, MAF is in grams/seconds
							MPG = (vSpeed*obdService.kmToMi)/((MAF*obdService.stoichRatio*3600.0)/obdService.gramGasToGal);

							calcMessage = mHandler.obtainMessage(MainActivity.WRITE_SCREEN, 0, -1);
							
						}
			

					bundle.putDouble("mpgData", MPG);


					calcMessage.setData(bundle);
					calcMessage.sendToTarget();
				}
				reply = "";
			}

			
			
		}
		
		
		public void write(byte[] data) throws IOException{
				
			try{
				mmOutStream.write(data);
			}catch(IOException e){
				throw new IOException();
			}
		}
		
		
		
		public void cancel(){
			try{
				mmInStream.close();
				mmOutStream.close();
				mmSocket.close();
				mState = STATE_UNCONNECTED;

			}catch(IOException e){}
		}	
	};

	public synchronized void connect(BluetoothDevice device){
		mConnectThread = new ConnectThread(device);
		mConnectThread.start();

	}
	
	public synchronized void connected(BluetoothSocket socket){
		//mConnectThread.cancel();
		mConnectedThread = new ConnectedThread(socket);
		mConnectedThread.start();
	}
	
    public void write(byte[] out) throws IOException{
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }

	public void AlertBox( String title, String message ){
	    new AlertDialog.Builder(parentContext)
	    .setTitle( title )
	    .setMessage( message)
	    .setPositiveButton("OK", new OnClickListener() {
	        public void onClick(DialogInterface arg0, int arg1) {
	          //finish();
	        }
	    }).show();
	  }
    
}


