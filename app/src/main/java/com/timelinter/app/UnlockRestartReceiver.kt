package com.timelinter.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class UnlockRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_USER_PRESENT || 
            action == Intent.ACTION_USER_UNLOCKED || 
            action == Intent.ACTION_BOOT_COMPLETED || 
            action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            try {
                val serviceIntent = Intent(context, AppUsageMonitorService::class.java)
                context.startForegroundService(serviceIntent)
                Log.i("UnlockRestartReceiver", "Restarting AppUsageMonitorService on $action")
            } catch (e: Exception) {
                Log.e("UnlockRestartReceiver", "Failed to restart service on $action", e)
            }
        }
    }
}







