package com.hikaricalyx.lmnflash.firmwareflash

import android.content.Context
import android.hardware.usb.UsbManager
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hikaricalyx.lmnflash.fastboot.FastbootCandidate
import com.hikaricalyx.lmnflash.fastboot.FastbootDeviceInfo
import com.hikaricalyx.lmnflash.fastboot.FastbootRebootMode
import com.hikaricalyx.lmnflash.fastboot.FastbootUsbPermissionBroker
import com.hikaricalyx.lmnflash.fastboot.UsbFastbootClient
import com.hikaricalyx.lmnflash.firmwareflash.FlashPart
import com.hikaricalyx.lmnflash.firmwareflash.flashPart
import com.hikaricalyx.lmnflash.firmwareflash.isMediatek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MISMATCH_CONFIRMATION_SECONDS = 10
private const val MAX_LOG_LINES = 500

data class FirmwareFlashUiState(
    val packageName: String? = null,
    val flashPackage: FlashPackage? = null,
    val enabledOperations: List<Boolean> = emptyList(),
    val editingOperations: Boolean = false,
    val loading: Boolean = false,
    val packageProgress: Pair<Long, Long>? = null,
    val candidates: List<FastbootCandidate> = emptyList(),
    val picker: List<FastbootCandidate> = emptyList(),
    val selected: FastbootCandidate? = null,
    val deviceInfo: FastbootDeviceInfo? = null,
    val requestingPermission: Boolean = false,
    val readingDevice: Boolean = false,
    val readingInfo: Boolean = false,
    val infoView: Boolean = false,
    val infoLines: List<String> = emptyList(),
    val infoError: String? = null,
    val hideSensitiveInfo: Boolean = false,
    val customCommandExecuting: Boolean = false,
    val customCommandResponse: List<String> = emptyList(),
    val customCommandError: String? = null,
    val error: String? = null,
    val confirming: Boolean = false,
    val mismatchCountdown: Int = 0,
    val flashing: Boolean = false,
    val rebooting: Boolean = false,
    val stepIndex: Int = 0,
    val stepTotal: Int = 0,
    val stepLabel: String = "",
    val transferProgress: Pair<Long, Long>? = null,
    val result: Result<Unit>? = null,
    val log: List<String> = emptyList(),
) {
    val enabledOperationCount: Int get() = enabledOperations.count { it }
    val operationCount: Int get() = flashPackage?.operations?.size ?: 0
    val busy: Boolean get() = loading || requestingPermission || readingDevice || readingInfo || customCommandExecuting || flashing || rebooting
    val ready: Boolean get() = flashPackage != null && selected != null && deviceInfo != null && enabledOperationCount > 0 && !busy
}

class FirmwareFlashViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(UsbManager::class.java)
    private val client = UsbFastbootClient(usbManager)
    private var operationId = 0L
    private var infoRequestId = 0L
    private var pendingCandidate: FastbootCandidate? = null

    var state by mutableStateOf(FirmwareFlashUiState())
        private set

    fun selectZip(uri: Uri) {
        val id = ++operationId
        state.flashPackage?.deleteCachedArchive()
        state = state.copy(
            packageName = null,
            flashPackage = null,
            enabledOperations = emptyList(),
            editingOperations = false,
            loading = true,
            packageProgress = null,
            error = null,
            result = null,
            log = emptyList(),
        )
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FlashPackage.load(appContext, uri) { done, total -> updateIfCurrent(id) { it.copy(packageProgress = done to total) } } }
            }.onSuccess { flashPackage ->
                if (id == operationId) state = state.copy(
                    packageName = flashPackage.displayName,
                    flashPackage = flashPackage,
                    enabledOperations = List(flashPackage.operations.size) { true },
                    loading = false,
                    packageProgress = null,
                )
            }.onFailure { error ->
                if (id == operationId) state = state.copy(loading = false, packageProgress = null, error = error.message ?: "Unable to load the firmware ZIP.")
            }
        }
    }

    fun refreshDevices() {
        if (state.busy || state.confirming || state.editingOperations) return
        val candidates = client.candidates()
        state = state.copy(
            candidates = candidates,
            picker = emptyList(),
            selected = null,
            deviceInfo = null,
            infoView = false,
            infoLines = emptyList(),
            infoError = null,
            error = if (candidates.isEmpty()) "No fastboot device connected" else null,
        )
        if (candidates.size == 1) selectDevice(candidates.single()) else if (candidates.size > 1) state = state.copy(picker = candidates)
    }

    fun selectDevice(candidate: FastbootCandidate) {
        if (state.busy || state.confirming || state.editingOperations) return
        state = state.copy(picker = emptyList(), selected = candidate, deviceInfo = null, error = null, result = null, infoView = false, infoLines = emptyList(), infoError = null)
        if (usbManager.hasPermission(candidate.device)) readDevice(candidate) else requestPermission(candidate)
    }

    fun dismissPicker() { state = state.copy(picker = emptyList()) }

    fun openOperationEditor() {
        if (state.busy || state.flashPackage == null) return
        state = state.copy(editingOperations = true, confirming = false, mismatchCountdown = 0)
    }

    fun closeOperationEditor() { if (!state.flashing) state = state.copy(editingOperations = false) }

    fun setOperationEnabled(index: Int, enabled: Boolean) {
        if (state.busy || !state.editingOperations) return
        val flags = state.enabledOperations.toMutableList()
        if (index !in flags.indices) return
        flags[index] = enabled
        state = state.copy(enabledOperations = flags)
    }

    fun setAllOperationsEnabled(enabled: Boolean) {
        if (state.busy || !state.editingOperations) return
        state = state.copy(enabledOperations = List(state.operationCount) { enabled })
    }

    /** Adds all PC-classified image steps of one part without clearing other selected rows. */
    fun selectFlashPart(part: FlashPart) {
        val flashPackage = state.flashPackage ?: return
        if (state.busy || !state.editingOperations) return
        val mediatek = flashPackage.isMediatek()
        val enabled = state.enabledOperations.toMutableList()
        flashPackage.operations.forEachIndexed { index, operation ->
            if (operation.flashPart(mediatek) == part && index in enabled.indices) enabled[index] = true
        }
        state = state.copy(enabledOperations = enabled)
    }

    fun requestFlashConfirmation() {
        if (!state.ready) {
            if (state.flashPackage != null && state.enabledOperationCount == 0) state = state.copy(error = "Select at least one flashing step.")
            return
        }
        val mismatch = FlashPackage.projectMismatch(state.flashPackage?.projectCode, state.deviceInfo?.product)
        state = state.copy(confirming = true, mismatchCountdown = if (mismatch) MISMATCH_CONFIRMATION_SECONDS else 0, error = null)
        if (mismatch) startCountdown()
    }

    fun dismissFlashConfirmation() { if (!state.flashing) state = state.copy(confirming = false, mismatchCountdown = 0) }

    fun confirmFlash() {
        val snapshot = state
        val packageToFlash = snapshot.flashPackage ?: return
        val candidate = snapshot.selected ?: return
        val operations = packageToFlash.operations.filterIndexed { index, _ -> snapshot.enabledOperations.getOrElse(index) { true } }
        if (!snapshot.confirming || snapshot.mismatchCountdown > 0 || snapshot.flashing || operations.isEmpty()) return
        val id = ++operationId
        state = snapshot.copy(
            editingOperations = false,
            confirming = false,
            flashing = true,
            result = null,
            error = null,
            stepIndex = 0,
            stepTotal = operations.size,
            stepLabel = "",
            transferProgress = null,
            log = emptyList(),
        )
        viewModelScope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    client.flash(candidate, packageToFlash, operations, object : UsbFastbootClient.FlashCallbacks {
                        override fun onStep(index: Int, total: Int, label: String) = updateIfCurrent(id) { current ->
                            current.copy(stepIndex = index, stepTotal = total, stepLabel = label, transferProgress = null, log = appendLog(current.log, "[${index + 1}/$total] $label"))
                        }
                        override fun onProgress(done: Long, total: Long) = updateIfCurrent(id) { current -> current.copy(transferProgress = done to total) }
                        override fun onLog(line: String) = updateIfCurrent(id) { current -> current.copy(log = appendLog(current.log, line)) }
                    })
                    Unit
                }
            }
            if (id == operationId) state = state.copy(flashing = false, transferProgress = null, result = outcome)
        }
    }

    fun reboot(mode: FastbootRebootMode = FastbootRebootMode.NORMAL) {
        val candidate = state.selected ?: return
        if (state.busy || state.confirming || state.editingOperations) return
        state = state.copy(rebooting = true, error = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.reboot(candidate, mode) } }
                .onSuccess { state = state.copy(rebooting = false) }
                .onFailure { error -> state = state.copy(rebooting = false, error = error.message ?: "Fastboot reboot failed") }
        }
    }

    /** Sends one non-flash Fastboot command and retains every raw reply packet for the custom command page. */
    fun executeCustomCommand(command: String) {
        val candidate = state.selected ?: return
        if (state.busy || state.confirming || state.editingOperations) return
        val normalized = command.trim()
        when {
            normalized.isBlank() -> {
                state = state.copy(customCommandError = "Enter a Fastboot command.")
                return
            }
            normalized.startsWith("fastboot", ignoreCase = true) -> {
                state = state.copy(customCommandError = "Omit the word fastboot from the command.")
                return
            }
            normalized.startsWith("flash", ignoreCase = true) -> {
                state = state.copy(customCommandError = "Flash commands are unsupported on this page.")
                return
            }
            normalized.startsWith("download", ignoreCase = true) -> {
                state = state.copy(customCommandError = "Download commands are unsupported because they require a payload.")
                return
            }
        }
        state = state.copy(customCommandExecuting = true, customCommandResponse = emptyList(), customCommandError = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.executeCustomCommand(candidate, normalized) } }
                .onSuccess { response -> state = state.copy(customCommandExecuting = false, customCommandResponse = response) }
                .onFailure { error -> state = state.copy(customCommandExecuting = false, customCommandError = error.message ?: "Fastboot command failed") }
        }
    }

    fun clearCustomCommandOutput() {
        if (!state.customCommandExecuting) state = state.copy(customCommandResponse = emptyList(), customCommandError = null)
    }

    fun readInfo() {
        val candidate = state.selected ?: return
        if (state.busy || state.confirming || state.editingOperations) return
        val requestId = ++infoRequestId
        val deviceName = candidate.device.deviceName
        state = state.copy(readingInfo = true, infoView = true, infoLines = emptyList(), infoError = null, hideSensitiveInfo = false)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.readFlashInfo(candidate) } }
                .onSuccess { lines ->
                    if (requestId == infoRequestId && state.selected?.device?.deviceName == deviceName) state = state.copy(readingInfo = false, infoLines = lines)
                }
                .onFailure { error ->
                    if (requestId == infoRequestId && state.selected?.device?.deviceName == deviceName) state = state.copy(readingInfo = false, infoError = error.message ?: "Unable to read device information.")
                }
        }
    }

    fun setHideSensitiveInfo(hidden: Boolean) { state = state.copy(hideSensitiveInfo = hidden) }

    fun closeInfo() {
        if (state.readingInfo) return
        infoRequestId += 1
        state = state.copy(infoView = false, infoLines = emptyList(), infoError = null, hideSensitiveInfo = false)
    }

    fun clearResult() {
        if (!state.flashing && !state.rebooting) state = state.copy(result = null, error = null, log = emptyList())
    }

    private fun requestPermission(candidate: FastbootCandidate) {
        val id = ++operationId
        pendingCandidate = candidate
        state = state.copy(requestingPermission = true)
        FastbootUsbPermissionBroker.request(appContext, candidate.device) { granted ->
            if (id != operationId || pendingCandidate?.device?.deviceName != candidate.device.deviceName) return@request
            pendingCandidate = null
            state = state.copy(requestingPermission = false)
            if (granted) readDevice(candidate) else state = state.copy(error = "USB permission was denied.")
        }
        viewModelScope.launch {
            delay(60_000)
            if (id == operationId && pendingCandidate?.device?.deviceName == candidate.device.deviceName) {
                FastbootUsbPermissionBroker.cancel(candidate.device)
                pendingCandidate = null
                state = state.copy(requestingPermission = false, error = "USB permission request timed out.")
            }
        }
    }

    private fun readDevice(candidate: FastbootCandidate) {
        val id = ++operationId
        state = state.copy(readingDevice = true, selected = candidate, deviceInfo = null)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { client.readFlashDeviceInfo(candidate) } }
                .onSuccess { info -> if (id == operationId) state = state.copy(readingDevice = false, deviceInfo = info) }
                .onFailure { error -> if (id == operationId) state = state.copy(readingDevice = false, error = error.message ?: "Unable to read the fastboot device.") }
        }
    }

    private fun startCountdown() {
        val id = operationId
        viewModelScope.launch {
            while (id == operationId && state.confirming && state.mismatchCountdown > 0) {
                delay(1_000)
                if (id == operationId && state.confirming) state = state.copy(mismatchCountdown = (state.mismatchCountdown - 1).coerceAtLeast(0))
            }
        }
    }

    private fun updateIfCurrent(id: Long, transform: (FirmwareFlashUiState) -> FirmwareFlashUiState) {
        viewModelScope.launch {
            if (id == operationId) state = transform(state)
        }
    }

    override fun onCleared() {
        pendingCandidate?.device?.let(FastbootUsbPermissionBroker::cancel)
        state.flashPackage?.deleteCachedArchive()
    }
}

private fun appendLog(log: List<String>, line: String): List<String> = (log + line).takeLast(MAX_LOG_LINES)
