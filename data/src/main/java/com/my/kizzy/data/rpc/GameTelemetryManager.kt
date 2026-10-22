package com.my.kizzy.data.rpc

import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.repository.KizzyRepository
import com.my.kizzy.preference.Prefs
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private var currentGameName: String? = null
    private var currentApplicationId: String? = null
    private var sessionStartTime: Long = 0L
    private val accountAnalyticsTokens = ConcurrentHashMap<String, String>()
    private val accountSessionIds = ConcurrentHashMap<String, String>()
    // Map of token -> (questId -> targetApplicationId)
    private val activeQuestTargets = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
    // Set of token -> Set of questId where console/start was activated
    private val activeConsoleQuests = ConcurrentHashMap<String, MutableSet<String>>()

    companion object {
        private const val TAG = "GameTelemetry"
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.165 Chrome/134.0.6998.205 Electron/35.4.0 Safari/537.36"
        private const val SUPER_PROPERTIES =
            "eyJvcyI6IkxpbnV4IiwiYnJvd3NlciI6IkRpc2NvcmQgQ2xpZW50IiwicmVsZWFzZV9jaGFubmVsIjoic3RhYmxlIiwiY2xpZW50X3ZlcnNpb24iOiIxLjAuMTY1Iiwib3NfdmVyc2lvbiI6IjYuNi4xMzcrIiwib3NfYXJjaCI6Ing2NCIsImFwcF9hcmNoIjoieDY0Iiwic3lzdGVtX2xvY2FsZSI6ImVuLVVTIiwiYnJvd3Nlcl91c2VyX2FnZW50IjoiTW96aWxsYS81LjAgKFgxMTsgTGludXggeDg2XzY0KSBBcHBsZVdlYktpdC81MzcuMzYgKEtIVE1MLCBsaWtlIEdlY2tvKSBkaXNjb3JkLzEuMC4xNjUgQ2hyb21lLzEzNC4wLjY5OTguMjA1IEVsZWN0cm9uLzM1LjQuMCBTYWZhcmkvNTM3LjM2IiwiYnJvd3Nlcl92ZXJzaW9uIjoiMzUuNC4wIiwiY2xpZW50X2J1aWxkX251bWJlciI6NjQzMTg2LCJjbGllbnRfZXZlbnRfc291cmNlIjpudWxsLCJjbGllbnRfYXBwX3N0YXRlIjoiZm9jdXNlZCIsImhhc19jbGllbnRfbW9kcyI6ZmFsc2V9"

        val KNOWN_GAME_IDS = mapOf(
            "call of duty: modern warfare 4" to "1511443377927880775",
            "call of duty: modern warfare iv" to "1511443377927880775",
            "call of duty modern warfare 4" to "1511443377927880775",
            "modern warfare 4" to "1511443377927880775",
            "call of duty" to "1445181071103627284",
            "call of duty: warzone" to "1445181071103627284",
            "call of duty: modern warfare" to "1445181071103627284",
            "call of duty: black ops 6" to "1445181071103627284",
            "league of legends" to "401518687463948290",
            "valorant" to "700136079562375258",
            "genshin impact" to "762434991303950386",
            "honkai: star rail" to "1098522646399008809",
            "honkai star rail" to "1098522646399008809",
            "minecraft" to "432980957394370572",
            "fortnite" to "402016428784189440",
            "counter strike 2" to "710189746684821535",
            "counter-strike 2" to "710189746684821535",
            "grand theft auto v" to "438029511594835978",
            "gta v" to "438029511594835978",
            "apex legends" to "542387796919910400",
            "overwatch 2" to "356875221078245376",
            "roblox" to "435553657014648832",
            "cyberpunk 2077" to "543886561237893120",
            "rocket league" to "437672437637840896",
            "dead by daylight" to "438058223945416715",
            "tom clancy's rainbow six siege" to "438057999818588160",
            "rainbow six siege" to "438057999818588160",
            "warframe" to "438075677941727232",
            "destiny 2" to "438059737409552384",
            "dota 2" to "438058444456755200",
            "world of warcraft" to "356875936882262016",
            "the sims 4" to "438059049443295243",
            "monster hunter: world" to "473919632168583168",
            "the witcher 3: wild hunt" to "438059424883441664",
            "rust" to "438058694072369152",
            "terraria" to "438059287310532608",
            "among us" to "478881268478443520",
            "elden ring" to "940286821269667850",
            "black myth: wukong" to "1275811059471716393",
            "helldivers 2" to "1203063806294724648",
            "palworld" to "1197621457855078501"
        )
    }

    fun start(gameName: String, applicationId: String) {
        val tokens = Prefs.getActiveRpcTokens()
        if (tokens.isEmpty()) return

        val oldGameName = currentGameName
        val oldAppId = currentApplicationId
        val oldStartTime = sessionStartTime

        currentGameName = gameName
        currentApplicationId = applicationId
        sessionStartTime = System.currentTimeMillis()

        logger.i(TAG, "Starting game telemetry tracking for '$gameName' (raw ID: $applicationId)")

        trackingJob?.cancel()
        trackingJob = scope.launch {
            // If switching from another game, gracefully finalize previous session first
            if (!oldGameName.isNullOrBlank() && !oldAppId.isNullOrBlank() &&
                (oldGameName != gameName || oldAppId != applicationId)) {
                finalizeGameSession(oldGameName, oldAppId, oldStartTime, tokens)
            }

            val resolvedAppId = resolveApplicationId(gameName, applicationId)
            logger.i(TAG, "Resolved application ID for '$gameName': $resolvedAppId")

            tokens.forEach { token ->
                launch {
                    trackForAccount(token, gameName, resolvedAppId)
                }
            }
        }
    }

    private suspend fun finalizeGameSession(
        gameName: String,
        appId: String,
        startTime: Long,
        tokens: List<String>
    ) {
        val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1000L)
        logger.i(TAG, "Finalizing session for previous game '$gameName' (duration: ${elapsed}ms)")
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

            // Stop active console sessions for previous game
            val consoleQuests = activeConsoleQuests[token]
            consoleQuests?.forEach { questId ->
                try {
                    client.post("https://discord.com/api/v9/quests/$questId/console/stop") {
                        header(HttpHeaders.Authorization, token)
                        header("User-Agent", USER_AGENT)
                        header("X-Super-Properties", SUPER_PROPERTIES)
                        header("X-Discord-Locale", "en-US")
                        header("X-Discord-Timezone", "UTC")
                        contentType(ContentType.Application.Json)
                    }
                } catch (e: Exception) {
                    logger.d(TAG, "console/stop failed for quest $questId: ${e.message}")
                }
            }
            consoleQuests?.clear()

            // Terminal heartbeat to active Quests for previous game
            val targetsMap = activeQuestTargets[token]
            targetsMap?.forEach { (questId, targetAppId) ->
                try {
                    val bodyPayload = buildJsonObject {
                        put("application_id", targetAppId)
                        put("terminal", true)
                    }
                    client.post("https://discord.com/api/v9/quests/$questId/heartbeat") {
                        header(HttpHeaders.Authorization, token)
                        header("User-Agent", USER_AGENT)
                        header("X-Super-Properties", SUPER_PROPERTIES)
                        header("X-Discord-Locale", "en-US")
                        header("X-Discord-Timezone", "UTC")
                        contentType(ContentType.Application.Json)
                        setBody(bodyPayload.toString())
                    }
                } catch (e: Exception) {
                    logger.d(TAG, "Terminal quest heartbeat failed: ${e.message}")
                }
            }
        }
        activeQuestTargets.clear()
        activeConsoleQuests.clear()
    }

    private suspend fun resolveApplicationId(gameName: String, rawAppId: String): String {
        if (rawAppId.isNotBlank() && rawAppId.all { it.isDigit() } && rawAppId != Constants.APPLICATION_ID) {
            return rawAppId
        }
        val cleanName = cleanGameName(gameName)
        KNOWN_GAME_IDS[cleanName]?.let { return it }
        val expanded = expandAbbreviations(cleanName)
        KNOWN_GAME_IDS[expanded]?.let { return it }

        runCatching {
            val games = repository.getGames()
            val found = games.firstOrNull { it.game_title.equals(gameName, ignoreCase = true) }
                ?: games.firstOrNull { it.game_title.contains(gameName, ignoreCase = true) }
                ?: games.firstOrNull { cleanGameName(it.game_title) == cleanName }
                ?: games.firstOrNull { isMatchingGame(gameName, it.game_title) }
            val candidate = found?.application_id
            if (!candidate.isNullOrBlank() && candidate.all { it.isDigit() }) {
                return candidate
            }
        }
        return rawAppId.takeIf { it.isNotBlank() && it.all { c -> c.isDigit() } } ?: Constants.APPLICATION_ID
    }

    private suspend fun trackForAccount(token: String, gameName: String, applicationId: String) {
        var analyticsToken = accountAnalyticsTokens[token]
        if (analyticsToken == null) {
            try {
                val userResp = client.get("https://discord.com/api/v9/users/@me?with_analytics_token=true") {
                    header(HttpHeaders.Authorization, token)
                    header("User-Agent", USER_AGENT)
                    header("X-Super-Properties", SUPER_PROPERTIES)
                    header("X-Discord-Locale", "en-US")
                    header("X-Discord-Timezone", "UTC")
                }
                if (userResp.status.value in 200..299) {
                    val rawText = userResp.bodyAsText()
                    val json = jsonParser.parseToJsonElement(rawText).jsonObject
                    analyticsToken = json["analytics_token"]?.jsonPrimitive?.content
                    analyticsToken?.let {
                        accountAnalyticsTokens[token] = it
                        logger.i(TAG, "Successfully fetched analytics token for account: ${token.take(8)}...")
                    }
                } else {
                    logger.w(TAG, "Failed fetching analytics token, HTTP ${userResp.status.value}")
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
        if (analyticsToken.isNullOrBlank()) {
            logger.w(TAG, "Skipping science heartbeat: analyticsToken is missing")
            return
        }
        try {
            val now = System.currentTimeMillis()
            val payload = buildJsonObject {
                put("token", analyticsToken)
                putJsonArray("events") {
                    add(buildJsonObject {
                        put("type", "running_game_heartbeat")
                        putJsonObject("properties") {
                            put("client_track_timestamp", now)
                            put("client_send_timestamp", now)
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
                header("X-Discord-Locale", "en-US")
                header("X-Discord-Timezone", "UTC")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            logger.i(TAG, "Heartbeat sent to /science (initial=$isInitial, final=$isFinal, duration=${durationTrackedMs}ms, game='$gameName', id=$applicationId), status: ${scienceResp.status.value}")
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
                header("X-Discord-Locale", "en-US")
                header("X-Discord-Timezone", "UTC")
            }
            if (questsResp.status.value in 200..299) {
                val rawText = questsResp.bodyAsText()
                val parsedEl = jsonParser.parseToJsonElement(rawText)
                val questsArray = when (parsedEl) {
                    is JsonObject -> parsedEl["quests"]?.jsonArray
                    is JsonArray -> parsedEl
                    else -> null
                }
                val targetsMap = activeQuestTargets.getOrPut(token) { ConcurrentHashMap() }
                val consoleQuests = activeConsoleQuests.getOrPut(token) { ConcurrentHashMap.newKeySet() }

                questsArray?.forEach { questEl ->
                    val quest = questEl.jsonObject
                    val questId = quest["id"]?.jsonPrimitive?.content ?: return@forEach

                    val userStatus = quest["user_status"]?.jsonObject
                    val completedAt = userStatus?.get("completed_at")?.jsonPrimitive?.content
                    val claimedAt = userStatus?.get("claimed_at")?.jsonPrimitive?.content
                    if (completedAt != null) {
                        // If quest is completed but not claimed yet, try auto-claiming reward!
                        if (claimedAt.isNullOrBlank()) {
                            try {
                                val claimPayload = buildJsonObject {
                                    put("platform", 0)
                                    put("location", "quest_home")
                                }
                                val claimResp = client.post("https://discord.com/api/v9/quests/$questId/claim-reward") {
                                    header(HttpHeaders.Authorization, token)
                                    header("User-Agent", USER_AGENT)
                                    header("X-Super-Properties", SUPER_PROPERTIES)
                                    header("X-Discord-Locale", "en-US")
                                    header("X-Discord-Timezone", "UTC")
                                    contentType(ContentType.Application.Json)
                                    setBody(claimPayload.toString())
                                }
                                logger.i(TAG, "Quest $questId auto-claimed reward status: ${claimResp.status.value}, body: ${claimResp.bodyAsText()}")
                            } catch (e: Exception) {
                                logger.w(TAG, "Quest $questId reward claim attempt skipped: ${e.message}")
                            }
                        }
                        targetsMap.remove(questId)
                        consoleQuests.remove(questId)
                        return@forEach
                    }

                    // Extract candidate application IDs and game titles from quest
                    val candidateAppIds = mutableListOf<String>()
                    val candidateGameNames = mutableListOf<String>()
                    val desktopAppIds = mutableListOf<String>()

                    val config = quest["config"]?.jsonObject
                    config?.get("application_id")?.jsonPrimitive?.content?.let { candidateAppIds.add(it) }
                    quest["application_id"]?.jsonPrimitive?.content?.let { candidateAppIds.add(it) }

                    config?.get("messages")?.jsonObject?.get("game_title")?.jsonPrimitive?.content?.let {
                        candidateGameNames.add(it)
                    }
                    config?.get("messages")?.jsonObject?.get("quest_name")?.jsonPrimitive?.content?.let {
                        candidateGameNames.add(it)
                    }

                    // Parse task_config_v2 tasks
                    val taskConfigV2 = config?.get("task_config_v2")?.jsonObject
                    val tasks = taskConfigV2?.get("tasks")?.jsonObject
                    tasks?.forEach { (taskKey, taskEl) ->
                        val taskObj = runCatching { taskEl.jsonObject }.getOrNull()
                        val apps = taskObj?.get("applications")?.jsonArray
                        val isDesktopTask = taskKey.contains("desktop", ignoreCase = true) ||
                                            taskKey.contains("play", ignoreCase = true)
                        apps?.forEach { appEl ->
                            val appObj = runCatching { appEl.jsonObject }.getOrNull()
                            appObj?.get("id")?.jsonPrimitive?.content?.let { id ->
                                candidateAppIds.add(id)
                                if (isDesktopTask) desktopAppIds.add(id)
                            }
                            appObj?.get("name")?.jsonPrimitive?.content?.let { name ->
                                candidateGameNames.add(name)
                            }
                        }
                    }

                    // For Call of Duty quests, explicitly associate both detectable IDs
                    if (candidateGameNames.any { it.contains("call of duty", ignoreCase = true) || it.contains("modern warfare", ignoreCase = true) }) {
                        candidateAppIds.add("1511443377927880775")
                        candidateAppIds.add("1445181071103627284")
                        desktopAppIds.add("1511443377927880775")
                    }

                    // Check if current running game matches this quest using smart fuzzy matching
                    val matchesId = applicationId.isNotBlank() && candidateAppIds.contains(applicationId)
                    val matchesName = candidateGameNames.any { isMatchingGame(gameName, it) }

                    if (matchesId || matchesName) {
                        // Official desktop app ID prioritized so Discord validates the heartbeat
                        val targetAppId = if (matchesId) {
                            applicationId
                        } else {
                            desktopAppIds.firstOrNull() ?: candidateAppIds.firstOrNull() ?: applicationId
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
                                    header("X-Discord-Locale", "en-US")
                                    header("X-Discord-Timezone", "UTC")
                                    contentType(ContentType.Application.Json)
                                    setBody(enrollPayload.toString())
                                }
                                logger.i(TAG, "Quest $questId auto-enrolled with status: ${enrollResp.status.value}")
                            } catch (e: Exception) {
                                logger.w(TAG, "Failed to auto-enroll quest $questId: ${e.message}")
                            }
                        }

                        // Activate console session if quest supports cross-platform / console
                        if (!consoleQuests.contains(questId)) {
                            try {
                                val consoleResp = client.post("https://discord.com/api/v9/quests/$questId/console/start") {
                                    header(HttpHeaders.Authorization, token)
                                    header("User-Agent", USER_AGENT)
                                    header("X-Super-Properties", SUPER_PROPERTIES)
                                    header("X-Discord-Locale", "en-US")
                                    header("X-Discord-Timezone", "UTC")
                                    contentType(ContentType.Application.Json)
                                }
                                consoleQuests.add(questId)
                                logger.i(TAG, "Quest $questId console/start called with status: ${consoleResp.status.value}")
                            } catch (e: Exception) {
                                logger.d(TAG, "Quest $questId console/start ignored: ${e.message}")
                            }
                        }

                        // Send quest heartbeat (clean payload matching Discord desktop client)
                        val bodyPayload = buildJsonObject {
                            put("application_id", targetAppId)
                            put("terminal", terminal)
                        }
                        val questHbResp = client.post("https://discord.com/api/v9/quests/$questId/heartbeat") {
                            header(HttpHeaders.Authorization, token)
                            header("User-Agent", USER_AGENT)
                            header("X-Super-Properties", SUPER_PROPERTIES)
                            header("X-Discord-Locale", "en-US")
                            header("X-Discord-Timezone", "UTC")
                            contentType(ContentType.Application.Json)
                            setBody(bodyPayload.toString())
                        }
                        val hbStatus = questHbResp.status.value
                        val hbBody = runCatching { questHbResp.bodyAsText() }.getOrDefault("")
                        logger.i(TAG, "Quest heartbeat sent for quest $questId (targetAppId=$targetAppId, terminal=$terminal), status: $hbStatus, body: $hbBody")

                        // If heartbeat returned completion, trigger reward claim immediately
                        if (hbBody.contains("completed_at") && !hbBody.contains("\"completed_at\":null")) {
                            logger.i(TAG, "Quest $questId completed! Triggering auto-claim...")
                            try {
                                val claimPayload = buildJsonObject {
                                    put("platform", 0)
                                    put("location", "quest_home")
                                }
                                val claimResp = client.post("https://discord.com/api/v9/quests/$questId/claim-reward") {
                                    header(HttpHeaders.Authorization, token)
                                    header("User-Agent", USER_AGENT)
                                    header("X-Super-Properties", SUPER_PROPERTIES)
                                    header("X-Discord-Locale", "en-US")
                                    header("X-Discord-Timezone", "UTC")
                                    contentType(ContentType.Application.Json)
                                    setBody(claimPayload.toString())
                                }
                                logger.i(TAG, "Quest $questId claim reward status: ${claimResp.status.value}, body: ${claimResp.bodyAsText()}")
                            } catch (e: Exception) {
                                logger.w(TAG, "Failed claiming quest $questId: ${e.message}")
                            }
                        }
                    }
                }
            } else {
                logger.w(TAG, "Fetching quests/@me returned status: ${questsResp.status.value}")
            }
        } catch (e: Exception) {
            logger.e(TAG, "Quests progress error: ${e.message}")
        }
    }

    private fun isMatchingGame(runningGame: String, candidateTitle: String): Boolean {
        if (runningGame.isBlank() || candidateTitle.isBlank()) return false
        val cleanA = expandAbbreviations(cleanGameName(runningGame))
        val cleanB = expandAbbreviations(cleanGameName(candidateTitle))

        if (cleanA == cleanB) return true
        if (cleanA.contains(cleanB) || cleanB.contains(cleanA)) return true

        val stopWords = setOf(
            "the", "a", "an", "of", "and", "in", "on", "for", "to", "edition",
            "remastered", "bundle", "game", "online", "client"
        )
        val tokensA = cleanA.split(" ").filter { it.length > 1 && it !in stopWords }.toSet()
        val tokensB = cleanB.split(" ").filter { it.length > 1 && it !in stopWords }.toSet()

        if (tokensA.isNotEmpty() && tokensB.isNotEmpty()) {
            val intersection = tokensA.intersect(tokensB)
            if (tokensA.contains("call") && tokensB.contains("call") &&
                tokensA.contains("duty") && tokensB.contains("duty")
            ) {
                return true
            }
            if (intersection.any { it.length >= 5 }) return true
            if (intersection.size >= 2) return true
            if (intersection.size.toDouble() / tokensA.size >= 0.5 ||
                intersection.size.toDouble() / tokensB.size >= 0.5
            ) {
                return true
            }
        }
        return false
    }

    private fun cleanGameName(name: String): String {
        return name.lowercase()
            .replace(Regex("[®™©:;!?\"'\",_\\-\\.\\[\\]\\(\\)]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun expandAbbreviations(name: String): String {
        val map = mapOf(
            "cod" to "call of duty",
            "lol" to "league of legends",
            "cs2" to "counter strike",
            "csgo" to "counter strike",
            "r6" to "rainbow six",
            "r6s" to "rainbow six",
            "gta" to "grand theft auto",
            "gta5" to "grand theft auto",
            "gtav" to "grand theft auto",
            "mc" to "minecraft",
            "hsr" to "honkai star rail",
            "genshin" to "genshin impact",
            "destiny" to "destiny 2",
            "fortnite" to "fortnite",
            "apex" to "apex legends",
            "valorant" to "valorant",
            "pubg" to "pubg battlegrounds",
            "warframe" to "warframe"
        )
        val tokens = name.split(" ")
        return tokens.joinToString(" ") { map[it] ?: it }
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

                // Stop active console sessions
                val consoleQuests = activeConsoleQuests[token]
                consoleQuests?.forEach { questId ->
                    try {
                        client.post("https://discord.com/api/v9/quests/$questId/console/stop") {
                            header(HttpHeaders.Authorization, token)
                            header("User-Agent", USER_AGENT)
                            header("X-Super-Properties", SUPER_PROPERTIES)
                            header("X-Discord-Locale", "en-US")
                            header("X-Discord-Timezone", "UTC")
                            contentType(ContentType.Application.Json)
                        }
                    } catch (e: Exception) {
                        logger.d(TAG, "console/stop failed for quest $questId: ${e.message}")
                    }
                }
                consoleQuests?.clear()

                // Terminal heartbeat to active Quests
                val targetsMap = activeQuestTargets[token]
                targetsMap?.forEach { (questId, targetAppId) ->
                    try {
                        val bodyPayload = buildJsonObject {
                            put("application_id", targetAppId)
                            put("terminal", true)
                        }
                        client.post("https://discord.com/api/v9/quests/$questId/heartbeat") {
                            header(HttpHeaders.Authorization, token)
                            header("User-Agent", USER_AGENT)
                            header("X-Super-Properties", SUPER_PROPERTIES)
                            header("X-Discord-Locale", "en-US")
                            header("X-Discord-Timezone", "UTC")
                            contentType(ContentType.Application.Json)
                            setBody(bodyPayload.toString())
                        }
                        logger.i(TAG, "Sent terminal heartbeat for quest $questId")
                    } catch (e: Exception) {
                        logger.e(TAG, "Error sending terminal quest heartbeat: ${e.message}")
                    }
                }
                targetsMap?.clear()
            }
        }

        trackingJob?.cancel()
        trackingJob = null
        activeQuestTargets.clear()
        activeConsoleQuests.clear()
        currentGameName = null
        currentApplicationId = null
        logger.i(TAG, "Game telemetry tracking stopped gracefully.")
    }
}
