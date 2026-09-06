package com.hikaricalyx.lmnflash

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
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
import com.hikaricalyx.lmnflash.ui.theme.LMNFlashTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LMNFlashTheme {
                Surface(modifier = Modifier.fillMaxSize()) { FirmwareLookupApp() }
            }
        }
    }
}

@Composable
private fun FirmwareLookupApp() {
    val context = LocalContext.current
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
        LoginState.LoggedOut -> LoginStart(
            onLogin = { viewModel.startLogin() },
            onManualLogin = { viewModel.startLogin(manual = true) },
            message = (state.lookupStatus as? LookupStatus.Error)?.message,
        )
        LoginState.Loading -> CenteredProgress("Fetching Lenovo login URL…")
        is LoginState.Web -> WebLogin(
            url = login.url,
            onCallback = { callback -> viewModel.submitLoginCallback(callback, login.expectedState) },
            onManualLogin = { viewModel.showManualLogin("Use manual login if the embedded browser cannot finish sign-in.") },
            onCancel = viewModel::cancelLogin,
        )
        is LoginState.Manual -> ManualLogin(
            url = login.url,
            notice = login.notice,
            onCopyUrl = { clipboard.setText(AnnotatedString(login.url)) },
            onOpenBrowser = { openBrowser(context, login.url) },
            onSubmit = { callback -> viewModel.submitLoginCallback(callback, login.expectedState) },
            onCancel = viewModel::cancelLogin,
        )
        is LoginState.Error -> LoginStart(
            onLogin = { viewModel.startLogin() },
            onManualLogin = { viewModel.startLogin(manual = true) },
            message = "Login failed: ${login.message}",
        )
        is LoginState.LoggedIn -> LookupScreen(
            state = state,
            onSelectMode = viewModel::selectMode,
            onUpdateRowImei = viewModel::updateRowImei,
            onUpdateRetcn = viewModel::updateRetcn,
            onUpdateTabletSerial = viewModel::updateTabletSerialNumber,
            onUpdateModelName = viewModel::updateModelName,
            onUpdateModel = viewModel::updateModel,
            onSelectModelCategory = viewModel::selectModelCategory,
            onLookup = viewModel::lookup,
            onLogout = viewModel::logout,
            onCopy = { clipboard.setText(AnnotatedString(it)) },
        )
    }
}

@Composable
private fun LoginStart(onLogin: () -> Unit, onManualLogin: () -> Unit, message: String?) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Firmware Lookup", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Log in with your Lenovo ID to look up firmware.", style = MaterialTheme.typography.bodyLarge)
        if (message != null) {
            Spacer(Modifier.height(12.dp))
            ErrorText(message)
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = onLogin, modifier = Modifier.fillMaxWidth()) { Text("Log in") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onManualLogin, modifier = Modifier.fillMaxWidth()) { Text("Log in manually") }
    }
}

@Composable
private fun CenteredProgress(label: String) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(label)
    }
}

@Composable
private fun WebLogin(url: String, onCallback: (String) -> Unit, onManualLogin: () -> Unit, onCancel: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onManualLogin) { Text("Manual login") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        LoginWebView(url = url, onCallback = onCallback, modifier = Modifier.fillMaxWidth().weight(1f))
    }
}

@Composable
private fun LoginWebView(url: String, onCallback: (String) -> Unit, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val target = request?.url?.toString().orEmpty()
                        return if (target.startsWith("softwarefix://", ignoreCase = true)) { onCallback(target); true } else false
                    }

                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        if (url?.startsWith("softwarefix://", ignoreCase = true) == true) onCallback(url)
                    }
                }
                loadUrl(url)
            }
        },
    )
}

