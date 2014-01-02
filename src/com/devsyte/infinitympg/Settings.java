package com.devsyte.infinitympg;



import android.os.Bundle;
import android.preference.PreferenceActivity;


/*  This activity simply starts and immediately displays the fragment that shows the various preferences*/
public class Settings extends PreferenceActivity{
	

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Display the fragment as the main content.
      

        
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }
}
