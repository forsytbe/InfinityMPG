package com.devsyte.infinitympg;


import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;


public class SettingsFragment extends PreferenceFragment {

	SharedPreferences.OnSharedPreferenceChangeListener listener= new SharedPreferences.OnSharedPreferenceChangeListener() {
	  	  public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
		        if(key.equals("bt_device")) {
		        	
		            Preference pref = (Preference) findPreference(key);
			        String summary =pref.getSharedPreferences().getString("bt_device", "None");
			        pref.setSummary(summary);
		        }else if(key.equals("units_pref")){
		        	 Preference pref = (Preference) findPreference(key);
		        	 
				        String summary =pref.getSharedPreferences().getString("units_pref", "None");
				        pref.setSummary(summary);
		        }
		  }
		};
	@Override
  public void onCreate(Bundle savedInstanceState) {
      super.onCreate(savedInstanceState);
      PreferenceManager.getDefaultSharedPreferences(this.getActivity())
      .registerOnSharedPreferenceChangeListener(listener);
      // Load the preferences from an XML resource
  	
      addPreferencesFromResource(R.xml.preferences);
      
      Preference pref = (Preference) findPreference("units_pref");
      String summary = pref.getSharedPreferences().getString("units_pref", "Mi/G");
      pref.setSummary(summary);
      
      pref = (Preference) findPreference("bt_device");
      summary = pref.getSharedPreferences().getString("bt_device", "None");
      pref.setSummary(summary);
      pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
        @Override
        public boolean onPreferenceClick(Preference preference) {
      	  

      	startActivityForResult(preference.getIntent(), 0);
      	preference.getSharedPreferences().registerOnSharedPreferenceChangeListener(listener);
          return true;
        }
      });
  }
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data){
		if (resultCode == Activity.RESULT_OK) {
	        Preference pref = (Preference) findPreference("bt_device");
			SharedPreferences.Editor prefs = pref.getSharedPreferences().edit();
	        String deviceData = data.getExtras()
	                .getString(BluetoothSettings.DEVICE_DATA);
	        prefs.putString("bt_device", deviceData).apply();

		}
  }

  
  @Override
  public void onDestroy() {
      super.onDestroy();
      PreferenceManager.getDefaultSharedPreferences(this.getActivity())
              .unregisterOnSharedPreferenceChangeListener(listener);
  }
	
}
