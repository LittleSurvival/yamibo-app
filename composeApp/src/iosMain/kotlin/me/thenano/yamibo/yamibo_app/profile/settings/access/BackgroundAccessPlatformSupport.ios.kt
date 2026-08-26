@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package me.thenano.yamibo.yamibo_app.profile.settings.access

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNUserNotificationCenter

@Composable
actual fun rememberBackgroundAccessNotificationPermissionRequester(
    onPermissionHandled: () -> Unit,
): (() -> Unit)? = remember(onPermissionHandled) {
    {
        requestIOSNotificationAuthorization {
            onPermissionHandled()
        }
    }
}

@Composable
actual fun BackgroundAccessResumeRefreshEffect(
    onResume: () -> Unit,
) = Unit

fun requestIOSNotificationAuthorizationIfNeeded() {
    val center = UNUserNotificationCenter.currentNotificationCenter()
    center.getNotificationSettingsWithCompletionHandler { settings ->
        if (settings?.authorizationStatus == UNAuthorizationStatusNotDetermined) {
            requestIOSNotificationAuthorization()
        }
    }
}

private fun requestIOSNotificationAuthorization(onHandled: () -> Unit = {}) {
    UNUserNotificationCenter.currentNotificationCenter().requestAuthorizationWithOptions(
        options = UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
    ) { _, _ -> onHandled() }
}
