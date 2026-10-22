package kizzy.gateway

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.interfaces.NoOpLogger
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kizzy.gateway.entities.Heartbeat
import kizzy.gateway.entities.Identify.Companion.toIdentifyPayload
import kizzy.gateway.entities.OutgoingPayload
import kizzy.gateway.entities.Payload
import kizzy.gateway.entities.PayloadData
import kizzy.gateway.entities.Ready
import kizzy.gateway.entities.Resume
import kizzy.gateway.entities.op.OpCode
import kizzy.gateway.entities.op.OpCode.*
import kizzy.gateway.entities.presence.Presence
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

open class DiscordWebSocketImpl(
    private val token: String,
    private val logger: Logger = NoOpLogger
) : DiscordWebSocket {
    private val gatewayUrl = "wss://gateway.discord.gg/?v=10&encoding=json"
    private var websocket: DefaultClientWebSocketSession? = null
    private var sequence = 0
    private var sessionId: String? = null
    private var heartbeatInterval = 0L
    private var resumeGatewayUrl: String? = null
    private var heartbeatJob: Job? = null
    private var connectionJob: Job? = null
    private var connected = false
    private var isExplicitClose = false
    private var currentPresence: Presence? = null
    private var awaitingHeartbeatAck = false
    private var reconnectAttempts = 0

    private var parentJob = SupervisorJob()

    private var client: HttpClient = HttpClient {
        install(WebSockets) {
            pingInterval = 25_000
        }
    }
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val coroutineContext: CoroutineContext
        get() = parentJob + Dispatchers.Default

    override suspend fun connect() {
        if (parentJob.isCancelled) {
            parentJob = SupervisorJob()
        }
        isExplicitClose = false
        connectionJob?.cancel()
        connectionJob = launch {
            try {
                logger.i("Gateway", "Connecting to Gateway...")
                val url = resumeGatewayUrl ?: gatewayUrl
                websocket = client.webSocketSession(url)

                // start receiving messages
                websocket!!.incoming.receiveAsFlow()
                    .collect { frame ->
                        when (frame) {
                            is Frame.Text -> {
                                val jsonString = frame.readText()
                                onMessage(jsonString)
                            }
                            else -> {}
                        }
                    }
                handleClose()
            } catch (e: Exception) {
                logger.e("Gateway", "Connection error: ${e.message}")
                handleClose()
            }
        }
    }

    private suspend fun handleClose() {
        heartbeatJob?.cancel()
        connected = false
        awaitingHeartbeatAck = false
        val close = websocket?.closeReason?.await()
        val code = close?.code?.toInt()
        val reason = close?.message
        logger.w("Gateway", "Closed with code: $code, reason: $reason, isExplicitClose: $isExplicitClose")

        if (isExplicitClose) {
            return
        }

        // Fatal codes where reconnection must not be attempted
        val fatalCodes = setOf(4004, 4010, 4011, 4012, 4013, 4014)
        if (code in fatalCodes) {
            logger.e("Gateway", "Fatal close code $code received, terminating session")
            close()
        } else {
            val backoff = (1500L * (1 shl reconnectAttempts.coerceAtMost(3))).coerceAtMost(10_000L)
            reconnectAttempts++
            logger.i("Gateway", "Recoverable disconnect (code: $code). Reconnecting in ${backoff}ms (attempt $reconnectAttempts)...")
            delay(backoff.milliseconds)
            if (!isExplicitClose && isActive) {
                connect()
            }
        }
    }

    private suspend fun onMessage(jsonString: String) {
        val payload = json.decodeFromString<Payload>(jsonString)
        logger.d("Gateway", "Received op:${payload.op}, seq:${payload.s}, event :${payload.t}")

        payload.s?.let {
            sequence = it
        }
        when (payload.op) {
            DISPATCH -> payload.handleDispatch(jsonString)
            HEARTBEAT -> sendHeartBeat()
            RECONNECT -> reconnectWebSocket()
            INVALID_SESSION -> handleInvalidSession()
            HELLO -> handleHello(jsonString)
            HEARTBEAT_ACK -> {
                awaitingHeartbeatAck = false
                logger.d("Gateway", "Received Heartbeat ACK (OP 11)")
            }
            else -> {}
        }
    }

    open fun Payload.handleDispatch(jsonString: String) {
        when (this.t.toString()) {
            "READY" -> {
                val ready = decodePayloadData<Ready>(jsonString) ?: return
                sessionId = ready.sessionId
                resumeGatewayUrl = ready.resumeGatewayUrl + "/?v=10&encoding=json"
                logger.i("Gateway", "resume_gateway_url updated to $resumeGatewayUrl")
                logger.i("Gateway", "session_id updated to $sessionId")
                connected = true
                reconnectAttempts = 0

                // Re-broadcast presence on READY to ensure Discord never loses the active game session
                currentPresence?.let { p ->
                    launch {
                        delay(200.milliseconds)
                        if (isSocketConnectedToAccount()) {
                            logger.i("Gateway", "Re-sending presence after READY dispatch")
                            send(op = PRESENCE_UPDATE, d = p)
                        }
                    }
                }
                return
            }
            "RESUMED" -> {
                logger.i("Gateway", "Session Resumed")
                connected = true
                reconnectAttempts = 0
                currentPresence?.let { p ->
                    launch {
                        delay(100.milliseconds)
                        if (isSocketConnectedToAccount()) {
                            logger.i("Gateway", "Re-sending presence after RESUMED dispatch")
                            send(op = PRESENCE_UPDATE, d = p)
                        }
                    }
                }
            }
            else -> {}
        }
    }

    private suspend inline fun handleInvalidSession() {
        logger.i("Gateway", "Handling Invalid Session")
        logger.d("Gateway", "Sending Identify after 150ms")
        delay(150)
        sendIdentify()
    }

    private suspend inline fun handleHello(jsonString: String) {
        if (sequence > 0 && !sessionId.isNullOrBlank()) {
            sendResume()
        } else {
            sendIdentify()
        }
        heartbeatInterval = decodePayloadData<Heartbeat>(jsonString)?.heartbeatInterval ?: return
        logger.i("Gateway", "Setting heartbeatInterval= $heartbeatInterval")
        startHeartbeatJob(heartbeatInterval)
    }

    protected fun decodeReady(jsonString: String): Ready? {
        return decodePayloadData(jsonString)
    }

    private inline fun <reified T> decodePayloadData(jsonString: String): T? {
        return json.decodeFromString<PayloadData<T>>(jsonString).d
    }

    private suspend fun sendHeartBeat() {
        logger.i("Gateway", "Sending $HEARTBEAT with seq: $sequence")
        send(
            op = HEARTBEAT,
            d = if (sequence == 0) "null" else sequence.toString(),
        )
    }

    private suspend inline fun reconnectWebSocket() {
        websocket?.close(
            CloseReason(
                code = 4000,
                message = "Attempting to reconnect"
            )
        )
    }

    private suspend fun sendIdentify() {
        logger.i("Gateway", "Sending $IDENTIFY")
        send(
            op = IDENTIFY,
            d = token.toIdentifyPayload()
        )
    }

    private suspend fun sendResume() {
        logger.i("Gateway", "Sending $RESUME")
        send(
            op = RESUME,
            d = Resume(
                seq = sequence,
                sessionId = sessionId,
                token = token
            )
        )
    }

    private fun startHeartbeatJob(interval: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = launch {
            awaitingHeartbeatAck = false
            // Jitter for first heartbeat per Discord Gateway protocol
            val initialDelay = (interval * kotlin.random.Random.nextFloat()).toLong().coerceAtLeast(100L)
            delay(initialDelay)
            while (isActive) {
                if (awaitingHeartbeatAck) {
                    logger.w("Gateway", "Heartbeat ACK not received in time (zombie socket detected). Reconnecting...")
                    awaitingHeartbeatAck = false
                    reconnectWebSocket()
                    break
                }
                awaitingHeartbeatAck = true
                sendHeartBeat()
                delay(interval)
            }
        }
    }

    private fun isSocketConnectedToAccount(): Boolean {
        return connected && websocket?.isActive == true
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isWebSocketConnected(): Boolean {
        return websocket?.incoming != null && websocket?.outgoing?.isClosedForSend == false
    }

    private suspend inline fun <reified T> send(op: OpCode, d: T?) {
        if (websocket?.isActive == true) {
            val payload = json.encodeToString(
                OutgoingPayload(
                    op = op,
                    d = d,
                )
            )
            websocket?.send(Frame.Text(payload))
        }
    }

    override fun close() {
        isExplicitClose = true
        reconnectAttempts = 0
        awaitingHeartbeatAck = false
        heartbeatJob?.cancel()
        heartbeatJob = null
        connectionJob?.cancel()
        connectionJob = null
        currentPresence = null
        resumeGatewayUrl = null
        sessionId = null
        connected = false
        runBlocking {
            try {
                websocket?.close()
            } catch (_: Exception) {}
            logger.e("Gateway", "Connection to gateway closed explicitly")
        }
        parentJob.cancel()
        parentJob = SupervisorJob()
    }

    override suspend fun sendActivity(presence: Presence) {
        currentPresence = presence
        while (!isSocketConnectedToAccount() && !isExplicitClose) {
            delay(20.milliseconds)
        }
        if (isSocketConnectedToAccount()) {
            logger.i("Gateway", "Sending $PRESENCE_UPDATE")
            send(
                op = PRESENCE_UPDATE,
                d = presence
            )
        }
    }

    override suspend fun clearActivity() {
        currentPresence = null
        if (isSocketConnectedToAccount()) {
            logger.i("Gateway", "Clearing presence activities (graceful stop for Recent Activity)")
            send(
                op = PRESENCE_UPDATE,
                d = Presence(
                    activities = emptyList(),
                    afk = false,
                    since = 0L,
                    status = "online"
                )
            )
        }
    }
}
