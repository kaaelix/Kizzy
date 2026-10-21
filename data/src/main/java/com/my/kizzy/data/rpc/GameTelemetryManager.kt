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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GameTelemetryManager @Inject constructor(
    private val client: HttpClient,
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null

    companion object {
        private const val TAG = "GameTelemetry"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.9150 Chrome/128.0.6613.186 Electron/32.2.0 Safari/537.36"
        private const val SUPER_PROPERTIES =
            "eyJvcyI6IldpbmRvd3MiLCJicm93c2VyIjoiRGlzY29yZCBDbGllbnQiLCJyZWxlYXNlX2NoYW5uZWwiOiJzdGFibGUiLCJjbGllbnRfdmVyc2lvbiI6IjEuMC45MTUwIiwib3NfdmVyc2lvbiI6IjEwLjAuMTkwNDUiLCJvc19hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiY2xpZW50X2J1aWxkX251bWJlciI6MzQwMDAwfQ=="
    }

    fun start(gameName: String, applicationId: String) {
        if (applicationId.isBlank()) return
        stop()

        val tokens = Prefs.getActiveRpcTokens()
        if (tokens.isEmpty()) return

        logger.i(TAG, "Starting game telemetry tracking for '$gameName' (ID: $applicationId)")

        trackingJob = scope.launch {
            tokens.forEach { token ->
                launch {
                    trackForAccount(token, gameName, applicationId)
                }
            }
        }
    }

    private suspend fun trackForAccount(token: String, gameName: String, applicationId: String) {
        var analyticsToken: String? = null
        try {
            val userResp = client.get("https://discord.com/api/v9/users/@me?with_analytics_token=true") {
                header(HttpHeaders.Authorization, token)
                header("User-Agent", USER_AGENT)
                header("X-Super-Properties", SUPER_PROPERTIES)
            }
            if (userResp.status.value in 200..299) {
                val json = userResp.body<JsonObject>()
                analyticsToken = json["analytics_token"]?.jsonPrimitive?.content
            }
        } catch (e: Exception) {
            logger.w(TAG, "Failed to fetch analytics token: ${e.message}")
        }

        val sessionId = UUID.randomUUID().toString()
        var seq = 1

        while (trackingJob?.isActive == true) {
            // 1. Send running_game_heartbeat to /api/v9/science
            if (!analyticsToken.isNullOrBlank()) {
                try {
                    val payload = buildJsonObject {
                        put("token", analyticsToken)
                        putJsonArray("events") {
                            add(buildJsonObject {
                                put("type", "running_game_heartbeat")
                                putJsonObject("properties") {
                                    put("client_track_timestamp", System.currentTimeMillis())
                                    put("client_heartbeat_session_id", sessionId)
                                    put("event_sequence_number", seq++)
                                    put("game_id", applicationId)
                                    put("game_name", gameName)
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
                    logger.d(TAG, "Heartbeat sent to /science, status: ${scienceResp.status.value}")
                } catch (e: Exception) {
                    logger.e(TAG, "Error sending heartbeat to science: ${e.message}")
                }
            }

            // 2. Check and progress active Quests
            try {
                val questsResp = client.get("https://discord.com/api/v9/quests/@me") {
                    header(HttpHeaders.Authorization, token)
                    header("User-Agent", USER_AGENT)
                    header("X-Super-Properties", SUPER_PROPERTIES)
                }
                if (questsResp.status.value in 200..299) {
                    val questsObj = questsResp.body<JsonObject>()
                    val questsArray = questsObj["quests"]?.jsonArray
                    questsArray?.forEach { questEl ->
                        val quest = questEl.jsonObject
                        val questId = quest["id"]?.jsonPrimitive?.content
                        val targetAppId = quest["config"]?.jsonObject?.get("application_id")?.jsonPrimitive?.content
                            ?: quest["application_id"]?.jsonPrimitive?.content

                        if (questId != null && (targetAppId == null || targetAppId == applicationId)) {
                            val questHbResp = client.post("https://discord.com/api/v9/quests/$questId/heartbeat") {
                                header(HttpHeaders.Authorization, token)
                                header("User-Agent", USER_AGENT)
                                header("X-Super-Properties", SUPER_PROPERTIES)
                                contentType(ContentType.Application.Json)
                                setBody("{\"terminal\":false}")
                            }
                            logger.i(TAG, "Quest heartbeat sent for quest $questId, status: ${questHbResp.status.value}")
                        }
                    }
                }
            } catch (e: Exception) {
                logger.d(TAG, "Quests check skipped: ${e.message}")
            }

            delay(30_000)
        }
    }

    fun stop() {
        trackingJob?.cancel()
        trackingJob = null
        logger.i(TAG, "Game telemetry tracking stopped.")
    }
}
