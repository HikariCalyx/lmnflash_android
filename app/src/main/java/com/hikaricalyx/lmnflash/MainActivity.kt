package com.hikaricalyx.lmnflash

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
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
import com.hikaricalyx.lmnflash.firmware.LookupMode
import com.hikaricalyx.lmnflash.firmware.LookupResult
import com.hikaricalyx.lmnflash.firmware.LookupStatus
import com.hikaricalyx.lmnflash.firmware.Platform
import com.hikaricalyx.lmnflash.l10n.AppLanguage
import com.hikaricalyx.lmnflash.l10n.Translator
import com.hikaricalyx.lmnflash.l10n.rememberTranslator
import com.hikaricalyx.lmnflash.ui.theme.LMNFlashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LMNFlashTheme { Surface(Modifier.fillMaxSize()) { FirmwareLookupApp() } } }
    }
}

@Composable
private fun FirmwareLookupApp() {
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
    val clipboard = LocalClipboardManager.current
    when (val login = state.login) {
        LoginState.LoggedOut -> LoginStart(t, context, { viewModel.startLogin() }, { viewModel.startLogin(true) }, (state.lookupStatus as? LookupStatus.Error)?.message)
        LoginState.Loading -> CenteredProgress(t.text("login-fetching"))
        is LoginState.Web -> WebLogin(t, login.url, { viewModel.submitLoginCallback(it, login.expectedState) }, { viewModel.showManualLogin(t.text("login-webview-fallback")) }, viewModel::cancelLogin)
        is LoginState.Manual -> ManualLogin(t, login.url, login.notice, { clipboard.setText(AnnotatedString(login.url)) }, { openBrowser(context, login.url) }, { viewModel.submitLoginCallback(it, login.expectedState) }, viewModel::cancelLogin)
        is LoginState.Error -> LoginStart(t, context, { viewModel.startLogin() }, { viewModel.startLogin(true) }, t.text("login-error", "error" to t.error(login.message)))
        is LoginState.LoggedIn -> LookupScreen(t, context, state, viewModel::selectMode, viewModel::updateRowImei, viewModel::updateRetcn, viewModel::updateTabletSerialNumber, viewModel::updateModelName, viewModel::updateModel, viewModel::selectModelCategory, viewModel::lookup, viewModel::logout, { clipboard.setText(AnnotatedString(it)) })
    }
}

@Composable
private fun LoginStart(t: Translator, context: Context, onLogin: () -> Unit, onManual: () -> Unit, message: String?) {
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LanguageMenu(t, context)
        Spacer(Modifier.height(24.dp))
        Text(t.text("mode-1"), style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp)); Text(t.text("login-prompt"), style = MaterialTheme.typography.bodyLarge)
        if (message != null) { Spacer(Modifier.height(12.dp)); ErrorText(t.error(message)) }
        Spacer(Modifier.height(24.dp)); Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-button")) }
        Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text(t.text("login-manual")) }
    }
}

@Composable private fun CenteredProgress(label: String) = Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) { CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(label) }

