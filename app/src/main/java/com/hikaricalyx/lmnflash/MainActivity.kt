package com.hikaricalyx.lmnflash

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.net.toUri
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hikaricalyx.lmnflash.fastboot.BootloaderStatus
import com.hikaricalyx.lmnflash.fastboot.BootloaderUnlockViewModel
import com.hikaricalyx.lmnflash.fastboot.DeviceReadSource
import com.hikaricalyx.lmnflash.fastboot.FastbootPlatform
import com.hikaricalyx.lmnflash.fastboot.FastbootRebootMode
import com.hikaricalyx.lmnflash.fastboot.RetcnDeviceReadStatus
import com.hikaricalyx.lmnflash.fastboot.RetcnDeviceReadViewModel
import com.hikaricalyx.lmnflash.fastboot.UnlockEligibility
import com.hikaricalyx.lmnflash.fastboot.maskFastbootInfo
import com.hikaricalyx.lmnflash.firmware.CnTabletInfo
import com.hikaricalyx.lmnflash.firmware.DeviceCategory
import com.hikaricalyx.lmnflash.firmware.FirmwareInfo
import com.hikaricalyx.lmnflash.firmware.FirmwareLookupViewModel
import com.hikaricalyx.lmnflash.firmware.FirmwareUiState
import com.hikaricalyx.lmnflash.firmware.LoginState
import com.hikaricalyx.lmnflash.firmware.LookupHistoryRecord
import com.hikaricalyx.lmnflash.firmware.LookupMode
import com.hikaricalyx.lmnflash.firmware.LookupResult
import com.hikaricalyx.lmnflash.firmware.LookupStatus
import com.hikaricalyx.lmnflash.firmware.Platform
import com.hikaricalyx.lmnflash.firmwareflash.FirmwareFlashViewModel
import com.hikaricalyx.lmnflash.firmwareflash.FirmwareFlashUiState
import com.hikaricalyx.lmnflash.firmwareflash.FlashPart
import com.hikaricalyx.lmnflash.firmwareflash.FlashPackage
import com.hikaricalyx.lmnflash.l10n.AppLanguage
import com.hikaricalyx.lmnflash.l10n.Translator
import com.hikaricalyx.lmnflash.l10n.rememberTranslator
import com.hikaricalyx.lmnflash.ui.theme.LMNFlashTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private data class SoftwareFixCallback(val id: Long, val uri: String)

