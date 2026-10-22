package com.my.kizzy.data.rpc

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.preference.Prefs
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameTelemetryManager @Inject constructor(
    private val client: HttpClient,
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null

    private var currentGameName: String? = null
    private var currentApplicationId: String? = null
    private var sessionStartTime: Long = 0L
    private val accountAnalyticsTokens = ConcurrentHashMap<String, String>()
    private val accountSessionIds = ConcurrentHashMap<String, String>()
    private val activeQuestIds = ConcurrentHashMap<String, MutableSet<String>>()

    companion object {
        private const val TAG = "GameTelemetry"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.165 Chrome/134.0.6998.205 Electron/35.4.0 Safari/537.36"
        private const val SUPER_PROPERTIES =
            "eyJvcyI6IkxpbnV4IiwiYnJvd3NlciI6IkRpc2NvcmQgQ2xpZW50IiwicmVsZWFzZV9jaGFubmVsIjoic3RhYmxlIiwiY2xpZW50X3ZlcnNpb24iOiIxLjAuMTY1Iiwib3NfdmVyc2lvbiI6IjYuNi4xMzcrIiwib3NfYXJjaCI6Ing2NCIsImFwcF9hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFgxMTsgTGludXggeDg2XzY0KSBBcHBsZVdlYktpdC81MzcuMzYgKEtIVE1MLCBsaWtlIEdlY2tvKSBkaXNjb3JkLzEuMC4xNjUgQ2hyb21lLzEzNC4wLjY5OTguMjA1IEVsZWN0cm9uLzM1LjQuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMzUuNC4wIiwiY2xpZW50X2J1aWxkX251bWJlciI6NjQzMTg2LCJjbGllbnRfZXZlbnRfc291cmNlIjpudWxsLCJjbGllbnRfYXBwX3N0YXRlIjoiZm9jdXNlZCIsImhhc19jbGllbnRfbW9kcyI6ZmFsc2V9"
    }

    fun start(gameName: String, applicationId: String) {
        if (applicationId.isBlank()) return

        val tokens = Prefs.getActiveRpcTokens()
        if (tokens.isEmpty()) return

        currentGameName = gameName
        currentApplicationId = applicationId
        sessionStartTime = System.currentTimeMillis()

        logger.i(TAG, "Starting game telemetry tracking for '$gameName' (ID: $applicationId)")

        trackingJob?.cancel()
        trackingJob = scope.launch {
            tokens.forEach { token ->
                launch {
                    trackForAccount(token, gameName, applicationId)
                }
            }
        }
    }

    private suspend fun trackForAccount(token: String, gameName: String, applicationId: String) {
        var analyticsToken = accountAnalyticsTokens[token]
        if (analyticsToken == null) {
            try {
                val userResp = client.get("https://discord.com/api/v9/users/@me?with_analytics_token=true") {
                    header(HttpHeaders.Authorization, token)
                    header("User-Agent", USER_AGENT)
                    header("X-Super-Properties", SUPER_PROPERTIES)
                }
                if (userResp.status.value in 200..299) {
                    val json = userResp.body<JsonObject>()
                    analyticsToken = json["analytics_token"]?.jsonPrimitive?.content
                    analyticsToken?.let { accountAnalyticsTokens[token] = it }
                }
            } catch (e: Exception) {
                logger.w(TAG, "Failed to fetch analytics token: ${e.message}")
            }
        }

        val sessionId = UUID.randomUUID().toString()
        accountSessionIds[token] = sessionId
        var seq = 1
        var isInitial = true
        var lastHeartbeatTime = System.currentTimeMillis()

        while (trackingJob?.isActive == true) {
            val now = System.currentTimeMillis()
            val durationTracked = if (isInitial) 0L else (now - lastHeartbeatTime)
            lastHeartbeatTime = now

            // 1. Send running_game_heartbeat to /api/v9/science
            sendScienceHeartbeat(
                token = token,
                analyticsToken = analyticsToken,
                sessionId = sessionId,
                seq = seq++,
                applicationId = applicationId,
                gameName = gameName,
                isInitial = isInitial,
                isFinal = false,
                durationTrackedMs = durationTracked
            )
            isInitial = false

            // 2. Check and progress active Quests
            progressQuests(token, applicationId, terminal = false)

            delay(30_000)
        }
    }

    private suspend fun sendScienceHeartbeat(
        token: String,
        analyticsToken: String?,
        sessionId: String,
        seq: Int,
        applicationId: String,
        gameName: String,
        isInitial: Boolean,
        isFinal: Boolean,
        durationTrackedMs: Long
    ) {
        if (analyticsToken.isNullOrBlank()) return
        try {
            val payload = buildJsonObject {
                put("token", analyticsToken)
                putJsonArray("events") {
                    add(buildJsonObject {
                        put("type", "running_game_heartbeat")
                        putJsonObject("properties") {
                            put("client_track_timestamp", System.currentTimeMillis())
                            put("client_heartbeat_session_id", sessionId)
                            put("event_sequence_number", seq)
                            put("game_id", applicationId)
                            put("game_name", gameName)
                            put("game_detection_enabled", true)
                            put("initial_heartbeat", isInitial)
                            put("final_heartbeat", isFinal)
                            put("game_session_id", sessionId)
                            put("duration_tracked_ms", durationTrackedMs)
                            put("discord_window_state", "focused")
                        }
                    })
                }
            }

            val scienceResp = client.post("https://discord.com/api/v9/science") {
                header(HttpHeaders.Authorization, token)
                header("User-Agent", USER_AGENT)
                header("X-Super-Properties", SUPER_PROPERTIES)
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            logger.d(TAG, "Heartbeat sent to /science (initial=$isInitial, final=$isFinal), status: ${scienceResp.status.value}")
        } catch (e: Exception) {
            logger.e(TAG, "Error sending heartbeat to science: ${e.message}")
        }
    }

    private suspend fun progressQuests(token: String, applicationId: String, terminal: Boolean) {
        try {
            val questsResp = client.get("https://discord.com/api/v9/quests/@me") {
                header(HttpHeaders.Authorization, token)
                header("User-Agent", USER_AGENT)
                header("X-Super-Properties", SUPER_PROPERTIES)
            }
            if (questsResp.status.value in 200..299) {
                val questsObj = questsResp.body<JsonObject>()
                val questsArray = questsObj["quests"]?.jsonArray
                val set = activeQuestIds.getOrPut(token) { ConcurrentHashMap.newKeySet() }

                questsArray?.forEach { questEl ->
                    val quest = questEl.jsonObject
                    val questId = quest["id"]?.jsonPrimitive?.content
                    val targetAppId = quest["config"]?.jsonObject?.get("application_id")?.jsonPrimitive?.content
                        ?: quest["application_id"]?.jsonPrimitive?.content

                    if (questId != null && (targetAppId == null || targetAppId == applicationId)) {
                        set.add(questId)
                        val bodyPayload = buildJsonObject {
                            put("stream_key", null as String?)
                            put("application_id", applicationId)
                            put("terminal", terminal)
                            put("executable_path", null as String?)
                            put("executable_fingerprint", null as String?)
                        }
                        val questHbResp = client.post("https://discord.com/api/v9/quests/$questId/heartbeat") {
                            header(HttpHeaders.Authorization, token)
                            header("User-Agent", USER_AGENT)
                            header("X-Super-Properties", SUPER_PROPERTIES)
                            contentType(ContentType.Application.Json)
                            setBody(bodyPayload.toString())
                        }
                        logger.i(TAG, "Quest heartbeat sent for quest $questId (terminal=$terminal), status: ${questHbResp.status.value}")
                    }
                }
            }
        } catch (e: Exception) {
            logger.d(TAG, "Quests progress skipped: ${e.message}")
        }
    }

    suspend fun stop() {
        val appId = currentApplicationId
        val gameName = currentGameName
        val startTime = sessionStartTime
        val tokens = Prefs.getActiveRpcTokens()

        if (!appId.isNullOrBlank() && !gameName.isNullOrBlank() && tokens.isNotEmpty()) {
            val elapsed = System.currentTimeMillis() - startTime
            logger.i(TAG, "Performing graceful stop for '$gameName' (total duration: ${elapsed}ms)")

            tokens.forEach { token ->
                val analyticsToken = accountAnalyticsTokens[token]
                val sessionId = accountSessionIds[token] ?: UUID.randomUUID().toString()

                // Final heartbeat to /science
                sendScienceHeartbeat(
                    token = token,
                    analyticsToken = analyticsToken,
                    sessionId = sessionId,
                    seq = 9999,
                    applicationId = appId,
                    gameName = gameName,
                    isInitial = false,
                    isFinal = true,
                    durationTrackedMs = elapsed
                )

                // Terminal heartbeat to active Quests
                val questIds = activeQuestIds[token]
                questIds?.forEach { questId ->
                    try {
                        val bodyPayload = buildJsonObject {
                            put("stream_key", null as String?)
                            put("application_id", appId)
                            put("terminal", true)
                            put("executable_path", null as String?)
                            put("executable_fingerprint", null as String?)
                        }
                        client.post("https://discord.com/api/v9/quests/$questId/heartbeat") {
                            header(HttpHeaders.Authorization, token)
                            header("User-Agent", USER_AGENT)
                            header("X-Super-Properties", SUPER_PROPERTIES)
                            contentType(ContentType.Application.Json)
                            setBody(bodyPayload.toString())
                        }
                    } catch (e: Exception) {
                        logger.e(TAG, "Error sending terminal quest heartbeat: ${e.message}")
                    }
                }
            }
        }

        trackingJob?.cancel()
        trackingJob = null
        activeQuestIds.clear()
        currentGameName = null
        currentApplicationId = null
        logger.i(TAG, "Game telemetry tracking stopped gracefully.")
    }
}