@Composable
private fun WebLogin(t: Translator, url: String, onCallback: (String) -> Unit, onManual: () -> Unit, onCancel: () -> Unit) {
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.End) { TextButton(onClick = onManual) { Text(t.text("login-manual-short")) }; TextButton(onClick = onCancel) { Text(t.text("login-cancel")) } }
        LoginWebView(url, onCallback, Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun LoginWebView(url: String, onCallback: (String) -> Unit, modifier: Modifier = Modifier) = AndroidView(modifier = modifier, factory = { context ->
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true; settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val target = request?.url?.toString().orEmpty()
                return if (target.startsWith("softwarefix://", true)) { onCallback(target); true } else false
            }
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) { if (url?.startsWith("softwarefix://", true) == true) onCallback(url) }
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
        item { OutlinedTextField(callback, { callback = it }, Modifier.fillMaxWidth(), label = { Text(t.text("login-manual-placeholder")) }, minLines = 3) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { onSubmit(callback) }) { Text(t.text("login-submit")) }; OutlinedButton(onClick = onCancel) { Text(t.text("login-cancel")) } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookupScreen(t: Translator, context: Context, state: FirmwareUiState, onMode: (LookupMode) -> Unit, onImei: (String) -> Unit, onRetcn: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit, onTablet: (String) -> Unit, onModelName: (String) -> Unit, onModel: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit, onCategory: (DeviceCategory) -> Unit, onLookup: () -> Unit, onLogout: () -> Unit, onCopy: (String) -> Unit) {
    val loading = state.lookupStatus is LookupStatus.Loading
    Scaffold(topBar = { TopAppBar(title = { Text(t.text("mode-1")) }, actions = { LanguageMenu(t, context); TextButton(onClick = onLogout) { Text(t.text("logout")) } }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ModeMenu(t, state.mode, !loading, onMode) }
            when (state.mode) {
                LookupMode.ROW_SMARTPHONE -> item { Field(t.text("lookup-imei-label"), state.rowImei, placeholder = t.text("lookup-imei-placeholder"), onChange = onImei) }
                LookupMode.RETCN_SMARTPHONE -> retcnItems(t, state, onRetcn)
                LookupMode.TABLET -> item { Field(t.text("tablet-sn-label"), state.tabletSerialNumber, onChange = onTablet) }
                LookupMode.BY_MODEL -> modelItems(t, state, onModelName, onModel, onCategory)
            }
            item { Button(onClick = onLookup, enabled = !loading, modifier = Modifier.fillMaxWidth()) { if (loading) { CircularProgressIndicator(Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }; Text(if (loading) t.text("lookup-fetching") else t.text("lookup-button")) } }
            item { LookupStatusView(t, state.lookupStatus, onCopy) }; item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable private fun ModeMenu(t: Translator, mode: LookupMode, enabled: Boolean, onSelect: (LookupMode) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Button({ expanded = true }, Modifier.fillMaxWidth(), enabled) { Text(mode.label(t)) }; DropdownMenu(expanded, { expanded = false }) { LookupMode.entries.forEach { item -> DropdownMenuItem({ Text(item.label(t)) }, { onSelect(item); expanded = false }) } } } }

private fun androidx.compose.foundation.lazy.LazyListScope.retcnItems(t: Translator, state: FirmwareUiState, onUpdate: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit) {
    val f = state.retcn
    item { Text(t.text("lookup-mode-retcn"), style = MaterialTheme.typography.titleMedium) }
    item { Field(t.text("lookup-imei-label"), f.imei) { value -> onUpdate { it.copy(imei = value) } } }; item { Field(t.text("retcn-sn-label"), f.serialNumber) { v -> onUpdate { it.copy(serialNumber = v) } } }; item { Field(t.text("retcn-model-label"), f.model) { v -> onUpdate { it.copy(model = v) } } }; item { Field(t.text("retcn-carrier-label"), f.carrier) { v -> onUpdate { it.copy(carrier = v) } } }; item { Field(t.text("retcn-fingerprint-label"), f.fingerprint, 2) { v -> onUpdate { it.copy(fingerprint = v) } } }
    item { PlatformMenu(t, f.platform) { v -> onUpdate { it.copy(platform = v) } } }
    if (f.platform == Platform.QUALCOMM) item { Field(t.text("retcn-fsg-label"), f.fsgVersion) { v -> onUpdate { it.copy(fsgVersion = v) } } } else item { SimMenu(t, f.simCount) { v -> onUpdate { it.copy(simCount = v) } } }
}

private fun androidx.compose.foundation.lazy.LazyListScope.modelItems(t: Translator, state: FirmwareUiState, onName: (String) -> Unit, onUpdate: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit, onCategory: (DeviceCategory) -> Unit) {
    val f = state.model
    item { Field(t.text("fw-model-name"), f.model, onChange = onName) }; item { CategoryMenu(t, f.category, onCategory) }
    if (f.category != DeviceCategory.PHONE) item { Field(t.text("by-model-country-label"), f.countryCode) { v -> onUpdate { it.copy(countryCode = v) } } }
    if (f.requiredForModel == f.model.trim()) items(f.requiredParameters, key = { it }) { key -> Field(key.label(t), f.parameterValues[key].orEmpty()) { v -> onUpdate { it.copy(parameterValues = it.parameterValues + (key to v)) } } }
}

@Composable private fun Field(label: String, value: String, minLines: Int = 1, placeholder: String? = null, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = placeholder?.let { { Text(it) } }, minLines = minLines)
@Composable private fun PlatformMenu(t: Translator, platform: Platform, onSelect: (Platform) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text(t.text("retcn-platform-label"), style = MaterialTheme.typography.labelLarge); OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(platform.label(t)) }; DropdownMenu(expanded, { expanded = false }) { Platform.entries.forEach { item -> DropdownMenuItem({ Text(item.label(t)) }, { onSelect(item); expanded = false }) } } } }
@Composable private fun SimMenu(t: Translator, count: Int, onSelect: (Int) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text(t.text("retcn-sim-label"), style = MaterialTheme.typography.labelLarge); OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(if (count == 2) t.text("sim-dual") else t.text("sim-single")) }; DropdownMenu(expanded, { expanded = false }) { DropdownMenuItem({ Text(t.text("sim-single")) }, { onSelect(1); expanded = false }); DropdownMenuItem({ Text(t.text("sim-dual")) }, { onSelect(2); expanded = false }) } } }
@Composable private fun CategoryMenu(t: Translator, category: DeviceCategory, onSelect: (DeviceCategory) -> Unit) { var expanded by remember { mutableStateOf(false) }; Column { Text(t.text("by-model-category-label"), style = MaterialTheme.typography.labelLarge); OutlinedButton({ expanded = true }, Modifier.fillMaxWidth()) { Text(category.label(t)) }; DropdownMenu(expanded, { expanded = false }) { DeviceCategory.entries.forEach { item -> DropdownMenuItem({ Text(item.label(t)) }, { onSelect(item); expanded = false }) } } } }

