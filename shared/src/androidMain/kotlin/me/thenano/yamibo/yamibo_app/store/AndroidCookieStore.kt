package me.thenano.yamibo.yamibo_app.store

import android.annotation.SuppressLint
import android.content.Context
import me.thenano.yamibo.yamibo_app.store.auth.CookieStore

class AndroidCookieStore(
    context: Context,
) : CookieStore {
    private val prefs = context.encryptedSharedPreferences(prefName)

    @SuppressLint("UseKtx")
    override fun save(value: String) {
        prefs.edit().putString(key, value).commit()
    }

    override fun load(): String? {
        return prefs.getString(key, null)
    }

    @SuppressLint("UseKtx")
    override fun clear() {
        prefs.edit().clear().commit()
    }
}
