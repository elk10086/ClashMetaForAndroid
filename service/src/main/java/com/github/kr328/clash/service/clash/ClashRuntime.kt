package com.github.kr328.clash.service.clash

import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.clash.module.Module
import kotlinx.coroutines.*

private val handover = RuntimeHandover()

interface ClashRuntimeScope {
    fun <E, T : Module<E>> install(module: T): T
}

interface ClashRuntime {
    fun launch()
    fun requestGc()
}

fun CoroutineScope.clashRuntime(block: suspend ClashRuntimeScope.() -> Unit): ClashRuntime {
    return object : ClashRuntime {
        override fun launch() {
            launch(Dispatchers.IO) {
                handover.run(cleanup = {
                    Clash.reset()
                    Clash.clearOverride(Clash.OverrideSlot.Session)
                    Log.d("ClashRuntime: destroyed")
                }) {
                    Log.d("ClashRuntime: initialize")

                    Clash.reset()
                    Clash.clearOverride(Clash.OverrideSlot.Session)

                    val scope = object : ClashRuntimeScope {
                        override fun <E, T : Module<E>> install(module: T): T {
                            launch { module.execute() }
                            return module
                        }
                    }

                    scope.block()
                }
            }
        }

        override fun requestGc() {
            Clash.forceGc()
        }
    }
}
