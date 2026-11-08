package com.routinepilot.infrastructure.notifications

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** Notification permission is a runtime permission from Android 13. Callers must check it before posting. */
object NotificationAccess {
    fun isPermissionGranted(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun canPost(context: Context): Boolean =
        isPermissionGranted(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()
}
