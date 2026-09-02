package com.github.kr328.clash.cloud

import android.content.Context

class CloudSession(context: Context) {
    private val prefs = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)

    val loggedIn: Boolean get() = authData.isNotBlank()
    val authData: String get() = prefs.getString("auth_data", "").orEmpty()
    val email: String get() = prefs.getString("email", "").orEmpty()
    var profileUuid: String?
        get() = prefs.getString("profile_uuid", null)
        set(value) { prefs.edit().putString("profile_uuid", value).apply() }

    fun save(email: String, token: String, authData: String) {
        prefs.edit()
            .putString("email", email.trim().lowercase())
            .putString("token", token)
            .putString("auth_data", authData)
            .apply()
    }

    fun clear() {
        val profile = profileUuid
        prefs.edit().clear().putString("profile_uuid", profile).apply()
    }
}
