package com.hikaricalyx.lmnflash.fastboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat

private const val FASTBOOT_USB_PERMISSION_ACTION = "com.hikaricalyx.lmnflash.USB_FASTBOOT_PERMISSION"

/** Application-scoped USB-permission receiver shared by every Fastboot feature. */
object FastbootUsbPermissionBroker {
    private val pendingCallbacks = mutableMapOf<String, MutableList<(Boolean) -> Unit>>()
    private var initialized = false

    fun request(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        pendingCallbacks.getOrPut(device.deviceName) { mutableListOf() }.add(onResult)
        // Scope the result broadcast to this app; the receiver is registered before this call.
        val intent = Intent(FASTBOOT_USB_PERMISSION_ACTION).setPackage(appContext.packageName)
        // UsbManager fills the device and grant-result extras into this callback intent.
        // It is package-scoped, so mutability does not expose it to other applications.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(appContext, device.deviceId, intent, flags)
        appContext.getSystemService(UsbManager::class.java).requestPermission(device, permissionIntent)
    }

    fun cancel(device: UsbDevice) {
        pendingCallbacks.remove(device.deviceName)
    }

    private fun ensureInitialized(context: Context) {
        if (initialized) return
        ContextCompat.registerReceiver(
            context,
            permissionReceiver,
            IntentFilter(FASTBOOT_USB_PERMISSION_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )
        initialized = true
    }

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != FASTBOOT_USB_PERMISSION_ACTION) return
            val device = intent.usbDevice() ?: return
            pendingCallbacks.remove(device.deviceName)?.forEach { callback ->
                callback(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
    }
}

private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
