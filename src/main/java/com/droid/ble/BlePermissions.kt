package com.droid.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object BlePermissions {

    fun required(): Array<String> {
        val perms = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            perms.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            perms.addAll(listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN))
            perms.addAll(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
        return perms.toTypedArray()
    }

    fun hasAll(context: Context): Boolean =
        required().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}