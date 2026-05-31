package com.novadial.phone.models

sealed class Events {
    data object RefreshCallLog : Events()
}
