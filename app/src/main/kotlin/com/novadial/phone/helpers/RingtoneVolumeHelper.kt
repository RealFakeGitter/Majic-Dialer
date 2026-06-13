package com.novadial.phone.helpers

import android.content.Context
import android.media.AudioManager
import android.telecom.Call
import com.novadial.phone.extensions.audioManager
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.extensions.isOutgoing

object RingtoneVolumeHelper {

    fun handleCallStateChanged(context: Context, call: Call) {
        if (call.isOutgoing()) return
        if (!context.config.maxRingtoneVolumeIncoming) return

        val state = call.getStateCompat()
        if (state == Call.STATE_RINGING) {
            boostVolume(context)
        } else {
            // Call transitioned out of RINGING (answered, rejected, missed, disconnected)
            if (!hasOtherRingingIncomingCalls()) {
                restoreVolume(context)
            }
        }
    }

    fun handleCallRemoved(context: Context, call: Call) {
        if (call.isOutgoing()) return

        // When a call is removed, if there are no other ringing incoming calls, restore volume.
        if (!hasOtherRingingIncomingCalls()) {
            restoreVolume(context)
        }
    }

    private fun hasOtherRingingIncomingCalls(): Boolean {
        return CallManager.inCallService?.calls?.any {
            !it.isOutgoing() && it.getStateCompat() == Call.STATE_RINGING
        } == true
    }

    private fun boostVolume(context: Context) {
        val config = context.config
        val audioManager = context.audioManager

        // Only boost in NORMAL (General) ringer mode.
        // In VIBRATE or SILENT mode, do nothing — preserve Android's native behavior.
        if (audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return

        if (!config.isRingtoneVolumeBoosted) {
            val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_RING)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_RING)

            config.previousRingtoneVolume = currentVolume
            config.isRingtoneVolumeBoosted = true

            try {
                audioManager.setStreamVolume(AudioManager.STREAM_RING, maxVolume, 0)
            } catch (e: SecurityException) {
                // Ignore DND or policy restrictions
            } catch (e: Exception) {
                // Catch-all safety
            }
        }
    }

    fun restoreVolume(context: Context) {
        val config = context.config
        val audioManager = context.audioManager

        if (config.isRingtoneVolumeBoosted) {
            val prevVolume = config.previousRingtoneVolume
            if (prevVolume >= 0) {
                try {
                    audioManager.setStreamVolume(AudioManager.STREAM_RING, prevVolume, 0)
                } catch (e: SecurityException) {
                    // Ignore DND or policy restrictions
                } catch (e: Exception) {
                    // Catch-all safety
                }
            }
            config.isRingtoneVolumeBoosted = false
            config.previousRingtoneVolume = -1
        }
    }
}
