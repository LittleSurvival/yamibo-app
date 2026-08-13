package me.thenano.yamibo.yamibo_app.store.settings

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import me.thenano.yamibo.yamibo_app.store.encryptedSharedPreferences

/**
 * 加密的 [SettingsStore]，底層為 AndroidX Security Crypto 的 EncryptedSharedPreferences
 * （AES256-GCM，金鑰在 Keystore）。用於網盤憑證（refresh_token / 密碼）等敏感值。
 *
 * 寫入使用 commit() 同步落盤：憑證寫入後若 App 立刻被殺，也不丟失。
 */
@SuppressLint("UseKtx")
class EncryptedSettingsStore(context: Context) : SettingsStore {
    private val prefs: SharedPreferences =
        context.applicationContext.encryptedSharedPreferences("yamibo_app_credentials")

    override fun getInt(key: String, defaultValue: Int): Int = prefs.getInt(key, defaultValue)
    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).commit()
    }

    override fun getFloat(key: String, defaultValue: Float): Float = prefs.getFloat(key, defaultValue)
    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).commit()
    }

    override fun getString(key: String, defaultValue: String): String = prefs.getString(key, defaultValue) ?: defaultValue
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = prefs.getBoolean(key, defaultValue)
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).commit()
    }

    override fun hasKey(key: String): Boolean = prefs.contains(key)
}