private const val LANGUAGE_PREFERENCES = "app-language"
private const val LANGUAGE_TAG_KEY = "selected-tag"

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val languageTag = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            newBase.getSharedPreferences(LANGUAGE_PREFERENCES, MODE_PRIVATE)
                .getString(LANGUAGE_TAG_KEY, null)
        } else {
            null
        }
        val localizedBase = languageTag?.let { tag ->
            val configuration = Configuration(newBase.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
            newBase.createConfigurationContext(configuration)
        } ?: newBase
        super.attachBaseContext(localizedBase)
    }
    private var callbackSequence = 0L
    private var callbackEvent by mutableStateOf<SoftwareFixCallback?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveSoftwareFixCallback(intent)
        enableEdgeToEdge()
        setContent {
            LMNFlashTheme {
                Surface(Modifier.fillMaxSize()) {
                    FirmwareLookupApp(callbackEvent) { callbackId ->
                        if (callbackEvent?.id == callbackId) callbackEvent = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receiveSoftwareFixCallback(intent)
    }

    private fun receiveSoftwareFixCallback(intent: Intent) {
        val uri = intent.data ?: run {
            setIntent(intent)
            return
        }
        if (intent.action != Intent.ACTION_VIEW || !uri.scheme.equals("softwarefix", true)) {
            setIntent(intent)
            return
        }
        intent.data = null
        setIntent(intent)
        if (uri.host.equals("callback", true)) {
            callbackEvent = SoftwareFixCallback(++callbackSequence, uri.toString())
        }
    }
}

private fun setLegacyAppLanguage(context: Context, languageTag: String?) {
    context.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE).edit().apply {
        if (languageTag == null) remove(LANGUAGE_TAG_KEY) else putString(LANGUAGE_TAG_KEY, languageTag)
    }.apply()
    (context as? Activity)?.recreate()
}

private enum class AppMode { FIRMWARE_LOOKUP, SMARTPHONE_FLASH }

@Composable
private fun FirmwareLookupApp(
    externalCallback: SoftwareFixCallback?,
    onExternalCallbackConsumed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val t = rememberTranslator()
    val factory = remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FirmwareLookupViewModel(context.applicationContext) as T
        }
    }
    val viewModel: FirmwareLookupViewModel = viewModel(factory = factory)
    val state = viewModel.state
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var appMode by rememberSaveable { mutableStateOf(AppMode.FIRMWARE_LOOKUP) }
    var showSmartphoneFlashDisclaimer by remember { mutableStateOf(false) }
    var smartphoneFlashDisclaimerShown by remember { mutableStateOf(false) }
    fun requestAppMode(mode: AppMode) {
        if (mode == appMode) return
        if (mode == AppMode.SMARTPHONE_FLASH) {
            if (smartphoneFlashDisclaimerShown) {
                appMode = mode
            } else {
                smartphoneFlashDisclaimerShown = true
                showSmartphoneFlashDisclaimer = true
            }
        } else appMode = mode
    }
    LaunchedEffect(externalCallback?.id) {
        externalCallback?.let { callback ->
            viewModel.submitExternalLoginCallback(callback.uri)
            onExternalCallbackConsumed(callback.id)
        }
    }
    AnimatedContent(
        targetState = appMode,
        transitionSpec = {
            (fadeIn(animationSpec = tween(220, delayMillis = 60)) +
                slideInVertically(animationSpec = tween(280)) { height -> height / 12 }) togetherWith
                fadeOut(animationSpec = tween(90))
        },
        label = "app mode",
    ) { selectedMode ->
        when (selectedMode) {
        AppMode.SMARTPHONE_FLASH -> SmartphoneFlashScreen(t, context, selectedMode) { requestAppMode(it) }
        AppMode.FIRMWARE_LOOKUP -> AnimatedContent(
            targetState = state.login,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(animationSpec = tween(220, delayMillis = 60)) +
                    slideInVertically(animationSpec = tween(280)) { height -> height / 12 }) togetherWith
                    fadeOut(animationSpec = tween(90))
            },
            label = "login state",
        ) { login ->
            when (login) {
                LoginState.LoggedOut -> LoginStart(t, context, viewModel::startLogin, { viewModel.startLogin(manual = true) }, (state.lookupStatus as? LookupStatus.Error)?.message, appMode) { requestAppMode(it) }
                LoginState.Loading -> CenteredProgress(t.text("login-fetching")) { AppModeMenu(t, appMode) { requestAppMode(it) } }
                is LoginState.Browser -> BrowserLogin(t, login, viewModel::showManualLogin, appMode) { requestAppMode(it) }
                is LoginState.Manual -> ManualLogin(t, login.url, login.notice, { copyToClipboard(context, login.url) }, { openBrowser(context, login.url) }, viewModel::submitLoginCallback, viewModel::cancelLogin, appMode) { requestAppMode(it) }
                is LoginState.Error -> LoginStart(t, context, { viewModel.startLogin() }, { viewModel.startLogin(true) }, t.text("login-error", "error" to t.error(login.message)), appMode) { requestAppMode(it) }
                is LoginState.LoggedIn -> AnimatedContent(
                    targetState = showHistory,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180, delayMillis = 40)) +
                            slideInVertically(animationSpec = tween(240)) { height -> height / 16 }) togetherWith
                            fadeOut(animationSpec = tween(90))
                    },
                    label = "lookup history",
                ) { historyVisible ->
                    if (historyVisible) {
                        HistoryScreen(t, state.history, viewModel::removeHistory, { record ->
                            viewModel.restoreHistory(record)
                            showHistory = false
                        }, appMode, { requestAppMode(it) }) { showHistory = false }
                    } else {
                        LookupScreen(t, context, state, viewModel::selectMode, viewModel::updateRowImei, viewModel::updateRetcn, viewModel::updateTabletSerialNumber, viewModel::updateModelName, viewModel::updateModel, viewModel::selectModelCategory, viewModel::lookup, { showHistory = true }, viewModel::logout, appMode, { requestAppMode(it) }) { copyToClipboard(context, it) }
                    }
                }
            }
        }
    }
    }
    if (showSmartphoneFlashDisclaimer) {
        AlertDialog(
            onDismissRequest = { showSmartphoneFlashDisclaimer = false },
            title = { Text(t.text("smartphone-flash-disclaimer-title")) },
            text = { Text(t.text("smartphone-flash-disclaimer-message")) },
            confirmButton = {
                TextButton(onClick = {
                    context.cacheDir.deleteRecursively()
                    context.cacheDir.mkdirs()
                    showSmartphoneFlashDisclaimer = false
                    appMode = AppMode.SMARTPHONE_FLASH
                }) {
                    Text(t.text("smartphone-flash-disclaimer-continue"))
                }
            },
            dismissButton = {
                TextButton(onClick = { showSmartphoneFlashDisclaimer = false }) { Text(t.text("login-cancel")) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginStart(
    t: Translator,
    context: Context,
    onLogin: () -> Unit,
    onManual: () -> Unit,
    message: String?,
    appMode: AppMode,
    onAppModeSelected: (AppMode) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.text("mode-1")) },
                navigationIcon = { AppModeMenu(t, appMode, onAppModeSelected) },
                actions = { LanguageMenu(context) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(t.text("login-prompt"), style = MaterialTheme.typography.bodyLarge)
            if (message != null) { Spacer(Modifier.height(12.dp)); ErrorText(t.error(message)) }
            Spacer(Modifier.height(24.dp)); Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-button")) }
            Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-manual")) }
        }
    }
}

@Composable
private fun LanguageMenu(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return

    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_language),
            contentDescription = context.getString(R.string.language_menu_description),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text(context.getString(R.string.language_system_default)) },
            onClick = {
                setLegacyAppLanguage(context, null)
                expanded = false
            },
        )
        AppLanguage.entries.forEach { language ->
            DropdownMenuItem(
                text = { Text(language.nativeName) },
                onClick = {
                    setLegacyAppLanguage(context, language.tag)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun CenteredProgress(label: String, navigation: @Composable (() -> Unit)? = null) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (navigation != null) Row(Modifier.fillMaxWidth()) { navigation() }
        Column(
            modifier = Modifier.fillMaxSize().weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(label)
        }
    }
}

@Composable
private fun BrowserLogin(
    t: Translator,
    login: LoginState.Browser,
    onBrowserOpened: () -> Unit,
    appMode: AppMode,
    onAppModeSelected: (AppMode) -> Unit,
) {
    val context = LocalContext.current
    LaunchedEffect(login.url, login.expectedState) {
        openBrowser(context, login.url)
        onBrowserOpened()
    }
    CenteredProgress(t.text("login-fetching")) { AppModeMenu(t, appMode, onAppModeSelected) }
}

@Composable
private fun ManualLogin(
    t: Translator,
    url: String,
    notice: String?,
    onCopy: () -> Unit,
    onBrowser: () -> Unit,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
    appMode: AppMode,
    onAppModeSelected: (AppMode) -> Unit,
) {
    var callback by remember(url) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { AppModeMenu(t, appMode, onAppModeSelected) }
        item { Text(t.text("login-manual-prompt"), style = MaterialTheme.typography.titleLarge) }
        if (notice != null) item { Text(t.error(notice), style = MaterialTheme.typography.bodyMedium) }
        item { Text(t.text("login-url-label"), fontWeight = FontWeight.SemiBold) }; item { Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = onCopy) { Text(t.text("login-copy-url")) }; OutlinedButton(onClick = onBrowser) { Text(t.text("login-open-browser")) } } }
        item { Field(t.text("login-manual-placeholder"), callback, onChange = { callback = it }) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onSubmit(callback) }) { Text(t.text("login-submit")) }; OutlinedButton(onClick = onCancel) { Text(t.text("login-cancel")) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookupScreen(t: Translator, context: Context, state: FirmwareUiState, onMode: (LookupMode) -> Unit, onImei: (String) -> Unit, onRetcn: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit, onTablet: (String) -> Unit, onModelName: (String) -> Unit, onModel: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit, onCategory: (DeviceCategory) -> Unit, onLookup: () -> Unit, onShowHistory: () -> Unit, onLogout: () -> Unit, appMode: AppMode, onAppModeSelected: (AppMode) -> Unit, onCopy: (String) -> Unit) {
    val loading = state.lookupStatus is LookupStatus.Loading
    val lookupEnabled = !loading && when (state.mode) {
        LookupMode.ROW_SMARTPHONE -> isValidImei(state.rowImei)
        LookupMode.RETCN_SMARTPHONE -> isValidImei(state.retcn.imei)
        LookupMode.TABLET, LookupMode.BY_MODEL -> true
    }
    Scaffold(topBar = { TopAppBar(title = { Text(t.text("mode-1")) }, navigationIcon = { AppModeMenu(t, appMode, onAppModeSelected) }, actions = { HistoryButton(t, onShowHistory); LanguageMenu(context); TextButton(onClick = onLogout) { Text(t.text("logout")) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ModeMenu(t, state.mode, !loading, onMode) }
            item(key = "lookup-form") {
                AnimatedContent(
                    targetState = state.mode,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180, delayMillis = 40)) +
                            slideInVertically(animationSpec = tween(220)) { height -> height / 16 }) togetherWith
                            fadeOut(animationSpec = tween(90))
                    },
                    label = "lookup form",
                ) { mode ->
                    LookupForm(t, state, mode, onImei, onRetcn, onTablet, onModelName, onModel, onCategory)
                }
            }
            item {
                Button(onClick = onLookup, enabled = lookupEnabled, modifier = Modifier.fillMaxWidth()) {
                    AnimatedVisibility(
                        visible = loading,
                        enter = fadeIn(animationSpec = tween(120)),
                        exit = fadeOut(animationSpec = tween(90)) + shrinkHorizontally(animationSpec = tween(90)),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                    AnimatedContent(targetState = loading, label = "lookup button label") { isLoading ->
                        Text(if (isLoading) t.text("lookup-fetching") else t.text("lookup-button"))
                    }
                }
            }
            item(key = "lookup-status") {
                AnimatedContent(
                    targetState = state.lookupStatus,
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180, delayMillis = 40)) + expandVertically(animationSpec = tween(220))) togetherWith
                            (fadeOut(animationSpec = tween(90)) + shrinkVertically(animationSpec = tween(120)))
                    },
                    label = "lookup result",
                ) { status -> LookupStatusView(t, status, onCopy) }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun AppModeMenu(t: Translator, selectedMode: AppMode, onModeSelected: (AppMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(
            painter = painterResource(R.drawable.ic_menu),
            contentDescription = t.text("navigation-menu"),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        AppMode.entries.forEach { mode ->
            DropdownMenuItem(
                text = { Text(t.text(mode.translationKey)) },
                onClick = {
                    onModeSelected(mode)
                    expanded = false
                },
                enabled = mode != selectedMode,
            )
        }
    }
}

private val AppMode.translationKey: String
    get() = when (this) {
        AppMode.FIRMWARE_LOOKUP -> "mode-1"
        AppMode.SMARTPHONE_FLASH -> "mode-2"
    }

private enum class SmartphoneFlashPage { HOME, FIRMWARE, FLASHING, CUSTOM_COMMAND, BOOTLOADER }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartphoneFlashScreen(
    t: Translator,
    context: Context,
    selectedMode: AppMode,
    onModeSelected: (AppMode) -> Unit,
) {
    val bootloaderFactory = remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = BootloaderUnlockViewModel(context.applicationContext) as T
        }
    }
    val bootloaderViewModel: BootloaderUnlockViewModel = viewModel(factory = bootloaderFactory)
    val bootloaderState = bootloaderViewModel.state
    val flashFactory = remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = FirmwareFlashViewModel(context.applicationContext) as T
        }
    }
    val flashViewModel: FirmwareFlashViewModel = viewModel(factory = flashFactory)
    val flashState = flashViewModel.state
    KeepScreenAwake(flashState.flashing)
    var page by rememberSaveable { mutableStateOf(SmartphoneFlashPage.HOME) }
    var burnInPreventionMode by rememberSaveable { mutableStateOf(false) }
    // An active run has its own chrome-free screen while the user remains in Firmware Flash.
    val displayedPage = if (page == SmartphoneFlashPage.FIRMWARE && (flashState.flashing || flashState.result != null)) SmartphoneFlashPage.FLASHING else page
    var showBootloaderChooser by rememberSaveable { mutableStateOf(false) }
    var pendingMode by remember { mutableStateOf<AppMode?>(null) }
    var showLeaveWarning by remember { mutableStateOf(false) }

    fun requestPageExit() {
        when {
            flashState.flashing || flashState.rebooting -> showLeaveWarning = true
            flashState.customCommandExecuting -> Unit
            displayedPage == SmartphoneFlashPage.FLASHING -> flashViewModel.clearResult()
            displayedPage == SmartphoneFlashPage.CUSTOM_COMMAND -> page = SmartphoneFlashPage.FIRMWARE
            else -> page = SmartphoneFlashPage.HOME
        }
    }
    fun requestMode(mode: AppMode) {
        if (mode == selectedMode) return
        if (flashState.flashing) {
            pendingMode = mode
            showLeaveWarning = true
        } else onModeSelected(mode)
    }
    BackHandler(enabled = displayedPage != SmartphoneFlashPage.HOME || flashState.flashing) { requestPageExit() }

    val burnInVisible = burnInPreventionMode && page == SmartphoneFlashPage.FIRMWARE && (flashState.flashing || flashState.result != null)
    if (burnInVisible) {
        BurnInPreventionScreen(
            t = t,
            state = flashState,
            onDismiss = { burnInPreventionMode = false },
        )
    } else {
    Scaffold(
        topBar = {
            if (displayedPage != SmartphoneFlashPage.FLASHING) {
                TopAppBar(
                    title = {
                        Text(
                            when (displayedPage) {
                                SmartphoneFlashPage.FIRMWARE -> t.text("firmware-flash-title")
                                SmartphoneFlashPage.CUSTOM_COMMAND -> t.text("firmware-flash-custom-title")
                                else -> t.text("mode-2")
                            },
                        )
                    },
                    navigationIcon = { AppModeMenu(t, selectedMode, ::requestMode) },
                    actions = {
                        if (displayedPage == SmartphoneFlashPage.FIRMWARE && flashState.editingOperations) {
                            TextButton(onClick = flashViewModel::closeOperationEditor) { Text(t.text("firmware-flash-edit-done")) }
                        } else {
                            LanguageMenu(context)
                        }
                    },
                )
            }
        },
    ) { padding ->
        AnimatedContent(
            targetState = displayedPage,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180, delayMillis = 30)) +
                    slideInHorizontally(animationSpec = tween(240)) { width -> width / 14 }) togetherWith
                    (fadeOut(animationSpec = tween(90)) +
                        slideOutHorizontally(animationSpec = tween(160)) { width -> -width / 18 })
            },
            label = "smartphone flash page",
        ) { currentPage ->
        when (currentPage) {
            SmartphoneFlashPage.HOME -> Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(top = 24.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(t.text("firmware-flash-title"), style = MaterialTheme.typography.titleLarge)
                        Text(t.text("firmware-flash-description"), style = MaterialTheme.typography.bodyMedium)
                        Button(onClick = { page = SmartphoneFlashPage.FIRMWARE }, modifier = Modifier.fillMaxWidth()) { Text(t.text("firmware-flash-open")) }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(t.text("flash-bootloader-title"), style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { showBootloaderChooser = true }, modifier = Modifier.fillMaxWidth()) { Text(t.text("flash-bootloader-button")) }
                    }
                }
            }
            SmartphoneFlashPage.FIRMWARE -> FirmwareFlashScreen(
                t, context, flashState, flashViewModel, padding,
                onOpenCustomCommand = { page = SmartphoneFlashPage.CUSTOM_COMMAND },
            )
            SmartphoneFlashPage.FLASHING -> FirmwareFlashingScreen(
                t, context, flashState, flashViewModel,
                onEnterBurnIn = { burnInPreventionMode = true },
            )
            SmartphoneFlashPage.CUSTOM_COMMAND -> CustomFastbootCommandScreen(
                t, flashState, flashViewModel, padding,
                onReturn = { flashViewModel.clearCustomCommandOutput(); page = SmartphoneFlashPage.FIRMWARE },
            )
            SmartphoneFlashPage.BOOTLOADER -> Column(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .padding(top = 24.dp),
            ) {
                ManualBootloaderUnlockScreen(t, context, bootloaderState, bootloaderViewModel, onReturn = { page = SmartphoneFlashPage.HOME })
            }
        }
        }
    }
    }

    if (showLeaveWarning) {
        AlertDialog(
            onDismissRequest = { showLeaveWarning = false; pendingMode = null },
            title = { Text(t.text("firmware-flash-leave-title")) },
            text = { Text(t.text("firmware-flash-leave-warning")) },
            confirmButton = {
                TextButton(onClick = {
                    val destination = pendingMode
                    burnInPreventionMode = false
                    showLeaveWarning = false
                    pendingMode = null
                    if (destination != null) onModeSelected(destination) else page = SmartphoneFlashPage.HOME
                }) { Text(t.text("firmware-flash-leave")) }
            },
            dismissButton = { TextButton(onClick = { showLeaveWarning = false; pendingMode = null }) { Text(t.text("firmware-flash-stay")) } },
        )
    }
    if (showBootloaderChooser) {
        AlertDialog(
            onDismissRequest = { showBootloaderChooser = false },
            title = { Text(t.text("flash-bootloader-title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.text("flash-bootloader-choose"))
                    Button(onClick = { showBootloaderChooser = false; page = SmartphoneFlashPage.BOOTLOADER }, modifier = Modifier.fillMaxWidth()) { Text(t.text("flash-bootloader-smartphone")) }
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(t.text("flash-bootloader-tablet")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showBootloaderChooser = false }) { Text(t.text("login-cancel")) } },
        )
    }
    if (bootloaderState.picker.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = bootloaderViewModel::cancelPicker,
            title = { Text(t.text("retcn-pick-device-title")) },
            text = { Text(t.text("retcn-fill-fastboot-fetching")) },
            confirmButton = { Column { bootloaderState.picker.forEach { candidate -> TextButton(onClick = { bootloaderViewModel.selectDevice(candidate) }, modifier = Modifier.fillMaxWidth()) { Text(candidate.label) } } } },
            dismissButton = { TextButton(onClick = bootloaderViewModel::cancelPicker) { Text(t.text("login-cancel")) } },
        )
    }
}

