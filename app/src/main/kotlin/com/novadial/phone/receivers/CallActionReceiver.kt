package com.novadial.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.novadial.phone.activities.CallActivity
import com.novadial.phone.helpers.ACCEPT_CALL
import com.novadial.phone.helpers.CallManager
import com.novadial.phone.helpers.DECLINE_CALL

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCEPT_CALL -> {
                context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
            }

            DECLINE_CALL -> CallManager.reject()
        }
    }
}
