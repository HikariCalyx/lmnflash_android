package com.hikaricalyx.lmnflash.firmware

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RetcnForm(
    val imei: String = "",
    val serialNumber: String = "",
    val model: String = "",
    val carrier: String = "",
    val fingerprint: String = "",
    val platform: Platform = Platform.QUALCOMM,
    val fsgVersion: String = "",
    val simCount: Int = 1,
)

data class ModelForm(
    val model: String = "",
    val category: DeviceCategory = DeviceCategory.PHONE,
    val categoryIsAutomatic: Boolean = true,
    val countryCode: String = "US",
    val requiredForModel: String = "",
    val requiredParameters: List<String> = emptyList(),
    val parameterValues: Map<String, String> = emptyMap(),
)

sealed interface LoginState {
    data object LoggedOut : LoginState
    data object Loading : LoginState
    data class Web(val url: String, val expectedState: String) : LoginState
    data class Manual(val url: String, val expectedState: String, val notice: String? = null) : LoginState
    data class Error(val message: String) : LoginState
    data class LoggedIn(val session: Session) : LoginState
}

sealed interface LookupStatus {
    data object Idle : LookupStatus
    data object Loading : LookupStatus
    data class Error(val message: String) : LookupStatus
    data class Done(val result: LookupResult) : LookupStatus
}

data class FirmwareUiState(
    val login: LoginState = LoginState.LoggedOut,
    val mode: LookupMode = LookupMode.ROW_SMARTPHONE,
    val rowImei: String = "",
    val retcn: RetcnForm = RetcnForm(),
    val tabletSerialNumber: String = "",
    val model: ModelForm = ModelForm(),
    val lookupStatus: LookupStatus = LookupStatus.Idle,
)

class FirmwareLookupViewModel(context: Context) : ViewModel() {
    private val credentials = CredentialStore(context.applicationContext)
    private val repository = FirmwareRepository()
    private var pendingLookup: (() -> Unit)? = null

    var state by mutableStateOf(FirmwareUiState())
        private set

    init {
        viewModelScope.launch {
            credentials.load()?.let { session -> state = state.copy(login = LoginState.LoggedIn(session)) }
        }
    }

    fun selectMode(mode: LookupMode) { state = state.copy(mode = mode, lookupStatus = LookupStatus.Idle) }
    fun updateRowImei(value: String) { state = state.copy(rowImei = value) }
    fun updateRetcn(transform: (RetcnForm) -> RetcnForm) { state = state.copy(retcn = transform(state.retcn)) }
    fun updateTabletSerialNumber(value: String) { state = state.copy(tabletSerialNumber = value) }
    fun updateModel(transform: (ModelForm) -> ModelForm) { state = state.copy(model = transform(state.model)) }

    fun updateModelName(value: String) {
        val form = state.model
        val inferred = if (form.categoryIsAutomatic) detectCategory(value) else null
        state = state.copy(model = form.copy(
            model = value,
            category = inferred?.first ?: form.category,
            countryCode = inferred?.second ?: form.countryCode,
        ))
    }

    fun selectModelCategory(category: DeviceCategory) {
        state = state.copy(model = state.model.copy(category = category, categoryIsAutomatic = false))
    }

