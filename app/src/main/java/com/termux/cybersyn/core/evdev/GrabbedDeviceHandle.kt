package com.termux.cybersyn.core.evdev

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class GrabbedDeviceHandle(
    val id: Int,
    val name: String,
    val bus: Int,
    val vendor: Int,
    val product: Int,
) : Parcelable
