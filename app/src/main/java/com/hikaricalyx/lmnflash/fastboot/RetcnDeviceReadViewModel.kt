package com.hikaricalyx.lmnflash.fastboot

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DeviceReadSource { FASTBOOT, ADB_USB }

data class RetcnDeviceReadState(
    val status: RetcnDeviceReadStatus = RetcnDeviceReadStatus.Idle,
    val picker: List<FastbootCandidate> = emptyList(),
    val deviceInfo: RetcnDeviceInfo? = null,
    val fillSequence: Long = 0,
    /** Retry becomes available only after the current ADB authorization attempt releases USB. */
    val adbAuthorizationRetryAvailable: Boolean = false,
)

sealed interface RetcnDeviceReadStatus {
    data object Idle : RetcnDeviceReadStatus
    data class RequestingPermission(val source: DeviceReadSource) : RetcnDeviceReadStatus
    data object ConnectingToAdb : RetcnDeviceReadStatus
    /** The device has received our RSA public key and is waiting for approval or a user-triggered retry. */
    data object WaitingForAdbAuthorization : RetcnDeviceReadStatus
    data class AdbConnectionFailed(val detail: String) : RetcnDeviceReadStatus
    data object Reading : RetcnDeviceReadStatus
    data class Filled(val serial: String) : RetcnDeviceReadStatus
    data class Error(val message: String) : RetcnDeviceReadStatus
}

class RetcnDeviceReadViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val fastbootClient = UsbFastbootClient(usbManager)
    private val adbClient = UsbAdbClient(appContext, usbManager)
    private var pendingDevice: FastbootCandidate? = null
    private var pendingAdbAuthorization: FastbootCandidate? = null
    private var readAttempt = 0L

    var state by mutableStateOf(RetcnDeviceReadState())
        private set

    fun readFromFastboot() = selectCandidates(fastbootClient.candidates(), "No fastboot device connected")

    /** Reads the fields stock adbd exposes after the user approves the USB debugging RSA key. */
    fun readFromAdbUsb() = selectCandidates(adbClient.candidates(), "No ADB device connected")

    /** Rescans because the target may re-enumerate as a new UsbDevice after ADB authorization. */
    fun retryAdbAuthorization() = retryAdbUsb()

    fun retryAdbConnection() = retryAdbUsb()

    private fun retryAdbUsb() {
        val retryAttempt = ++readAttempt
        pendingAdbAuthorization = null
        state = state.copy(
            status = RetcnDeviceReadStatus.ConnectingToAdb,
            picker = emptyList(),
            adbAuthorizationRetryAvailable = false,
        )
        viewModelScope.launch {
            // Give USB framework enumeration a moment to replace the stale device instance.
            delay(500)
            if (retryAttempt == readAttempt) readFromAdbUsb()
        }
    }

    fun cancelAdbAuthorization() {
        readAttempt++
        pendingAdbAuthorization = null
        state = state.copy(status = RetcnDeviceReadStatus.Idle, adbAuthorizationRetryAvailable = false)
    }

    fun cancelAdbConnection() = cancelAdbAuthorization()

    fun cancelUsbPermission() {
        pendingDevice?.device?.let(FastbootUsbPermissionBroker::cancel)
        pendingDevice = null
        state = state.copy(status = RetcnDeviceReadStatus.Idle)
    }

    fun selectDevice(candidate: FastbootCandidate) {
        state = state.copy(picker = emptyList())
        requestPermissionOrRead(candidate)
    }

    fun cancelPicker() {
        state = state.copy(picker = emptyList(), status = RetcnDeviceReadStatus.Idle)
    }

    private fun selectCandidates(candidates: List<FastbootCandidate>, emptyMessage: String) {
        when (candidates.size) {
            0 -> state = state.copy(status = RetcnDeviceReadStatus.Error(emptyMessage))
            1 -> requestPermissionOrRead(candidates.single())
            else -> state = state.copy(picker = candidates)
        }
    }

    private fun requestPermissionOrRead(candidate: FastbootCandidate) {
        if (usbManager.hasPermission(candidate.device)) {
            execute(candidate)
            return
        }
        pendingDevice = candidate
        state = state.copy(status = RetcnDeviceReadStatus.RequestingPermission(candidate.source))
        val deviceName = candidate.device.deviceName
        FastbootUsbPermissionBroker.request(appContext, candidate.device) { granted ->
            if (pendingDevice?.device?.deviceName != deviceName) return@request
            pendingDevice = null
            if (granted) execute(candidate)
            else state = state.copy(status = RetcnDeviceReadStatus.Error("USB permission was denied."))
        }
        viewModelScope.launch {
            delay(60_000)
            if (pendingDevice?.device?.deviceName == deviceName) {
                FastbootUsbPermissionBroker.cancel(candidate.device)
                pendingDevice = null
                state = state.copy(status = RetcnDeviceReadStatus.Error("USB permission request timed out."))
            }
        }
    }

    private fun execute(candidate: FastbootCandidate) {
        val attempt = ++readAttempt
        val isAdb = candidate.source == DeviceReadSource.ADB_USB
        if (isAdb) pendingAdbAuthorization = candidate
        state = state.copy(
            status = if (isAdb) RetcnDeviceReadStatus.ConnectingToAdb else RetcnDeviceReadStatus.Reading,
            adbAuthorizationRetryAvailable = false,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (candidate.source) {
                        DeviceReadSource.FASTBOOT -> fastbootClient.readRetcnInfo(candidate)
                        DeviceReadSource.ADB_USB -> adbClient.readRetcnInfo(candidate)
                    }
                }
            }.onSuccess { info ->
                if (attempt != readAttempt) return@onSuccess
                pendingAdbAuthorization = null
                state = state.copy(
                    deviceInfo = info,
                    fillSequence = state.fillSequence + 1,
                    status = RetcnDeviceReadStatus.Filled(info.serialNumber ?: candidate.label),
                    adbAuthorizationRetryAvailable = false,
                )
            }.onFailure { error ->
                if (attempt != readAttempt) return@onFailure
                if (error is AdbAuthorizationPendingException) {
                    state = state.copy(
                        status = RetcnDeviceReadStatus.WaitingForAdbAuthorization,
                        adbAuthorizationRetryAvailable = true,
                    )
                } else if (error is AdbTransportException) {
                    state = state.copy(
                        status = RetcnDeviceReadStatus.AdbConnectionFailed(error.message.orEmpty()),
                        adbAuthorizationRetryAvailable = false,
                    )
                } else {
                    pendingAdbAuthorization = null
                    state = state.copy(
                        status = RetcnDeviceReadStatus.Error(error.message ?: "Unable to read device"),
                        adbAuthorizationRetryAvailable = false,
                    )
                }
            }
        }
    }

    override fun onCleared() {
        pendingDevice?.device?.let(FastbootUsbPermissionBroker::cancel)
    }
}
