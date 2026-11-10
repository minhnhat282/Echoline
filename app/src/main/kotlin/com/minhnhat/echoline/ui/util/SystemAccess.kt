package com.minhnhat.echoline.ui.util

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import com.minhnhat.echoline.data.systemService

/** Kiểm tra và yêu cầu vai trò, quyền của hệ thống. */
object SystemAccess {

    fun requiredPermissions(): List<String> = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.READ_CALL_LOG)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ANSWER_PHONE_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun missingPermissions(context: Context): List<String> =
        requiredPermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    fun isDefaultDialer(context: Context): Boolean =
        try {
            context.systemService<TelecomManager>().defaultDialerPackage == context.packageName
        } catch (e: Exception) {
            false
        }

    fun isRoleHeld(context: Context, role: String): Boolean =
        try {
            val rm = context.systemService<RoleManager>()
            rm.isRoleAvailable(role) && rm.isRoleHeld(role)
        } catch (e: Exception) {
            false
        }

    /** Intent xin vai trò, hoặc null nếu đã giữ vai trò / vai trò không khả dụng. */
    fun roleIntent(context: Context, role: String): Intent? =
        try {
            val rm = context.systemService<RoleManager>()
            if (rm.isRoleAvailable(role) && !rm.isRoleHeld(role)) rm.createRequestRoleIntent(role) else null
        } catch (e: Exception) {
            null
        }

    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 ||
            try {
                context.systemService<NotificationManager>().canUseFullScreenIntent()
            } catch (e: Exception) {
                true
            }

    fun fullScreenIntentSettings(context: Context): Intent =
        Intent("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT", "package:${context.packageName}".toUri())
}