private enum class FirmwareFlashSubPage { SETUP, EDIT, INFO }

@Composable
private fun FirmwareFlashScreen(
    t: Translator,
    context: Context,
    state: FirmwareFlashUiState,
    viewModel: FirmwareFlashViewModel,
    scaffoldPadding: androidx.compose.foundation.layout.PaddingValues,
    onOpenCustomCommand: () -> Unit,
) {
    val target = when {
        state.editingOperations -> FirmwareFlashSubPage.EDIT
        state.infoView -> FirmwareFlashSubPage.INFO
        else -> FirmwareFlashSubPage.SETUP
    }
    AnimatedContent(
        targetState = target,
        transitionSpec = {
            (fadeIn(animationSpec = tween(180, delayMillis = 30)) +
                slideInHorizontally(animationSpec = tween(240)) { width -> width / 14 }) togetherWith
                (fadeOut(animationSpec = tween(90)) +
                    slideOutHorizontally(animationSpec = tween(160)) { width -> -width / 18 })
        },
        label = "firmware flash subpage",
    ) { subPage ->
        when (subPage) {
            FirmwareFlashSubPage.SETUP -> FirmwareFlashSetupContent(t, context, state, viewModel, scaffoldPadding, onOpenCustomCommand)
            FirmwareFlashSubPage.EDIT -> FirmwareFlashOperationEditor(t, state, viewModel, scaffoldPadding)
            FirmwareFlashSubPage.INFO -> FirmwareFlashInfoScreen(t, context, state, viewModel, scaffoldPadding)
        }
    }
}

