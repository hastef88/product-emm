/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.emm.agent;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import org.wso2.emm.agent.services.AgentDeviceAdminReceiver;
import org.wso2.emm.agent.utils.Constants;
import org.wso2.emm.agent.utils.Preference;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static android.os.UserManager.DISALLOW_ADD_USER;
import static android.os.UserManager.DISALLOW_ADJUST_VOLUME;
import static android.os.UserManager.DISALLOW_FACTORY_RESET;
import static android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA;
import static android.os.UserManager.DISALLOW_SAFE_BOOT;

public class KioskAppActivity extends Activity {

    private static final String TAG = "KioskModeActivity";

    private static final String KIOSK_PREFERENCE_FILE = "kiosk_preference_file";
    private static final String KIOSK_APPS_KEY = "kiosk_apps";

    public static final String LOCKED_APP_PACKAGE_LIST
            = "com.afwsamples.testdpc.policy.locktask.LOCKED_APP_PACKAGE_LIST";

    private ComponentName adminComponentName;
    private ArrayList<String> kioskPackages;
    private DevicePolicyManager devicePolicyManager;
    private PackageManager packageManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        adminComponentName = AgentDeviceAdminReceiver.getComponentName(this);
        devicePolicyManager = (DevicePolicyManager) getSystemService(
                Context.DEVICE_POLICY_SERVICE);
        packageManager = getPackageManager();

        if (Preference.getBoolean(this, Constants.PreferenceFlag.KIOSK_MODE)) {
            boolean isKioskDisabled = getIntent().getBooleanExtra(Constants.DISABLE_KIOSK_MODE, false);
            if (isKioskDisabled) {
                onBackPressed();
            }
        }

        // check if a new list of apps was sent, otherwise fall back to saved list
        String[] packageArray = getIntent().getStringArrayExtra(LOCKED_APP_PACKAGE_LIST);
        if (packageArray != null) {
            kioskPackages = new ArrayList<>();
            for (String pkg : packageArray) {
                kioskPackages.add(pkg);
            }
            kioskPackages.remove(getPackageName());
            kioskPackages.add(getPackageName());
            setDefaultKioskPolicies(true);
        } else {
            // after a reboot there is no need to set the policies again
            SharedPreferences sharedPreferences = getSharedPreferences(KIOSK_PREFERENCE_FILE,
                                                                       MODE_PRIVATE);
            kioskPackages = new ArrayList<>(sharedPreferences.getStringSet(KIOSK_APPS_KEY,
                                                                            new HashSet<String>()));
        }

        // remove TestDPC package and add to end of list; it will act as back door
        kioskPackages.remove(getPackageName());
        kioskPackages.add(getPackageName());

