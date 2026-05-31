package com.novadial.phone.models

import org.fossify.commons.helpers.DAY_SECONDS

sealed class CallLogItem {
    data class Date(
        val timestamp: Long,
        val dayCode: String,
    ) : CallLogItem()

    fun getItemId(): Int {
        return when (this) {
            is Date -> {
                val cleanDate = dayCode.replace("-", "")
                val dayInt = cleanDate.toIntOrNull()
                if (dayInt != null) {
                    -dayInt
                } else {
                    val hash = dayCode.hashCode()
                    val uniqueId = if (hash == Int.MIN_VALUE) Int.MAX_VALUE else kotlin.math.abs(hash)
                    -uniqueId
                }
            }
            is RecentCall -> id
        }
    }
}
