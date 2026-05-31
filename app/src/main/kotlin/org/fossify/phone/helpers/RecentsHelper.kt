package org.fossify.phone.helpers

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.provider.CallLog.Calls
import android.provider.CallLog.Calls.PRESENTATION_UNAVAILABLE
import android.provider.CallLog.Calls.PRESENTATION_UNKNOWN
import android.telephony.PhoneNumberUtils
import android.util.Log
import org.fossify.commons.extensions.*
import org.fossify.commons.helpers.*
import org.fossify.commons.models.contacts.Contact
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.extensions.getAvailableSIMCardLabels
import org.fossify.phone.models.CallLogItem
import org.fossify.phone.models.RecentCall
import org.fossify.phone.models.SIMAccount

class RecentsHelper(private val context: Context) {
    companion object {
        private const val COMPARABLE_PHONE_NUMBER_LENGTH = 9
        const val QUERY_LIMIT = 100
        private const val TAG = "RecentsHelper_Perf"
    }

    private val contentUri = Calls.CONTENT_URI
    private var queryLimit = QUERY_LIMIT

    fun getRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(ArrayList())
            return
        }

            ContactsCache.getContacts(context) { contacts ->
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                this.queryLimit = queryLimit
                val recentCalls = if (previousRecents.isNotEmpty()) {
                    val previousRecentCalls = previousRecents
                        .flatMap { it.groupedCalls ?: listOf(it) }
                        .map { it.copy(groupedCalls = null) }

                    val newerRecents = getRecents(
                        contacts = contacts,
                        selection = "${Calls.DATE} > ?",
                        selectionParams = arrayOf("${previousRecentCalls.first().startTS}")
                    )

                    val olderRecents = getRecents(
                        contacts = contacts,
                        selection = "${Calls.DATE} < ?",
                        selectionParams = arrayOf("${previousRecentCalls.last().startTS}")
                    )

                    newerRecents + previousRecentCalls + olderRecents
                } else {
                    getRecents(contacts)
                }

                callback(
                    recentCalls
                        .sortedByDescending { it.startTS }
                        .distinctBy { it.id }
                )
            }
        }
    }

    fun getGroupedRecentCalls(
        previousRecents: List<RecentCall> = ArrayList(),
        queryLimit: Int = QUERY_LIMIT,
        callback: (List<CallLogItem>) -> Unit,
    ) {
        val appStartTime = System.currentTimeMillis()
        Log.d(TAG, "[PERF_START] Starting getGroupedRecentCalls")
        
        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            Log.d(TAG, "[PERF_PERMISSION] No call log permission")
            callback(emptyList())
            return
        }

        ContactsCache.getContacts(context) { contacts ->
            val contactsLoadTime = System.currentTimeMillis()
            Log.d(TAG, "[PERF_CONTACTS] Loaded ${contacts.size} contacts in ${contactsLoadTime - appStartTime}ms")
            
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                this.queryLimit = queryLimit
                
                val queryStartTime = System.currentTimeMillis()
                // Use optimized aggregated query instead of loading all records
                val aggregatedCalls = getRecentsAggregated(
                    contacts = contacts,
                    limit = queryLimit,
                    previousRecents = previousRecents
                )
                val queryEndTime = System.currentTimeMillis()
                Log.d(TAG, "[PERF_QUERY] Aggregated query returned ${aggregatedCalls.size} unique contacts in ${queryEndTime - queryStartTime}ms")

                val ignoredSources = context.baseConfig.ignoredContactSources
                val filterStartTime = System.currentTimeMillis()
                val filteredCalls = if (SMT_PRIVATE in ignoredSources) {
                    val privateNumbers = privateContacts.flatMap { it.phoneNumbers }.map { it.value }
                    aggregatedCalls.filterNot { it.phoneNumber in privateNumbers }
                } else {
                    aggregatedCalls
                }
                Log.d(TAG, "[PERF_FILTER] Filtered to ${filteredCalls.size} contacts in ${System.currentTimeMillis() - filterStartTime}ms")

                val dateGroupStartTime = System.currentTimeMillis()
                val finalResult = groupCallsByDate(filteredCalls)
                val dateGroupEndTime = System.currentTimeMillis()
                Log.d(TAG, "[PERF_DATE_GROUP] Date grouping completed in ${dateGroupEndTime - dateGroupStartTime}ms")
                
                val totalTime = dateGroupEndTime - appStartTime
                Log.d(TAG, "[PERF_TOTAL] Total time: ${totalTime}ms (Contacts: ${contactsLoadTime - appStartTime}ms | Query: ${queryEndTime - queryStartTime}ms | Filter: ${System.currentTimeMillis() - filterStartTime}ms | DateGroup: ${dateGroupEndTime - dateGroupStartTime}ms)")
                
                callback(finalResult)
            }
        }
    }

    fun getRecentCallsForNumber(
        recentCall: RecentCall,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val phoneNumber = recentCall.phoneNumber
        if (phoneNumber.isBlank() || !context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(emptyList())
            return
        }

        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
            ContactsCache.getContacts(context) { contacts ->
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                val matchingContact = contacts.firstOrNull { it.doesHavePhoneNumber(phoneNumber) }
                val numbersToMatch = (matchingContact?.phoneNumbers
                    ?.flatMap { listOf(it.value, it.normalizedNumber) }
                    ?: listOf(phoneNumber))
                    .plus(phoneNumber)
                    .filter { it.isNotBlank() }
                    .distinct()

                queryLimit = Int.MAX_VALUE
                val calls = getRecents(
                    contacts = contacts,
                    selection = "${Calls.NUMBER} IN (${getQuestionMarks(numbersToMatch.size)})",
                    selectionParams = numbersToMatch.toTypedArray()
                )

                callback(
                    calls
                        .filter { call -> numbersToMatch.any { PhoneNumberUtils.compare(call.phoneNumber, it) || call.phoneNumber == it } }
                        .sortedByDescending { it.startTS }
                        .distinctBy { it.id }
                )
            }
        }
    }

    /**
     * Get FULL call history for a specific phone number.
     * This is called when user opens a contact from the Recents screen.
     * 
     * Returns all calls for the given number (not limited to recent ones).
     * Includes call timestamps, duration, type, etc. for timeline/statistics.
     * 
     * Performance: Only loads data for one contact, so fast despite loading all history.
     */
    fun getCallHistoryForNumber(
        phoneNumber: String,
        callback: (List<RecentCall>) -> Unit,
    ) {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[HISTORY_START] Loading full call history for $phoneNumber")
        
        if (phoneNumber.isBlank() || !context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            callback(emptyList())
            return
        }

        val privateCursor = context.getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ContactsCache.getContacts(context) { contacts ->
            ensureBackgroundThread {
                val privateContacts = MyContactsContentProvider.getContacts(context, privateCursor)
                if (privateContacts.isNotEmpty()) {
                    contacts.addAll(privateContacts)
                }

                val matchingContact = contacts.firstOrNull { it.doesHavePhoneNumber(phoneNumber) }
                val numbersToMatch = (matchingContact?.phoneNumbers
                    ?.flatMap { listOf(it.value, it.normalizedNumber) }
                    ?: listOf(phoneNumber))
                    .plus(phoneNumber)
                    .filter { it.isNotBlank() }
                    .distinct()

                // Load FULL history - no limit
                val savedQueryLimit = queryLimit
                queryLimit = Int.MAX_VALUE
                
                val calls = getRecents(
                    contacts = contacts,
                    selection = "${Calls.NUMBER} IN (${getQuestionMarks(numbersToMatch.size)})",
                    selectionParams = numbersToMatch.toTypedArray()
                )

                queryLimit = savedQueryLimit

                val result = calls
                    .filter { call -> numbersToMatch.any { PhoneNumberUtils.compare(call.phoneNumber, it) || call.phoneNumber == it } }
                    .sortedByDescending { it.startTS }
                    .distinctBy { it.id }
                
                val elapsed = System.currentTimeMillis() - startTime
                Log.d(TAG, "[HISTORY_END] Loaded ${result.size} calls for $phoneNumber in ${elapsed}ms")

                callback(result)
            }
        }
    }

    /**
     * Get aggregated recents grouped by phone number.
     * Returns one record per unique contact with latest call info + total count.
     *
     * Performance design:
     *  - URI limit is capped at QUERY_LIMIT*20 (never passes Int.MAX_VALUE to ContentProvider)
     *  - Contact name/photo lookup maps are built ONCE before the cursor loop (O(1) per row)
     *  - Deduplication uses a last-N-digits HashMap (O(1)) instead of PhoneNumberUtils.compare()
     *    linear scan (was O(N×M) = ~10 million JNI calls for 19k rows × 1k contacts)
     *  - Early-exit fires as soon as `limit` unique contacts are found
     */
    @SuppressLint("NewApi")
    private fun getRecentsAggregated(
        contacts: List<Contact>,
        limit: Int = QUERY_LIMIT,
        previousRecents: List<RecentCall> = ArrayList(),
    ): List<RecentCall> {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[AGG_START] Starting aggregated query with limit=$limit")

        if (!context.hasPermission(PERMISSION_READ_CALL_LOG)) {
            return emptyList()
        }

        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }

        // Build selection to filter by previous recents if provided
        val (selection, selectionParams) = if (previousRecents.isNotEmpty()) {
            val previousRecentCalls = previousRecents
                .flatMap { it.groupedCalls ?: listOf(it) }
                .map { it.copy(groupedCalls = null) }
            Pair(
                "${Calls.DATE} >= ?",
                arrayOf("${previousRecentCalls.minOf { it.startTS }}")
            )
        } else {
            Pair(null, null)
        }

        // Never pass Int.MAX_VALUE to the ContentProvider URI — it overflows to negative.
        // QUERY_LIMIT*20 gives ample headroom for the early-exit to fire.
        val safeUriLimit = limit.coerceAtMost(QUERY_LIMIT * 20)

        val projection = arrayOf(
            Calls._ID, Calls.NUMBER, Calls.CACHED_NAME, Calls.CACHED_PHOTO_URI,
            Calls.DATE, Calls.DURATION, Calls.TYPE, Calls.PHONE_ACCOUNT_ID, Calls.NUMBER_PRESENTATION
        )

        val cursor = if (isNougatPlus()) {
            val limitedUri = contentUri.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, safeUriLimit.toString())
                .build()
            context.contentResolver.query(limitedUri, projection, selection, selectionParams, "${Calls.DATE} DESC")
        } else {
            context.contentResolver.query(
                contentUri, projection, selection, selectionParams, "${Calls.DATE} DESC LIMIT $safeUriLimit"
            )
        }

        // ── Pre-build O(1) contact lookup maps ──────────────────────────────────────
        // Key: last COMPARABLE_PHONE_NUMBER_LENGTH digits of normalizedNumber
        // Built once here; each cursor row gets a HashMap lookup instead of a contacts.filter{} scan.
        val normalizedToName  = HashMap<String, String>()
        val normalizedToPhoto = HashMap<String, String>()
        // Direct-value cache (exact match, fastest path)
        val valueToName  = HashMap<String, String>()
        val valueToPhoto = HashMap<String, String>()
        // For contacts with multiple numbers
        val numbersToContactIDMap = HashMap<String, Int>()

        contacts.forEach { contact ->
            val displayName = contact.getNameToDisplay()
            contact.phoneNumbers.forEach { pn ->
                // Exact-value maps
                if (pn.value.isNotBlank()) {
                    valueToName[pn.value]  = displayName
                    if (contact.photoUri.isNotBlank()) valueToPhoto[pn.value] = contact.photoUri
                }
                // Normalized-suffix maps
                val norm = pn.normalizedNumber
                val key = if (norm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                    norm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH) else norm
                if (key.isNotBlank()) {
                    normalizedToName[key]  = displayName
                    if (contact.photoUri.isNotBlank()) normalizedToPhoto[key] = contact.photoUri
                }
                // Multi-number map
                if (contact.phoneNumbers.size > 1) {
                    numbersToContactIDMap[pn.value]           = contact.contactId
                    numbersToContactIDMap[pn.normalizedNumber] = contact.contactId
                }
            }
        }
        // ────────────────────────────────────────────────────────────────────────────

        // ── O(1) deduplication HashMap ───────────────────────────────────────────────
        // Key:   last COMPARABLE_PHONE_NUMBER_LENGTH digits of the normalized number
        // Value: canonical phoneNumber string used as key in groupedByNumber
        // This replaces the previous PhoneNumberUtils.compare() linear scan — was ~10M JNI calls.
        val normalizedKeyToCanonical = HashMap<String, String>()

        // LinkedHashMap preserves insertion order (cursor is DESC, so first insertion = latest call)
        val groupedByNumber = LinkedHashMap<String, MutableList<RecentCall>>()
        val blockedNumbers = context.getBlockedNumbers()
        var rowsRead = 0

        cursor?.use {
            if (!cursor.moveToFirst()) return@use

            do {
                rowsRead++
                val id     = cursor.getIntValue(Calls._ID)
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED

                var isUnknownNumber = presentationBlocked || number.isNullOrBlank() || number == "-1"

                if (context.isNumberBlocked(number ?: "", blockedNumbers)) continue

                // ── Name resolution (O(1)) ───────────────────────────────────────────
                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                    ?.takeIf { it.isNotEmpty() && it != "-1" }

                if (name == null && !isUnknownNumber && !number.isNullOrBlank()) {
                    name = valueToName[number]
                        ?: run {
                            val norm = number.normalizePhoneNumber()
                            if (!norm.isNullOrBlank() && norm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                                normalizedToName[norm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)]
                            else null
                        }
                }
                if (name.isNullOrBlank() || name == "-1") {
                    name = if (isUnknownNumber) context.getString(R.string.unknown) else number.orEmpty()
                }
                // ────────────────────────────────────────────────────────────────────

                // ── Photo resolution (O(1)) ──────────────────────────────────────────
                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI)
                    ?.takeIf { it.isNotEmpty() }
                    ?: ""

                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    photoUri = valueToPhoto[number]
                        ?: run {
                            val norm = number.normalizePhoneNumber()
                            if (!norm.isNullOrBlank() && norm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                                normalizedToPhoto[norm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)]
                            else null
                        }
                        ?: ""
                }
                // ────────────────────────────────────────────────────────────────────

                val startTS    = cursor.getLongValue(Calls.DATE)
                val duration   = cursor.getIntValue(Calls.DURATION)
                val type       = cursor.getIntValue(Calls.TYPE)
                val accountId  = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                val simAccount = accountIdToSimAccountMap[accountId]

                var specificNumber = ""
                var specificType   = ""
                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val specificPhoneNumber = contacts
                        .firstOrNull { it.contactId == contactIdWithMultipleNumbers }
                        ?.phoneNumbers?.firstOrNull { it.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType   = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                val recentCall = RecentCall(
                    id             = id,
                    phoneNumber    = number.orEmpty(),
                    name           = name,
                    photoUri       = photoUri,
                    startTS        = startTS,
                    duration       = duration,
                    type           = type,
                    simID          = simAccount?.id ?: -1,
                    simColor       = simAccount?.color ?: -1,
                    specificNumber = specificNumber,
                    specificType   = specificType,
                    isUnknownNumber = isUnknownNumber
                )

                // ── O(1) dedup: normalized-suffix HashMap ────────────────────────────
                val rawNorm = number?.normalizePhoneNumber() ?: ""
                val dedupKey = if (rawNorm.length >= COMPARABLE_PHONE_NUMBER_LENGTH)
                    rawNorm.takeLast(COMPARABLE_PHONE_NUMBER_LENGTH)
                else
                    rawNorm.ifBlank { number.orEmpty() }

                val canonical = normalizedKeyToCanonical[dedupKey]
                if (canonical != null) {
                    // Already seen — append to existing group
                    groupedByNumber[canonical]?.add(recentCall)
                } else {
                    // New unique contact
                    normalizedKeyToCanonical[dedupKey] = recentCall.phoneNumber
                    groupedByNumber[recentCall.phoneNumber] = mutableListOf(recentCall)

                    // *** Early exit: stop reading as soon as we have enough unique contacts ***
                    if (groupedByNumber.size >= limit) break
                }
                // ────────────────────────────────────────────────────────────────────
            } while (cursor.moveToNext())
        }

        // Collapse each group: latest call becomes the parent, rest stored in groupedCalls
        val recentCalls = mutableListOf<RecentCall>()
        for ((_, callsForNumber) in groupedByNumber) {
            val sortedByTime = callsForNumber.sortedByDescending { it.startTS }
            val latestCall   = sortedByTime[0]
            recentCalls.add(
                if (sortedByTime.size > 1) latestCall.copy(groupedCalls = sortedByTime.toMutableList())
                else latestCall
            )
        }

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[AGG_END] Read $rowsRead rows → ${recentCalls.size} unique contacts in ${elapsed}ms")

        return recentCalls.sortedByDescending { it.startTS }
    }

    private fun shouldGroupCalls(callA: RecentCall, callB: RecentCall): Boolean {
        // Group calls from the same contact regardless of day or SIM
        // Only require phone number match
        val namesAreBothRealAndDifferent =
            callA.name != callB.name &&
                    callA.name != callA.phoneNumber &&
                    callB.name != callB.phoneNumber

        if (namesAreBothRealAndDifferent) return false

        @Suppress("DEPRECATION")
        return PhoneNumberUtils.compare(callA.phoneNumber, callB.phoneNumber)
    }

    private fun groupSubsequentCalls(calls: List<RecentCall>): List<RecentCall> {
        if (calls.isEmpty()) return emptyList()

        val startTime = System.currentTimeMillis()
        var comparisonCount = 0
        
        // Group all calls by phone number, not just sequential ones
        val groupedByNumber = mutableMapOf<String, MutableList<RecentCall>>()
        
        for (call in calls) {
            // Find if we already have a group for this phone number
            val existingGroup = groupedByNumber.values.find { group ->
                comparisonCount++
                @Suppress("DEPRECATION")
                PhoneNumberUtils.compare(group[0].phoneNumber, call.phoneNumber)
            }
            
            if (existingGroup != null) {
                existingGroup.add(call)
            } else {
                groupedByNumber[call.phoneNumber] = mutableListOf(call)
            }
        }

        // Convert to result: latest call as parent, rest as groupedCalls
        val result = mutableListOf<RecentCall>()
        for ((_, callsForNumber) in groupedByNumber) {
            val sortedByTime = callsForNumber.sortedByDescending { it.startTS }
            val latestCall = sortedByTime[0]
            
            result.add(
                if (sortedByTime.size > 1) {
                    latestCall.copy(groupedCalls = sortedByTime.toMutableList())
                } else {
                    latestCall
                }
            )
        }

        // Sort result by latest call timestamp descending
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[GROUPING_DETAIL] Grouped ${calls.size} calls into ${result.size} groups | ${comparisonCount} comparisons | ${elapsed}ms")
        
        return result.sortedByDescending { it.startTS }
    }

    @SuppressLint("NewApi")
    private fun getRecents(
        contacts: List<Contact>,
        selection: String? = null,
        selectionParams: Array<String>? = null,
    ): List<RecentCall> {
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "[GETRECENTS_START] Starting with ${contacts.size} contacts, selection: $selection")
        
        val recentCalls = mutableListOf<RecentCall>()
        var previousStartTS = 0L
        val contactsNumbersMap = HashMap<String, String>()
        val contactPhotosMap = HashMap<String, String>()

        val projection = arrayOf(
            Calls._ID,
            Calls.NUMBER,
            Calls.CACHED_NAME,
            Calls.CACHED_PHOTO_URI,
            Calls.DATE,
            Calls.DURATION,
            Calls.TYPE,
            Calls.PHONE_ACCOUNT_ID,
            Calls.NUMBER_PRESENTATION
        )

        val accountIdToSimAccountMap = HashMap<String, SIMAccount>()
        context.getAvailableSIMCardLabels().forEach {
            accountIdToSimAccountMap[it.handle.id] = it
        }

        val cursor = if (isNougatPlus()) {
            // https://issuetracker.google.com/issues/175198972?pli=1#comment6
            val limitedUri = contentUri.buildUpon()
                .appendQueryParameter(Calls.LIMIT_PARAM_KEY, queryLimit.toString())
                .build()
            val sortOrder = "${Calls.DATE} DESC"
            context.contentResolver.query(limitedUri, projection, selection, selectionParams, sortOrder)
        } else {
            val sortOrder = "${Calls.DATE} DESC LIMIT $queryLimit"
            context.contentResolver.query(contentUri, projection, selection, selectionParams, sortOrder)
        }

        val contactsWithMultipleNumbers = contacts.filter { it.phoneNumbers.size > 1 }
        val numbersToContactIDMap = HashMap<String, Int>()
        contactsWithMultipleNumbers.forEach { contact ->
            contact.phoneNumbers.forEach { phoneNumber ->
                numbersToContactIDMap[phoneNumber.value] = contact.contactId
                numbersToContactIDMap[phoneNumber.normalizedNumber] = contact.contactId
            }
        }

        cursor?.use {
            if (!cursor.moveToFirst()) {
                return@use
            }

            do {
                val id = cursor.getIntValue(Calls._ID)
                var isUnknownNumber = false
                val number = cursor.getStringValueOrNull(Calls.NUMBER)
                val presentation = cursor.getIntValueOrNull(Calls.NUMBER_PRESENTATION) ?: Calls.PRESENTATION_ALLOWED
                val presentationBlocked = presentation == PRESENTATION_UNKNOWN
                        || presentation == PRESENTATION_UNAVAILABLE
                        || presentation == Calls.PRESENTATION_RESTRICTED
                if (presentationBlocked || number.isNullOrBlank() || number == "-1") {
                    isUnknownNumber = true
                }

                var name = cursor.getStringValueOrNull(Calls.CACHED_NAME)
                if (name.isNullOrEmpty() || name == "-1") {
                    name = number.orEmpty()
                }

                if (name == number && !isUnknownNumber) {
                    if (contactsNumbersMap.containsKey(number)) {
                        name = contactsNumbersMap[number]!!
                    } else {
                        val normalizedNumber = number.normalizePhoneNumber()
                        if (normalizedNumber!!.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                            name = contacts.filter { it.phoneNumbers.isNotEmpty() }.firstOrNull { contact ->
                                val curNumber = contact.phoneNumbers.first().normalizedNumber
                                if (curNumber.length >= COMPARABLE_PHONE_NUMBER_LENGTH) {
                                    if (curNumber.substring(curNumber.length - COMPARABLE_PHONE_NUMBER_LENGTH) == normalizedNumber.substring(
                                            normalizedNumber.length - COMPARABLE_PHONE_NUMBER_LENGTH
                                        )
                                    ) {
                                        contactsNumbersMap[number] = contact.getNameToDisplay()
                                        return@firstOrNull true
                                    }
                                }
                                false
                            }?.name ?: number
                        }
                    }
                }

                if (name.isEmpty() || name == "-1") {
                    name = context.getString(R.string.unknown)
                }

                var photoUri = cursor.getStringValue(Calls.CACHED_PHOTO_URI) ?: ""
                if (photoUri.isEmpty() && !number.isNullOrEmpty()) {
                    if (contactPhotosMap.containsKey(number)) {
                        photoUri = contactPhotosMap[number]!!
                    } else {
                        val contact = contacts.firstOrNull { it.doesHavePhoneNumber(number) }
                        if (contact != null) {
                            photoUri = contact.photoUri
                            contactPhotosMap[number] = contact.photoUri
                        }
                    }
                }

                val startTS = cursor.getLongValue(Calls.DATE)
                if (previousStartTS == startTS) {
                    continue
                } else {
                    previousStartTS = startTS
                }

                val duration = cursor.getIntValue(Calls.DURATION)
                val type = cursor.getIntValue(Calls.TYPE)
                val accountId = cursor.getStringValue(Calls.PHONE_ACCOUNT_ID)
                val simAccount = accountIdToSimAccountMap[accountId]
                var specificNumber = ""
                var specificType = ""

                val contactIdWithMultipleNumbers = numbersToContactIDMap[number]
                if (contactIdWithMultipleNumbers != null) {
                    val specificPhoneNumber =
                        contacts.firstOrNull { it.contactId == contactIdWithMultipleNumbers }?.phoneNumbers?.firstOrNull { it.value == number }
                    if (specificPhoneNumber != null) {
                        specificNumber = specificPhoneNumber.value
                        specificType = context.getPhoneNumberTypeText(specificPhoneNumber.type, specificPhoneNumber.label)
                    }
                }

                recentCalls.add(
                    RecentCall(
                        id = id,
                        phoneNumber = number.orEmpty(),
                        name = name,
                        photoUri = photoUri,
                        startTS = startTS,
                        duration = duration,
                        type = type,
                        simID = simAccount?.id ?: -1,
                        simColor = simAccount?.color ?: -1,
                        specificNumber = specificNumber,
                        specificType = specificType,
                        isUnknownNumber = isUnknownNumber
                    )
                )
            } while (cursor.moveToNext() && recentCalls.size < queryLimit)
        }

        val blockedNumbers = context.getBlockedNumbers()

        val result = recentCalls
            .filter { !context.isNumberBlocked(it.phoneNumber, blockedNumbers) }
        
        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "[GETRECENTS_END] getRecents completed in ${elapsed}ms | Processed ${recentCalls.size} calls | Blocked ${recentCalls.size - result.size} | Final: ${result.size}")
        
        return result
    }

    fun removeRecentCalls(ids: List<Int>, callback: () -> Unit) {
        ensureBackgroundThread {
            ids.chunked(30).forEach { chunk ->
                val selection = "${Calls._ID} IN (${getQuestionMarks(chunk.size)})"
                val selectionArgs = chunk.map { it.toString() }.toTypedArray()
                context.contentResolver.delete(contentUri, selection, selectionArgs)
            }
            callback()
        }
    }

    @SuppressLint("MissingPermission")
    fun removeAllRecentCalls(activity: SimpleActivity, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) {
            if (it) {
                ensureBackgroundThread {
                    context.contentResolver.delete(contentUri, null, null)
                    callback()
                }
            }
        }
    }

    fun restoreRecentCalls(activity: SimpleActivity, objects: List<RecentCall>, callback: () -> Unit) {
        activity.handlePermission(PERMISSION_WRITE_CALL_LOG) { granted ->
            if (granted) {
                ensureBackgroundThread {
                    val values = objects
                        .sortedBy { it.startTS }
                        .map {
                            ContentValues().apply {
                                put(Calls.NUMBER, it.phoneNumber)
                                put(Calls.TYPE, it.type)
                                put(Calls.DATE, it.startTS)
                                put(Calls.DURATION, it.duration)
                                put(Calls.CACHED_NAME, it.name)
                            }
                        }.toTypedArray()

                    context.contentResolver.bulkInsert(contentUri, values)
                    callback()
                }
            }
        }
    }

    private fun groupCallsByDate(recentCalls: List<RecentCall>): List<CallLogItem> {
        val callLog = mutableListOf<CallLogItem>()
        var lastDayCode = ""
        for (call in recentCalls) {
            val currentDayCode = call.dayCode
            if (currentDayCode != lastDayCode) {
                callLog += CallLogItem.Date(timestamp = call.startTS, dayCode = currentDayCode)
                lastDayCode = currentDayCode
            }

            callLog += call
        }

        return callLog
    }
}

