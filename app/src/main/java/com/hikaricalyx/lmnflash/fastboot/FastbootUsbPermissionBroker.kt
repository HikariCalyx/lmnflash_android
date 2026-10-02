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
    private val pendingCallbacks = mutableMapOf<String, (Boolean) -> Unit>()
    private var initialized = false

    fun request(context: Context, device: UsbDevice, onResult: (Boolean) -> Unit) {
        val appContext = context.applicationContext
        ensureInitialized(appContext)
        pendingCallbacks[device.deviceName] = onResult
        val intent = Intent(FASTBOOT_USB_PERMISSION_ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            pendingCallbacks.remove(device.deviceName)?.invoke(
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false),
            )
        }
    }
}

private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
