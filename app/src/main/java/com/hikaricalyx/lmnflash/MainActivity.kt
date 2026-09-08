package com.hikaricalyx.lmnflash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.net.toUri
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
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
import com.hikaricalyx.lmnflash.l10n.AppLanguage
import com.hikaricalyx.lmnflash.l10n.Translator
import com.hikaricalyx.lmnflash.l10n.rememberTranslator
import com.hikaricalyx.lmnflash.ui.theme.LMNFlashTheme
import java.util.Locale

private data class SoftwareFixCallback(val id: Long, val uri: String)

private const val LANGUAGE_PREFERENCES = "app-language"
private const val LANGUAGE_TAG_KEY = "selected-tag"

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val languageTag = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            newBase.getSharedPreferences(LANGUAGE_PREFERENCES, Context.MODE_PRIVATE)
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
    LaunchedEffect(externalCallback?.id) {
        externalCallback?.let { callback ->
            viewModel.submitExternalLoginCallback(callback.uri)
            onExternalCallbackConsumed(callback.id)
        }
    }
    AnimatedContent(
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
            LoginState.LoggedOut -> LoginStart(t, context, { viewModel.startLogin() }, { viewModel.startLogin(true) }, (state.lookupStatus as? LookupStatus.Error)?.message)
            LoginState.Loading -> CenteredProgress(t.text("login-fetching"))
            is LoginState.Web -> WebLogin(t, login.url, viewModel::submitLoginCallback, { viewModel.showManualLogin(t.text("login-webview-fallback")) }, viewModel::cancelLogin)
            is LoginState.Manual -> ManualLogin(t, login.url, login.notice, { copyToClipboard(context, login.url) }, { openBrowser(context, login.url) }, viewModel::submitLoginCallback, viewModel::cancelLogin)
            is LoginState.Error -> LoginStart(t, context, { viewModel.startLogin() }, { viewModel.startLogin(true) }, t.text("login-error", "error" to t.error(login.message)))
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
                    }) { showHistory = false }
                } else {
                    LookupScreen(t, context, state, viewModel::selectMode, viewModel::updateRowImei, viewModel::updateRetcn, viewModel::updateTabletSerialNumber, viewModel::updateModelName, viewModel::updateModel, viewModel::selectModelCategory, viewModel::lookup, { showHistory = true }, viewModel::logout) { copyToClipboard(context, it) }
                }
            }
        }
    }
}

@Composable
private fun LoginStart(t: Translator, context: Context, onLogin: () -> Unit, onManual: () -> Unit, message: String?) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LanguageMenu(context)
        Spacer(Modifier.height(24.dp))
        Text(t.text("mode-1"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp)); Text(t.text("login-prompt"), style = MaterialTheme.typography.bodyLarge)
        if (message != null) { Spacer(Modifier.height(12.dp)); ErrorText(t.error(message)) }
        Spacer(Modifier.height(24.dp)); Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-button")) }
        Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-manual")) }
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

@Composable private fun CenteredProgress(label: String) = Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(label) }

@Composable
private fun WebLogin(t: Translator, url: String, onCallback: (String) -> Unit, onManual: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onManual) { Text(t.text("login-manual-short")) }; TextButton(onClick = onCancel) { Text(t.text("login-cancel")) } }
        LoginWebView(url, onCallback, onManual, Modifier.fillMaxWidth().weight(1f))
    }
}

@SuppressLint(
    "SetJavaScriptEnabled", // Lenovo's login page requires JavaScript; no JavaScript bridge is exposed.
    "MissingOnRenderProcessGone", // Renderer loss is handled by the API-26 override in this WebViewClient.
)
@Composable
private fun LoginWebView(
    url: String,
    onCallback: (String) -> Unit,
    onManual: () -> Unit,
    modifier: Modifier = Modifier,
) = AndroidView(modifier = modifier, factory = { context ->
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
            androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                return if (target.startsWith("softwarefix://", true)) { onCallback(target); true } else false
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                if (url?.startsWith("softwarefix://", true) == true) onCallback(url)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                view.destroy()
                onManual()
                return true
            }
        }
        loadUrl(url)
    }
})

@Composable
private fun ManualLogin(t: Translator, url: String, notice: String?, onCopy: () -> Unit, onBrowser: () -> Unit, onSubmit: (String) -> Unit, onCancel: () -> Unit) {
    var callback by remember(url) { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
private fun LookupScreen(t: Translator, context: Context, state: FirmwareUiState, onMode: (LookupMode) -> Unit, onImei: (String) -> Unit, onRetcn: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit, onTablet: (String) -> Unit, onModelName: (String) -> Unit, onModel: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit, onCategory: (DeviceCategory) -> Unit, onLookup: () -> Unit, onShowHistory: () -> Unit, onLogout: () -> Unit, onCopy: (String) -> Unit) {
    val loading = state.lookupStatus is LookupStatus.Loading
    val lookupEnabled = !loading && when (state.mode) {
        LookupMode.ROW_SMARTPHONE -> isValidImei(state.rowImei)
        LookupMode.RETCN_SMARTPHONE -> isValidImei(state.retcn.imei)
        LookupMode.TABLET, LookupMode.BY_MODEL -> true
    }
    Scaffold(topBar = { TopAppBar(title = { Text(t.text("mode-1")) }, actions = { HistoryButton(t, onShowHistory); LanguageMenu(context); TextButton(onClick = onLogout) { Text(t.text("logout")) } }) }) { padding ->
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
    val f = state.retcn
    Text(t.text("lookup-mode-retcn"), style = MaterialTheme.typography.titleMedium)
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
private fun HistoryScreen(t: Translator, history: List<LookupHistoryRecord>, onRemove: (Set<String>) -> Unit, onRestore: (LookupHistoryRecord) -> Unit, onBack: () -> Unit) {
    var managing by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    fun exitManageMode() { managing = false; selectedIds = emptySet() }
    BackHandler { if (managing) exitManageMode() else onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(t.text("history-title")) },
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
        AnimatedContent(
            targetState = history,
            transitionSpec = {
                (fadeIn(animationSpec = tween(180, delayMillis = 40)) + expandVertically(animationSpec = tween(220))) togetherWith
                    (fadeOut(animationSpec = tween(90)) + shrinkVertically(animationSpec = tween(120)))
            },
            label = "history content",
        ) { records ->
            if (records.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Text(t.text("history-empty"), style = MaterialTheme.typography.bodyMedium) }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
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

private fun LookupMode.label(t: Translator) = t.text(when (this) { LookupMode.ROW_SMARTPHONE -> "lookup-mode-row"; LookupMode.RETCN_SMARTPHONE -> "lookup-mode-retcn"; LookupMode.TABLET -> "lookup-mode-tablet"; LookupMode.BY_MODEL -> "lookup-mode-by-model" })
private fun Platform.label(t: Translator) = t.text(if (this == Platform.QUALCOMM) "platform-qualcomm" else "platform-mediatek")
private fun DeviceCategory.label(t: Translator) = t.text(when (this) { DeviceCategory.PHONE -> "category-phone"; DeviceCategory.TABLET -> "category-tablet"; DeviceCategory.SMART -> "category-smart" })
private fun String.label(t: Translator) = when (this) { "fingerPrint" -> t.text("retcn-fingerprint-label"); "roCarrier" -> t.text("retcn-carrier-label"); "fsgVersion.qcom" -> t.text("retcn-fsg-label"); "simCount" -> t.text("retcn-sim-label"); else -> this }
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("LMN Flash", text))
}

private fun openBrowser(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
}
