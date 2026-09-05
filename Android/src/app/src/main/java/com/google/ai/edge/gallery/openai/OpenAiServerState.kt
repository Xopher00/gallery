/*
 * Ported from mobile-server (com.server.edge.gallery) into the Gallery API-server fork.
 */
package com.google.ai.edge.gallery.openai

import android.content.Context
import android.util.Base64
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.security.SecureRandom
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OpenAiServerState {
    private const val TAG = "AGOpenAiServerState"
    private const val PREFS_NAME = "openai_server_prefs"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_BIND_MODE = "bind_mode"
    private const val KEY_SELECTED_INTERFACE = "selected_interface_name"
    private const val KEY_ALLOWED_TOOLS = "agent_allowed_tools"
    private const val ALLOWED_TOOLS_DELIMITER = ","
    // Last successfully pinned model, for boot preload. Same SharedPreferences store as
    // apiKey/bindMode/selectedInterfaceName above.
    private const val KEY_LAST_PINNED_MODEL_NAME = "last_pinned_model_name"
    private const val KEY_LAST_PINNED_ACCELERATOR = "last_pinned_accelerator"

    const val DEFAULT_PORT = 8080
    const val BIND_HOST = "127.0.0.1" // retained for callers that only ever want loopback

    /**
     * Where the embedded server listens. Defaults to LOOPBACK -- this default must not change.
     *
     * The user picks from whichever real interfaces are present on the device (see
     * [listAvailableInterfaces]); Tailscale is just one entry in that list if it happens to be
     * up, never a special case in code.
     */
    enum class BindMode {
        LOOPBACK,  // 127.0.0.1 only
        LAN,       // 0.0.0.0, reachable from other devices on the same network
        INTERFACE, // bind to exactly one user-selected interface's IPv4 address; see selectedInterfaceName
    }

    /** One real, currently-up, non-loopback network interface with an IPv4 address. */
    data class InterfaceInfo(
        val name: String,        // e.g. "wlan0", "tailscale0", "rndis0" -- exactly as the OS names it
        val displayName: String, // NetworkInterface.displayName; on Android this is usually == name
        val ipv4: String,
    )

    // Static reference to the live OpenAiServer instance, set by OpenAiServer.start()/cleared
    // by OpenAiServer.stop(). Lets MainActivity's headless `--es load_model` extra call
    // loadModel() without OpenAiServerService needing to expose its private server field.
    var runningServer: com.google.ai.edge.gallery.openai.OpenAiServer? = null

    // Models the API server loaded (via POST /v1/models/{id}/load) that should be kept alive --
    // ModelManagerViewModel.cleanupModel() checks this set first and no-ops for a pinned model,
    // so a model the API loaded survives the UI navigating away from its screen. Unpinned (by
    // OpenAiServer.unloadModel / server stop / ModelManagerViewModel.deleteModel) before the
    // normal cleanup path can free it again.
    //
    // Keyed by model NAME, not by Model.getPath(context). For every model this pin set covers,
    // Model.getPath() is a deterministic function of `downloadFileName`, and for imported models
    // `downloadFileName == info.fileName == name` (see
    // ModelManagerViewModel.createModelFromImportedModelInfo) -- so a delete-then-reimport of
    // the same file produces the identical path for the identical name; rekeying to path would
    // not change that. deleteModel() unconditionally unpins before returning, so no pin can
    // outlive the Model object it was set for. One known gap: ModelManagerViewModel.
    // addImportedLlmModel/addImportedSdModel replace an existing same-name entry directly
    // (`task.models.removeAt`) without going through deleteModel(), so a pin set while the old
    // Model object was loaded would still apply to a newly-imported one with the same name.
    private val _pinnedModels = MutableStateFlow<Set<String>>(emptySet())
    val pinnedModels = _pinnedModels.asStateFlow()

    /**
     * Pins [name] as loaded/keep-alive.
     *
     * When [context] is non-null, also persists `(name, accelerator)` as the "last pinned model"
     * for boot preload -- see [loadLastPinnedModel]. [context] is optional (defaulting to null,
     * i.e. no persistence); a call site that omits it still pins in-memory exactly as before --
     * in-memory pin/unpin behaviour is unchanged either way.
     */
    fun pin(name: String, context: Context? = null, accelerator: String? = null) {
        _pinnedModels.value = _pinnedModels.value + name
        if (context != null) {
            persistLastPinnedModel(context, name, accelerator)
        }
    }

    /**
     * Unpins [name].
     *
     * When [context] is non-null and [name] is the currently-persisted "last pinned model", also
     * clears that persisted pin -- so an explicit unload/delete isn't silently reloaded on the
     * next boot. [context] is optional for the same reason as [pin]'s.
     */
    fun unpin(name: String, context: Context? = null) {
        _pinnedModels.value = _pinnedModels.value - name
        if (context != null) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_LAST_PINNED_MODEL_NAME, null) == name) {
                prefs.edit().remove(KEY_LAST_PINNED_MODEL_NAME).remove(KEY_LAST_PINNED_ACCELERATOR).apply()
            }
        }
    }

    private fun persistLastPinnedModel(context: Context, name: String, accelerator: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_LAST_PINNED_MODEL_NAME, name)
            .putString(KEY_LAST_PINNED_ACCELERATOR, accelerator)
            .apply()
    }

    /**
     * Reads the last-pinned `(name, acceleratorLabel)` persisted by [pin], or null if nothing
     * has ever been pinned with a [Context] on this install. [acceleratorLabel] in the returned
     * pair may itself be null (pin() was called with a null accelerator, e.g. a CPU-only engine
     * kind) -- callers pass that straight to [OpenAiServer.loadModel]'s nullable `accelerator`
     * parameter, which already treats null as "use the model's recorded/default accelerator".
     */
    fun loadLastPinnedModel(context: Context): Pair<String, String?>? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_LAST_PINNED_MODEL_NAME, null) ?: return null
        val accelerator = prefs.getString(KEY_LAST_PINNED_ACCELERATOR, null)
        return name to accelerator
    }

    fun isPinned(name: String): Boolean = name in _pinnedModels.value

    private val _isRunning = MutableStateFlow(false)
    val isRunning = _isRunning.asStateFlow()

    private val _localUrl = MutableStateFlow<String?>(null)
    val localUrl = _localUrl.asStateFlow()

    /**
     * The host the embedded server is ACTUALLY bound to right now, set by the service after a
     * successful [com.google.ai.edge.gallery.openai.OpenAiServer.start] and cleared whenever the
     * server stops. ServerScreen reads this (never the service/server object directly) so the
     * displayed URL always reflects the live socket instead of a pref-derived guess.
     */
    private val _liveBoundHost = MutableStateFlow<String?>(null)
    val liveBoundHost = _liveBoundHost.asStateFlow()

    fun setLiveBoundHost(host: String?) {
        _liveBoundHost.value = host
    }

    private val _bindMode = MutableStateFlow(BindMode.LOOPBACK)
    val bindMode = _bindMode.asStateFlow()

    private val _selectedInterfaceName = MutableStateFlow<String?>(null)
    val selectedInterfaceName = _selectedInterfaceName.asStateFlow()

    /** Set whenever bindHost()/validateBindReady() finds INTERFACE mode not ready to start. */
    private val _bindError = MutableStateFlow<String?>(null)
    val bindError = _bindError.asStateFlow()

    /** The IPv4 address INTERFACE mode last actually bound to, to detect runtime drift. */
    private val _boundInterfaceAddress = MutableStateFlow<String?>(null)

    fun setRunning(running: Boolean, local: String? = null) {
        _isRunning.value = running
        _localUrl.value = local
    }

    /**
     * The host the embedded server should bind to for the current [bindMode].
     *
     * For INTERFACE mode this throws rather than widening the bind scope when the selected
     * interface is absent or has no IPv4 -- never falls back to 0.0.0.0 here. Callers (currently
     * OpenAiServer.start()) are expected to let this propagate; ServerScreen additionally checks
     * [validateBindReady] before ever starting the service, so this throw is a last line of
     * defense, not the primary UX.
     */
    fun bindHost(): String = when (_bindMode.value) {
        BindMode.LOOPBACK -> "127.0.0.1"
        BindMode.LAN -> "0.0.0.0"
        BindMode.INTERFACE -> {
            val error = validateBindReady()
            if (error != null) {
                _bindError.value = error
                throw IllegalStateException(error)
            }
            _bindError.value = null
            val addr = resolveInterfaceAddress(_selectedInterfaceName.value!!)!!
            _boundInterfaceAddress.value = addr
            addr
        }
    }

    /**
     * Real, currently-up, non-loopback interfaces with an IPv4 address, for the bind picker.
     * No interface name is hardcoded or special-cased: tailscale0, if present and up, shows up
     * here for the same reason wlan0 does -- because NetworkInterface reports it, nothing more.
     */
    fun listAvailableInterfaces(): List<InterfaceInfo> = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .mapNotNull { iface ->
                val ipv4 = iface.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .firstOrNull()
                    ?.hostAddress
                ipv4?.let { InterfaceInfo(name = iface.name, displayName = iface.displayName ?: iface.name, ipv4 = it) }
            }
            .toList()
    } catch (e: Exception) {
        Log.e(TAG, "Failed to enumerate network interfaces", e)
        emptyList()
    }

    private fun resolveInterfaceAddress(name: String): String? = try {
        NetworkInterface.getByName(name)
            ?.takeIf { it.isUp }
            ?.inetAddresses?.asSequence()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull()
            ?.hostAddress
    } catch (e: Exception) {
        Log.e(TAG, "Failed to resolve interface '$name'", e)
        null
    }

    /**
     * Returns null if INTERFACE mode (or any other mode) is ready to start; otherwise a
     * user-facing message explaining why it is not -- WITHOUT ever implying a wider fallback.
     * ServerScreen calls this before starting the service so the UI can refuse to start rather
     * than let the server silently widen its bind scope.
     */
    fun validateBindReady(): String? {
        if (_bindMode.value != BindMode.INTERFACE) return null
        val name = _selectedInterfaceName.value
        if (name.isNullOrBlank()) {
            return "No network interface selected. Pick one before starting the server."
        }
        val addr = resolveInterfaceAddress(name)
        return if (addr == null) {
            "Interface '$name' is not available or has no IPv4 address right now " +
                "(disconnected, or its address changed). The server will NOT bind to 0.0.0.0 " +
                "automatically -- reconnect '$name' or choose a different interface."
        } else {
            null
        }
    }

    /**
     * Re-checks that the interface INTERFACE mode is bound to is still present with the same
     * address. Handles runtime drift (interface disconnects, or gets re-IP'd, after the server
     * already started). Rebinding automatically is out of scope here -- this call updates
     * [bindError] so the UI can tell the user to restart the server; it does not touch the
     * already-running server. Returns true if still consistent, false (and sets bindError) if not.
     */
    fun checkInterfaceDrift(): Boolean {
        if (_bindMode.value != BindMode.INTERFACE) return true
        val name = _selectedInterfaceName.value
        val bound = _boundInterfaceAddress.value
        if (name.isNullOrBlank() || bound == null) return true
        val current = resolveInterfaceAddress(name)
        return when {
            current == null -> {
                _bindError.value = "Interface '$name' disconnected -- the server is still bound " +
                    "to its old address ($bound), which no longer exists. Restart the server."
                false
            }
            current != bound -> {
                _bindError.value = "Interface '$name' changed address ($bound -> $current) -- " +
                    "restart the server to rebind to the new address."
                false
            }
            else -> {
                _bindError.value = null
                true
            }
        }
    }

    /**
     * Runs [validateBindReady] and publishes the result to [bindError], without attempting to
     * start anything. Lets ServerScreen show why the server refused to start (or won't be
     * allowed to) even when it never calls bindHost() itself.
     */
    fun refreshBindError(): String? {
        val error = validateBindReady()
        _bindError.value = error
        return error
    }

    /** Loads the persisted bind mode into the in-memory state flow. Call once at startup / screen open. */
    fun loadBindMode(context: Context): BindMode {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_BIND_MODE, null)
        val mode = stored?.let { runCatching { BindMode.valueOf(it) }.getOrNull() } ?: BindMode.LOOPBACK
        _bindMode.value = mode
        return mode
    }

    fun setBindMode(context: Context, mode: BindMode) {
        _bindMode.value = mode
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_BIND_MODE, mode.name).apply()
    }

    fun loadSelectedInterfaceName(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_SELECTED_INTERFACE, null)
        _selectedInterfaceName.value = stored
        return stored
    }

    fun setSelectedInterfaceName(context: Context, name: String?) {
        _selectedInterfaceName.value = name
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_SELECTED_INTERFACE, name).apply()
    }

    // User-configurable allowlist for /v1/agent/run's tool gate (AgentHandler.kt). Same
    // SharedPreferences store as bindMode/selectedInterfaceName above -- no DataStore/proto, this
    // is the only config mechanism this server's settings use. Kept generic (just a Set<String>
    // of tool names) rather than importing anything from customtasks.mobileactions, so this file
    // stays decoupled from which concrete tools exist; AgentHandler owns the tool-name constants
    // and the default value it passes in here.
    private val _allowedTools = MutableStateFlow<Set<String>?>(null) // null = never loaded this process
    val allowedTools = _allowedTools.asStateFlow()

    /**
     * Loads the persisted agent-tool allowlist into [allowedTools] and returns it.
     *
     * [defaultIfUnset] seeds the in-memory value (NOT written to disk) the first time this is
     * called with nothing yet persisted, so an existing install's behaviour does not change
     * silently -- callers pass the tool set that was previously hardcoded (today:
     * AgentHandler.DEFAULT_ALLOWED_TOOLS). An explicitly-persisted EMPTY set (the user turned
     * every tool off) is distinct from "never set" and is returned as empty, not replaced by
     * [defaultIfUnset].
     */
    fun loadAllowedTools(context: Context, defaultIfUnset: Set<String>): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_ALLOWED_TOOLS, null)
        val tools = when {
            stored == null -> defaultIfUnset
            stored.isEmpty() -> emptySet()
            else -> stored.split(ALLOWED_TOOLS_DELIMITER).toSet()
        }
        _allowedTools.value = tools
        return tools
    }

    fun setAllowedTools(context: Context, tools: Set<String>) {
        _allowedTools.value = tools
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_ALLOWED_TOOLS, tools.joinToString(ALLOWED_TOOLS_DELIMITER)).apply()
    }

    /**
     * Returns the persisted API key, generating and persisting a new 32-byte
     * base64url key on first access.
     */
    fun apiKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_API_KEY, null)
        if (existing != null) return existing

        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_API_KEY, key).apply()
        return key
    }

    fun regenerateApiKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val key = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs.edit().putString(KEY_API_KEY, key).apply()

        // The live server (if any) captured the old key once at start() and won't see this
        // write. Nudge the service so it rebinds with the new key -- see
        // OpenAiServer.checkConfig / OpenAiServerService.onStartCommand.
        if (isRunning.value) {
            OpenAiServerService.startService(context.applicationContext)
        }
        return key
    }

    /** First 6 hex chars of SHA-256(key) -- a short, non-secret way to tell keys apart in the UI. */
    fun fingerprint(key: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }.take(6)
    }
}
