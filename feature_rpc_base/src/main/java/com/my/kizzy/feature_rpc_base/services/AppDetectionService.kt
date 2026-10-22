/*
 *
 *  ******************************************************************
 *  *  * Copyright (C) 2022
 *  *  * AppDetectionService.kt is part of Kizzy
 *  *  *  and can not be copied and/or distributed without the express
 *  *  * permission of yzziK(Vaibhav)
 *  *  *****************************************************************
 *
 *
 */

@file:Suppress("DEPRECATION")

package com.my.kizzy.feature_rpc_base.services

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.blankj.utilcode.util.AppUtils
import com.my.kizzy.data.rpc.KizzyRPC
import com.my.kizzy.data.rpc.RpcImage
import com.my.kizzy.domain.model.rpc.RpcButtons
import com.my.kizzy.feature_rpc_base.Constants
import com.my.kizzy.feature_rpc_base.setLargeIcon
import com.my.kizzy.preference.Prefs
import com.my.kizzy.resources.R
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Inject

@AndroidEntryPoint
class AppDetectionService : Service() {

    @Inject
    lateinit var kizzyRPC: KizzyRPC

    @Inject
    lateinit var gameTelemetryManager: com.my.kizzy.data.rpc.GameTelemetryManager

    @Inject
    lateinit var scope: CoroutineScope

    @Inject
    lateinit var notificationBuilder: Notification.Builder

    @Inject
    lateinit var notificationManager: NotificationManager

    private lateinit var pendingIntent: PendingIntent
    private lateinit var restartPendingIntent: PendingIntent

    private var runningPackage = ""

