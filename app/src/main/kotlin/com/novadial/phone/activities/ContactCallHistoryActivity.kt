package com.novadial.phone.activities

import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog.Calls
import android.provider.ContactsContract
import android.telecom.VideoProfile
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.adjustForContrast
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.formatDateOrTime
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.formatSecondsToShortTimeString
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getColoredDrawableWithColor
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.launchSendSMSIntent
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CALL_LOG
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import com.novadial.phone.R
import com.novadial.phone.databinding.ActivityContactCallHistoryBinding
import com.novadial.phone.databinding.ItemRecentCallBinding
import com.novadial.phone.extensions.areMultipleSIMsAvailable
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.startCallWithConfirmationCheck
import com.novadial.phone.helpers.RecentsHelper
import com.novadial.phone.models.RecentCall

class ContactCallHistoryActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityContactCallHistoryBinding::inflate)
    private lateinit var seedCall: RecentCall
    private var contactId: Long? = null
    private var isFavorite = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        seedCall = getSeedCall() ?: run {
            finish()
            return
        }

        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(contactDetailsScrollView))
            setupMaterialScrollListener(contactDetailsScrollView, contactCallHistoryAppbar)
        }

        // Tint Call button with the accent color
        val accentColor = getNovaAccentColor()
        binding.callActionIcon.backgroundTintList = ColorStateList.valueOf(accentColor)
        binding.callActionIcon.applyColorFilter(accentColor.getContrastColor())

        setupActions()
        updateTextColors(binding.contactCallHistoryCoordinator)
        binding.contactCallHistoryCoordinator.setBackgroundColor(resources.getColor(R.color.nova_amoled_black, theme))

        queryContactInfo()
        loadCallHistory()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.contactCallHistoryAppbar, NavigationIcon.Arrow)
        queryContactInfo()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (contactId != null) {
            menuInflater.inflate(R.menu.menu_contact_details, menu)
            val favoriteItem = menu.findItem(R.id.action_favorite)
            favoriteItem?.setIcon(if (isFavorite) R.drawable.ic_star_vector else R.drawable.ic_star_outline)
            favoriteItem?.icon?.applyColorFilter(getProperTextColor())
            menu.findItem(R.id.action_edit)?.icon?.applyColorFilter(getProperTextColor())
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_favorite -> {
                toggleFavorite()
                return true
            }
            R.id.action_edit -> {
                editContact()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupActions() {
        binding.apply {
            callAction.setOnClickListener {
                startCallWithConfirmationCheck(seedCall.phoneNumber, seedCall.name)
            }

            messageAction.setOnClickListener {
                launchSendSMSIntent(seedCall.phoneNumber)
            }

            videoCallAction.setOnClickListener {
                launchVideoCall()
            }

            showFullHistoryButton.setOnClickListener {
                Intent(this@ContactCallHistoryActivity, ContactFullHistoryActivity::class.java).apply {
                    putExtras(intent)
                    startActivity(this)
                }
            }

            deleteContactRow.setOnClickListener {
                deleteContact()
            }
        }
    }

    private fun launchVideoCall() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.fromParts("tel", seedCall.phoneNumber, null)).apply {
            putExtra("android.telecom.extra.START_CALL_WITH_VIDEO_STATE", VideoProfile.STATE_BIDIRECTIONAL)
        }

        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            toast(R.string.no_video_call_app)
        }
    }

    private fun queryContactInfo() {
        if (seedCall.isUnknownNumber) {
            binding.contactSettingsCard.beGone()
            contactId = null
            invalidateOptionsMenu()
            return
        }

        ensureBackgroundThread {
            try {
                val uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(seedCall.phoneNumber)
                )
                val projection = arrayOf(
                    ContactsContract.PhoneLookup._ID,
                    ContactsContract.PhoneLookup.STARRED
                )
                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup._ID)
                        val starredIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.STARRED)
                        
                        contactId = if (idIndex >= 0) cursor.getLong(idIndex) else null
                        isFavorite = if (starredIndex >= 0) cursor.getInt(starredIndex) == 1 else false
                        
                        runOnUiThread {
                            binding.contactSettingsCard.beVisible()
                            invalidateOptionsMenu()
                        }
                    } else {
                        runOnUiThread {
                            binding.contactSettingsCard.beGone()
                            contactId = null
                            invalidateOptionsMenu()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.contactSettingsCard.beGone()
                    contactId = null
                    invalidateOptionsMenu()
                }
            }
        }
    }

    private fun toggleFavorite() {
        val currentContactId = contactId ?: return
        ensureBackgroundThread {
            try {
                val values = ContentValues().apply {
                    put(ContactsContract.Contacts.STARRED, if (isFavorite) 0 else 1)
                }
                val contactUri = ContentUris.withAppendedId(
                    ContactsContract.Contacts.CONTENT_URI,
                    currentContactId
                )
                contentResolver.update(contactUri, values, null, null)
                isFavorite = !isFavorite
                runOnUiThread {
                    invalidateOptionsMenu()
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private fun editContact() {
        val currentContactId = contactId ?: return
        val contactUri = ContentUris.withAppendedId(
            ContactsContract.Contacts.CONTENT_URI,
            currentContactId
        )
        val intent = Intent(Intent.ACTION_EDIT, contactUri)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No app found to edit contact")
        }
    }

    private fun deleteContact() {
        val currentContactId = contactId ?: return
        val question = String.format(getString(R.string.deletion_confirmation), seedCall.name)
        org.fossify.commons.dialogs.ConfirmationDialog(this, question) {
            handlePermission(PERMISSION_WRITE_CONTACTS) {
                ensureBackgroundThread {
                    try {
                        val contactUri = ContentUris.withAppendedId(
                            ContactsContract.Contacts.CONTENT_URI,
                            currentContactId
                        )
                        contentResolver.delete(contactUri, null, null)
                        runOnUiThread {
                            finish()
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            toast("Failed to delete contact")
                        }
                    }
                }
            }
        }
    }

    private fun loadCallHistory() {
        if (!hasPermission(PERMISSION_READ_CALL_LOG)) {
            binding.progressIndicator.hide()
            binding.contactCallHistoryPlaceholder.beVisible()
            return
        }

        val groupedCalls = seedCall.groupedCalls
        if (!groupedCalls.isNullOrEmpty()) {
            val callsToShow = groupedCalls.sortedByDescending { it.startTS }
            binding.progressIndicator.hide()
            bindHeader(callsToShow.first(), callsToShow)
            bindRecentActivity(callsToShow)
            binding.contactCallHistoryPlaceholder.beGone()
        } else {
            RecentsHelper(this).getRecentCallsForNumber(seedCall) { calls ->
                runOnUiThread {
                    binding.progressIndicator.hide()
                    val callsToShow = calls.ifEmpty { listOf(seedCall) }
                    bindHeader(callsToShow.first(), callsToShow)
                    bindRecentActivity(callsToShow)
                    binding.contactCallHistoryPlaceholder.beGone()
                }
            }
        }
    }

    private fun bindHeader(call: RecentCall, calls: List<RecentCall>) {
        val displayNumber = if (config.formatPhoneNumbers) {
            call.phoneNumber.formatPhoneNumber()
        } else {
            call.phoneNumber
        }

        binding.apply {
            contactName.text = call.name
            contactNumber.text = displayNumber
            SimpleContactsHelper(this@ContactCallHistoryActivity).loadContactImage(call.photoUri, contactImage, call.name)

            totalCallsValue.text = "${getString(R.string.total)}\n${calls.size}"
            incomingCallsValue.text = "${getString(R.string.incoming)}\n${calls.count { it.type == Calls.INCOMING_TYPE }}"
            outgoingCallsValue.text = "${getString(R.string.outgoing)}\n${calls.count { it.type == Calls.OUTGOING_TYPE }}"
            missedCallsValue.text = "${getString(R.string.missed)}\n${calls.count { it.type == Calls.MISSED_TYPE }}"
            totalCallDurationValue.text = formatSecondsToShortTimeString(calls.sumOf { it.duration })
        }
    }

    private fun bindRecentActivity(calls: List<RecentCall>) {
        binding.recentCallsContainer.removeAllViews()
        val callsToDisplay = calls.take(3)
        val areMultipleSIMsAvailable = areMultipleSIMsAvailable()
        val theme = theme
        val missedCallColor = resources.getColor(R.color.color_missed_call, theme)
        val outgoingCallColor = resources.getColor(R.color.color_outgoing_call, theme)
        val incomingCallColor = resources.getColor(R.color.color_incoming_call, theme)
        
        val outgoingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, outgoingCallColor)
        val redOutgoingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_made_vector, missedCallColor)
        val incomingCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_received_vector, incomingCallColor)
        val missedCallIcon = resources.getColoredDrawableWithColor(R.drawable.ic_call_missed_vector, missedCallColor)
        
        val textColor = getProperTextColor()
        val secondaryTextColor = textColor.adjustAlpha(0.6f)
        val fontSize = getTextSize()

        for (call in callsToDisplay) {
            val itemBinding = ItemRecentCallBinding.inflate(layoutInflater, binding.recentCallsContainer, false)
            itemBinding.apply {
                root.setupViewBackground(this@ContactCallHistoryActivity)
                
                overflowMenuIcon.beGone()
                overflowMenuAnchor.beGone()
                itemRecentsLocation.beGone()
                
                SimpleContactsHelper(this@ContactCallHistoryActivity).loadContactImage(call.photoUri, itemRecentsImage, call.name)
                
                itemRecentsName.apply {
                    text = when (call.type) {
                        Calls.INCOMING_TYPE -> getString(R.string.incoming)
                        Calls.OUTGOING_TYPE -> getString(R.string.outgoing)
                        Calls.MISSED_TYPE -> getString(R.string.missed)
                        Calls.REJECTED_TYPE -> getString(R.string.rejected)
                        else -> getString(R.string.incoming)
                    }
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize)
                }

                itemRecentsDateTime.apply {
                    text = call.startTS.formatDateOrTime(
                        context = this@ContactCallHistoryActivity,
                        hideTimeOnOtherDays = false,
                        showCurrentYear = false,
                        hideTodaysDate = false
                    )
                    setTextColor(if (call.type == Calls.MISSED_TYPE) missedCallColor else secondaryTextColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.8f)
                }

                val shouldShowDuration = call.type != Calls.MISSED_TYPE && call.type != Calls.REJECTED_TYPE && call.duration > 0
                itemRecentsDateTimeDurationSeparator.apply {
                    text = "•"
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.8f)
                    beVisibleIf(shouldShowDuration)
                }

                itemRecentsDuration.apply {
                    text = formatSecondsToShortTimeString(call.duration)
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, fontSize * 0.8f)
                    beVisibleIf(shouldShowDuration)
                }

                itemRecentsSimImage.beVisibleIf(areMultipleSIMsAvailable && call.simID != -1)
                itemRecentsSimId.beVisibleIf(areMultipleSIMsAvailable && call.simID != -1)
                if (areMultipleSIMsAvailable && call.simID != -1) {
                    val simColor = call.simColor.adjustForContrast(getProperBackgroundColor())
                    itemRecentsSimImage.applyColorFilter(simColor)
                    itemRecentsSimId.setTextColor(simColor.getContrastColor())
                    itemRecentsSimId.text = call.simID.toString()
                }

                val drawable = when (call.type) {
                    Calls.OUTGOING_TYPE -> if (call.duration > 0) outgoingCallIcon else redOutgoingCallIcon
                    Calls.MISSED_TYPE -> missedCallIcon
                    else -> incomingCallIcon
                }
                itemRecentsType.setImageDrawable(drawable)
                
                root.setOnClickListener {
                    startCallWithConfirmationCheck(call.phoneNumber, call.name)
                }
            }
            binding.recentCallsContainer.addView(itemBinding.root)
        }
    }

    private fun getSeedCall(): RecentCall? {
        if (!intent.hasExtra(EXTRA_CALL_ID)) {
            return null
        }

        return RecentCall(
            id = intent.getIntExtra(EXTRA_CALL_ID, 0),
            phoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER).orEmpty(),
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            photoUri = intent.getStringExtra(EXTRA_PHOTO_URI).orEmpty(),
            startTS = intent.getLongExtra(EXTRA_START_TS, 0L),
            duration = intent.getIntExtra(EXTRA_DURATION, 0),
            type = intent.getIntExtra(EXTRA_TYPE, Calls.INCOMING_TYPE),
            simID = intent.getIntExtra(EXTRA_SIM_ID, -1),
            simColor = intent.getIntExtra(EXTRA_SIM_COLOR, -1),
            specificNumber = intent.getStringExtra(EXTRA_SPECIFIC_NUMBER).orEmpty(),
            specificType = intent.getStringExtra(EXTRA_SPECIFIC_TYPE).orEmpty(),
            isUnknownNumber = intent.getBooleanExtra(EXTRA_IS_UNKNOWN_NUMBER, false),
        )
    }

    companion object {
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PHONE_NUMBER = "phone_number"
        const val EXTRA_NAME = "name"
        const val EXTRA_PHOTO_URI = "photo_uri"
        const val EXTRA_START_TS = "start_ts"
        const val EXTRA_DURATION = "duration"
        const val EXTRA_TYPE = "type"
        const val EXTRA_SIM_ID = "sim_id"
        const val EXTRA_SIM_COLOR = "sim_color"
        const val EXTRA_SPECIFIC_NUMBER = "specific_number"
        const val EXTRA_SPECIFIC_TYPE = "specific_type"
        const val EXTRA_IS_UNKNOWN_NUMBER = "is_unknown_number"
    }
}
