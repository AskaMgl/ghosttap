package com.aska.ghosttap;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

/**
 * 开机广播接收器
 * 设备重启后自动启动 GhostTap 服务
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = Config.LOG_TAG + ".BootReceiver";
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed, checking if service should start");
            
            SharedPreferences prefs = context.getSharedPreferences(Config.PREFS_NAME, Context.MODE_PRIVATE);
            String userId = prefs.getString(Config.PREF_USER_ID, null);
            String serverUrl = prefs.getString(Config.PREF_SERVER_URL, null);
            
            // 如果用户已配置，自动启动服务
            if (userId != null && serverUrl != null && !serverUrl.contains("your-server.com")) {
                Log.i(TAG, "Auto-starting GhostTap service");
                Intent serviceIntent = new Intent(context, GhostTapService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent);
                } else {
                    context.startService(serviceIntent);
                }
            } else {
                Log.i(TAG, "Not auto-starting: user not configured yet");
            }
        }
    }
}