@Composable private fun LookupStatusView(t: Translator, status: LookupStatus, onCopy: (String) -> Unit) { when (status) { LookupStatus.Idle -> Unit; LookupStatus.Loading -> Text(t.text("lookup-fetching")); is LookupStatus.Error -> ErrorText(t.error(status.message)); is LookupStatus.Done -> when (val r = status.result) { is LookupResult.Standard -> FirmwareResult(t, r.info, onCopy); is LookupResult.CnTablet -> CnTabletResult(t, r.info, onCopy) } } }
@Composable private fun FirmwareResult(t: Translator, info: FirmwareInfo, onCopy: (String) -> Unit) = ResultCard(t, listOf("fw-market-name" to info.marketName, "fw-model-name" to info.modelName, "fw-sale-model" to info.saleModel, "fw-carrier" to info.carrier, "fw-publish-date" to info.publishDate, "fw-file-name" to info.fileName, "fw-file-size" to info.fileSize, "fw-rom-id" to info.romId, "fw-rom-match-id" to info.romMatchId, "fw-fingerprint" to info.fingerprint, "fw-comments" to info.comments), listOf("fw-copy-uri" to info.downloadUri, "fw-copy-tool" to info.toolUri, "fw-copy-raw" to info.rawJson), onCopy)
@Composable private fun CnTabletResult(t: Translator, info: CnTabletInfo, onCopy: (String) -> Unit) = ResultCard(t, listOf("cn-product-name" to info.productName, "cn-product-model" to info.productModel, "cn-market-name" to info.marketName, "cn-mtm-compat" to info.compatibleMtm, "cn-latest-version" to info.latestVersion, "cn-id" to info.resourceId, "fw-publish-date" to info.publishDate, "fw-file-name" to info.fileName, "fw-file-size" to info.fileSize), listOf("fw-copy-uri" to info.downloadUri, "tablet-copy-password" to info.unzipPassword), onCopy)
@Composable private fun ResultCard(t: Translator, fields: List<Pair<String, String>>, actions: List<Pair<String, String>>, onCopy: (String) -> Unit) { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { SelectionContainer { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { fields.forEach { (label, value) -> Text("${t.text(label)}: ${value.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium) } } }; HorizontalDivider(Modifier.padding(vertical = 6.dp)); actions.forEach { (label, value) -> OutlinedButton({ onCopy(value) }, Modifier.fillMaxWidth(), enabled = value.isNotBlank()) { Text(t.text(label)) } } } } }
@Composable private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

@Composable private fun LanguageMenu(t: Translator, context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(LocaleManager::class.java)
    var expanded by remember { mutableStateOf(false) }
    TextButton(onClick = { expanded = true }) { Text("Language") }
    DropdownMenu(expanded, { expanded = false }) {
        DropdownMenuItem({ Text(context.getString(R.string.language_system_default)) }, { manager.applicationLocales = LocaleList.getEmptyLocaleList(); expanded = false })
        AppLanguage.entries.forEach { language -> DropdownMenuItem({ Text(language.nativeName) }, { manager.applicationLocales = LocaleList.forLanguageTags(language.tag); expanded = false }) }
    }
}

private fun LookupMode.label(t: Translator) = t.text(when (this) { LookupMode.ROW_SMARTPHONE -> "lookup-mode-row"; LookupMode.RETCN_SMARTPHONE -> "lookup-mode-retcn"; LookupMode.TABLET -> "lookup-mode-tablet"; LookupMode.BY_MODEL -> "lookup-mode-by-model" })
private fun Platform.label(t: Translator) = t.text(if (this == Platform.QUALCOMM) "platform-qualcomm" else "platform-mediatek")
private fun DeviceCategory.label(t: Translator) = t.text(when (this) { DeviceCategory.PHONE -> "category-phone"; DeviceCategory.TABLET -> "category-tablet"; DeviceCategory.SMART -> "category-smart" })
private fun String.label(t: Translator) = when (this) { "fingerPrint" -> t.text("retcn-fingerprint-label"); "roCarrier" -> t.text("retcn-carrier-label"); "fsgVersion.qcom" -> t.text("retcn-fsg-label"); "simCount" -> t.text("retcn-sim-label"); else -> this }
private fun openBrowser(context: Context, url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
