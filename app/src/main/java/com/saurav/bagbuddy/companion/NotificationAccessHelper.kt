package com.saurav.bagbuddy.companion

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings

class NotificationAccessHelper(context: Context) {
    private val appContext = context.applicationContext
    private val listenerComponent = ComponentName(
        appContext,
        BagBuddyNotificationListenerService::class.java
    )

    fun hasAccess(): Boolean {
        val enabledListeners = Settings.Secure.getString(
            appContext.contentResolver,
            ENABLED_NOTIFICATION_LISTENERS_SETTING
        ) ?: return false

        return isListenerEnabled(enabledListeners, listenerComponent.flattenToString())
    }

    fun settingsIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    internal companion object {
        private const val ENABLED_NOTIFICATION_LISTENERS_SETTING = "enabled_notification_listeners"

        fun isListenerEnabled(settingValue: String?, componentName: String): Boolean =
            settingValue
                ?.split(':')
                ?.any { it == componentName }
                ?: false
    }
}
