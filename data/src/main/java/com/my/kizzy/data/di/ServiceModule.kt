/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * ServiceModule.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

package com.my.kizzy.data.di

import com.my.kizzy.data.rpc.KizzyRPC
import com.my.kizzy.domain.interfaces.Logger
import com.my.kizzy.domain.repository.KizzyRepository
import com.my.kizzy.preference.Prefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import kizzy.gateway.DiscordWebSocket
import kizzy.gateway.MultiDiscordWebSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {
    @Provides
    fun providesDiscordWebsocket(
        logger: Logger
    ): DiscordWebSocket =
        MultiDiscordWebSocket(
            tokenSupplier = { Prefs.getActiveRpcTokens() },
            logger = logger
        )

    @Provides
    fun provideKizzyRpc(
        kizzyRepository: KizzyRepository,
        discordWebSocket: DiscordWebSocket,
        logger: Logger
    ) = KizzyRPC(Prefs.getPrimaryToken(), kizzyRepository, discordWebSocket, logger)

    @Provides
    fun providesCoroutineScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}