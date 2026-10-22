package kizzy.gateway.entities

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Identify(
    @SerialName("token")
    val token: String,
    @SerialName("capabilities")
    val capabilities: Int = 1734653,
    @SerialName("properties")
    val properties: IdentifyProperties = IdentifyProperties(),
    @SerialName("presence")
    val presence: IdentifyPresence = IdentifyPresence(),
    @SerialName("compress")
    val compress: Boolean = false,
    @SerialName("client_state")
    val clientState: IdentifyClientState = IdentifyClientState()
) {
    companion object {
        fun String.toIdentifyPayload() = Identify(
            token = this,
            capabilities = 1734653,
            properties = IdentifyProperties(),
            presence = IdentifyPresence(),
            compress = false,
            clientState = IdentifyClientState()
        )
    }
}

@Serializable
data class IdentifyProperties(
    @SerialName("os")
    val os: String = "Linux",
    @SerialName("browser")
    val browser: String = "Discord Client",
    @SerialName("release_channel")
    val releaseChannel: String = "stable",
    @SerialName("client_version")
    val clientVersion: String = "1.0.165",
    @SerialName("os_version")
    val osVersion: String = "6.6.137+",
    @SerialName("os_arch")
    val osArch: String = "x64",
    @SerialName("app_arch")
    val appArch: String = "x64",
    @SerialName("system_locale")
    val systemLocale: String = "en-US",
    @SerialName("browser_user_agent")
    val browserUserAgent: String = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) discord/1.0.165 Chrome/134.0.6998.205 Electron/35.4.0 Safari/537.36",
    @SerialName("browser_version")
    val browserVersion: String = "35.4.0",
    @SerialName("client_build_number")
    val clientBuildNumber: Int = 643186,
    @SerialName("client_event_source")
    val clientEventSource: String? = null,
    @SerialName("client_app_state")
    val clientAppState: String = "focused",
    @SerialName("has_client_mods")
    val hasClientMods: Boolean = false,
    @SerialName("is_fast_connect")
    val isFastConnect: Boolean = false
)

typealias Properties = IdentifyProperties

@Serializable
data class IdentifyPresence(
    @SerialName("status")
    val status: String = "online",
    @SerialName("since")
    val since: Long = 0L,
    @SerialName("activities")
    val activities: List<kizzy.gateway.entities.presence.Activity> = emptyList(),
    @SerialName("afk")
    val afk: Boolean = false
)

@Serializable
data class IdentifyClientState(
    @SerialName("guild_versions")
    val guildVersions: Map<String, Int> = emptyMap()
)