@Composable
private fun ManualLogin(
    url: String, notice: String?, onCopyUrl: () -> Unit, onOpenBrowser: () -> Unit,
    onSubmit: (String) -> Unit, onCancel: () -> Unit,
) {
    var callback by remember(url) { mutableStateOf("") }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Log in with your browser, then paste the SoftwareFix callback link.", style = MaterialTheme.typography.titleLarge) }
        if (notice != null) item { Text(notice, style = MaterialTheme.typography.bodyMedium) }
        item { Text("Login URL", fontWeight = FontWeight.SemiBold) }
        item { Text(url, style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCopyUrl) { Text("Copy URL") }
                OutlinedButton(onClick = onOpenBrowser) { Text("Open in browser") }
            }
        }
        item {
            OutlinedTextField(
                value = callback, onValueChange = { callback = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("SoftwareFix://callback?Authorization=…") }, minLines = 3,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSubmit(callback) }) { Text("Confirm") }
                OutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LookupScreen(
    state: FirmwareUiState,
    onSelectMode: (LookupMode) -> Unit,
    onUpdateRowImei: (String) -> Unit,
    onUpdateRetcn: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit,
    onUpdateTabletSerial: (String) -> Unit,
    onUpdateModelName: (String) -> Unit,
    onUpdateModel: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit,
    onSelectModelCategory: (DeviceCategory) -> Unit,
    onLookup: () -> Unit,
    onLogout: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val loading = state.lookupStatus is LookupStatus.Loading
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Firmware Lookup") },
                actions = { TextButton(onClick = onLogout) { Text("Log out") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { LookupModeMenu(mode = state.mode, enabled = !loading, onSelect = onSelectMode) }
            when (state.mode) {
                LookupMode.ROW_SMARTPHONE -> item {
                    OutlinedTextField(
                        value = state.rowImei, onValueChange = onUpdateRowImei, modifier = Modifier.fillMaxWidth(),
                        label = { Text("IMEI") }, placeholder = { Text("14- or 15-digit IMEI") }, singleLine = true,
                    )
                }
                LookupMode.RETCN_SMARTPHONE -> retcnItems(state, onUpdateRetcn)
                LookupMode.TABLET -> item {
                    OutlinedTextField(
                        value = state.tabletSerialNumber, onValueChange = onUpdateTabletSerial, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Serial number") }, singleLine = true,
                    )
                }
                LookupMode.BY_MODEL -> modelItems(state, onUpdateModelName, onUpdateModel, onSelectModelCategory)
            }
            item {
                Button(onClick = onLookup, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                    if (loading) { CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                    Text(if (loading) "Looking up firmware…" else "Look up")
                }
            }
            item { LookupStatusView(status = state.lookupStatus, onCopy = onCopy) }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun LookupModeMenu(mode: LookupMode, enabled: Boolean, onSelect: (LookupMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Lookup type", style = MaterialTheme.typography.labelLarge)
        Button(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(mode.title) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LookupMode.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { onSelect(item); expanded = false }) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.retcnItems(
    state: FirmwareUiState,
    onUpdate: ((com.hikaricalyx.lmnflash.firmware.RetcnForm) -> com.hikaricalyx.lmnflash.firmware.RetcnForm) -> Unit,
) {
    val form = state.retcn
    item { Text("RETCN Smartphone", style = MaterialTheme.typography.titleMedium) }
    item { Field("IMEI", form.imei) { value -> onUpdate { it.copy(imei = value) } } }
    item { Field("Serial number", form.serialNumber) { value -> onUpdate { it.copy(serialNumber = value) } } }
    item { Field("XT model code", form.model) { value -> onUpdate { it.copy(model = value) } } }
    item { Field("Carrier", form.carrier) { value -> onUpdate { it.copy(carrier = value) } } }
    item { Field("Build fingerprint", form.fingerprint, minLines = 2) { value -> onUpdate { it.copy(fingerprint = value) } } }
    item { PlatformMenu(form.platform) { platform -> onUpdate { it.copy(platform = platform) } } }
    if (form.platform == Platform.QUALCOMM) {
        item { Field("FSG version", form.fsgVersion) { value -> onUpdate { it.copy(fsgVersion = value) } } }
    } else {
        item { SimMenu(form.simCount) { count -> onUpdate { it.copy(simCount = count) } } }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.modelItems(
    state: FirmwareUiState,
    onUpdateName: (String) -> Unit,
    onUpdate: ((com.hikaricalyx.lmnflash.firmware.ModelForm) -> com.hikaricalyx.lmnflash.firmware.ModelForm) -> Unit,
    onSelectCategory: (DeviceCategory) -> Unit,
) {
    val form = state.model
    item { Field("Model name", form.model, onChange = onUpdateName) }
    item { CategoryMenu(form.category, onSelectCategory) }
    if (form.category != DeviceCategory.PHONE) item { Field("Country code", form.countryCode) { value -> onUpdate { it.copy(countryCode = value) } } }
    if (form.requiredForModel == form.model.trim()) {
        items(form.requiredParameters, key = { it }) { key ->
            val label = when (key) {
                "fingerPrint" -> "Build fingerprint"
                "roCarrier" -> "Carrier"
                "fsgVersion.qcom" -> "FSG version"
                "simCount" -> "SIM slots"
                else -> key
            }
            Field(label, form.parameterValues[key].orEmpty()) { value -> onUpdate { it.copy(parameterValues = it.parameterValues + (key to value)) } }
        }
    }
}

@Composable
private fun Field(label: String, value: String, minLines: Int = 1, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(), label = { Text(label) }, minLines = minLines)
}

@Composable
private fun PlatformMenu(platform: Platform, onSelect: (Platform) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Platform", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(platform.title) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Platform.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { onSelect(item); expanded = false }) }
        }
    }
}

@Composable
private fun SimMenu(simCount: Int, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("SIM slots", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(if (simCount == 2) "Dual SIM" else "Single SIM") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Single SIM") }, onClick = { onSelect(1); expanded = false })
            DropdownMenuItem(text = { Text("Dual SIM") }, onClick = { onSelect(2); expanded = false })
        }
    }
}

@Composable
private fun CategoryMenu(category: DeviceCategory, onSelect: (DeviceCategory) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text("Device type", style = MaterialTheme.typography.labelLarge)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(category.title) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DeviceCategory.entries.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { onSelect(item); expanded = false }) }
        }
    }
}

