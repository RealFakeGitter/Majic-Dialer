package com.novadial.phone.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import com.novadial.phone.activities.CallActivity
import com.novadial.phone.helpers.ACCEPT_CALL
import com.novadial.phone.helpers.CallManager
import com.novadial.phone.helpers.DECLINE_CALL
import com.novadial.phone.helpers.TOGGLE_MUTE
import com.novadial.phone.helpers.TOGGLE_SPEAKER
import com.novadial.phone.helpers.DISMISS_CALL_NOTIFICATION
import com.novadial.phone.helpers.CallNotificationManager
import com.novadial.phone.models.AudioRoute

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACCEPT_CALL -> {
                context.startActivity(CallActivity.getStartIntent(context))
                CallManager.accept()
            }

            DECLINE_CALL -> CallManager.reject()

            TOGGLE_MUTE -> {
                val inCallService = CallManager.inCallService
                if (inCallService != null) {
                    val isMuted = inCallService.callAudioState?.isMuted == true
                    inCallService.setMuted(!isMuted)
                }
            }

            TOGGLE_SPEAKER -> {
                val currentRoute = CallManager.getCallAudioRoute()
                val isSpeakerOn = currentRoute == AudioRoute.SPEAKER
                val newRoute = if (isSpeakerOn) {
                    val routes = CallManager.getSupportedAudioRoutes()
                    when {
                        routes.contains(AudioRoute.BLUETOOTH) -> CallAudioState.ROUTE_BLUETOOTH
                        routes.contains(AudioRoute.WIRED_HEADSET) -> CallAudioState.ROUTE_WIRED_HEADSET
                        else -> CallAudioState.ROUTE_WIRED_OR_EARPIECE
                    }
                } else {
                    CallAudioState.ROUTE_SPEAKER
                }
                CallManager.setAudioRoute(newRoute)
            }

            DISMISS_CALL_NOTIFICATION -> {
                val state = CallManager.getState()
                if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    val inCallService = CallManager.inCallService
                    if (inCallService != null) {
                        CallNotificationManager(context).setupNotification()
                    }
                }
            }
        }
    }
}
