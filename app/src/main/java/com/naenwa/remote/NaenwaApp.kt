package com.naenwa.remote

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class NaenwaApp : Application() {

    companion object {
        const val CHANNEL_ID = "naenwa_floating"
        const val CHANNEL_NAME = "Naenwa Remote"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Claude Remote Control"
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