        // create list view with all kiosk packages
        final KioskAppsArrayAdapter kioskAppsArrayAdapter =
                new KioskAppsArrayAdapter(this, R.id.pkg_name, kioskPackages);
        ListView listView = new ListView(this);
        listView.setAdapter(kioskAppsArrayAdapter);
        listView.setOnItemClickListener(
                new AdapterView.OnItemClickListener() {
                    @Override
                    public void onItemClick(AdapterView<?> parent, View view, int position,
                                            long id) {
                        kioskAppsArrayAdapter.onItemClick(parent, view, position, id);
                    }
                });
        setContentView(listView);
    }

    @Override
    protected void onStart() {
        super.onStart();

        // start lock task mode if it's not already active
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        // ActivityManager.getLockTaskModeState api is not available in pre-M.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            if (!am.isInLockTaskMode()) {
                startLockTask();
                enableKioskMode();
            }
        } else {
            if (am.getLockTaskModeState() == ActivityManager.LOCK_TASK_MODE_NONE) {
                startLockTask();
                enableKioskMode();
            }
        }
    }

    @Override
    public void onBackPressed() {
        // do nothing
    }

    private void enableKioskMode() {
        Preference.putBoolean(this, Constants.PreferenceFlag.KIOSK_MODE, true);
    }

    private void disableKioskMode() {
        Preference.putBoolean(this, Constants.PreferenceFlag.KIOSK_MODE, false);
    }

    public void onBackdoorClicked() {
        stopLockTask();
        disableKioskMode();
        setDefaultKioskPolicies(false);
        packageManager.setComponentEnabledSetting(
                new ComponentName(getPackageName(), getClass().getName()),
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                PackageManager.DONT_KILL_APP);
        finish();
    }

    private void setUserRestriction(String restriction, boolean disallow) {
        if (disallow) {
            devicePolicyManager.addUserRestriction(adminComponentName, restriction);
        } else {
            devicePolicyManager.clearUserRestriction(adminComponentName, restriction);
        }
    }

    private void setDefaultKioskPolicies(boolean active) {
        // set user restrictions
        setUserRestriction(DISALLOW_SAFE_BOOT, active);
        setUserRestriction(DISALLOW_FACTORY_RESET, active);
        setUserRestriction(DISALLOW_ADD_USER, active);
        setUserRestriction(DISALLOW_MOUNT_PHYSICAL_MEDIA, active);
        setUserRestriction(DISALLOW_ADJUST_VOLUME, active);

        // disable keyguard and status bar
        devicePolicyManager.setKeyguardDisabled(adminComponentName, active);
        devicePolicyManager.setStatusBarDisabled(adminComponentName, active);

        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_MAIN);
        intentFilter.addCategory(Intent.CATEGORY_HOME);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);

        // set lock task packages
        devicePolicyManager.setLockTaskPackages(adminComponentName,
                                                 active ? kioskPackages.toArray(new String[]{}) : new String[]{});
        SharedPreferences sharedPreferences = getSharedPreferences(KIOSK_PREFERENCE_FILE,
                                                                   MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        if (active) {
            // set kiosk activity as home intent receiver
            devicePolicyManager.addPersistentPreferredActivity(adminComponentName, intentFilter,
                                                                new ComponentName(getPackageName(),
                                                                                  KioskAppActivity.class.getName()));
            editor.putStringSet(KIOSK_APPS_KEY, new HashSet<String>(kioskPackages));
        } else {
            devicePolicyManager.clearPackagePersistentPreferredActivities(adminComponentName,
                                                                           getPackageName());
            editor.remove(KIOSK_APPS_KEY);
        }
        editor.commit();
    }

    private class KioskAppsArrayAdapter extends ArrayAdapter<String> implements AdapterView.OnItemClickListener {

        public KioskAppsArrayAdapter(Context context, int resource, List<String > objects) {
            super(context, resource, objects);
        }

        @Override
        public View getView(final int position, View convertView, ViewGroup parent) {
            ApplicationInfo applicationInfo;
            try {
                applicationInfo = packageManager.getApplicationInfo(
                        getItem(position), 0);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Fail to retrieve application info for the entry: " + position, e);
                return null;
            }

            if (convertView == null) {
                convertView = LayoutInflater.from(getContext()).inflate(R.layout.kiosk_mode_item, parent, false);
            }
            ImageView iconImageView = (ImageView) convertView.findViewById(R.id.pkg_icon);
            iconImageView.setImageDrawable(applicationInfo.loadIcon(packageManager));
            TextView pkgNameTextView = (TextView) convertView.findViewById(R.id.pkg_name);
            if (getPackageName().equals(getItem(position))) {
                // back door
                pkgNameTextView.setText(getString(R.string.exit_kiosk_mode));
            } else {
                pkgNameTextView.setText(applicationInfo.loadLabel(packageManager));
            }
            return convertView;
        }

        @Override
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (getPackageName().equals(getItem(position))) {
                onBackdoorClicked();
            }
            PackageManager pm = getPackageManager();
            startActivity(pm.getLaunchIntentForPackage(getItem(position)));
        }
    }
}