    fun startLogin(manual: Boolean = false) {
        val clientUuid = credentials.clientUuid()
        state = state.copy(login = LoginState.Loading)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { repository.fetchLoginUrl(clientUuid) } }
                .onSuccess { loginUrl -> state = state.copy(login = if (manual) {
                    LoginState.Manual(loginUrl.url, loginUrl.state)
                } else {
                    LoginState.Web(loginUrl.url, loginUrl.state)
                }) }
                .onFailure { error -> state = state.copy(login = LoginState.Error(error.userMessage())) }
        }
    }

    fun showManualLogin(notice: String? = null) {
        val login = state.login as? LoginState.Web ?: return
        state = state.copy(login = LoginState.Manual(login.url, login.expectedState, notice))
    }

    fun submitLoginCallback(callback: String, expectedState: String) {
        val clientUuid = credentials.clientUuid()
        runCatching { repository.parseLoginCallback(callback, expectedState) }
            .onFailure { state = state.copy(login = LoginState.Error(it.userMessage())) }
            .onSuccess { parsed ->
                val session = parsed.copy(clientUuid = clientUuid)
                viewModelScope.launch {
                    withContext(Dispatchers.IO) { credentials.save(session) }
                    state = state.copy(login = LoginState.LoggedIn(session))
                    pendingLookup?.also { pendingLookup = null; it() }
                }
            }
    }

    fun cancelLogin() { state = state.copy(login = LoginState.LoggedOut); pendingLookup = null }
    fun logout() { credentials.clearToken(); state = state.copy(login = LoginState.LoggedOut, lookupStatus = LookupStatus.Idle) }

    fun lookup() {
        when (state.mode) {
            LookupMode.ROW_SMARTPHONE -> lookupRow()
            LookupMode.RETCN_SMARTPHONE -> lookupRetcn()
            LookupMode.TABLET -> lookupTablet()
            LookupMode.BY_MODEL -> lookupModel()
        }
    }

    private fun sessionOrError(action: () -> Unit): Session? {
        val session = (state.login as? LoginState.LoggedIn)?.session
        if (session == null) {
            state = state.copy(lookupStatus = LookupStatus.Error("Log in to look up firmware"))
            return null
        }
        pendingLookup = action
        return session
    }

    private fun lookupRow() {
        val imei = validateImei(state.rowImei).getOrElse { return showValidation(it) }
        val session = sessionOrError(::lookupRow) ?: return
        runLookup(::lookupRow) { repository.lookupByImei(imei, session).let(LookupResult::Standard) }
    }

    private fun lookupRetcn() {
        val form = state.retcn
        val imei = validateDigits(form.imei).getOrElse { return showValidation(it) }
        val validationError = when {
            form.model.isBlank() -> "XT model code is required"
            form.fingerprint.isBlank() -> "Build fingerprint is required"
            form.carrier.isBlank() -> "Carrier is required"
            form.serialNumber.isBlank() -> "Serial number is required"
            form.platform == Platform.QUALCOMM && form.fsgVersion.isBlank() -> "FSG version is required for Qualcomm"
            else -> null
        }
        if (validationError != null) return showValidation(FirmwareException(validationError))
        val session = sessionOrError(::lookupRetcn) ?: return
        val request = RetcnRequest(imei, form.serialNumber.trim(), form.fingerprint.trim(), form.model.trim(), form.carrier.trim(), form.platform, form.fsgVersion.trim(), form.simCount)
        runLookup(::lookupRetcn) { repository.lookupRetcn(request, session).let(LookupResult::Standard) }
    }

    private fun lookupTablet() {
        val serial = state.tabletSerialNumber.trim()
        if (serial.isEmpty()) return showValidation(FirmwareException("Serial number is required"))
        val session = sessionOrError(::lookupTablet) ?: return
        runLookup(::lookupTablet) { repository.lookupTablet(serial, session) }
    }

    private fun lookupModel() {
        val form = state.model
        val model = form.model.trim()
        if (model.isEmpty()) return showValidation(FirmwareException("Model name is required"))
        val session = sessionOrError(::lookupModel) ?: return
        if (form.requiredForModel != model) {
            state = state.copy(lookupStatus = LookupStatus.Loading)
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.lookupModelParams(model, session) } }
                    .onSuccess { required -> state = state.copy(model = state.model.copy(
                        requiredForModel = model, requiredParameters = required, parameterValues = required.associateWith { "" },
                    ), lookupStatus = LookupStatus.Idle) }
                    .onFailure(::handleLookupFailure)
            }
        } else {
            runLookup(::lookupModel) { repository.lookupByModel(model, form.category, form.countryCode, form.parameterValues, session).let(LookupResult::Standard) }
        }
    }

    private fun runLookup(retry: () -> Unit, request: suspend () -> LookupResult) {
        state = state.copy(lookupStatus = LookupStatus.Loading)
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { request() } }
                .onSuccess { result -> state = state.copy(lookupStatus = LookupStatus.Done(result)); pendingLookup = null }
                .onFailure(::handleLookupFailure)
        }
    }

    private fun handleLookupFailure(error: Throwable) {
        val firmwareError = error as? FirmwareException
        if (firmwareError?.authExpired == true) {
            credentials.clearToken()
            state = state.copy(login = LoginState.LoggedOut, lookupStatus = LookupStatus.Error("Your Lenovo session expired. Log in again to retry."))
        } else state = state.copy(lookupStatus = LookupStatus.Error(error.userMessage()))
    }

    private fun showValidation(error: Throwable) { state = state.copy(lookupStatus = LookupStatus.Error(error.userMessage())) }
}

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "An unexpected error occurred"
private fun detectCategory(model: String): Pair<DeviceCategory, String?>? = when {
    model.trim().startsWith("XT") -> DeviceCategory.PHONE to null
    model.trim().startsWith("TB") -> DeviceCategory.TABLET to null
    model.trim().startsWith("PC-") -> DeviceCategory.TABLET to "JP"
    model.trim().startsWith("CD") || model.trim().startsWith("SD") -> DeviceCategory.SMART to null
    else -> null
}