    override fun onBind(intent: Intent): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == Constants.ACTION_STOP_SERVICE) {
            stopSelf()
        } else if (intent?.action == Constants.ACTION_RESTART_SERVICE) {
            stopSelf()
            startService(Intent(this, AppDetectionService::class.java))
        } else {
            handleAppDetection()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(2500) {
                gameTelemetryManager.stop()
                kizzyRPC.clearRPC()
                delay(800)
            }
        }
        kizzyRPC.closeRPC()
        scope.cancel()
        super.onDestroy()
    }

    private fun handleAppDetection() {
        val stopIntent = createStopIntent()
        pendingIntent = createPendingIntent(stopIntent)

        val restartIntent = createRestartIntent()
        restartPendingIntent = PendingIntent.getService(
            this,
            0, restartIntent, PendingIntent.FLAG_IMMUTABLE
        )

        notificationBuilder
            .setSmallIcon(R.drawable.ic_apps)
            .addAction(R.drawable.ic_apps, getString(R.string.restart), restartPendingIntent)
            .addAction(R.drawable.ic_apps, getString(R.string.exit), pendingIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                startForeground(
                    Constants.NOTIFICATION_ID,
                    createDefaultNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } catch (e: Exception) {
                startForeground(
                    Constants.NOTIFICATION_ID,
                    createDefaultNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            }
        } else {
            startForeground(Constants.NOTIFICATION_ID, createDefaultNotification())
        }

        scope.launch {
            var missedDetections = 0
            while (isActive) {
                val enabledPackages = getEnabledPackages()
                val rpcButtons = getRpcButtons()
                val currentPackage = getForegroundPackageName()

                if (currentPackage != null && currentPackage !in EXCLUDED_APPS) {
                    if (currentPackage in enabledPackages) {
                        missedDetections = 0
                        if (currentPackage != runningPackage) {
                            handleEnabledPackage(currentPackage, rpcButtons)
                            runningPackage = currentPackage
                        }
                    } else {
                        // Grace period / debounce: wait 2 detection cycles (approx 6-8s)
                        // so brief launcher or notification shade checks don't immediately kill RPC
                        missedDetections++
                        if (missedDetections >= 2 && runningPackage.isNotEmpty()) {
                            handleDisabledPackage()
                            runningPackage = ""
                            missedDetections = 0
                        }
                    }
                }
                delay(3000)
            }
        }
    }

    private fun getEnabledPackages(): List<String> {
        val apps = Prefs[Prefs.ENABLED_APPS, "[]"]
        return try {
            Json.decodeFromString(apps)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun getRpcButtons(): RpcButtons {
        val rpcButtonsString = Prefs[Prefs.RPC_BUTTONS_DATA, "{}"]
        return try {
            Json.decodeFromString(rpcButtonsString)
        } catch (e: Exception) {
            RpcButtons()
        }
    }

    private fun getForegroundPackageName(): String? {
        val usageStatsManager = getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10000

        return try {
            val usageEvents = usageStatsManager.queryEvents(startTime, endTime)
            var latestEventPackage: String? = null
            var latestEventTime = 0L
            val event = UsageEvents.Event()

            while (usageEvents.hasNextEvent()) {
                usageEvents.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestEventTime) {
                        latestEventTime = event.timeStamp
                        latestEventPackage = event.packageName
                    }
                }
            }

            latestEventPackage ?: usageStatsManager.queryUsageStats(
                UsageStatsManager.INTERVAL_DAILY,
                endTime - 60000,
                endTime
            )?.maxByOrNull { it.lastTimeUsed }?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun handleEnabledPackage(packageName: String, rpcButtons: RpcButtons) {
        val appName = try {
            AppUtils.getAppName(packageName)
        } catch (e: Exception) {
            packageName
        }

        kizzyRPC.apply {
            setName(appName)
            setStartTimestamps(System.currentTimeMillis())
            setStatus(Prefs[Prefs.CUSTOM_ACTIVITY_STATUS, "dnd"])
            setLargeImage(RpcImage.ApplicationIcon(packageName, this@AppDetectionService))
            if (Prefs[Prefs.USE_RPC_BUTTONS, false]) {
                with(rpcButtons) {
                    setButton1(button1.takeIf { it.isNotEmpty() })
                    setButton1URL(button1Url.takeIf { it.isNotEmpty() })
                    setButton2(button2.takeIf { it.isNotEmpty() })
                    setButton2URL(button2Url.takeIf { it.isNotEmpty() })
                }
            }
            build()
            gameTelemetryManager.start(appName, packageName)
        }

        notificationManager.notify(
            Constants.NOTIFICATION_ID,
            notificationBuilder
                .setContentText(appName)
                .setLargeIcon(
                    rpcImage = RpcImage.ApplicationIcon(packageName, this@AppDetectionService),
                    context = this@AppDetectionService
                )
                .build()
        )
    }

    private fun handleDisabledPackage() {
        scope.launch {
            gameTelemetryManager.stop()
            if (kizzyRPC.isRpcRunning()) {
                kizzyRPC.clearRPC()
                delay(800)
                kizzyRPC.closeRPC()
            }
            notificationManager.notify(Constants.NOTIFICATION_ID, createDefaultNotification())
        }
    }

    private fun createDefaultNotification(): Notification {
        return Notification.Builder(this, Constants.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_apps)
            .setContentTitle(getString(R.string.service_enabled))
            .addAction(R.drawable.ic_apps, getString(R.string.exit), pendingIntent)
            .addAction(R.drawable.ic_apps, getString(R.string.restart), restartPendingIntent)
            .build()
    }

    private fun createStopIntent(): Intent {
        val stopIntent = Intent(this, AppDetectionService::class.java)
        stopIntent.action = Constants.ACTION_STOP_SERVICE
        return stopIntent
    }

    private fun createRestartIntent(): Intent {
        val restartIntent = Intent(this, AppDetectionService::class.java)
        restartIntent.action = Constants.ACTION_RESTART_SERVICE
        return restartIntent
    }

    private fun createPendingIntent(stopIntent: Intent): PendingIntent {
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        val EXCLUDED_APPS = listOf(
            "com.my.kizzy",
            "com.discord",
            "com.android.systemui"
        )
    }
}
