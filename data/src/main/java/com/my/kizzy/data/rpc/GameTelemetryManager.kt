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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val JsonElement?.asObj: JsonObject?
    get() = this as? JsonObject

private val JsonElement?.asArr: JsonArray?
    get() = this as? JsonArray

private val JsonElement?.asStr: String?
    get() = (this as? JsonPrimitive)?.takeIf { this !is JsonNull }?.content

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

        trackingJob?.cancel()
        trackingJob = scope.launch {
            val resolvedAppId = resolveApplicationId(gameName, applicationId)
            currentGameName = gameName
            currentApplicationId = resolvedAppId
            sessionStartTime = System.currentTimeMillis()

            logger.i(TAG, "Starting game telemetry tracking for '$gameName' (raw ID: $applicationId, resolved ID: $resolvedAppId)")

            // If switching from another game, gracefully finalize previous session first
            if (!oldGameName.isNullOrBlank() && !oldAppId.isNullOrBlank() &&
                (oldGameName != gameName || oldAppId != resolvedAppId)) {
                finalizeGameSession(oldGameName, oldAppId, oldStartTime, tokens)
            }

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
                ?: games.firstOrNull { isExactOrPrefixMatch(gameName, it.game_title) }
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
                    val json = jsonParser.parseToJsonElement(rawText).asObj
                    analyticsToken = json?.get("analytics_token").asStr
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

        // 1. Send launch_game event to register game in Discord's library and increment UNIQUE_GAMES_PLAYED & DAYS_PLAYED
        sendLaunchGame(
            token = token,
            analyticsToken = analyticsToken,
            sessionId = sessionId,
            seq = seq++,
            applicationId = applicationId,
            gameName = gameName
        )

        while (trackingJob?.isActive == true) {
            val now = System.currentTimeMillis()
            val durationTracked = if (isInitial) 0L else (now - lastHeartbeatTime)
            lastHeartbeatTime = now

            // 2. Send running_game_heartbeat to /api/v9/science
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

            // 3. Check, auto-enroll, and progress matching Quests
            progressQuests(token, gameName, applicationId, terminal = false)

            // Discord desktop client heartbeat interval is strictly 60 seconds (f.A.Millis.MINUTE)
            delay(60_000)
        }
    }

    private suspend fun sendLaunchGame(
        token: String,
        analyticsToken: String?,
        sessionId: String,
        seq: Int,
        applicationId: String,
        gameName: String
    ) {
        if (analyticsToken.isNullOrBlank()) {
            logger.w(TAG, "Skipping launch_game: analyticsToken is missing")
            return
        }
        try {
            val now = System.currentTimeMillis()
            val payload = buildJsonObject {
                put("token", analyticsToken)
                putJsonArray("events") {
                    add(buildJsonObject {
                        put("type", "launch_game")
                        putJsonObject("properties") {
                            put("client_track_timestamp", now)
                            put("client_send_timestamp", now)
                            put("client_heartbeat_session_id", sessionId)
                            put("event_sequence_number", seq)
                            put("game", gameName)
                            put("game_name", gameName)
                            put("game_id", applicationId)
                            put("verified", true)
                            put("elevated", false)
                            put("is_launcher", false)
                            put("game_platform", "desktop")
                            put("detection_method", "verified_game")
                            put("is_overlay_enabled", false)
                            put("is_overlay_game_enabled", false)
                            put("activity_status_enabled", true)
                            put("current_user_status", "online")
                            put("game_detection_enabled", true)
                            put("game_session_id", sessionId)
                            put("discord_window_state", "focused")
                        }
                    })
                }
            }

            val resp = client.post("https://discord.com/api/v9/science") {
                header(HttpHeaders.Authorization, token)
                header("User-Agent", USER_AGENT)
                header("X-Super-Properties", SUPER_PROPERTIES)
                header("X-Discord-Locale", "en-US")
                header("X-Discord-Timezone", "UTC")
                contentType(ContentType.Application.Json)
                setBody(payload.toString())
            }
            logger.i(TAG, "Event launch_game sent to /science (game='$gameName', id=$applicationId, session=$sessionId), status: ${resp.status.value}")
        } catch (e: Exception) {
            logger.e(TAG, "Error sending launch_game to science: ${e.message}")
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
                    is JsonObject -> parsedEl["quests"].asArr
                    is JsonArray -> parsedEl
                    else -> null
                }
                val targetsMap = activeQuestTargets.getOrPut(token) { ConcurrentHashMap() }

                questsArray?.forEach { questEl ->
                    val quest = questEl.asObj ?: return@forEach
                    val questId = quest["id"].asStr ?: return@forEach

                    val userStatus = quest["user_status"].asObj
                    val completedAt = userStatus?.get("completed_at").asStr
                    if (completedAt != null) {
                        logger.i(TAG, "Quest $questId is already completed (completedAt=$completedAt). Ready for manual claim in Discord.")
                        targetsMap.remove(questId)
                        return@forEach
                    }

                    // Extract candidate application IDs and game titles from quest
                    val candidateAppIds = mutableListOf<String>()
                    val candidateGameNames = mutableListOf<String>()
                    val desktopAppIds = mutableListOf<String>()

                    val config = quest["config"].asObj
                    config?.get("application_id").asStr?.let { candidateAppIds.add(it) }
                    quest["application_id"].asStr?.let { candidateAppIds.add(it) }

                    val messages = config?.get("messages").asObj
                    messages?.get("game_title").asStr?.let { candidateGameNames.add(it) }

                    // Parse task_config_v2 tasks
                    val taskConfigV2 = config?.get("task_config_v2").asObj
                    val tasks = taskConfigV2?.get("tasks").asObj
                    tasks?.forEach { (taskKey, taskEl) ->
                        val taskObj = taskEl.asObj
                        val apps = taskObj?.get("applications").asArr
                        val isDesktopTask = taskKey.contains("desktop", ignoreCase = true) ||
                                            taskKey.contains("play", ignoreCase = true)
                        apps?.forEach { appEl ->
                            val appObj = appEl.asObj
                            appObj?.get("id").asStr?.let { id ->
                                candidateAppIds.add(id)
                                if (isDesktopTask) desktopAppIds.add(id)
                            }
                            appObj?.get("name").asStr?.let { name ->
                                candidateGameNames.add(name)
                            }
                        }
                    }

                    // For Call of Duty quests, explicitly associate both detectable IDs
                    if (candidateGameNames.any { isCodGame(it) }) {
                        candidateAppIds.add("1511443377927880775")
                        candidateAppIds.add("1445181071103627284")
                        desktopAppIds.add("1511443377927880775")
                    }

                    val isCodRunning = isCodGame(gameName) || applicationId == "1511443377927880775" || applicationId == "1445181071103627284"
                    val isCodQuest = candidateGameNames.any { isCodGame(it) } ||
                                     candidateAppIds.contains("1511443377927880775") ||
                                     candidateAppIds.contains("1445181071103627284")

                    val matchesId = applicationId.isNotBlank() && candidateAppIds.contains(applicationId)
                    val matchesCod = isCodRunning && isCodQuest
                    val matchesName = candidateGameNames.any { isExactOrPrefixMatch(gameName, it) }

                    val isMatch = matchesId || matchesCod || matchesName

                    logger.i(TAG, "Quest $questId: titles=$candidateGameNames, appIds=$candidateAppIds, match=$isMatch")

                    if (isMatch) {
                        val targetAppId = when {
                            matchesCod -> {
                                if (candidateAppIds.contains("1511443377927880775")) "1511443377927880775"
                                else if (candidateAppIds.contains("1445181071103627284")) "1445181071103627284"
                                else desktopAppIds.firstOrNull() ?: candidateAppIds.firstOrNull() ?: applicationId
                            }
                            matchesId -> applicationId
                            else -> desktopAppIds.firstOrNull() ?: candidateAppIds.firstOrNull() ?: applicationId
                        }

                        targetsMap[questId] = targetAppId

                        // Check enrollment: if user_status or enrolled_at is null, auto-enroll first!
                        val enrolledAt = userStatus?.get("enrolled_at").asStr
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

                        // If heartbeat returned completion, quest is 100% finished and ready for manual claim in Discord
                        if (hbBody.contains("completed_at") && !hbBody.contains("\"completed_at\":null")) {
                            logger.i(TAG, "Quest $questId reached 100% completion! Ready for user to claim reward safely inside Discord client.")
                            targetsMap.remove(questId)
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

    private fun isCodGame(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("call of duty") || lower.contains("modern warfare") || lower.contains("black ops") || lower.contains("warzone")
    }

    private fun isExactOrPrefixMatch(runningGame: String, candidateTitle: String): Boolean {
        if (runningGame.isBlank() || candidateTitle.isBlank()) return false
        val cleanA = cleanGameName(runningGame)
        val cleanB = cleanGameName(candidateTitle)
        if (cleanA == cleanB) return true
        if (cleanA.startsWith(cleanB) || cleanB.startsWith(cleanA)) return true
        val expA = expandAbbreviations(cleanA)
        val expB = expandAbbreviations(cleanB)
        if (expA == expB) return true
        if (expA.startsWith(expB) || expB.startsWith(expA)) return true
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
        currentGameName = null
        currentApplicationId = null
        logger.i(TAG, "Game telemetry tracking stopped gracefully.")
    }
}
