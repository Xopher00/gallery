/*
 * Control panel for the local OpenAI-compatible API server, ported/simplified from
 * mobile-server's ServerScreen.kt (com.server.edge.gallery).
 *
 * Network-mode selector (Loopback / LAN / Interface). The security implication of each
 * non-default mode is spelled out in-line (strings.xml: server_mode_lan_warning) rather than
 * left implicit.
 */
package com.google.ai.edge.gallery.ui.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.DataStoreRepositoryEntryPoint
import com.google.ai.edge.gallery.openai.OpenAiServerService
import com.google.ai.edge.gallery.openai.OpenAiServerState
import com.google.ai.edge.gallery.openai.OpenAiServerState.BindMode
import com.google.ai.edge.gallery.openai.handlers.ALL_MOBILE_ACTION_TOOLS
import com.google.ai.edge.gallery.openai.handlers.DEFAULT_ALLOWED_TOOLS
import com.google.ai.edge.gallery.openai.handlers.RISKY_TOOLS
import dagger.hilt.android.EntryPointAccessors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerScreen(navigateUp: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val isRunning by OpenAiServerState.isRunning.collectAsState()
    val localUrl by OpenAiServerState.localUrl.collectAsState()
    val liveBoundHost by OpenAiServerState.liveBoundHost.collectAsState()
    var apiKey by remember { mutableStateOf(OpenAiServerState.apiKey(context)) }

    var bindMode by remember { mutableStateOf(OpenAiServerState.loadBindMode(context)) }

    var copiedNotice by remember { mutableStateOf(false) }

    var selectedInterfaceName by remember { mutableStateOf(OpenAiServerState.loadSelectedInterfaceName(context)) }
    var availableInterfaces by remember { mutableStateOf(OpenAiServerState.listAvailableInterfaces()) }
    val bindError by OpenAiServerState.bindError.collectAsState()

    // WP-C2: per-tool agent allowlist. loadAllowedTools seeds the persisted store with
    // DEFAULT_ALLOWED_TOOLS the first time it is read on an install that has never touched this
    // screen, so nothing changes silently for an existing install. Toggling a switch persists
    // immediately (setAllowedTools), same pattern as applyModeChange/applyInterfaceChange above.
    var allowedTools by remember {
        mutableStateOf(OpenAiServerState.loadAllowedTools(context, defaultIfUnset = DEFAULT_ALLOWED_TOOLS))
    }

    fun setToolAllowed(toolName: String, allowed: Boolean) {
        val updated = if (allowed) allowedTools + toolName else allowedTools - toolName
        allowedTools = updated
        OpenAiServerState.setAllowedTools(context, updated)
    }

    // WP (spec section 6): boot auto-start + battery-optimisation opt-in controls. Reached via
    // EntryPointAccessors (same pattern BootReceiver uses) rather than a Hilt-injected
    // ViewModel, since this Composable has no @AndroidEntryPoint scaffolding of its own -- see
    // DataStoreRepositoryEntryPoint.
    val dataStoreRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            DataStoreRepositoryEntryPoint::class.java,
        ).dataStoreRepository()
    }
    var startServerOnBoot by remember { mutableStateOf(dataStoreRepository.readStartServerOnBoot()) }

    val powerManager = remember { context.getSystemService(PowerManager::class.java) }
    fun checkIgnoringBatteryOptimizations(): Boolean =
        powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true
    var isIgnoringBatteryOptimizations by remember { mutableStateOf(checkIgnoringBatteryOptimizations()) }

    // The battery-optimisation exemption is granted via a system dialog the user returns from;
    // re-check on ON_RESUME (same LifecycleEventObserver pattern as
    // ui/common/chat/MessageInputText.kt) so the button correctly disappears once granted,
    // without needing a new screen or navigation result callback.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isIgnoringBatteryOptimizations = checkIgnoringBatteryOptimizations()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Re-check the bound interface periodically while INTERFACE mode is running, so drift
    // (interface disconnects, or re-IPs, after the server already started) gets reported.
    // Rebinding automatically is out of scope for this card; reporting is not.
    LaunchedEffect(isRunning, bindMode) {
        while (isRunning && bindMode == BindMode.INTERFACE) {
            OpenAiServerState.checkInterfaceDrift()
            delay(5000)
        }
    }

    // Restarting the running service picks up a mode/provider/token change immediately; if the
    // server is stopped the new value just takes effect the next time it's started.
    // D14: never widen the bind scope silently. If the current mode/selection is not actually
    // ready to bind (only possible today for INTERFACE mode -- see validateBindReady()), refuse
    // to start the service at all and surface why via bindError instead.
    fun applyModeChange(newMode: BindMode) {
        bindMode = newMode
        OpenAiServerState.setBindMode(context, newMode)
        if (isRunning) {
            val error = OpenAiServerState.refreshBindError()
            if (error != null) {
                OpenAiServerService.stopService(context)
            } else {
                OpenAiServerService.startService(context)
            }
        }
    }

    fun applyInterfaceChange(name: String) {
        selectedInterfaceName = name
        OpenAiServerState.setSelectedInterfaceName(context, name)
        if (isRunning && bindMode == BindMode.INTERFACE) {
            val error = OpenAiServerState.refreshBindError()
            if (error != null) {
                OpenAiServerService.stopService(context)
            } else {
                OpenAiServerService.startService(context)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Server") },
                navigationIcon = {
                    IconButton(onClick = navigateUp) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        modifier = modifier,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(if (isRunning) "Server running" else "Server stopped")
                Switch(
                    checked = isRunning,
                    onCheckedChange = { checked ->
                        if (checked) {
                            // D14: refuse to start rather than silently widen the bind scope --
                            // e.g. an INTERFACE selection that is currently absent/no-IPv4.
                            val error = OpenAiServerState.refreshBindError()
                            if (error == null) {
                                OpenAiServerService.startService(context)
                            } else {
                                OpenAiServerService.stopService(context)
                            }
                        } else {
                            OpenAiServerService.stopService(context)
                        }
                    },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val fallbackUrl = when (bindMode) {
                BindMode.INTERFACE -> selectedInterfaceName
                    ?.let { name -> availableInterfaces.find { it.name == name }?.ipv4 }
                    ?.let { ip -> "http://$ip:${OpenAiServerState.DEFAULT_PORT}" }
                else -> null
            } ?: localUrl ?: "http://127.0.0.1:${OpenAiServerState.DEFAULT_PORT}"
            // While running, prefer the live server's actual bound host over any pref-derived
            // guess -- liveBoundHost is set by the service right after a successful start() and
            // cleared on stop, so it can never point at a socket that isn't really listening.
            val reachableUrl = if (isRunning) {
                liveBoundHost?.let { host -> "http://$host:${OpenAiServerState.DEFAULT_PORT}" } ?: fallbackUrl
            } else {
                fallbackUrl
            }
            Text("URL: $reachableUrl")

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("API key: $apiKey (fingerprint ${OpenAiServerState.fingerprint(apiKey)})")
                IconButton(onClick = { copyToClipboard(context, apiKey, "API key") }) {
                    Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy API key")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(onClick = { apiKey = OpenAiServerState.regenerateApiKey(context) }) {
                Text("Regenerate API key")
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Send \"Authorization: Bearer <key>\" on every /v1/* request. /health is open.")

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                stringResource(R.string.server_mode_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = bindMode == BindMode.LOOPBACK,
                    onClick = { applyModeChange(BindMode.LOOPBACK) },
                    label = { Text(stringResource(R.string.server_mode_loopback)) },
                )
                FilterChip(
                    selected = bindMode == BindMode.LAN,
                    onClick = { applyModeChange(BindMode.LAN) },
                    label = { Text(stringResource(R.string.server_mode_lan)) },
                )
                FilterChip(
                    selected = bindMode == BindMode.INTERFACE,
                    onClick = {
                        availableInterfaces = OpenAiServerState.listAvailableInterfaces()
                        applyModeChange(BindMode.INTERFACE)
                    },
                    label = { Text(stringResource(R.string.server_mode_interface)) },
                )
            }

            when (bindMode) {
                BindMode.LOOPBACK -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.server_mode_loopback_info),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BindMode.LAN -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.server_mode_lan_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                BindMode.INTERFACE -> {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.server_interface_section_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Button(onClick = { availableInterfaces = OpenAiServerState.listAvailableInterfaces() }) {
                            Text(stringResource(R.string.server_interface_refresh))
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    if (availableInterfaces.isEmpty()) {
                        Text(
                            stringResource(R.string.server_interface_none_found),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            for (iface in availableInterfaces) {
                                FilterChip(
                                    selected = selectedInterfaceName == iface.name,
                                    onClick = { applyInterfaceChange(iface.name) },
                                    label = { Text("${iface.name} (${iface.ipv4})") },
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val ifaceForWarning = selectedInterfaceName
                        ?.let { name -> availableInterfaces.find { it.name == name } }
                    if (ifaceForWarning != null) {
                        Text(
                            stringResource(
                                R.string.server_interface_warning,
                                ifaceForWarning.name,
                                ifaceForWarning.ipv4,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (bindError != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.server_interface_error, bindError ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                stringResource(R.string.server_boot_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.server_start_on_boot_label))
                Switch(
                    checked = startServerOnBoot,
                    onCheckedChange = { checked ->
                        startServerOnBoot = checked
                        dataStoreRepository.saveStartServerOnBoot(checked)
                    },
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.server_start_on_boot_info),
                style = MaterialTheme.typography.bodySmall,
            )

            // Exemption is only meaningful once start-on-boot (or otherwise persisting across
            // app closure) is actually turned on, and hidden once already granted.
            if (startServerOnBoot && !isIgnoringBatteryOptimizations) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(onClick = {
                    val intent = Intent(
                        AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}"),
                    )
                    context.startActivity(intent)
                }) {
                    Text(stringResource(R.string.server_battery_optimization_button))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.server_battery_optimization_info),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text(
                stringResource(R.string.server_agent_tools_section_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.server_agent_tools_section_info),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (toolName in ALL_MOBILE_ACTION_TOOLS) {
                if (toolName in RISKY_TOOLS) continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(toolName)
                    Switch(
                        checked = toolName in allowedTools,
                        onCheckedChange = { checked -> setToolAllowed(toolName, checked) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                stringResource(R.string.server_agent_tools_risky_section_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.server_agent_tools_risky_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(8.dp))
            for (toolName in ALL_MOBILE_ACTION_TOOLS) {
                if (toolName !in RISKY_TOOLS) continue
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(toolName)
                    Switch(
                        checked = toolName in allowedTools,
                        onCheckedChange = { checked -> setToolAllowed(toolName, checked) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = {
                val base = reachableUrl
                val config = "base_url: $base/v1\napi_key: $apiKey"
                copyToClipboard(context, config, "Client config")
                copiedNotice = true
            }) {
                Text(stringResource(R.string.server_copy_client_config))
            }
            if (copiedNotice) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.server_client_config_copied),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun copyToClipboard(context: android.content.Context, text: String, label: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
}
