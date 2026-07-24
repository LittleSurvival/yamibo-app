package me.thenano.yamibo.yamibo_app

import android.util.Log as AndroidLog

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual object Logger {
    actual fun v(tag: String, message: String, throwable: Throwable?) {
        val formattedTag = "Yamibo_VERBOSE($tag)"
        if (throwable != null) {
            AndroidLog.v(formattedTag, message, throwable)
        } else {
            AndroidLog.v(formattedTag, message)
        }
    }

    actual fun d(tag: String, message: String, throwable: Throwable?) {
        val formattedTag = "Yamibo_DEBUG($tag)"
        if (throwable != null) {
            AndroidLog.d(formattedTag, message, throwable)
        } else {
            AndroidLog.d(formattedTag, message)
        }
    }

    actual fun i(tag: String, message: String, throwable: Throwable?) {
        val formattedTag = "Yamibo_INFO($tag)"
        if (throwable != null) {
            AndroidLog.i(formattedTag, message, throwable)
        } else {
            AndroidLog.i(formattedTag, message)
        }
    }

    actual fun w(tag: String, message: String, throwable: Throwable?) {
        val formattedTag = "Yamibo_WARN($tag)"
        if (throwable != null) {
            AndroidLog.w(formattedTag, message, throwable)
        } else {
            AndroidLog.w(formattedTag, message)
        }
    }

    actual fun e(tag: String, message: String, throwable: Throwable?) {
        val formattedTag = "Yamibo_ERROR($tag)"
        if (throwable != null) {
            AndroidLog.e(formattedTag, message, throwable)
        } else {
            AndroidLog.e(formattedTag, message)
        }
    }
}
