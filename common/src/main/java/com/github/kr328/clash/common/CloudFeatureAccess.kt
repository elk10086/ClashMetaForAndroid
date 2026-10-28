package com.github.kr328.clash.common

import android.content.Context

object CloudFeatureAccess {
    val testAccountEmails = listOf(
        "8764302013@gmail.com",
        "wawajiao522@gmail.com",
        "wawajiao533@gmail.com",
    )

    fun isTestAccount(email: String?): Boolean {
        return email?.trim()?.lowercase() in testAccountEmails
    }

    fun canViewConfiguration(context: Context, debug: Boolean): Boolean {
        if (debug) return true
        val email = context.getSharedPreferences("cloud_session", Context.MODE_PRIVATE)
            .getString("email", "")
        return isTestAccount(email)
    }
}