@Composable
private fun FirmwareFlashSetupContent(
    t: Translator,
    context: Context,
    state: FirmwareFlashUiState,
    viewModel: FirmwareFlashViewModel,
    scaffoldPadding: androidx.compose.foundation.layout.PaddingValues,
    onOpenCustomCommand: () -> Unit,
) {
    val saveLog = rememberLogSaver(context)
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::selectZip) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = scaffoldPadding.calculateTopPadding() + 24.dp + 16.dp,
            end = 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(t.text("firmware-flash-description"), style = MaterialTheme.typography.bodyMedium) }
        item {
            OutlinedButton(onClick = { zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed")) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(t.text("firmware-flash-select-zip"))
            }
        }
        if (state.loading) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(t.text("firmware-flash-loading"))
                state.packageProgress?.let { (done, total) -> if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) else CircularProgressIndicator() }
            }
        }
        state.flashPackage?.let { flashPackage -> item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.text("firmware-flash-package"), style = MaterialTheme.typography.titleMedium)
                    Text(state.packageName.orEmpty())
                    flashPackage.model?.let { Text(t.text("firmware-flash-model", "value" to it)) }
                    flashPackage.softwareVersion?.let { Text(t.text("firmware-flash-version", "value" to it)) }
                    flashPackage.cid?.let { Text(t.text("firmware-flash-package-cid", "value" to it)) }
                    flashPackage.projectCode?.let { Text(t.text("firmware-flash-project", "value" to it)) }
                    Text(t.text("firmware-flash-steps-selected", "selected" to state.enabledOperationCount, "total" to flashPackage.operations.size))
                    OutlinedButton(onClick = viewModel::openOperationEditor, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(t.text("firmware-flash-edit-steps")) }
                    if (flashPackage.ignoredPartitions.isNotEmpty()) Text(t.text("firmware-flash-ignored", "partitions" to flashPackage.ignoredPartitions.joinToString(", ")), color = MaterialTheme.colorScheme.error)
                }
            }
        } }
        item {
            OutlinedButton(onClick = viewModel::refreshDevices, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(t.text("firmware-flash-scan-device")) }
        }
        if (state.selected != null) item {
            OutlinedButton(onClick = viewModel::readInfo, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(t.text("firmware-flash-read-info")) }
        }
        if (state.selected != null && state.deviceInfo != null) item {
            OutlinedButton(onClick = onOpenCustomCommand, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(t.text("firmware-flash-custom-open"))
            }
        }
        if (state.selected != null && state.deviceInfo != null) item {
            RebootModeMenu(t, state.rebooting, viewModel::reboot)
        }
        if (state.requestingPermission) item { Text(t.text("firmware-flash-usb-permission")) }
        if (state.readingDevice) item { Text(t.text("firmware-flash-reading-device")) }
        state.selected?.let { candidate -> item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(t.text("firmware-flash-device"), style = MaterialTheme.typography.titleMedium)
                    Text(candidate.label)
                    state.deviceInfo?.serialNumber?.let { Text(t.text("firmware-flash-serial", "value" to it)) }
                    state.deviceInfo?.xtModel?.let { Text(t.text("firmware-flash-xt-model", "value" to it)) }
                    state.deviceInfo?.product?.let { Text(t.text("firmware-flash-product", "value" to it)) }
                    state.deviceInfo?.cid?.let { Text(t.text("firmware-flash-device-cid", "value" to it)) }
                    state.deviceInfo?.secureState?.let { Text(t.text("firmware-flash-secure-state", "value" to it)) }
                    if (FlashPackage.cidMismatch(state.flashPackage?.cid, state.deviceInfo?.cid)) Text(t.text("firmware-flash-cid-warning"), color = MaterialTheme.colorScheme.error)
                }
            }
        } }
        if (state.ready) item {
            Button(onClick = viewModel::requestFlashConfirmation, modifier = Modifier.fillMaxWidth()) { Text(t.text("firmware-flash-start")) }
        }
        if (state.flashing) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.text("firmware-flash-running"), style = MaterialTheme.typography.titleMedium)
                    Text(t.text("firmware-flash-current-step", "current" to (state.stepIndex + 1), "total" to state.stepTotal, "label" to state.stepLabel))
                    state.transferProgress?.let { (done, total) -> if (total > 0) LinearProgressIndicator(progress = { (done.toFloat() / total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth()) }
                    Text(t.text("firmware-flash-no-cancel"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.log.isNotEmpty()) item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { saveLog("lmnflash-flashing-log.txt", state.log.joinToString("\n")) }, modifier = Modifier.fillMaxWidth()) {
                        Text(t.text("firmware-flash-save-log"))
                    }
                    SelectionContainer { Text(state.log.joinToString("\n"), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        state.error?.let { message -> item { ErrorText(t.error(message)) } }
        state.result?.let { result -> item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (result.isSuccess) t.text("firmware-flash-success") else t.text("firmware-flash-failed"), style = MaterialTheme.typography.titleMedium)
                    result.exceptionOrNull()?.message?.let { ErrorText(t.error(it)) }
                    if (result.isSuccess) {
                        RebootModeMenu(t, state.rebooting, viewModel::reboot)
                    }
                    OutlinedButton(onClick = viewModel::clearResult, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-back")) }
                }
            }
        } }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (state.picker.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = viewModel::dismissPicker,
            title = { Text(t.text("retcn-pick-device-title")) },
            text = { Column { state.picker.forEach { candidate -> TextButton(onClick = { viewModel.selectDevice(candidate) }, modifier = Modifier.fillMaxWidth()) { Text(candidate.label) } } } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::dismissPicker) { Text(t.text("login-cancel")) } },
        )
    }
    if (state.confirming) {
        val mismatch = FlashPackage.projectMismatch(state.flashPackage?.projectCode, state.deviceInfo?.product)
        AlertDialog(
            onDismissRequest = viewModel::dismissFlashConfirmation,
            title = { Text(t.text(if (mismatch) "firmware-flash-mismatch-title" else "firmware-flash-confirm-title")) },
            text = { Text(t.text(if (mismatch) "firmware-flash-mismatch-warning" else "firmware-flash-confirm-warning")) },
            confirmButton = { TextButton(onClick = viewModel::confirmFlash, enabled = state.mismatchCountdown == 0) { Text(if (state.mismatchCountdown == 0) t.text("firmware-flash-start") else t.text("firmware-flash-wait", "seconds" to state.mismatchCountdown)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissFlashConfirmation) { Text(t.text("login-back")) } },
        )
    }
}

@Composable
private fun CustomFastbootCommandScreen(
    t: Translator,
    state: FirmwareFlashUiState,
    viewModel: FirmwareFlashViewModel,
    scaffoldPadding: androidx.compose.foundation.layout.PaddingValues,
    onReturn: () -> Unit,
) {
    var command by rememberSaveable { mutableStateOf("") }
    BackHandler(onBack = onReturn)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = scaffoldPadding.calculateTopPadding() + 24.dp + 16.dp,
            end = 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(t.text("firmware-flash-custom-title"), style = MaterialTheme.typography.titleLarge) }
        item { Text(t.text("firmware-flash-custom-instructions"), style = MaterialTheme.typography.bodyMedium) }
        item { Text(t.text("firmware-flash-custom-flash-unsupported"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        item {
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t.text("firmware-flash-custom-label")) },
                placeholder = { Text("oem get_unlock_data") },
                singleLine = true,
            )
        }
        item {
            Button(
                onClick = { viewModel.executeCustomCommand(command) },
                enabled = command.isNotBlank() && !state.customCommandExecuting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(t.text(if (state.customCommandExecuting) "firmware-flash-custom-executing" else "firmware-flash-custom-execute")) }
        }
        if (state.customCommandExecuting) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(t.text("firmware-flash-custom-executing"))
            }
        }
        state.customCommandError?.let { error -> item { ErrorText(t.error(error)) } }
        if (state.customCommandResponse.isNotEmpty()) {
            item { Text(t.text("firmware-flash-custom-response"), style = MaterialTheme.typography.titleMedium) }
            item { LogTextBox(state.customCommandResponse.joinToString("\n"), Modifier.height(280.dp)) }
        }
        item { OutlinedButton(onClick = onReturn, enabled = !state.customCommandExecuting, modifier = Modifier.fillMaxWidth()) { Text(t.text("firmware-flash-return")) } }
    }
}

@Composable
private fun BurnInPreventionScreen(
    t: Translator,
    state: FirmwareFlashUiState,
    onDismiss: () -> Unit,
) {
    ImmersiveSystemBars(enabled = true)
    var horizontalFraction by remember { mutableStateOf(0.12f) }
    var verticalFraction by remember { mutableStateOf(0.12f) }
    LaunchedEffect(state.flashing, state.result) {
        while (true) {
            horizontalFraction = kotlin.random.Random.nextFloat().coerceIn(0.04f, 0.72f)
            verticalFraction = kotlin.random.Random.nextFloat().coerceIn(0.04f, 0.88f)
            kotlinx.coroutines.delay(10_000)
        }
    }
    BackHandler(onBack = onDismiss)
    val message = if (state.flashing) {
        val transferPercent = state.transferProgress?.let { (done, total) -> if (total > 0) done * 100 / total else 0 } ?: 0
        t.text(
            "firmware-flash-burn-in-progress",
            "current" to state.stepIndex + 1,
            "total" to state.stepTotal,
            "percent" to transferPercent,
        )
    } else {
        t.text("firmware-flash-burn-in-finished")
    }
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        Text(
            text = message,
            color = Color.White.copy(alpha = 0.72f),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.offset(x = maxWidth * horizontalFraction, y = maxHeight * verticalFraction),
        )
    }
}

