package com.novadial.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import com.novadial.phone.helpers.MissedCallNotifier

class MissedCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == TelecomManager.ACTION_SHOW_MISSED_CALLS_NOTIFICATION) {
            val count = intent.getIntExtra(TelecomManager.EXTRA_NOTIFICATION_COUNT, 0)
            val number = intent.getStringExtra(TelecomManager.EXTRA_NOTIFICATION_PHONE_NUMBER)

            if (count > 0) {
                val pendingResult = goAsync()
                MissedCallNotifier.showMissedCallNotification(context, count, number) {
                    pendingResult.finish()
                }
            } else {
                MissedCallNotifier.cancelMissedCallNotification(context)
            }
        }
    }
}
