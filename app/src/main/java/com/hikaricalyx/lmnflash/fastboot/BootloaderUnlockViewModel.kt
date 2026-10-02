package com.hikaricalyx.lmnflash.fastboot

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class BootloaderUiState(
    val deviceId: String = "",
    val status: BootloaderStatus = BootloaderStatus.Idle,
    val eligibility: UnlockEligibility = UnlockEligibility.Idle,
    val picker: List<FastbootCandidate> = emptyList(),
)

sealed interface UnlockEligibility {
    data object Idle : UnlockEligibility
    data object Checking : UnlockEligibility
    data class Result(val qualified: Boolean) : UnlockEligibility
}

sealed interface BootloaderStatus {
    data object Idle : BootloaderStatus
    data object RequestingPermission : BootloaderStatus
    data object Reading : BootloaderStatus
    data object Unlocking : BootloaderStatus
    data class Success(val messageKey: String) : BootloaderStatus
    data class Error(val message: String) : BootloaderStatus
}

private sealed interface PendingAction {
    data object Read : PendingAction
    data class Unlock(val key: String) : PendingAction
}

class BootloaderUnlockViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val client = UsbFastbootClient(usbManager)
    private var pendingAction: PendingAction? = null
    private var pendingDevice: FastbootCandidate? = null
    private var eligibilityRequestId = 0L

    var state by mutableStateOf(BootloaderUiState())
        private set

    fun readUnlockData() = begin(PendingAction.Read)

    fun unlock(key: String) {
        if (key.isBlank()) {
            state = state.copy(status = BootloaderStatus.Error("Enter the unlock key first."))
            return
        }
        begin(PendingAction.Unlock(key))
    }

    fun selectDevice(candidate: FastbootCandidate) {
        val action = pendingAction ?: return
        state = state.copy(picker = emptyList())
        requestPermissionOrExecute(candidate, action)
    }

    fun cancelPicker() {
        pendingAction = null
        state = state.copy(picker = emptyList(), status = BootloaderStatus.Idle)
    }

    fun clearStatus() { state = state.copy(status = BootloaderStatus.Idle) }

    fun markDeviceIdCopied() {
        state = state.copy(status = BootloaderStatus.Success("flash-bootloader-copied"))
    }

    private fun begin(action: PendingAction) {
        val candidates = client.candidates()
        if (candidates.isEmpty()) {
            state = state.copy(status = BootloaderStatus.Error("No fastboot device connected"))
            return
        }
        pendingAction = action
        if (candidates.size == 1) requestPermissionOrExecute(candidates.single(), action)
        else state = state.copy(picker = candidates)
    }

    private fun requestPermissionOrExecute(candidate: FastbootCandidate, action: PendingAction) {
        if (usbManager.hasPermission(candidate.device)) {
            pendingAction = null
            execute(candidate, action)
            return
        }
        pendingAction = action
        pendingDevice = candidate
        state = state.copy(status = BootloaderStatus.RequestingPermission)
        val deviceName = candidate.device.deviceName
        FastbootUsbPermissionBroker.request(appContext, candidate.device) { granted ->
            if (pendingDevice?.device?.deviceName != deviceName) return@request
            val pending = pendingAction
            pendingDevice = null
            pendingAction = null
            if (granted && pending != null) execute(candidate, pending)
            else state = state.copy(status = BootloaderStatus.Error("USB permission was denied."))
        }
        viewModelScope.launch {
            delay(60_000)
            if (pendingDevice?.device?.deviceName == deviceName) {
                FastbootUsbPermissionBroker.cancel(candidate.device)
                pendingDevice = null
                pendingAction = null
                state = state.copy(status = BootloaderStatus.Error("USB permission request timed out."))
            }
        }
    }

    private fun execute(candidate: FastbootCandidate, action: PendingAction) {
        if (action is PendingAction.Read) eligibilityRequestId += 1
        state = state.copy(
            status = if (action is PendingAction.Read) BootloaderStatus.Reading else BootloaderStatus.Unlocking,
            eligibility = if (action is PendingAction.Read) UnlockEligibility.Idle else state.eligibility,
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    when (action) {
                        PendingAction.Read -> client.readUnlockData(candidate)
                        is PendingAction.Unlock -> client.unlock(candidate, action.key)
                    }
                }
            }.onSuccess { result ->
                if (result is String) {
                    state = state.copy(
                        deviceId = result,
                        status = BootloaderStatus.Success("flash-bootloader-copied"),
                        eligibility = UnlockEligibility.Checking,
                    )
                    startEligibilityCheck(result)
                } else {
                    state = when (result) {
                        FastbootUnlockResult.Unlocked -> state.copy(status = BootloaderStatus.Success("flash-bootloader-unlocked"))
                        FastbootUnlockResult.AlreadyUnlocked -> state.copy(status = BootloaderStatus.Success("flash-bootloader-unlocked"))
                        FastbootUnlockResult.OemUnlockingDisabled -> state.copy(status = BootloaderStatus.Error("Turn on \"OEM unlocking\" in Developer Options, then try again."))
                        FastbootUnlockResult.Cancelled -> state.copy(status = BootloaderStatus.Error("Bootloader unlock request has been cancelled."))
                        FastbootUnlockResult.WrongKey -> state.copy(status = BootloaderStatus.Error("Bootloader unlock failed due to wrong unlock key."))
                        is FastbootUnlockResult.Failed -> state.copy(status = BootloaderStatus.Error(result.message))
                        else -> state
                    }
                }
            }.onFailure { error -> state = state.copy(status = BootloaderStatus.Error(error.message ?: "Fastboot operation failed")) }
        }
    }

    private fun startEligibilityCheck(deviceId: String) {
        val requestId = ++eligibilityRequestId
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { checkUnlockEligibility(deviceId) } }
                .onSuccess { qualified ->
                    if (requestId == eligibilityRequestId) state = state.copy(eligibility = UnlockEligibility.Result(qualified))
                }
                .onFailure {
                    // Match the PC flow: a failed network check must not hide a successfully read Device ID.
                    if (requestId == eligibilityRequestId) state = state.copy(eligibility = UnlockEligibility.Idle)
                }
        }
    }

    override fun onCleared() {
        pendingDevice?.device?.let(FastbootUsbPermissionBroker::cancel)
    }
}

private fun Intent.usbDevice(): UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
} else {
    @Suppress("DEPRECATION") getParcelableExtra(UsbManager.EXTRA_DEVICE)
}
