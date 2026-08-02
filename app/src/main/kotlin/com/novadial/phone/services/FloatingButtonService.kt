package com.novadial.phone.services

import android.app.Service
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.net.Uri
import android.os.IBinder
import android.provider.ContactsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import com.novadial.phone.R
import com.novadial.phone.activities.CallActivity
import kotlin.math.abs

class FloatingButtonService : Service() {

    private var windowManager: WindowManager? = null
    private var floatingView: View? = null
    private lateinit var params: WindowManager.LayoutParams

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingView = LayoutInflater.from(this).inflate(R.layout.floating_button, null)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 50
            y = 200
            alpha = 0.65f
        }

        val button = floatingView!!.findViewById<ImageButton>(R.id.floating_button)
        setDefaultIcon()

        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    params.alpha = 1.0f
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = abs(event.rawX - initialTouchX)
                    val dy = abs(event.rawY - initialTouchY)

                    if (dx < 10 && dy < 10) {
                        startActivity(Intent(this, CallActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        })
                    } else {
                        snapToEdge()
                    }

                    params.alpha = 0.65f
                    windowManager?.updateViewLayout(floatingView, params)
                    true
                }

                else -> false
            }
        }

        windowManager?.addView(floatingView, params)

        floatingView?.post {
            snapToEdge()
            params.alpha = 0.65f
            windowManager?.updateViewLayout(floatingView, params)
        }
    }

    fun updateContactPhoto(phoneNumber: String?) {
        if (phoneNumber.isNullOrBlank()) {
            setDefaultIcon()
            return
        }

        val uri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(phoneNumber)
        )

        val projection = arrayOf(
            ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup._ID
        )

        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val photoUriString = cursor.getString(0)
                val contactId = cursor.getLong(1)

                val photoUri = when {
                    !photoUriString.isNullOrBlank() -> Uri.parse(photoUriString)
                    contactId > 0 -> Uri.withAppendedPath(
                        ContactsContract.Contacts.CONTENT_URI,
                        contactId.toString()
                    ).buildUpon().appendPath(ContactsContract.Contacts.Photo.CONTENT_DIRECTORY).build()
                    else -> null
                }

                if (photoUri != null) {
                    contentResolver.openInputStream(photoUri)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap != null) {
                            floatingView?.findViewById<ImageButton>(R.id.floating_button)
                                ?.setImageBitmap(bitmap)
                            return
                        }
                    }
                }
            }
        }

        setDefaultIcon()
    }

    private fun setDefaultIcon() {
        floatingView?.findViewById<ImageButton>(R.id.floating_button)
            ?.setImageResource(R.drawable.ic_phone)
    }

    private fun snapToEdge() {
        val screenWidth = resources.displayMetrics.widthPixels
        val bubbleWidth = floatingView?.width ?: 0
        val visiblePart = bubbleWidth / 2

        params.x = if (params.x + bubbleWidth / 2 < screenWidth / 2) {
            -visiblePart
        } else {
            screenWidth - visiblePart
        }

        windowManager?.updateViewLayout(floatingView, params)
    }

    override fun onDestroy() {
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
        windowManager = null
        super.onDestroy()
    }
}
