package com.novadial.phone.models

import android.telecom.Call
import androidx.lifecycle.ViewModel
import com.novadial.phone.helpers.CallManager
import com.novadial.phone.helpers.CallManagerListener
import com.novadial.phone.helpers.NoCall
import com.novadial.phone.helpers.SingleCall
import com.novadial.phone.helpers.TwoCalls
import com.novadial.phone.extensions.getStateCompat
import com.novadial.phone.extensions.isConference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for [com.novadial.phone.activities.CallActivity].
 *
 * Acts as a lifecycle-aware bridge between [CallManager] (singleton, no lifecycle) and
 * the Activity. Exposes [uiState] as a [StateFlow] so the UI can collect it inside
 * [androidx.lifecycle.lifecycleScope.repeatOnLifecycle] — state survives orientation
 * changes without re-registering listeners.
 *
 * The ViewModel registers itself as a [CallManagerListener] on creation and removes
 * itself on [onCleared], so there is no listener leak across rotations.
 */
class CallScreenViewModel : ViewModel() {

    private val _uiState = MutableStateFlow<CallUiState>(CallUiState.Idle)
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    /**
     * Emits true exactly once when a second call arrives (call-waiting scenario).
     * The Activity should observe this to trigger [com.novadial.phone.helpers.CallAudioManager.startCallWaitingTone].
     * Reset to false after the Activity consumes it.
     */
    private val _secondCallEvent = MutableStateFlow(false)
    val secondCallEvent: StateFlow<Boolean> = _secondCallEvent.asStateFlow()

    private val callManagerListener = object : CallManagerListener {
        override fun onStateChanged() {
            refreshState()
        }

        override fun onAudioStateChanged(audioState: AudioRoute) {
            refreshState()
        }

        override fun onPrimaryCallChanged(call: Call) {
            refreshState()
        }

        override fun onSecondCallArrived(call: Call) {
            // Signal the UI to start the call-waiting tone
            _secondCallEvent.value = true
            refreshState()
        }
    }

    init {
        CallManager.addListener(callManagerListener)
        refreshState()
    }

    override fun onCleared() {
        super.onCleared()
        CallManager.removeListener(callManagerListener)
    }

    /** Called by the UI after it has consumed the second-call event. */
    fun consumeSecondCallEvent() {
        _secondCallEvent.value = false
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State computation
    // ─────────────────────────────────────────────────────────────────────────

    private fun refreshState() {
        _uiState.value = computeUiState()
    }

    private fun computeUiState(): CallUiState {
        val phoneState = CallManager.getPhoneState()
        val ringingCall = CallManager.getRingingCall()
        val activeOrHeld = CallManager.getActiveCall() ?: CallManager.getHeldCall()
        val audioRoute = CallManager.getCallAudioRoute()

        return when {
            phoneState == NoCall -> CallUiState.Idle

            // Call-waiting: active/held call exists AND a ringing call exists
            ringingCall != null && activeOrHeld != null -> {
                CallUiState.CallWaiting(
                    active = activeOrHeld,
                    waiting = ringingCall,
                    audioRoute = audioRoute
                )
            }

            // First incoming call only
            ringingCall != null -> CallUiState.SingleRinging(ringingCall)

            // Two calls (one active, one held) — no ringing
            phoneState is TwoCalls -> CallUiState.TwoActiveCalls(
                primary = phoneState.active,
                held = phoneState.onHold,
                audioRoute = audioRoute
            )

            // Single call — may be conference
            phoneState is SingleCall -> {
                val call = phoneState.call
                if (call.isConference()) {
                    CallUiState.Conference(call = call, audioRoute = audioRoute)
                } else {
                    val state = call.getStateCompat()
                    if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                        CallUiState.CallEnded
                    } else {
                        CallUiState.ActiveCall(call = call, audioRoute = audioRoute)
                    }
                }
            }

            else -> CallUiState.Idle
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// UI State sealed hierarchy
// ─────────────────────────────────────────────────────────────────────────────

sealed class CallUiState {
    /** No calls at all — Activity should finish. */
    object Idle : CallUiState()

    /** A single incoming call, no active call exists. */
    data class SingleRinging(val call: Call) : CallUiState()

    /** One call, active or dialing/connecting. */
    data class ActiveCall(val call: Call, val audioRoute: AudioRoute?) : CallUiState()

    /** Active (or held) call + a second ringing call-waiting. */
    data class CallWaiting(
        val active: Call,
        val waiting: Call,
        val audioRoute: AudioRoute?
    ) : CallUiState()

    /** Two calls: one active, one held. No ringing call. */
    data class TwoActiveCalls(
        val primary: Call,
        val held: Call,
        val audioRoute: AudioRoute?
    ) : CallUiState()

    /** Conference call. */
    data class Conference(val call: Call, val audioRoute: AudioRoute?) : CallUiState()

    /** All calls ended — Activity should finish after a brief delay. */
    object CallEnded : CallUiState()
}
