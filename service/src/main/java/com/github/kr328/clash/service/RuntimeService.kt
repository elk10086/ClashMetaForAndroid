package com.github.kr328.clash.service

import android.app.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel

/** Core cleanup is serialized by RuntimeHandover, never by blocking Android's main thread. */
abstract class RuntimeService : Service(), CoroutineScope by CoroutineScope(Dispatchers.Default) {
    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }
}