@Composable
private fun ImmersiveSystemBars(enabled: Boolean) {
    val view = LocalView.current
    val activity = view.context.findActivity()
    DisposableEffect(enabled, activity, view) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, view) }
        if (enabled) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            if (enabled) controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun FirmwareFlashingScreen(
    t: Translator,
    context: Context,
    state: FirmwareFlashUiState,
    viewModel: FirmwareFlashViewModel,
    onEnterBurnIn: () -> Unit,
) {
    val saveLog = rememberLogSaver(context)
    val logText = state.log.joinToString("\n")
    val total = state.stepTotal.coerceAtLeast(1)
    val subProgress = state.transferProgress?.let { (done, bytes) -> if (bytes > 0) done.toFloat() / bytes else 0f } ?: 0f
    val overallProgress = ((state.stepIndex + subProgress) / total).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.flashing) {
            Text(t.text("firmware-flash-running"), style = MaterialTheme.typography.titleLarge)
            Text(t.text("firmware-flash-total-progress", "current" to state.stepIndex + 1, "total" to state.stepTotal), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(progress = { overallProgress }, modifier = Modifier.fillMaxWidth())
            val label = state.stepLabel.takeIf(String::isNotBlank)
            Text(
                if (label == null) t.text("firmware-flash-preparing")
                else t.text("firmware-flash-current-operation", "label" to label),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(t.text("firmware-flash-current-progress"), style = MaterialTheme.typography.bodyMedium)
            if (state.transferProgress == null) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(progress = { subProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Text(t.text("firmware-flash-no-cancel"), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onEnterBurnIn, modifier = Modifier.fillMaxWidth()) {
                Text(t.text("firmware-flash-burn-in-enter"))
            }
        } else {
            val result = state.result
            Text(
                t.text(if (result?.isSuccess == true) "firmware-flash-success" else "firmware-flash-failed"),
                style = MaterialTheme.typography.titleLarge,
            )
            result?.exceptionOrNull()?.message?.let { ErrorText(t.error(it)) }
            RebootModeMenu(t, state.rebooting, viewModel::reboot)
            OutlinedButton(onClick = viewModel::clearResult, enabled = !state.rebooting, modifier = Modifier.fillMaxWidth()) {
                Text(t.text("firmware-flash-return"))
            }
        }
        if (logText.isNotBlank()) {
            OutlinedButton(onClick = { saveLog("lmnflash-flashing-log.txt", logText) }, modifier = Modifier.fillMaxWidth()) {
                Text(t.text("firmware-flash-save-log"))
            }
            LogTextBox(logText, Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun LogTextBox(text: String, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    LaunchedEffect(text) { scrollState.scrollTo(scrollState.maxValue) }
    Card(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxSize().verticalScroll(scrollState).padding(12.dp)) {
            SelectionContainer { Text(text, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RebootModeMenu(
    t: Translator,
    rebooting: Boolean,
    onReboot: (FastbootRebootMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { expanded = true }, enabled = !rebooting, modifier = Modifier.fillMaxWidth()) {
            Text(t.text(if (rebooting) "firmware-flash-rebooting" else "firmware-flash-reboot"))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FastbootRebootMode.entries.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.label(t)) },
                    onClick = { expanded = false; onReboot(mode) },
                )
            }
        }
    }
}

private fun FastbootRebootMode.label(t: Translator): String = t.text(
    when (this) {
        FastbootRebootMode.NORMAL -> "firmware-flash-reboot-normal"
        FastbootRebootMode.FASTBOOTD -> "firmware-flash-reboot-fastbootd"
        FastbootRebootMode.RECOVERY -> "firmware-flash-reboot-recovery"
        FastbootRebootMode.ADB_SIDELOAD -> "firmware-flash-reboot-sideload"
        FastbootRebootMode.SWITCH_SLOT -> "firmware-flash-reboot-switch-slot"
    },
)

@Composable
private fun FirmwareFlashOperationEditor(
    t: Translator,
    state: FirmwareFlashUiState,
    viewModel: FirmwareFlashViewModel,
    scaffoldPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val operations = state.flashPackage?.operations.orEmpty()
    BackHandler(onBack = viewModel::closeOperationEditor)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = scaffoldPadding.calculateTopPadding() + 24.dp + 16.dp,
            end = 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(t.text("firmware-flash-edit-title"), style = MaterialTheme.typography.titleLarge) }
        item { Text(t.text("firmware-flash-steps-selected", "selected" to state.enabledOperationCount, "total" to operations.size)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.setAllOperationsEnabled(true) }) { Text(t.text("firmware-flash-select-all")) }
                OutlinedButton(onClick = { viewModel.setAllOperationsEnabled(false) }) { Text(t.text("firmware-flash-select-none")) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { viewModel.selectFlashPart(FlashPart.AP) }) { Text("AP") }
                OutlinedButton(onClick = { viewModel.selectFlashPart(FlashPart.BP) }) { Text("BP") }
                OutlinedButton(onClick = { viewModel.selectFlashPart(FlashPart.BL) }) { Text("BL") }
            }
        }
        if (state.enabledOperationCount == 0) item { ErrorText(t.text("firmware-flash-no-steps")) }
        operations.forEachIndexed { index, operation ->
            item(key = "flash-operation-$index") {
                val selected = state.enabledOperations.getOrElse(index) { true }
                Card(
                    Modifier.fillMaxWidth().clickable { viewModel.setOperationEnabled(index, !selected) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = { enabled -> viewModel.setOperationEnabled(index, enabled) },
                        )
                        Text("${index + 1}. ${operation.label}", modifier = Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun FirmwareFlashInfoScreen(
    t: Translator,
    context: Context,
    state: FirmwareFlashUiState,
    viewModel: FirmwareFlashViewModel,
    scaffoldPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val displayedLines = if (state.hideSensitiveInfo) maskFastbootInfo(state.infoLines) else state.infoLines
    val displayedText = displayedLines.joinToString("\n")
    val saveLog = rememberLogSaver(context)
    BackHandler(enabled = !state.readingInfo, onBack = viewModel::closeInfo)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp,
            top = scaffoldPadding.calculateTopPadding() + 24.dp + 16.dp,
            end = 16.dp,
            bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text(t.text("firmware-flash-info-title"), style = MaterialTheme.typography.titleLarge) }
        if (state.readingInfo) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(t.text("firmware-flash-reading-info"))
            }
        }
        state.infoError?.let { error -> item { ErrorText(t.error(error)) } }
        if (state.infoLines.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { viewModel.setHideSensitiveInfo(!state.hideSensitiveInfo) }) {
                            Text(t.text(if (state.hideSensitiveInfo) "firmware-flash-show-sensitive" else "firmware-flash-hide-sensitive"))
                        }
                        OutlinedButton(onClick = { copyToClipboard(context, displayedText) }) { Text(t.text("firmware-flash-copy-info")) }
                    }
                    OutlinedButton(onClick = { saveLog("lmnflash-read-info.txt", displayedText) }, modifier = Modifier.fillMaxWidth()) {
                        Text(t.text("firmware-flash-save-log"))
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    SelectionContainer { Text(displayedText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { OutlinedButton(onClick = viewModel::closeInfo, enabled = !state.readingInfo, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-back")) } }
    }
}

@Composable
private fun ManualBootloaderUnlockScreen(
    t: Translator,
    context: Context,
    state: com.hikaricalyx.lmnflash.fastboot.BootloaderUiState,
    viewModel: BootloaderUnlockViewModel,
    onReturn: () -> Unit,
) {
    var unlockKey by rememberSaveable { mutableStateOf("") }
    var confirmUnlock by rememberSaveable { mutableStateOf(false) }
    val busy = state.status is BootloaderStatus.RequestingPermission || state.status is BootloaderStatus.Reading || state.status is BootloaderStatus.Unlocking
    val deviceIdPrefix = state.deviceId.trim().take(17)
    val keyIsDeviceId = deviceIdPrefix.isNotEmpty() && unlockKey.trim().startsWith(deviceIdPrefix)
    LaunchedEffect(state.deviceId) {
        if (state.deviceId.isNotBlank()) copyToClipboard(context, state.deviceId)
    }
    BackHandler(onBack = onReturn)

    LazyColumn(
        modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }
        item { Text(t.text("flash-bootloader-title"), style = MaterialTheme.typography.titleLarge) }
        item { Text(t.text("flash-bootloader-manual-desc"), style = MaterialTheme.typography.bodyMedium) }
        item {
            Button(onClick = { openBrowser(context, "https://en-us.support.motorola.com/app/standalone/bootloader/unlock-your-device-b") }, modifier = Modifier.fillMaxWidth()) {
                Text(t.text("flash-bootloader-open-site"))
            }
        }
        item {
            OutlinedTextField(
                value = state.deviceId,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(t.text("flash-bootloader-device-id")) },
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyToClipboard(context, state.deviceId); viewModel.markDeviceIdCopied() }, enabled = !busy && state.deviceId.isNotBlank()) { Text(t.text("flash-bootloader-copy")) }
                OutlinedButton(onClick = viewModel::readUnlockData, enabled = !busy) { Text(t.text("flash-bootloader-read")) }
            }
        }
        item { BootloaderStatusView(t, state.status) }
        item { UnlockEligibilityView(t, state.eligibility) }
        item { HorizontalDivider() }
        item { Text(t.text("flash-bootloader-obtain-key"), style = MaterialTheme.typography.bodyMedium) }
        item { Text(t.text("flash-bootloader-warranty-note"), style = MaterialTheme.typography.bodyMedium) }
        item { Text(t.text("flash-bootloader-key-desc"), style = MaterialTheme.typography.bodyMedium) }
        item { Field(t.text("flash-bootloader-unlock"), unlockKey, isError = keyIsDeviceId, onChange = { unlockKey = it }) }
        if (keyIsDeviceId) item { ErrorText(t.text("flash-bootloader-key-is-device-id")) }
        item {
            Button(
                onClick = { confirmUnlock = true },
                enabled = !busy && unlockKey.isNotBlank() && !keyIsDeviceId,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (state.status is BootloaderStatus.Unlocking) t.text("flash-bootloader-unlocking") else t.text("flash-bootloader-unlock")) }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text(t.text("flash-bootloader-unlock")) },
            text = { Text(t.text("flash-bootloader-unlock-confirmation")) },
            confirmButton = { TextButton(onClick = { confirmUnlock = false; viewModel.unlock(unlockKey) }) { Text(t.text("flash-bootloader-unlock")) } },
            dismissButton = { TextButton(onClick = { confirmUnlock = false }) { Text(t.text("login-cancel")) } },
        )
    }
}

@Composable
private fun BootloaderStatusView(t: Translator, status: BootloaderStatus) {
    when (status) {
        BootloaderStatus.Idle -> Unit
        BootloaderStatus.RequestingPermission -> Text(t.text("flash-bootloader-usb-permission"), style = MaterialTheme.typography.bodyMedium)
        BootloaderStatus.Reading -> Text(t.text("retcn-fill-fastboot-fetching"), style = MaterialTheme.typography.bodyMedium)
        BootloaderStatus.Unlocking -> Text(t.text("flash-bootloader-unlocking"), style = MaterialTheme.typography.bodyMedium)
        is BootloaderStatus.Success -> Text(t.text(status.messageKey), style = MaterialTheme.typography.bodyMedium)
        is BootloaderStatus.Error -> if (status.message == "No supported Motorola device found") {
            ErrorText(t.text("retcn-fill-fastboot-not-motorola"))
        } else ErrorText(t.error(status.message))
    }
}

@Composable
private fun UnlockEligibilityView(t: Translator, eligibility: UnlockEligibility) {
    when (eligibility) {
        UnlockEligibility.Idle -> Unit
        UnlockEligibility.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.width(18.dp).height(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(t.text("flash-bootloader-checking"), style = MaterialTheme.typography.bodyMedium)
        }
        is UnlockEligibility.Result -> Text(
            t.text(if (eligibility.qualified) "flash-bootloader-eligible" else "flash-bootloader-not-eligible"),
            color = if (eligibility.qualified) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun LookupForm(
    t: Translator,
    state: FirmwareUiState,
    mode: LookupMode,
    onImei: (String) -> Unit,
    onRetcn: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit,
    onTablet: (String) -> Unit,
    onModelName: (String) -> Unit,
    onModel: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit,
    onCategory: (DeviceCategory) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (mode) {
            LookupMode.ROW_SMARTPHONE -> Field(
                t.text("lookup-imei-label"),
                state.rowImei,
                placeholder = t.text("lookup-imei-placeholder"),
                keyboardType = KeyboardType.Number,
                isError = hasInvalidImeiChecksum(state.rowImei),
                onChange = { onImei(it.filter(Char::isDigit)) },
            )
            LookupMode.RETCN_SMARTPHONE -> RetcnForm(t, state, onRetcn)
            LookupMode.TABLET -> Field(t.text("tablet-sn-label"), state.tabletSerialNumber, onChange = onTablet)
            LookupMode.BY_MODEL -> ModelForm(t, state, onModelName, onModel, onCategory)
        }
    }
}

@Composable private fun ModeMenu(t: Translator, mode: LookupMode, enabled: Boolean, onSelect: (LookupMode) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Button({ expanded = true }, Modifier.fillMaxWidth(), enabled) { Text(mode.label(t)) }; DropdownMenu(expanded, { expanded = false }) { LookupMode.entries.forEach { item -> DropdownMenuItem({ Text(item.label(t)) }, { onSelect(item); expanded = false }) } } } }

@Composable
private fun RetcnForm(
    t: Translator,
    state: FirmwareUiState,
    onUpdate: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit,
) {
    val context = LocalContext.current
    val factory = remember(context) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = RetcnDeviceReadViewModel(context.applicationContext) as T
        }
    }
    val deviceReader: RetcnDeviceReadViewModel = viewModel(factory = factory)
    val readState = deviceReader.state
    val adbUsbPermissionRequest = readState.status as? RetcnDeviceReadStatus.RequestingPermission
    var showConnectionChooser by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(readState.fillSequence) {
        if (readState.fillSequence > 0) readState.deviceInfo?.let { info ->
            onUpdate { form ->
                form.copy(
                    imei = info.imei ?: form.imei,
                    serialNumber = info.serialNumber ?: form.serialNumber,
                    model = info.model ?: form.model,
                    carrier = info.carrier ?: form.carrier,
                    fingerprint = info.fingerprint ?: form.fingerprint,
                    platform = when (info.platform) {
                        FastbootPlatform.QUALCOMM -> Platform.QUALCOMM
                        FastbootPlatform.MEDIATEK -> Platform.MEDIATEK
                        FastbootPlatform.UNKNOWN -> form.platform
                    },
                    fsgVersion = info.fsgVersion ?: form.fsgVersion,
                    simCount = if (info.platform == FastbootPlatform.MEDIATEK) info.simCount ?: form.simCount else form.simCount,
                )
            }
        }
    }
    val reading = readState.status is RetcnDeviceReadStatus.RequestingPermission ||
        readState.status is RetcnDeviceReadStatus.ConnectingToAdb ||
        readState.status is RetcnDeviceReadStatus.WaitingForAdbAuthorization ||
        readState.status is RetcnDeviceReadStatus.Reading
    val f = state.retcn
    OutlinedButton(onClick = { showConnectionChooser = true }, enabled = !reading, modifier = Modifier.fillMaxWidth()) {
        if (reading) {
            CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        Text(t.text("retcn-read-device"))
    }
    RetcnDeviceReadStatusView(t, readState.status)
    if (adbUsbPermissionRequest?.source == DeviceReadSource.ADB_USB) {
        AlertDialog(
            onDismissRequest = deviceReader::cancelUsbPermission,
            title = { Text(t.text("retcn-adb-usb-permission-title")) },
            text = { Text(t.text("retcn-adb-usb-permission")) },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = deviceReader::cancelUsbPermission) { Text(t.text("login-cancel")) }
            },
        )
    }
    if (readState.status is RetcnDeviceReadStatus.AdbConnectionFailed) {
        AlertDialog(
            onDismissRequest = deviceReader::cancelAdbConnection,
            title = { Text(t.text("adb-connection-failed-title")) },
            text = {
                Text(t.text("adb-connection-failed", "detail" to readState.status.detail))
            },
            confirmButton = {
                TextButton(onClick = deviceReader::retryAdbConnection) { Text(t.text("adb-connection-retry")) }
            },
            dismissButton = {
                TextButton(onClick = deviceReader::cancelAdbConnection) { Text(t.text("login-cancel")) }
            },
        )
    }
    if (readState.status is RetcnDeviceReadStatus.WaitingForAdbAuthorization) {
        AlertDialog(
            onDismissRequest = deviceReader::cancelAdbAuthorization,
            title = { Text(t.text("retcn-adb-authorizing-title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(t.text("retcn-adb-authorizing"))
                    Text(t.text("retcn-adb-retry-guidance"))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = deviceReader::retryAdbAuthorization,
                    enabled = readState.adbAuthorizationRetryAvailable,
                ) { Text(t.text("retcn-adb-retry")) }
            },
            dismissButton = {
                TextButton(onClick = deviceReader::cancelAdbAuthorization) { Text(t.text("login-cancel")) }
            },
        )
    }
    Field(
        t.text("lookup-imei-label"),
        f.imei,
        keyboardType = KeyboardType.Number,
        isError = hasInvalidImeiChecksum(f.imei),
    ) { value -> onUpdate { it.copy(imei = value.filter(Char::isDigit)) } }
    Field(t.text("retcn-sn-label"), f.serialNumber) { value -> onUpdate { it.copy(serialNumber = value) } }
    Field(t.text("retcn-model-label"), f.model) { value -> onUpdate { it.copy(model = value) } }
    Field(
        t.text("retcn-carrier-label"),
        f.carrier,
        helpTitle = t.text("retcn-adb-help-title", "field" to t.text("retcn-carrier-label")),
        helpMessage = t.text("retcn-adb-help-instructions"),
        helpCommand = "adb shell getprop ro.carrier",
        helpCopyLabel = t.text("retcn-adb-help-copy"),
        helpCloseLabel = t.text("retcn-adb-help-close"),
    ) { value -> onUpdate { it.copy(carrier = value) } }
    Field(
        t.text("retcn-fingerprint-label"),
        f.fingerprint,
        helpTitle = t.text("retcn-adb-help-title", "field" to t.text("retcn-fingerprint-label")),
        helpMessage = t.text("retcn-adb-help-instructions"),
        helpCommand = "adb shell getprop ro.build.fingerprint",
        helpCopyLabel = t.text("retcn-adb-help-copy"),
        helpCloseLabel = t.text("retcn-adb-help-close"),
    ) { value -> onUpdate { it.copy(fingerprint = value) } }
    PlatformMenu(t, f.platform) { value -> onUpdate { it.copy(platform = value) } }
    AnimatedContent(
        targetState = f.platform,
        transitionSpec = {
            (fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(180))) togetherWith
                (fadeOut(animationSpec = tween(80)) + shrinkVertically(animationSpec = tween(120)))
        },
        label = "RETCN platform details",
    ) { platform ->
        if (platform == Platform.QUALCOMM) {
            Field(
                t.text("retcn-fsg-label"),
                f.fsgVersion,
                helpTitle = t.text("retcn-adb-help-title", "field" to t.text("retcn-fsg-label")),
                helpMessage = t.text("retcn-adb-help-instructions"),
                helpCommand = "adb shell getprop vendor.ril.baseband.config.version",
                helpCopyLabel = t.text("retcn-adb-help-copy"),
                helpCloseLabel = t.text("retcn-adb-help-close"),
            ) { value -> onUpdate { it.copy(fsgVersion = value) } }
        } else {
            SimMenu(t, f.simCount) { value -> onUpdate { it.copy(simCount = value) } }
        }
    }
    if (showConnectionChooser) {
        AlertDialog(
            onDismissRequest = { showConnectionChooser = false },
            title = { Text(t.text("retcn-read-device")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(t.text("retcn-read-device-choose"))
                    Button(onClick = { showConnectionChooser = false; deviceReader.readFromFastboot() }, modifier = Modifier.fillMaxWidth()) { Text(t.text("retcn-fill-fastboot")) }
                    if (BuildConfig.DEBUG) {
                        Button(onClick = { showConnectionChooser = false; deviceReader.readFromAdbUsb() }, modifier = Modifier.fillMaxWidth()) { Text(t.text("retcn-read-adb-usb")) }
                        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text(t.text("retcn-read-adb-wireless")) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showConnectionChooser = false }) { Text(t.text("login-cancel")) } },
        )
    }
    if (readState.picker.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = deviceReader::cancelPicker,
            title = { Text(t.text("retcn-pick-device-title")) },
            text = { Text(t.text("retcn-fill-fastboot-fetching")) },
            confirmButton = {
                Column {
                    readState.picker.forEach { candidate ->
                        TextButton(onClick = { deviceReader.selectDevice(candidate) }, modifier = Modifier.fillMaxWidth()) { Text(candidate.label) }
                    }
                }
            },
            dismissButton = { TextButton(onClick = deviceReader::cancelPicker) { Text(t.text("login-cancel")) } },
        )
    }
}

