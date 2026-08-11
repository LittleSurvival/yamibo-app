package me.thenano.yamibo.yamibo_app.event.events

import me.thenano.yamibo.yamibo_app.event.AppEvent

/**
 * Emitted after a network fetch of the home page succeeds.
 *
 * Used to defer the Android 13+ notification-permission prompt out of the cold-start path:
 * requesting the permission during startup can pause the activity while the Baidu NOX WAF
 * recovery WebView is still solving the first challenge, which fails the in-flight
 * verification with FOREGROUND_REQUIRED (see dev-docs/foreground-required-first-launch.md).
 */
object HomePageLoadedEvent : AppEvent
