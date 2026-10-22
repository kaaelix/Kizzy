package kizzy.gateway

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.interfaces.NoOpLogger
import kizzy.gateway.entities.presence.Presence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class MultiDiscordWebSocket(
    private val tokenSupplier: () -> List<String>,
    private val logger: Logger = NoOpLogger
) : DiscordWebSocket {

    constructor(tokens: List<String>, logger: Logger = NoOpLogger) : this(
        tokenSupplier = { tokens },
        logger = logger
    )

    private val socketMap = mutableMapOf<String, DiscordWebSocketImpl>()
    private var parentJob = SupervisorJob()
    private var currentPresence: Presence? = null

    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Default

    @Synchronized
    private fun syncSockets(): List<DiscordWebSocketImpl> {
        val currentTokens = tokenSupplier().filter { it.isNotBlank() }.distinct()

        // Remove sockets whose tokens are no longer active
        val iterator = socketMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in currentTokens) {
                try {
                    entry.value.close()
                } catch (e: Exception) {
                    logger.e("MultiGateway", "Error closing removed socket: ${e.message}")
                }
                iterator.remove()
            }
        }

        // Add new sockets for newly active tokens
        for (token in currentTokens) {
            if (!socketMap.containsKey(token)) {
                val newSocket = DiscordWebSocketImpl(token, logger)
                socketMap[token] = newSocket
                currentPresence?.let { p ->
                    launch { newSocket.sendActivity(p) }
                }
            }
        }
        return socketMap.values.toList()
    }

    override suspend fun connect() {
        if (parentJob.isCancelled) {
            parentJob = SupervisorJob()
        }
        val sockets = syncSockets()
        logger.i("MultiGateway", "Connecting ${sockets.size} account(s)")
        sockets.forEach { socket ->
            launch {
                try {
                    socket.connect()
                } catch (e: Exception) {
                    logger.e("MultiGateway", "Failed to connect socket: ${e.message}")
                }
            }
        }
    }

    override suspend fun sendActivity(presence: Presence) {
        currentPresence = presence
        val sockets = syncSockets()
        logger.i("MultiGateway", "Broadcasting activity to ${sockets.size} account(s)")
        sockets.forEach { socket ->
            launch {
                try {
                    socket.sendActivity(presence)
                } catch (e: Exception) {
                    logger.e("MultiGateway", "Failed to send activity: ${e.message}")
                }
            }
        }
    }

    override fun isWebSocketConnected(): Boolean {
        return socketMap.values.isNotEmpty() && socketMap.values.any { it.isWebSocketConnected() }
    }

    override suspend fun clearActivity() {
        currentPresence = null
        val sockets = syncSockets()
        logger.i("MultiGateway", "Clearing presence activities across ${sockets.size} account(s)")
        sockets.forEach { socket ->
            try {
                socket.clearActivity()
            } catch (e: Exception) {
                logger.e("MultiGateway", "Failed to clear activity: ${e.message}")
            }
        }
    }

    override fun close() {
        logger.i("MultiGateway", "Closing ${socketMap.size} socket(s)")
        socketMap.values.forEach {
            try {
                it.close()
            } catch (e: Exception) {
                logger.e("MultiGateway", "Error closing socket: ${e.message}")
            }
        }
        socketMap.clear()
        parentJob.cancel()
        parentJob = SupervisorJob()
    }
}