@Composable
private fun RetcnDeviceReadStatusView(t: Translator, status: RetcnDeviceReadStatus) {
    when (status) {
        RetcnDeviceReadStatus.Idle -> Unit
        is RetcnDeviceReadStatus.RequestingPermission -> Text(
            t.text(if (status.source == DeviceReadSource.ADB_USB) "retcn-adb-usb-permission" else "retcn-fill-fastboot-permission"),
            style = MaterialTheme.typography.bodyMedium,
        )
        RetcnDeviceReadStatus.ConnectingToAdb -> Text(t.text("adb-connecting"), style = MaterialTheme.typography.bodyMedium)
        RetcnDeviceReadStatus.WaitingForAdbAuthorization -> Text(t.text("retcn-adb-authorizing"), style = MaterialTheme.typography.bodyMedium)
        is RetcnDeviceReadStatus.AdbConnectionFailed -> ErrorText(t.text("adb-connection-failed", "detail" to status.detail))
        RetcnDeviceReadStatus.Reading -> Text(t.text("retcn-fill-fastboot-fetching"), style = MaterialTheme.typography.bodyMedium)
        is RetcnDeviceReadStatus.Filled -> Text(t.text("retcn-fill-fastboot-filled", "serial" to status.serial), style = MaterialTheme.typography.bodyMedium)
        is RetcnDeviceReadStatus.Error -> when (status.message) {
            "No fastboot device connected" -> ErrorText(t.text("retcn-fill-fastboot-no-device"))
            "No supported Motorola device found" -> ErrorText(t.text("retcn-fill-fastboot-not-motorola"))
            else -> ErrorText(t.error(status.message))
        }
    }
}

