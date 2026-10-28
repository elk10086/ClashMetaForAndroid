package com.github.kr328.clash.service.clash

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keep the next core out until every old module and native cleanup has finished. */
internal class RuntimeHandover {
    private val lock = Mutex()

    suspend fun run(cleanup: suspend () -> Unit, block: suspend CoroutineScope.() -> Unit) {
        lock.withLock {
            try {
                coroutineScope {
                    try {
                        block()
                    } finally {
                        coroutineContext.cancelChildren()
                    }
                }
            } finally {
                withContext(NonCancellable) { cleanup() }
            }
        }
    }
}
