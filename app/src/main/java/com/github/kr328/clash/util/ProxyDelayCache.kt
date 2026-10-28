package com.github.kr328.clash.util

import android.content.Context
import android.util.Base64
import com.github.kr328.clash.core.model.Proxy
import com.github.kr328.clash.core.model.ProxyGroup

/**
 * Keeps the latest real health-check result across Clash core restarts.
 *
 * Delay histories in the native core are intentionally in-memory. Stopping
 * the proxy destroys that core, so the line page needs a small app-side cache
 * to avoid showing every node as untested when the core is started again.
 */
object ProxyDelayCache {
    private const val PREFS = "proxy_delay_cache"
    private const val KEY_PREFIX = "delay_"
    private const val SEPARATOR = "\u0000"

    fun restore(context: Context, profileUuid: String, groupName: String, group: ProxyGroup): ProxyGroup {
        if (profileUuid.isBlank()) return group

        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val proxies = group.proxies.map { proxy ->
            if (proxy.delay != 0) return@map proxy

            val key = key(profileUuid, groupName, proxy)
            if (!preferences.contains(key)) return@map proxy

            proxy.copy(delay = preferences.getInt(key, 0))
        }

        return if (proxies == group.proxies) group else group.copy(proxies = proxies)
    }

    fun save(context: Context, profileUuid: String, groupName: String, proxies: List<Proxy>) {
        if (profileUuid.isBlank()) return

        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        proxies.forEach { proxy ->
            // 0 means that this node has not been tested yet. Do not let a
            // fresh core overwrite a previously measured value with 0.
            if (proxy.delay != 0) {
                editor.putInt(key(profileUuid, groupName, proxy), proxy.delay)
            }
        }
        editor.apply()
    }

    fun clearProfile(context: Context, profileUuid: String) {
        if (profileUuid.isBlank()) return

        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(KEY_PREFIX) && belongsToProfile(it, profileUuid) }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun key(profileUuid: String, groupName: String, proxy: Proxy): String {
        val raw = listOf(profileUuid, groupName, proxy.name, proxy.server).joinToString(SEPARATOR)
        return KEY_PREFIX + Base64.encodeToString(
            raw.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
    }

    private fun belongsToProfile(key: String, profileUuid: String): Boolean {
        return runCatching {
            val encoded = key.removePrefix(KEY_PREFIX)
            val decoded = Base64.decode(encoded, Base64.URL_SAFE)
                .toString(Charsets.UTF_8)
            decoded.substringBefore(SEPARATOR) == profileUuid
        }.getOrDefault(false)
    }
}