@Composable
private fun ModelForm(
    t: Translator,
    state: FirmwareUiState,
    onName: (String) -> Unit,
    onUpdate: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit,
    onCategory: (DeviceCategory) -> Unit,
) {
    val f = state.model
    Field(t.text("fw-model-name"), f.model, onChange = onName)
    CategoryMenu(t, f.category, onCategory)
    AnimatedVisibility(
        visible = f.category != DeviceCategory.PHONE,
        enter = fadeIn(animationSpec = tween(150)) + expandVertically(animationSpec = tween(200)),
        exit = fadeOut(animationSpec = tween(80)) + shrinkVertically(animationSpec = tween(140)),
    ) {
        Field(t.text("by-model-country-label"), f.countryCode) { value -> onUpdate { it.copy(countryCode = value) } }
    }
    AnimatedVisibility(
        visible = f.requiredForModel == f.model.trim(),
        enter = fadeIn(animationSpec = tween(180, delayMillis = 40)) + expandVertically(animationSpec = tween(240)),
        exit = fadeOut(animationSpec = tween(80)) + shrinkVertically(animationSpec = tween(140)),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            f.requiredParameters.forEach { key ->
                Field(key.label(t), f.parameterValues[key].orEmpty()) { value ->
                    onUpdate { it.copy(parameterValues = it.parameterValues + (key to value)) }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    placeholder: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    isError: Boolean = false,
    helpTitle: String? = null,
    helpMessage: String? = null,
    helpCommand: String? = null,
    helpCopyLabel: String = "",
    helpCloseLabel: String = "",
    onChange: (String) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var showHelp by remember(helpTitle, helpMessage, helpCommand) { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        isError = isError,
        trailingIcon = if (helpTitle != null && helpMessage != null && helpCommand != null) {
            { TextButton(onClick = { showHelp = true }) { Text("?") } }
        } else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) }),
    )
    if (showHelp && helpTitle != null && helpMessage != null && helpCommand != null) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            title = { Text(helpTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(helpMessage)
                    Text(helpCommand)
                    OutlinedButton(onClick = { copyToClipboard(context, helpCommand) }, modifier = Modifier.fillMaxWidth()) {
                        Text(helpCopyLabel)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text(helpCloseLabel) } },
        )
    }
}

private fun isValidImei(imei: String): Boolean = com.hikaricalyx.lmnflash.firmware.validateImei(imei).isSuccess
private fun hasInvalidImeiChecksum(imei: String): Boolean = imei.length == 15 && !isValidImei(imei)

@Composable private fun PlatformMenu(t: Translator, platform: Platform, onSelect: (Platform) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text(t.text("retcn-platform-label"), style = MaterialTheme.typography.labelLarge); OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(platform.label(t)) }; DropdownMenu(expanded, { expanded = false }) { Platform.entries.forEach { item -> DropdownMenuItem({ Text(item.label(t)) }, { onSelect(item); expanded = false }) } } } }
@Composable private fun SimMenu(t: Translator, count: Int, onSelect: (Int) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text(t.text("retcn-sim-label"), style = MaterialTheme.typography.labelLarge); OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(if (count == 2) t.text("sim-dual") else t.text("sim-single")) }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text(t.text("sim-single")) }, { onSelect(1); expanded = false }); DropdownMenuItem({ Text(t.text("sim-dual")) }, { onSelect(2); expanded = false }) } } }
@Composable private fun CategoryMenu(t: Translator, category: DeviceCategory, onSelect: (DeviceCategory) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text(t.text("by-model-category-label"), style = MaterialTheme.typography.labelLarge); OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(category.label(t)) }; DropdownMenu(expanded, { expanded = false }) { DeviceCategory.entries.forEach { item -> DropdownMenuItem({ Text(item.label(t)) }, { onSelect(item); expanded = false }) } } } }

