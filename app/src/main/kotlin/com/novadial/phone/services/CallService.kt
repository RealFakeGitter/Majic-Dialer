package com.novadial.phone.services

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import org.fossify.commons.extensions.canUseFullScreenIntent
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_POST_NOTIFICATIONS
import com.novadial.phone.activities.CallActivity
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.extensions.isOutgoing
import com.novadial.phone.extensions.keyguardManager
import com.novadial.phone.extensions.powerManager
import com.novadial.phone.helpers.CallAudioManager
import com.novadial.phone.helpers.CallManager
import com.novadial.phone.helpers.CallManagerListener
import com.novadial.phone.helpers.CallNotificationManager
import com.novadial.phone.helpers.ContactsCache
import com.novadial.phone.helpers.NoCall
import com.novadial.phone.helpers.RingtoneVolumeHelper
import com.novadial.phone.models.AudioRoute
import com.novadial.phone.models.Events
import org.greenrobot.eventbus.EventBus
import com.novadial.phone.helpers.CallLogWatcher
import com.novadial.phone.helpers.RecentsHelper

class CallService : InCallService() {
    private val callNotificationManager by lazy { CallNotificationManager(this) }

    // Service-level audio manager ensures the call-waiting tone stops even if
    // CallActivity is in the background when the ringing call is removed.
    private val callAudioManager by lazy { CallAudioManager(this) }

    override fun onCreate() {
        super.onCreate()
        CallLogWatcher.ensureRegistered(this)
        CallManager.addListener(callManagerListener)
        ContactsCache.getContacts(this) { /* preload cache in background */ }
    }

    // CallManagerListener: drives notification updates from CallManager's post-update
    // state, ensuring the notification is always consistent with the call list.
    private val callManagerListener = object : CallManagerListener {
        override fun onStateChanged() {
            callNotificationManager.setupNotification()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            // handled directly in onCallAudioStateChanged() — no-op here to avoid double update
        }

        override fun onPrimaryCallChanged(call: Call) {
            callNotificationManager.setupNotification()
        }

        override fun onRingingCallEnded() {
            callNotificationManager.setupNotification()
        }
    }

    // Per-call Call.Callback: handles ringtone volume changes and immediate notification updates.
    private val callListener = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            super.onStateChanged(call, state)
            RingtoneVolumeHelper.handleCallStateChanged(this@CallService, call)
            if (state == Call.STATE_DISCONNECTED) {
                if (CallManager.getPhoneState() == NoCall) {
                    callNotificationManager.cancelNotification()
                } else {
                    callNotificationManager.setupNotification()
                }
            } else if (state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || state == Call.STATE_ACTIVE) {
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        ContactsCache.getContacts(this) { /* ensure cache is warming */ }
        CallManager.onCallAdded(call)
        CallManager.inCallService = this
        call.registerCallback(callListener)
        RingtoneVolumeHelper.handleCallStateChanged(this, call)

        // Incoming/Outgoing (locked): high priority (FSI)
        // Incoming (unlocked): if user opted in, low priority ➜ manual activity start, otherwise high priority (FSI)
        // Outgoing (unlocked): low priority ➜ manual activity start
        val isIncoming = !call.isOutgoing()
        val isDeviceLocked = !powerManager.isInteractive || keyguardManager.isDeviceLocked
        val lowPriority = when {
            isIncoming && isDeviceLocked -> false
            !isIncoming && isDeviceLocked -> false
            isIncoming && !isDeviceLocked -> config.alwaysShowFullscreen
            else -> true
        }

        callNotificationManager.setupNotification(lowPriority)

        // Only launch the CallActivity for the very first call (no active/held call yet).
        // When a second call arrives as call-waiting, the already-open CallActivity
        // will receive the state change via CallManagerListener and show the
        // in-call waiting banner — we must NOT replace the active-call UI.
        val hasExistingCall = CallManager.getActiveCall() != null || CallManager.getHeldCall() != null
        if (
            !hasExistingCall && (
                lowPriority
                || !hasPermission(PERMISSION_POST_NOTIFICATIONS)
                || !canUseFullScreenIntent()
            )
        ) {
            try {
                startActivity(CallActivity.getStartIntent(this))
            } catch (_: Exception) {
                // seems like startActivity can throw AndroidRuntimeException and
                // ActivityNotFoundException, not yet sure when and why, lets show a notification
                callNotificationManager.setupNotification()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callListener)
        RingtoneVolumeHelper.handleCallRemoved(this, call)

        // If the call that was removed was ringing (call-waiting), stop the tone.
        // This is a safety net: CallActivity also stops it, but may be backgrounded.
        if (call.getStateCompat() == Call.STATE_RINGING) {
            callAudioManager.stopCallWaitingTone()
        }

        CallManager.onCallRemoved(call)
        if (CallManager.getPhoneState() == NoCall) {
            CallManager.inCallService = null
            callNotificationManager.cancelNotification()
        } else {
            callNotificationManager.setupNotification()
            // The open CallActivity will handle the promotion of the held call
            // to ACTIVE via its CallManagerListener (onStateChanged / onPrimaryCallChanged).
            // Do NOT call startActivity() here — it would restart the Activity
            // and flash an unwanted screen transition.
        }

        CallLogWatcher.ensureRegistered(this)
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        super.onCallAudioStateChanged(audioState)
        if (audioState != null) {
            CallManager.onAudioStateChanged(audioState)
            callNotificationManager.setupNotification()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        CallManager.removeListener(callManagerListener)
        callNotificationManager.cancelNotification()
        callAudioManager.release()
        RingtoneVolumeHelper.restoreVolume(this)
    }
}
