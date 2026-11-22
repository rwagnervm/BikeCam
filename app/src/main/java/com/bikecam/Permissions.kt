package com.bikecam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object Permissions {
    fun has(ctx: Context, perm: String) =
        ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

    fun camera(ctx: Context) = has(ctx, Manifest.permission.CAMERA)
    fun location(ctx: Context) = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
    fun audio(ctx: Context) = has(ctx, Manifest.permission.RECORD_AUDIO)

    fun bluetooth(ctx: Context) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        has(ctx, Manifest.permission.BLUETOOTH_SCAN) && has(ctx, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        location(ctx)
    }

    /** Tudo o que o app pede na abertura. */
    fun required(): Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()
}
