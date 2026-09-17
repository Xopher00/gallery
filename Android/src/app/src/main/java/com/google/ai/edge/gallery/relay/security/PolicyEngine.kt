package com.google.ai.edge.gallery.relay.security

object PolicyEngine {

    // Moved verbatim from AgentHandler.kt:93-109 (do not reorder or rename entries).
    internal val ALL_MOBILE_ACTION_TOOLS =
        setOf(
            "turnOnFlashlight",
            "turnOffFlashlight",
            "openWifiSettings",
            "openBluetoothSettings",
            "openSoundSettings",
            "dialNumber",
            "sendSms",
            "sendEmail",
            "createContact",
            "createCalendarEvent",
            "setAlarm",
            "setTimer",
            "openUrl",
            "getCurrentDateTime",
        )

    // Moved verbatim from AgentHandler.kt:120.
    internal val RISKY_TOOLS = setOf("dialNumber", "sendSms", "openUrl")

    // Moved verbatim from AgentHandler.kt:139-146.
    internal val DEFAULT_ALLOWED_TOOLS =
        setOf(
            "turnOnFlashlight",
            "turnOffFlashlight",
            "openWifiSettings",
            "openBluetoothSettings",
            "openSoundSettings",
        )

    // In-app tools whose side effects warrant asking before the first use of each, unlike the rest
    // of the in-app tool surface which has always run unprompted.
    internal val LOCAL_TOOLS_REQUIRING_CONFIRMATION = setOf("runJs", "runIntent")

    init {
        check(RISKY_TOOLS.none { it in DEFAULT_ALLOWED_TOOLS }) {
            "a risky tool must never be seeded into the default allow-set"
        }
    }

    enum class Surface { HTTP_AGENT_RUN, IN_APP_LOCAL_TOOL, IN_APP_MCP }

    sealed class Operation {
        data class ExecuteTool(val toolName: String) : Operation()
    }

    sealed class Decision {
        object Allow : Decision()
        data class Deny(val reason: String) : Decision()
        object RequireUserConfirmation : Decision()
    }

    // litertlm's ReflectionTool emits snake_case tool names while the sets above hold camelCase
    // Kotlin @Tool method names, so compare normalised on both sides (moved from
    // AgentHandler.kt:161-169's normalizeToolName/NORMALIZED_DEFAULT_ALLOWED_TOOLS).
    private fun normalizeToolName(name: String): String = name.lowercase().replace("_", "")

    private val normalizedDefaultAllowedTools: Set<String> =
        DEFAULT_ALLOWED_TOOLS.map(::normalizeToolName).toSet()

    private fun isToolAllowed(toolName: String, allowedTools: Set<String>): Boolean =
        if (allowedTools === DEFAULT_ALLOWED_TOOLS) {
            normalizeToolName(toolName) in normalizedDefaultAllowedTools
        } else {
            normalizeToolName(toolName) in allowedTools.map(::normalizeToolName).toSet()
        }

    // Pure core: no Android or Ktor import, unit-tested directly.
    fun decide(
        surface: Surface,
        operation: Operation,
        allowedTools: Set<String>,
        userAlreadyAllowed: Boolean,
    ): Decision =
        when (surface) {
            Surface.HTTP_AGENT_RUN ->
                when (operation) {
                    is Operation.ExecuteTool ->
                        if (isToolAllowed(operation.toolName, allowedTools)) {
                            Decision.Allow
                        } else {
                            Decision.Deny("Tool '${operation.toolName}' is not in the allowed set")
                        }
                }
            Surface.IN_APP_MCP ->
                if (userAlreadyAllowed) Decision.Allow else Decision.RequireUserConfirmation
            Surface.IN_APP_LOCAL_TOOL ->
                when (operation) {
                    is Operation.ExecuteTool ->
                        if (operation.toolName !in LOCAL_TOOLS_REQUIRING_CONFIRMATION) {
                            Decision.Allow
                        } else if (userAlreadyAllowed) {
                            Decision.Allow
                        } else {
                            Decision.RequireUserConfirmation
                        }
                }
        }

    // Thin wrapper: reads the one persisted fact this engine needs, then delegates to the pure
    // core above. Every real call site uses this overload, not the one above directly.
    fun decide(
        context: android.content.Context,
        surface: Surface,
        operation: Operation,
        userAlreadyAllowed: Boolean,
    ): Decision {
        val allowedTools =
            when (surface) {
                Surface.HTTP_AGENT_RUN -> {
                    val repo =
                        dagger.hilt.android.EntryPointAccessors.fromApplication(
                                context.applicationContext,
                                com.google.ai.edge.gallery.data.DataStoreRepositoryEntryPoint::class
                                    .java,
                            )
                            .dataStoreRepository()
                    com.google.ai.edge.gallery.relay.server.ServerRuntime.loadAllowedTools(
                        repo,
                        defaultIfUnset = DEFAULT_ALLOWED_TOOLS,
                    )
                }
                else -> emptySet()
            }
        return decide(surface, operation, allowedTools, userAlreadyAllowed)
    }
}
