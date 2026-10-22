/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * KizzyRepositoryImpl.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.data.repository

import com.my.kizzy.data.remote.ApiService
import com.my.kizzy.data.remote.GamesResponse
import com.my.kizzy.data.remote.ImgurApiService
import com.my.kizzy.data.remote.toGame
import com.my.kizzy.data.rpc.Constants
import com.my.kizzy.data.utils.toAttachmentAsset
import com.my.kizzy.data.utils.toExternalAsset
import com.my.kizzy.data.utils.toImageURL
import com.my.kizzy.domain.model.Contributor
import com.my.kizzy.domain.model.Game
import com.my.kizzy.domain.model.release.Release
import com.my.kizzy.domain.model.user.User
import com.my.kizzy.domain.repository.KizzyRepository
import com.my.kizzy.preference.Prefs
import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import java.io.File
import javax.inject.Inject

class KizzyRepositoryImpl @Inject constructor(
    private val api: ApiService,
    private val imgurApi: ImgurApiService,
) : KizzyRepository {

    override suspend fun getImage(url: String): String? {
        return if (Prefs[Prefs.USE_IMGUR, false]) {
            imgurApi.getImage(url, Prefs.getPrimaryToken()).getOrNull()?.toExternalAsset()
        } else {
            api.getImage(url).getOrNull()?.toAttachmentAsset()
        }
    }

    override suspend fun uploadImage(file: File): String? {
        return if (Prefs[Prefs.USE_IMGUR, false]) {
            imgurApi.uploadImage(file, Prefs[Prefs.IMGUR_CLIENT_ID, Constants.IMGUR_CLIENT_ID])
                .getOrNull()?.toImageURL()?.let { this.getImage(it) }
        } else {
            api.uploadImage(file).getOrNull()?.toAttachmentAsset()
        }
    }

    companion object {
        private var inMemoryGames: List<Game>? = null

        val POPULAR_GAMES = listOf(
            Game(
                game_title = "Call of Duty: Modern Warfare 4",
                application_id = "1511443377927880775",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/1511443377927880775/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Call of Duty",
                application_id = "1445181071103627284",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/1445181071103627284/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Valorant",
                application_id = "700136079562375258",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/700136079562375258/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "League of Legends",
                application_id = "401518687463948290",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/401518687463948290/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Genshin Impact",
                application_id = "762434991303950386",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/762434991303950386/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Honkai: Star Rail",
                application_id = "1098522646399008809",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/1098522646399008809/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Grand Theft Auto V",
                application_id = "438029511594835978",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/438029511594835978/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Minecraft",
                application_id = "432980957394370572",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/432980957394370572/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Roblox",
                application_id = "435553657014648832",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/435553657014648832/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Fortnite",
                application_id = "402016428784189440",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/402016428784189440/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Counter-Strike 2",
                application_id = "710189746684821535",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/710189746684821535/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Apex Legends",
                application_id = "542387796919910400",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/542387796919910400/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Overwatch 2",
                application_id = "356875221078245376",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/356875221078245376/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "Cyberpunk 2077",
                application_id = "543886561237893120",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/543886561237893120/a_default.png",
                small_image = ""
            ),
            Game(
                game_title = "ELDEN RING",
                application_id = "940286821269667850",
                platform = "",
                large_image = "https://cdn.discordapp.com/app-icons/940286821269667850/a_default.png",
                small_image = ""
            )
        )
    }

    override suspend fun getGames(): List<Game> {
        inMemoryGames?.let {
            if (it.isNotEmpty()) return it
        }
        val fetched = runCatching {
            api.getGames().getOrNull()?.body<List<GamesResponse>>()?.map { it.toGame() }
        }.getOrNull() ?: emptyList()

        if (fetched.isNotEmpty()) {
            inMemoryGames = fetched
            return fetched
        }
        return POPULAR_GAMES
    }

    override suspend fun getApplicationDetails(appId: String): Game? {
        return api.getApplicationDetails(appId).getOrNull()?.body<GamesResponse>()?.toGame()
    }

    override suspend fun getUser(userid: String): User {
        return api.getUser(userid).getOrNull()?.body() ?: User()
    }

    override suspend fun getContributors(): List<Contributor> {
        return api.getContributors().getOrNull()?.body() ?: emptyList()
    }

    override suspend fun checkForUpdate(): Release {
        return api.checkForUpdate().getOrNull()?.releaseBody() ?: Release()
    }
}

suspend fun HttpResponse.releaseBody(): Release {
    return if (this.status.value == 200) {
        Prefs.saveLatestRelease(this.body())
        this.body()
    } else {
        Prefs.getSavedLatestRelease() ?: Release()
    }
}