@Composable
private fun LookupStatusView(status: LookupStatus, onCopy: (String) -> Unit) {
    when (status) {
        LookupStatus.Idle -> Unit
        LookupStatus.Loading -> Text("Looking up firmware…")
        is LookupStatus.Error -> ErrorText(status.message)
        is LookupStatus.Done -> when (val result = status.result) {
            is LookupResult.Standard -> FirmwareResult(info = result.info, onCopy = onCopy)
            is LookupResult.CnTablet -> CnTabletResult(info = result.info, onCopy = onCopy)
        }
    }
}

@Composable
private fun FirmwareResult(info: FirmwareInfo, onCopy: (String) -> Unit) {
    ResultCard(
        fields = listOf(
            "Market name" to info.marketName, "Model name" to info.modelName, "Sale model" to info.saleModel,
            "Carrier" to info.carrier, "Publish date" to info.publishDate, "File name" to info.fileName,
            "File size" to info.fileSize, "ROM resource ID" to info.romId, "ROM match ID" to info.romMatchId,
            "Fingerprint" to info.fingerprint, "Comments" to info.comments,
        ),
        actions = listOf(
            "Copy Download URI" to info.downloadUri, "Copy Tool URI" to info.toolUri, "Copy raw response" to info.rawJson,
        ), onCopy = onCopy,
    )
}

@Composable
private fun CnTabletResult(info: CnTabletInfo, onCopy: (String) -> Unit) {
    ResultCard(
        fields = listOf(
            "Product name" to info.productName, "Product model" to info.productModel, "Market name" to info.marketName,
            "Compatible MTM" to info.compatibleMtm, "Latest version" to info.latestVersion, "Resource ID" to info.resourceId,
            "Publish date" to info.publishDate, "File name" to info.fileName, "File size" to info.fileSize,
        ),
        actions = listOf("Copy Download URI" to info.downloadUri, "Copy extraction password" to info.unzipPassword), onCopy = onCopy,
    )
}

@Composable
private fun ResultCard(fields: List<Pair<String, String>>, actions: List<Pair<String, String>>, onCopy: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fields.forEach { (label, value) ->
                        Text("$label: ${value.ifBlank { "—" }}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            actions.forEach { (label, value) ->
                OutlinedButton(onClick = { onCopy(value) }, enabled = value.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(label) }
            }
        }
    }
}

@Composable
private fun ErrorText(message: String) = Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

private fun openBrowser(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
