package com.my.kizzy.data.rpc

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.repository.KizzyRepository
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
    private val repository: KizzyRepository,
    private val logger: Logger
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null

    private var currentGameName: String? = null
    private var currentApplicationId: String? = null
    private var sessionStartTime: Long = 0L
    private val accountAnalyticsTokens = ConcurrentHashMap<String, String>()
    private val accountSessionIds = ConcurrentHashMap<String, String>()
    // Map of token -> (questId -> targetApplicationId)
    private val activeQuestTargets = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()

    companion object {
        private const val TAG = "GameTelemetry"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.165 Chrome/134.0.6998.205 Electron/35.4.0 Safari/537.36"
        private const val SUPER_PROPERTIES =
            "eyJvcyI6IkxpbnV4IiwiYnJvd3NlciI6IkRpc2NvcmQgQ2xpZW50IiwicmVsZWFzZV9jaGFubmVsIjoic3RhYmxlIiwiY2xpZW50X3ZlcnNpb24iOiIxLjAuMTY1Iiwib3NfdmVyc2lvbiI6IjYuNi4xMzcrIiwib3NfYXJjaCI6Ing2NCIsImFwcF9hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFgxMTsgTGludXggeDg2XzY0KSBBcHBsZVdlYktpdC81MzcuMzYgKEtIVE1MLCBsaWtlIEdlY2tvKSBkaXNjb3JkLzEuMC4xNjUgQ2hyb21lLzEzNC4wLjY5OTguMjA1IEVsZWN0cm9uLzM1LjQuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMzUuNC4wIiwiY2xpZW50X2J1aWxkX251bWJlciI6NjQzMTg2LCJjbGllbnRfZXZlbnRfc291cmNlIjpudWxsLCJjbGllbnRfYXBwX3N0YXRlIjoiZm9jdXNlZCIsImhhc19jbGllbnRfbW9kcyI6ZmFsc2V9"
    }

    fun start(gameName: String, applicationId: String) {
        val tokens = Prefs.getActiveRpcTokens()
        if (tokens.isEmpty()) return

        currentGameName = gameName
        currentApplicationId = applicationId
        sessionStartTime = System.currentTimeMillis()

        logger.i(TAG, "Starting game telemetry tracking for '$gameName' (raw ID: $applicationId)")

        trackingJob?.cancel()
        trackingJob = scope.launch {
            val resolvedAppId = resolveApplicationId(gameName, applicationId)
            logger.i(TAG, "Resolved application ID for '$gameName': $resolvedAppId")

            tokens.forEach { token ->
                launch {
                    trackForAccount(token, gameName, resolvedAppId)
                }
            }
        }
    }

    private suspend fun resolveApplicationId(gameName: String, rawAppId: String): String {
        if (rawAppId.isNotBlank() && rawAppId.all { it.isDigit() }) {
            return rawAppId
        }
        runCatching {
            val games = repository.getGames()
            val found = games.firstOrNull { it.game_title.equals(gameName, ignoreCase = true) }
                ?: games.firstOrNull { it.game_title.contains(gameName, ignoreCase = true) }
            val candidate = found?.application_id
            if (!candidate.isNullOrBlank() && candidate.all { it.isDigit() }) {
                return candidate
            }
        }
        return rawAppId
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

            // 2. Check, auto-enroll, and progress matching Quests
            progressQuests(token, gameName, applicationId, terminal = false)

            // Discord desktop client heartbeat interval is strictly 60 seconds (f.A.Millis.MINUTE)
            delay(60_000)
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
            logger.d(TAG, "Heartbeat sent to /science (initial=$isInitial, final=$isFinal, duration=${durationTrackedMs}ms), status: ${scienceResp.status.value}")
        } catch (e: Exception) {
            logger.e(TAG, "Error sending heartbeat to science: ${e.message}")
        }
    }

    private suspend fun progressQuests(token: String, gameName: String, applicationId: String, terminal: Boolean) {
        try {
            val questsResp = client.get("https://discord.com/api/v9/quests/@me") {
                header(HttpHeaders.Authorization, token)
                header("User-Agent", USER_AGENT)
                header("X-Super-Properties", SUPER_PROPERTIES)
            }
            if (questsResp.status.value in 200..299) {
                val questsObj = questsResp.body<JsonObject>()
                val questsArray = questsObj["quests"]?.jsonArray
                val targetsMap = activeQuestTargets.getOrPut(token) { ConcurrentHashMap() }

                questsArray?.forEach { questEl ->
                    val quest = questEl.jsonObject
                    val questId = quest["id"]?.jsonPrimitive?.content ?: return@forEach

                    val userStatus = quest["user_status"]?.jsonObject
                    val completedAt = userStatus?.get("completed_at")?.jsonPrimitive?.content
                    if (completedAt != null) {
                        // Already completed, skip
                        targetsMap.remove(questId)
                        return@forEach
                    }

                    // Extract candidate application IDs and game titles from quest
                    val candidateAppIds = mutableListOf<String>()
                    val candidateGameNames = mutableListOf<String>()

                    val config = quest["config"]?.jsonObject
                    config?.get("application_id")?.jsonPrimitive?.content?.let { candidateAppIds.add(it) }
                    quest["application_id"]?.jsonPrimitive?.content?.let { candidateAppIds.add(it) }

                    config?.get("messages")?.jsonObject?.get("game_title")?.jsonPrimitive?.content?.let {
                        candidateGameNames.add(it)
                    }

                    // Parse task_config_v2 tasks
                    val taskConfigV2 = config?.get("task_config_v2")?.jsonObject
                    val tasks = taskConfigV2?.get("tasks")?.jsonObject
                    tasks?.values?.forEach { taskEl ->
                        val taskObj = runCatching { taskEl.jsonObject }.getOrNull()
                        val apps = taskObj?.get("applications")?.jsonArray
                        apps?.forEach { appEl ->
                            val appObj = runCatching { appEl.jsonObject }.getOrNull()
                            appObj?.get("id")?.jsonPrimitive?.content?.let { candidateAppIds.add(it) }
                            appObj?.get("name")?.jsonPrimitive?.content?.let { candidateGameNames.add(it) }
                        }
                    }

                    // Check if current running game matches this quest
                    val matchesId = applicationId.isNotBlank() && candidateAppIds.contains(applicationId)
                    val matchesName = gameName.isNotBlank() && candidateGameNames.any { it.equals(gameName, ignoreCase = true) }

                    if (matchesId || matchesName) {
                        val targetAppId = if (matchesId) {
                            applicationId
                        } else {
                            candidateAppIds.firstOrNull() ?: applicationId
                        }

                        targetsMap[questId] = targetAppId

                        // Check enrollment: if user_status or enrolled_at is null, auto-enroll first!
                        val enrolledAt = userStatus?.get("enrolled_at")?.jsonPrimitive?.content
                        if (enrolledAt.isNullOrBlank()) {
                            try {
                                val enrollPayload = buildJsonObject {
                                    put("location", "running_activity")
                                }
                                val enrollResp = client.post("https://discord.com/api/v9/quests/$questId/enroll") {
                                    header(HttpHeaders.Authorization, token)
                                    header("User-Agent", USER_AGENT)
                                    header("X-Super-Properties", SUPER_PROPERTIES)
                                    contentType(ContentType.Application.Json)
                                    setBody(enrollPayload.toString())
                                }
                                logger.i(TAG, "Quest $questId auto-enrolled with status: ${enrollResp.status.value}")
                            } catch (e: Exception) {
                                logger.w(TAG, "Failed to auto-enroll quest $questId: ${e.message}")
                            }
                        }

                        // Send quest heartbeat
                        val bodyPayload = buildJsonObject {
                            put("stream_key", null as String?)
                            put("application_id", targetAppId)
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
                        logger.i(TAG, "Quest heartbeat sent for quest $questId (targetAppId=$targetAppId, terminal=$terminal), status: ${questHbResp.status.value}")
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
                val targetsMap = activeQuestTargets[token]
                targetsMap?.forEach { (questId, targetAppId) ->
                    try {
                        val bodyPayload = buildJsonObject {
                            put("stream_key", null as String?)
                            put("application_id", targetAppId)
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
                        logger.i(TAG, "Sent terminal heartbeat for quest $questId")
                    } catch (e: Exception) {
                        logger.e(TAG, "Error sending terminal quest heartbeat: ${e.message}")
                    }
                }
            }
        }

        trackingJob?.cancel()
        trackingJob = null
        activeQuestTargets.clear()
        currentGameName = null
        currentApplicationId = null
        logger.i(TAG, "Game telemetry tracking stopped gracefully.")
    }
}
