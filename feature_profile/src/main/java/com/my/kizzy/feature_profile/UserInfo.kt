/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * UserInfo.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.feature_profile

import com.my.kizzy.preference.Prefs
import com.my.kizzy.preference.Prefs.USER_BIO
import com.my.kizzy.preference.Prefs.USER_BIO_2
import com.my.kizzy.preference.Prefs.USER_ID
import com.my.kizzy.preference.Prefs.USER_ID_2
import com.my.kizzy.preference.Prefs.USER_NITRO
import com.my.kizzy.preference.Prefs.USER_NITRO_2
import kizzy.gateway.DiscordWebSocket
import kizzy.gateway.DiscordWebSocketImpl
import kizzy.gateway.entities.Payload

suspend fun getUserInfo(token: String, accountIndex: Int = 1, onInfoSaved: () -> Unit) {
    val discordWebSocket: DiscordWebSocket = object: DiscordWebSocketImpl(token){
        override fun Payload.handleDispatch(jsonString: String) {
            if (this.t.toString() == "READY"){
                val user = decodeReady(jsonString)?.user ?: return
                if (accountIndex == 2) {
                    Prefs[USER_ID_2] = user.id
                    Prefs[USER_BIO_2] = user.bio
                    Prefs[USER_NITRO_2] = user.premiumType in 1..3
                } else {
                    Prefs[USER_ID] = user.id
                    Prefs[USER_BIO] = user.bio
                    Prefs[USER_NITRO] = user.premiumType in 1..3
                }
                close()
                onInfoSaved()
            }
        }
    }
    discordWebSocket.connect()
}
