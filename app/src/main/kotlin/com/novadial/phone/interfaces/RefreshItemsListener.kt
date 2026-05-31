package com.novadial.phone.interfaces

interface RefreshItemsListener {
    fun refreshItems(invalidate: Boolean = false, callback: (() -> Unit)? = null)
}
