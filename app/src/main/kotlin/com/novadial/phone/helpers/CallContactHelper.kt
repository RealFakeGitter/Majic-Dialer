package com.novadial.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.Call
import org.fossify.commons.extensions.formatPhoneNumber
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getPhoneNumberTypeText
import org.fossify.commons.helpers.ContactsHelper
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.ensureBackgroundThread
import com.novadial.phone.R
import com.novadial.phone.extensions.config
import com.novadial.phone.extensions.isConference
import com.novadial.phone.models.CallContact

fun getFastCallContact(context: Context, call: Call?): CallContact {
    if (call == null) return CallContact("", "", "", "")
    if (call.isConference()) {
        return CallContact(context.getString(R.string.conference), "", "", "")
    }

    val handle = try {
        call.details?.handle?.toString()
    } catch (e: Exception) {
        null
    }

    if (handle == null) return CallContact("", "", "", "")

    val uri = Uri.decode(handle)
    if (uri.startsWith("tel:")) {
        val rawNumber = uri.substringAfter("tel:")
        val formattedNumber = if (context.config.formatPhoneNumbers) {
            rawNumber.formatPhoneNumber()
        } else {
            rawNumber
        }
        return CallContact(name = formattedNumber, photoUri = "", number = formattedNumber, numberLabel = "")
    }

    return CallContact("", "", "", "")
}

fun getCallContact(context: Context, call: Call?, callback: (CallContact) -> Unit) {
    if (call == null) {
        callback(CallContact("", "", "", ""))
        return
    }

    if (call.isConference()) {
        callback(CallContact(context.getString(R.string.conference), "", "", ""))
        return
    }

    ensureBackgroundThread {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        val callContact = CallContact("", "", "", "")
        val handle = try {
            call.details?.handle?.toString()
        } catch (e: Exception) {
            null
        }

        if (handle == null) {
            callback(callContact)
            return@ensureBackgroundThread
        }

        val uri = Uri.decode(handle)
        if (uri.startsWith("tel:")) {
            val number = uri.substringAfter("tel:")
            ContactsCache.getContacts(context) { contacts ->
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                callContact.number = if (context.config.formatPhoneNumbers) {
                    number.formatPhoneNumber()
                } else {
                    number
                }

                val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                if (contact != null) {
                    callContact.name = contact.getNameToDisplay()
                    callContact.photoUri = contact.photoUri

                    if (contact.phoneNumbers.size > 1) {
                        val specificPhoneNumber = contact.phoneNumbers.firstOrNull { it.value == number }
                        if (specificPhoneNumber != null) {
                            callContact.numberLabel = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                        }
                    }
                } else {
                    callContact.name = callContact.number
                }

                callback(callContact)
            }
        } else {
            callback(callContact)
        }
    }
}
