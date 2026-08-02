package com.novadial.phone.helpers

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.telecom.Call
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.novadial.phone.R
import com.novadial.phone.activities.CallActivity
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.isOutgoing
import com.novadial.phone.receivers.CallActionReceiver
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.extensions.setText
import org.fossify.commons.extensions.setVisibleIf
import org.fossify.commons.helpers.isQPlus
import com.novadial.phone.models.AudioRoute

class CallNotificationManager(private val context: Context) {
    companion object {
        private const val CALL_NOTIFICATION_ID = 42
        private const val ACCEPT_CALL_CODE = 0
        private const val DECLINE_CALL_CODE = 1
        private const val CALL_SHORTCUT_ID = "call_shortcut"
    }

    private val notificationManager = context.notificationManager
    private val callContactAvatarHelper = CallContactAvatarHelper(context)

    @SuppressLint("NewApi")
    fun setupNotification(lowPriority: Boolean = false) {
        getCallContact(context.applicationContext, CallManager.getPrimaryCall()) { callContact ->
            val callContactAvatar = callContactAvatarHelper.getCallContactAvatar(callContact)
            val callState = CallManager.getState()
            val isHighPriority = callState == Call.STATE_RINGING && !lowPriority
            val channelId = if (isHighPriority) "simple_dialer_call_high_priority" else "simple_dialer_call"
            createNotificationChannel(isHighPriority, channelId)

            val openAppIntent = CallActivity.getStartIntent(context)
            val openAppPendingIntent = PendingIntent.getActivity(context, 0, openAppIntent, PendingIntent.FLAG_MUTABLE)
            val bubbleIntent = Intent(context, CallActivity::class.java).apply {
    action = Intent.ACTION_VIEW
    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
        Intent.FLAG_ACTIVITY_SINGLE_TOP or
        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
        Intent.FLAG_ACTIVITY_CLEAR_TOP
}
val bubblePendingIntent = PendingIntent.getActivity(context, 5, bubbleIntent, PendingIntent.FLAG_MUTABLE)
            val acceptCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = ACCEPT_CALL }
            val acceptPendingIntent = PendingIntent.getBroadcast(
                context,
                ACCEPT_CALL_CODE,
                acceptCallIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val declineCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = DECLINE_CALL }
            val declinePendingIntent = PendingIntent.getBroadcast(
                context,
                DECLINE_CALL_CODE,
                declineCallIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val muteCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = TOGGLE_MUTE }
            val mutePendingIntent = PendingIntent.getBroadcast(
                context,
                2,
                muteCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val speakerCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = TOGGLE_SPEAKER }
            val speakerPendingIntent = PendingIntent.getBroadcast(
                context,
                3,
                speakerCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            val deleteCallIntent = Intent(context, CallActionReceiver::class.java).apply { action = DISMISS_CALL_NOTIFICATION }
            val deletePendingIntent = PendingIntent.getBroadcast(
                context,
                4,
                deleteCallIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            var callerName = callContact.name.ifEmpty { context.getString(R.string.unknown_caller) }
            if (callContact.numberLabel.isNotEmpty()) callerName += " - ${callContact.numberLabel}"

            val contentTextId = when (callState) {
                Call.STATE_RINGING -> R.string.is_calling
                Call.STATE_DIALING -> R.string.dialing
                Call.STATE_DISCONNECTED -> R.string.call_ended
                Call.STATE_DISCONNECTING -> R.string.call_ending
                else -> R.string.ongoing_call
            }

            val isMuted = CallManager.inCallService?.callAudioState?.isMuted == true
            val currentRoute = CallManager.getCallAudioRoute()
            val isSpeakerOn = currentRoute == AudioRoute.SPEAKER

            val collapsedView = RemoteViews(context.packageName, R.layout.call_notification).apply {
                setText(R.id.notification_caller_name, callerName)
                setText(R.id.notification_call_status, context.getString(contentTextId))
                setVisibleIf(R.id.notification_accept_call, callState == Call.STATE_RINGING)

                val isCallActiveOrDialing = callState == Call.STATE_ACTIVE ||
                    callState == Call.STATE_DIALING ||
                    callState == Call.STATE_CONNECTING ||
                    callState == Call.STATE_HOLDING
                setVisibleIf(R.id.notification_toggle_mute, isCallActiveOrDialing)
                setVisibleIf(R.id.notification_toggle_speaker, isCallActiveOrDialing)

                val speakerIcon = when (currentRoute) {
                    AudioRoute.WIRED_HEADSET -> R.drawable.ic_volume_down_vector
                    AudioRoute.BLUETOOTH -> R.drawable.ic_bluetooth_audio_vector
                    AudioRoute.EARPIECE -> R.drawable.ic_volume_down_vector
                    else -> R.drawable.ic_volume_up_vector
                }
                setImageViewResource(R.id.notification_toggle_speaker, speakerIcon)

                val activeColor = if (context.config.novaDynamicColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    context.getColor(android.R.color.system_accent1_600)
                } else {
                    context.getProperPrimaryColor()
                }
                val inactiveColor = 0xFF888888.toInt()

                val muteColor = if (isMuted) activeColor else inactiveColor
                val speakerColor = if (isSpeakerOn) activeColor else inactiveColor

                setInt(R.id.notification_toggle_mute, "setColorFilter", muteColor)
                setInt(R.id.notification_toggle_speaker, "setColorFilter", speakerColor)

                setOnClickPendingIntent(R.id.notification_decline_call, declinePendingIntent)
                setOnClickPendingIntent(R.id.notification_accept_call, acceptPendingIntent)
                setOnClickPendingIntent(R.id.notification_toggle_mute, mutePendingIntent)
                setOnClickPendingIntent(R.id.notification_toggle_speaker, speakerPendingIntent)

                if (callContactAvatar != null) {
                    setImageViewBitmap(
                        R.id.notification_thumbnail,
                        callContactAvatarHelper.getCircularBitmap(callContactAvatar)
                    )
                }
            }

            val iconId = if (CallManager.getPrimaryCall()?.isOutgoing() == true) {
                R.drawable.ic_call_made_notification
            } else {
                R.drawable.ic_call_received_notification
            }

            val builder = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(iconId)
                .setContentIntent(openAppPendingIntent)
                .setDeleteIntent(deletePendingIntent)
                .setCategory(Notification.CATEGORY_CALL)
                .setCustomContentView(collapsedView)
                .setOngoing(true)
                .setUsesChronometer(callState == Call.STATE_ACTIVE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())

            if (context.config.enableCallBubbles && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val shortcutIntent = Intent(context, CallActivity::class.java).apply {
                        action = Intent.ACTION_VIEW
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }

                    val shortcut = ShortcutInfoCompat.Builder(context, CALL_SHORTCUT_ID)
                        .setShortLabel(callerName)
                        .setLongLabel(callerName)
                        .setIntent(shortcutIntent)
                        .setIcon(IconCompat.createWithResource(context, iconId))
                        .setLongLived(true)
                        .build()

                    ShortcutManagerCompat.pushDynamicShortcut(context, shortcut)

                    val bubbleMetadata = NotificationCompat.BubbleMetadata.Builder(
                        bubblePendingIntent,
                        IconCompat.createWithResource(context, iconId)
                    )
                        .setAutoExpandBubble(false)
                        .setSuppressNotification(false)
                        .build()

                    builder
                        .setShortcutId(CALL_SHORTCUT_ID)
                        .setBubbleMetadata(bubbleMetadata)
                } catch (_: Throwable) {
                }
            }

            if (isHighPriority) {
                builder.setFullScreenIntent(openAppPendingIntent, true)
            }

            val notification = builder.build()

            if (CallManager.getState() == callState) {
                val service = context as? Service
                if (service != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        service.startForeground(
                            CALL_NOTIFICATION_ID,
                            notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                        )
                    } else {
                        service.startForeground(CALL_NOTIFICATION_ID, notification)
                    }
                } else {
                    notificationManager.notify(CALL_NOTIFICATION_ID, notification)
                }
            }
        }
    }

    fun createNotificationChannel(isHighPriority: Boolean, channelId: String) {
        val name = if (isHighPriority) {
            context.getString(R.string.call_notification_channel_high_priority)
        } else {
            context.getString(R.string.call_notification_channel)
        }

        val importance = if (isHighPriority) IMPORTANCE_HIGH else IMPORTANCE_DEFAULT
        NotificationChannel(channelId, name, importance).apply {
            setSound(null, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setAllowBubbles(true)
            }
            notificationManager.createNotificationChannel(this)
        }
    }

    fun cancelNotification() {
        val service = context as? Service
        if (service != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                service.stopForeground(true)
            }
        }
        notificationManager.cancel(CALL_NOTIFICATION_ID)
    }
}
