package com.github.kr328.clash.cloud

object CloudTestAccount {
    val emails = listOf(
        "8764302012@gmail.com",
        "8764302013@gmail.com",
        "wawajiao520@gmail.com",
        "wawajiao521@gmail.com",
        "wawajiao522@gmail.com",
    )

    fun isTest(email: String?): Boolean = email?.trim()?.lowercase() in emails
}
