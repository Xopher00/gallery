package com.google.ai.edge.gallery.openai

import com.google.ai.edge.gallery.data.DataStoreRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

object ServerRuntime {
    const val DEFAULT_PORT = 8080
    const val BIND_HOST = "127.0.0.1"

    enum class BindMode { LOOPBACK, LAN, INTERFACE }

    data class InterfaceInfo(
        val name: String,
        val displayName: String,
        val ipv4: String,
    )

    var runningServer: OpenAiServer? = null

    enum class ServerState { STOPPED, STARTING, READY, STOPPING }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    // Derived (== READY), not an independently-written boolean, so it can never disagree with
    // serverState -- transitionTo() is the only writer of both.
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _localUrl = MutableStateFlow<String?>(null)
    val localUrl: StateFlow<String?> = _localUrl.asStateFlow()

    private val _liveBoundHost = MutableStateFlow<String?>(null)
    val liveBoundHost: StateFlow<String?> = _liveBoundHost.asStateFlow()

    // local/liveBoundHost default to their current value so re-marking READY on an
    // already-running server doesn't clobber a value it didn't re-derive.
    private fun transitionTo(
        state: ServerState,
        local: String? = _localUrl.value,
        liveBoundHost: String? = _liveBoundHost.value,
    ) {
        _serverState.value = state
        _isRunning.value = state == ServerState.READY
        _localUrl.value = local
        _liveBoundHost.value = liveBoundHost
    }

    fun markStarting() = transitionTo(ServerState.STARTING)

    fun markReady(local: String, liveBoundHost: String? = _liveBoundHost.value) =
        transitionTo(ServerState.READY, local = local, liveBoundHost = liveBoundHost)

    fun markStopping() = transitionTo(ServerState.STOPPING, local = null, liveBoundHost = null)

    fun markStopped() = transitionTo(ServerState.STOPPED, local = null, liveBoundHost = null)

    suspend fun awaitReady(timeoutMs: Long): OpenAiServer? {
        val reachedReady = withTimeoutOrNull(timeoutMs) {
            serverState.first { it == ServerState.READY }
        }
        return if (reachedReady != null) runningServer else null
    }

    private val _bindMode = MutableStateFlow(BindMode.LOOPBACK)
    val bindMode = _bindMode.asStateFlow()

    private val _selectedInterfaceName = MutableStateFlow<String?>(null)
    val selectedInterfaceName = _selectedInterfaceName.asStateFlow()

    private val _bindError = MutableStateFlow<String?>(null)
    val bindError = _bindError.asStateFlow()

    // Last address INTERFACE mode actually bound to, for checkInterfaceDrift's comparison.
    private val _boundInterfaceAddress = MutableStateFlow<String?>(null)

    private val _allowedTools = MutableStateFlow<Set<String>?>(null)
    val allowedTools = _allowedTools.asStateFlow()

    fun bindHost(): String {
        val mode = _bindMode.value
        val ifaceName = _selectedInterfaceName.value
        return try {
            val host = BindConfig.bindHost(mode, ifaceName)
            _bindError.value = null
            if (mode == BindMode.INTERFACE) _boundInterfaceAddress.value = host
            host
        } catch (e: IllegalStateException) {
            _bindError.value = e.message
            throw e
        }
    }

    fun listAvailableInterfaces(): List<InterfaceInfo> = BindConfig.listAvailableInterfaces()

    fun checkInterfaceDrift(): Boolean {
        val error = BindConfig.checkInterfaceDrift(
            mode = _bindMode.value,
            ifaceName = _selectedInterfaceName.value,
            boundAddress = _boundInterfaceAddress.value,
        )
        _bindError.value = error
        return error == null
    }

    fun refreshBindError(): String? {
        val error = BindConfig.validateBindReady(_bindMode.value, _selectedInterfaceName.value)
        _bindError.value = error
        return error
    }

    fun loadBindMode(repo: DataStoreRepository): BindMode {
        val stored = repo.readServerBindMode()
        val mode = stored?.let { runCatching { BindMode.valueOf(it) }.getOrNull() } ?: BindMode.LOOPBACK
        _bindMode.value = mode
        return mode
    }

    fun setBindMode(repo: DataStoreRepository, mode: BindMode) {
        _bindMode.value = mode
        repo.saveServerBindMode(mode.name)
    }

    fun loadSelectedInterfaceName(repo: DataStoreRepository): String? {
        val stored = repo.readServerSelectedInterfaceName()
        _selectedInterfaceName.value = stored
        return stored
    }

    fun setSelectedInterfaceName(repo: DataStoreRepository, name: String?) {
        _selectedInterfaceName.value = name
        repo.saveServerSelectedInterfaceName(name ?: "")
    }

    // No has-been-set bit on the proto field, so empty (never-set or explicit all-off) falls
    // back to defaultIfUnset -- an explicit all-off no longer survives a process restart.
    fun loadAllowedTools(repo: DataStoreRepository, defaultIfUnset: Set<String>): Set<String> {
        val tools = repo.readServerAllowedTools().ifEmpty { defaultIfUnset }
        _allowedTools.value = tools
        return tools
    }

    fun setAllowedTools(repo: DataStoreRepository, tools: Set<String>) {
        _allowedTools.value = tools
        repo.saveServerAllowedTools(tools)
    }
}
