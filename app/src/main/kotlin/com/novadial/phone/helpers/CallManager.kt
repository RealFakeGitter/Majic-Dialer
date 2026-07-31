package com.novadial.phone.helpers

import android.annotation.SuppressLint
import android.os.Handler
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.extensions.hasCapability
import com.novadial.phone.extensions.isConference
import com.novadial.phone.models.AudioRoute
import java.util.concurrent.CopyOnWriteArraySet

// inspired by https://github.com/Chooloo/call_manage
class CallManager {
    companion object {
        @SuppressLint("StaticFieldLeak")
        var inCallService: InCallService? = null
        private var call: Call? = null
        private val calls = mutableListOf<Call>()
        private val listeners = CopyOnWriteArraySet<CallManagerListener>()

        fun onCallAdded(call: Call) {
            if (!calls.contains(call)) {
                calls.add(call)
            }
            call.registerCallback(object : Call.Callback() {
                override fun onStateChanged(call: Call, state: Int) {
                    updateState()
                }

                override fun onDetailsChanged(call: Call, details: Call.Details) {
                    updateState()
                }

                override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) {
                    updateState()
                }
            })
            updateState()
        }

        fun onCallRemoved(call: Call) {
            calls.remove(call)
            updateState()
            val remainingCall = calls.firstOrNull()
            if (remainingCall != null && remainingCall.getStateCompat() == Call.STATE_HOLDING) {
                remainingCall.unhold()
            }
        }

        fun onAudioStateChanged(audioState: CallAudioState) {
            val route = AudioRoute.fromRoute(audioState.route) ?: return
            for (listener in listeners) {
                listener.onAudioStateChanged(route)
            }
        }

        fun getActiveCall(): Call? = calls.find { it.getStateCompat() == Call.STATE_ACTIVE }
        fun getConnectingCall(): Call? = calls.find { it.getStateCompat() == Call.STATE_CONNECTING || it.getStateCompat() == Call.STATE_DIALING }
        fun getHeldCall(): Call? = calls.find { it.getStateCompat() == Call.STATE_HOLDING }
        fun getRingingCall(): Call? = calls.find { it.getStateCompat() == Call.STATE_RINGING }

        fun getPhoneState(): PhoneState {
            return when (calls.size) {
                0 -> NoCall
                1 -> SingleCall(calls.first())
                else -> {
                    val primary = getPrimaryCall() ?: return NoCall
                    val held = getHeldCall() ?: calls.find { it != primary && it.getStateCompat() != Call.STATE_RINGING }
                    if (held != null) {
                        TwoCalls(primary, held)
                    } else {
                        SingleCall(primary)
                    }
                }
            }
        }

        private fun getCallAudioState() = inCallService?.callAudioState

        fun getSupportedAudioRoutes(): Array<AudioRoute> {
            return AudioRoute.values().filter {
                val supportedRouteMask = getCallAudioState()?.supportedRouteMask
                if (supportedRouteMask != null) {
                    supportedRouteMask and it.route == it.route
                } else {
                    false
                }
            }.toTypedArray()
        }

        fun getCallAudioRoute() = AudioRoute.fromRoute(getCallAudioState()?.route)

        fun setAudioRoute(newRoute: Int) {
            inCallService?.setAudioRoute(newRoute)
        }

        private fun updateState() {
            val primaryCall = getPrimaryCall()
            var notify = true
            if (primaryCall == null) {
                call = null
            } else if (primaryCall != call) {
                call = primaryCall
                for (listener in listeners) {
                    listener.onPrimaryCallChanged(primaryCall)
                }
                notify = false
            }
            if (notify) {
                for (listener in listeners) {
                    listener.onStateChanged()
                }
            }

            // remove all disconnected calls manually in case they are still here
            calls.removeAll { it.getStateCompat() == Call.STATE_DISCONNECTED }
        }

        fun getPrimaryCall(): Call? {
            return getActiveCall()
                ?: getConnectingCall()
                ?: getHeldCall()
                ?: getRingingCall()
                ?: calls.find { it.isConference() }
                ?: calls.firstOrNull()
        }

        fun getConferenceCalls(): List<Call> {
            return calls.find { it.isConference() }?.children ?: emptyList()
        }

        fun acceptRingingCall() {
            getRingingCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
        }

        fun rejectRingingCall() {
            val ringingCall = getRingingCall()
            if (ringingCall != null) {
                if (ringingCall.getStateCompat() == Call.STATE_RINGING) {
                    ringingCall.reject(false, null)
                } else {
                    ringingCall.disconnect()
                }
            }
        }

        fun endHeldCall() {
            val heldCall = getHeldCall()
            if (heldCall != null && heldCall.getStateCompat() != Call.STATE_DISCONNECTED && heldCall.getStateCompat() != Call.STATE_DISCONNECTING) {
                heldCall.disconnect()
            }
        }

        fun accept() {
            val ringingCall = getRingingCall()
            if (ringingCall != null) {
                acceptRingingCall()
            } else {
                getPrimaryCall()?.answer(VideoProfile.STATE_AUDIO_ONLY)
            }
        }

        fun reject() {
            val ringingCall = getRingingCall()
            if (ringingCall != null && ringingCall != getPrimaryCall()) {
                rejectRingingCall()
            } else if (call != null) {
                val state = getState()
                if (state == Call.STATE_RINGING) {
                    call!!.reject(false, null)
                } else if (state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) {
                    call!!.disconnect()
                }
            }
        }

        fun toggleHold(): Boolean {
            val isOnHold = getState() == Call.STATE_HOLDING
            if (isOnHold) {
                call?.unhold()
            } else {
                call?.hold()
            }
            return !isOnHold
        }

        fun swap() {
            if (calls.size > 1) {
                calls.find { it.getStateCompat() == Call.STATE_HOLDING }?.unhold()
            }
        }

        fun merge() {
            val conferenceableCalls = call!!.conferenceableCalls
            if (conferenceableCalls.isNotEmpty()) {
                call!!.conference(conferenceableCalls.first())
            } else {
                if (call!!.hasCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
                    call!!.mergeConference()
                }
            }
        }

        fun addListener(listener: CallManagerListener) {
            listeners.add(listener)
        }

        fun removeListener(listener: CallManagerListener) {
            listeners.remove(listener)
        }

        fun getState() = getPrimaryCall()?.getStateCompat()

        fun keypad(char: Char) {
            call?.playDtmfTone(char)
            Handler().postDelayed({
                call?.stopDtmfTone()
            }, DIALPAD_TONE_LENGTH_MS)
        }
    }
}

interface CallManagerListener {
    fun onStateChanged()
    fun onAudioStateChanged(audioState: AudioRoute)
    fun onPrimaryCallChanged(call: Call)
}

sealed class PhoneState
object NoCall : PhoneState()
class SingleCall(val call: Call) : PhoneState()
class TwoCalls(val active: Call, val onHold: Call) : PhoneState()
