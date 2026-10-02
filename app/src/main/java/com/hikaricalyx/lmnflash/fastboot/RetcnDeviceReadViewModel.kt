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

data class RetcnDeviceReadState(
    val status: RetcnDeviceReadStatus = RetcnDeviceReadStatus.Idle,
    val picker: List<FastbootCandidate> = emptyList(),
    val deviceInfo: RetcnDeviceInfo? = null,
    val fillSequence: Long = 0,
)

sealed interface RetcnDeviceReadStatus {
    data object Idle : RetcnDeviceReadStatus
    data object RequestingPermission : RetcnDeviceReadStatus
    data object Reading : RetcnDeviceReadStatus
    data class Filled(val serial: String) : RetcnDeviceReadStatus
    data class Error(val message: String) : RetcnDeviceReadStatus
}

class RetcnDeviceReadViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val client = UsbFastbootClient(usbManager)
    private var pendingDevice: FastbootCandidate? = null

    var state by mutableStateOf(RetcnDeviceReadState())
        private set

    fun readFromFastboot() {
        val candidates = client.candidates()
        if (candidates.isEmpty()) {
            state = state.copy(status = RetcnDeviceReadStatus.Error("No fastboot device connected"))
        } else if (candidates.size == 1) {
            requestPermissionOrRead(candidates.single())
        } else {
            state = state.copy(picker = candidates)
        }
    }

    fun selectDevice(candidate: FastbootCandidate) {
        state = state.copy(picker = emptyList())
        requestPermissionOrRead(candidate)
    }

    fun cancelPicker() {
        state = state.copy(picker = emptyList(), status = RetcnDeviceReadStatus.Idle)
    }

    private fun requestPermissionOrRead(candidate: FastbootCandidate) {
        if (usbManager.hasPermission(candidate.device)) {
            execute(candidate)
            return
        }
        pendingDevice = candidate
        state = state.copy(status = RetcnDeviceReadStatus.RequestingPermission)
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
        state = state.copy(status = RetcnDeviceReadStatus.Reading)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.readRetcnInfo(candidate) } }
                .onSuccess { info ->
                    state = state.copy(
                        deviceInfo = info,
                        fillSequence = state.fillSequence + 1,
                        status = RetcnDeviceReadStatus.Filled(info.serialNumber ?: candidate.label),
                    )
                }
                .onFailure { error -> state = state.copy(status = RetcnDeviceReadStatus.Error(error.message ?: "Unable to read device")) }
        }
    }

    override fun onCleared() {
        pendingDevice?.device?.let(FastbootUsbPermissionBroker::cancel)
    }
}
