package com.novadial.phone.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.IBinder
import android.provider.ContactsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ImageView
import com.novadial.phone.R
import com.novadial.phone.activities.CallActivity
import kotlin.math.abs
import kotlin.math.min

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
            dp(40),
            dp(40),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(120)
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateContactPhoto(intent?.getStringExtra("phone_number"))
        return START_STICKY
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
                            floatingView?.findViewById<ImageButton>(R.id.floating_button)?.apply {
                                setImageBitmap(getCircularBitmap(bitmap))
                                background = null
                                clearColorFilter()
                                scaleType = ImageView.ScaleType.CENTER_CROP
                            }
                            return
                        }
                    }
                }
            }
        }

        setDefaultIcon()
    }

    private fun getCircularBitmap(srcBitmap: Bitmap): Bitmap {
        val size = min(srcBitmap.width, srcBitmap.height)
        val squareBitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(squareBitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val rect = Rect(0, 0, size, size)
        val rectF = RectF(rect)

        canvas.drawOval(rectF, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)

        val left = (srcBitmap.width - size) / 2
        val top = (srcBitmap.height - size) / 2
        canvas.drawBitmap(srcBitmap, -left.toFloat(), -top.toFloat(), paint)

        return squareBitmap
    }

    private fun setDefaultIcon() {
        floatingView?.findViewById<ImageButton>(R.id.floating_button)?.apply {
            setImageResource(R.drawable.ic_phone_green_vector)
            clearColorFilter()
            setColorFilter(resources.getColor(android.R.color.black, theme))
            background = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
        }
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        floatingView?.let { windowManager?.removeView(it) }
        floatingView = null
        windowManager = null
        super.onDestroy()
    }
}
