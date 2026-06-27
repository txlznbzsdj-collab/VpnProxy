package com.vpnproxy.app

import android.content.Context
import android.content.SharedPreferences

class ConfigManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("vpn_config", Context.MODE_PRIVATE)

    var serverAddress: String
        get() = prefs.getString(KEY_SERVER_ADDR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SERVER_ADDR, value).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_SERVER_PORT, 1080)
        set(value) = prefs.edit().putInt(KEY_SERVER_PORT, value).apply()

    var username: String
        get() = prefs.getString(KEY_USERNAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    companion object {
        private const val KEY_SERVER_ADDR = "server_address"
        private const val KEY_SERVER_PORT = "server_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
    }
}