@Composable private fun LookupStatusView(t: Translator, status: LookupStatus, onCopy: (String) -> Unit) { when (status) { LookupStatus.Idle -> Unit; LookupStatus.Loading -> Text(t.text("lookup-fetching")); is LookupStatus.Error -> ErrorText(t.error(status.message)); is LookupStatus.Done -> when (val r = status.result) { is LookupResult.Standard -> FirmwareResult(t, r.info, onCopy); is LookupResult.CnTablet -> CnTabletResult(t, r.info, onCopy) } } }
@Composable private fun FirmwareResult(t: Translator, info: FirmwareInfo, onCopy: (String) -> Unit) = ResultCard(t, listOf("fw-market-name" to info.marketName, "fw-model-name" to info.modelName, "fw-sale-model" to info.saleModel, "fw-carrier" to info.carrier, "fw-publish-date" to info.publishDate, "fw-file-name" to info.fileName, "fw-file-size" to info.fileSize, "fw-rom-id" to info.romId, "fw-rom-match-id" to info.romMatchId, "fw-fingerprint" to info.fingerprint, "fw-comments" to info.comments), listOf("fw-copy-uri" to info.downloadUri, "fw-copy-tool" to info.toolUri, "fw-copy-raw" to info.rawJson), onCopy)
@Composable private fun CnTabletResult(t: Translator, info: CnTabletInfo, onCopy: (String) -> Unit) = ResultCard(t, listOf("cn-product-name" to info.productName, "cn-product-model" to info.productModel, "cn-market-name" to info.marketName, "cn-mtm-compat" to info.compatibleMtm, "cn-latest-version" to info.latestVersion, "cn-id" to info.resourceId, "fw-publish-date" to info.publishDate, "fw-file-name" to info.fileName, "fw-file-size" to info.fileSize), listOf("fw-copy-uri" to info.downloadUri, "tablet-copy-password" to info.unzipPassword), onCopy)
@Composable private fun ResultCard(t: Translator, fields: List<Pair<String, String>>, actions: List<Pair<String, String>>, onCopy: (String) -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { fields.forEach { (label, value) -> Text("${t.text(label)}: ${value.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium) } } }; HorizontalDivider(Modifier.padding(vertical = 6.dp)); actions.forEach { (label, value) -> OutlinedButton({ onCopy(value) }, Modifier.fillMaxWidth(), enabled = value.isNotBlank()) { Text(t.text(label)) } } } } }
@Composable private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

@Composable
private fun HistoryButton(t: Translator, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            painter = painterResource(R.drawable.ic_history),
            contentDescription = t.text("history-button"),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryScreen(
    t: Translator,
    history: List<LookupHistoryRecord>,
    onRemove: (Set<String>) -> Unit,
    onRestore: (LookupHistoryRecord) -> Unit,
    appMode: AppMode,
    onAppModeSelected: (AppMode) -> Unit,
    onBack: () -> Unit,
) {
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    val filteredHistory = history.filter { it.matchesHistorySearch(query, t) }
    val displayedHistory = if (managing) history else filteredHistory
    fun exitManageMode() { managing = false; selectedIds = emptySet() }
    BackHandler { if (managing) exitManageMode() else onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.text("history-title")) },
                navigationIcon = { AppModeMenu(t, appMode, onAppModeSelected) },
                actions = {
                    AnimatedContent(targetState = managing, label = "history actions") { isManaging ->
                        if (isManaging) {
                            Row {
                                TextButton(onClick = { onRemove(selectedIds); exitManageMode() }, enabled = selectedIds.isNotEmpty()) { Text(t.text("history-remove", "count" to selectedIds.size)) }
                                TextButton(onClick = { selectedIds = history.mapTo(linkedSetOf()) { it.id } }, enabled = selectedIds.size < history.size) { Text(t.text("history-select-all")) }
                            }
                        } else if (history.isNotEmpty()) {
                            TextButton(onClick = { managing = true }) { Text(t.text("history-manage")) }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (history.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { Text(t.text("history-empty"), style = MaterialTheme.typography.bodyMedium) }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                if (!managing) {
                    Spacer(Modifier.height(4.dp))
                    Field(t.text("history-search"), query, onChange = { query = it })
                    Spacer(Modifier.height(12.dp))
                }
                AnimatedContent(
                    targetState = displayedHistory,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    transitionSpec = {
                        (fadeIn(animationSpec = tween(180, delayMillis = 40)) + expandVertically(animationSpec = tween(220))) togetherWith
                            (fadeOut(animationSpec = tween(90)) + shrinkVertically(animationSpec = tween(120)))
                    },
                    label = "history content",
                ) { records ->
                    if (records.isEmpty()) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) { Text(t.text("history-no-results"), style = MaterialTheme.typography.bodyMedium) }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(records, key = LookupHistoryRecord::id) { record ->
                                HistoryRecord(t, record, managing, record.id in selectedIds) {
                                    if (managing) selectedIds = selectedIds.toggle(record.id) else onRestore(record)
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecord(t: Translator, record: LookupHistoryRecord, managing: Boolean, selected: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().then(if (managing) Modifier else Modifier.clickable(onClick = onClick))
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(record.mode.label(t), style = MaterialTheme.typography.titleMedium)
                if (record.identifier.isNotBlank()) Text("${record.identifierLabel(t)}: ${record.identifier}", style = MaterialTheme.typography.bodyMedium)
                Text("${t.text("fw-model-name")}: ${record.model.displayHistoryValue()}", style = MaterialTheme.typography.bodyMedium)
                Text("${t.text("fw-market-name")}: ${record.marketName.displayHistoryValue()}", style = MaterialTheme.typography.bodyMedium)
                Text("${record.carrierOrCountryLabel(t)}: ${record.carrierOrCountry.displayHistoryValue()}", style = MaterialTheme.typography.bodyMedium)
            }
            AnimatedVisibility(
                visible = managing,
                enter = fadeIn(animationSpec = tween(120)) + expandVertically(animationSpec = tween(160)),
                exit = fadeOut(animationSpec = tween(80)) + shrinkVertically(animationSpec = tween(120)),
            ) { Checkbox(checked = selected, onCheckedChange = { onClick() }) }
        }
    }
}

private fun Set<String>.toggle(id: String): Set<String> = if (id in this) this - id else this + id

private fun LookupHistoryRecord.identifierLabel(t: Translator): String = if (mode == LookupMode.TABLET) t.text("history-psn") else t.text("lookup-imei-label")
private fun LookupHistoryRecord.carrierOrCountryLabel(t: Translator): String = if (mode == LookupMode.BY_MODEL) t.text("by-model-country-label") else t.text("fw-carrier")
private fun String.displayHistoryValue(): String = ifBlank { "—" }
private fun LookupHistoryRecord.matchesHistorySearch(query: String, t: Translator): Boolean {
    val trimmedQuery = query.trim()
    return trimmedQuery.isBlank() || sequenceOf(mode.label(t), identifier, model, marketName, carrierOrCountry)
        .any { value -> value.contains(trimmedQuery, ignoreCase = true) }
}

private fun LookupMode.label(t: Translator) = t.text(when (this) { LookupMode.ROW_SMARTPHONE -> "lookup-mode-row"; LookupMode.RETCN_SMARTPHONE -> "lookup-mode-retcn"; LookupMode.TABLET -> "lookup-mode-tablet"; LookupMode.BY_MODEL -> "lookup-mode-by-model" })
private fun Platform.label(t: Translator) = t.text(if (this == Platform.QUALCOMM) "platform-qualcomm" else "platform-mediatek")
private fun DeviceCategory.label(t: Translator) = t.text(when (this) { DeviceCategory.PHONE -> "category-phone"; DeviceCategory.TABLET -> "category-tablet"; DeviceCategory.SMART -> "category-smart" })
private fun String.label(t: Translator) = when (this) { "fingerPrint" -> t.text("retcn-fingerprint-label"); "roCarrier" -> t.text("retcn-carrier-label"); "fsgVersion.qcom" -> t.text("retcn-fsg-label"); "simCount" -> t.text("retcn-sim-label"); else -> this }

@Composable
private fun KeepScreenAwake(keepAwake: Boolean) {
    val activity = LocalView.current.context.findActivity()
    DisposableEffect(activity, keepAwake) {
        val window = activity?.window
        val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        val alreadyKeptAwake = window?.attributes?.flags?.and(flag) != 0
        if (keepAwake && !alreadyKeptAwake) window?.addFlags(flag)
        onDispose {
            if (keepAwake && !alreadyKeptAwake) window?.clearFlags(flag)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private data class PendingLogSave(val fileName: String, val text: String)

/** Opens Android's user-owned document picker and writes the captured text after a destination is chosen. */
@Composable
private fun rememberLogSaver(context: Context): (String, String) -> Unit {
    val scope = rememberCoroutineScope()
    var pendingSave by remember { mutableStateOf<PendingLogSave?>(null) }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val save = pendingSave
        pendingSave = null
        if (uri != null && save != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer -> writer.write(save.text) }
                }
            }
        }
    }
    return { fileName, text ->
        if (text.isNotBlank()) {
            pendingSave = PendingLogSave(fileName, text)
            createDocument.launch(fileName)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LMN Flash", text))
}

private fun openBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
