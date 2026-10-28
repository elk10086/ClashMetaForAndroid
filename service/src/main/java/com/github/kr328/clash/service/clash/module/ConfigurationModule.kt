package com.github.kr328.clash.service.clash.module

import android.app.Service
import com.github.kr328.clash.common.constants.Intents
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.core.Clash
import com.github.kr328.clash.service.ProfileStorage
import com.github.kr328.clash.service.StatusProvider
import com.github.kr328.clash.service.data.ImportedDao
import com.github.kr328.clash.service.data.SelectionDao
import com.github.kr328.clash.service.store.ServiceStore
import com.github.kr328.clash.service.util.importedDir
import com.github.kr328.clash.service.util.sendProfileLoaded
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.util.*

class ConfigurationModule(service: Service) : Module<ConfigurationModule.LoadException>(service) {
    data class LoadException(val message: String)

    private val store = ServiceStore(service)
    private val reload = Channel<Unit>(Channel.CONFLATED)
    private val ready = CompletableDeferred<Unit>()

    /** The VPN must not intercept traffic until persisted selectors have been restored. */
    suspend fun awaitReady() = ready.await()

    override suspend fun run() {
        val runtimeRoot = service.cacheDir.resolve("profiles-runtime")
        val broadcasts = receiveBroadcast {
            addAction(Intents.ACTION_PROFILE_CHANGED)
            addAction(Intents.ACTION_OVERRIDE_CHANGED)
        }

        var loaded: UUID? = null

        reload.trySend(Unit)

        try {
        while (true) {
            val changed: UUID? = select {
                broadcasts.onReceive {
                    if (it.action == Intents.ACTION_PROFILE_CHANGED)
                        UUID.fromString(it.getStringExtra(Intents.EXTRA_UUID))
                    else
                        null
                }
                reload.onReceive {
                    null
                }
            }

            try {
                val current = store.activeProfile
                    ?: throw NullPointerException("No profile selected")

                if (current == loaded && changed != null && changed != loaded)
                    continue

                loaded = current

                val active = ImportedDao().queryByUUID(current)
                    ?: throw NullPointerException("No profile selected")
                val storedProfile = service.importedDir.resolve(active.uuid.toString())
                val runtimeProfile = runtimeRoot.resolve(active.uuid.toString())

                runtimeRoot.deleteRecursively()
                storedProfile.copyRecursively(runtimeProfile, overwrite = true)
                ProfileStorage.decryptProfileFiles(runtimeProfile, service)

                Clash.setAgeSecretKey(active.ageSecretKey?.takeIf { it.isNotBlank() })

                // Native loading cannot be cancelled. Wait off the main thread so
                // teardown cannot delete its files or reset a newly started core.
                withContext(NonCancellable) { Clash.load(runtimeProfile).await() }
                currentCoroutineContext().ensureActive()
                ProfileStorage.copyEncryptedConfiguration(runtimeProfile, storedProfile, service)
                ProfileStorage.encryptProfileFiles(runtimeProfile, service)

                val remove = SelectionDao().querySelections(active.uuid)
                    .filterNot { Clash.patchSelector(it.proxy, it.selected) }
                    .map { it.proxy }

                SelectionDao().removeSelections(active.uuid, remove)

                StatusProvider.currentProfile = active.name
                ready.complete(Unit)

                service.sendProfileLoaded(current)

                Log.d("Profile ${active.name} loaded")
            } catch (e: CancellationException) {
                ready.cancel(e)
                throw e
            } catch (e: Exception) {
                ready.completeExceptionally(e)
                return enqueueEvent(LoadException(e.message ?: "Unknown"))
            }
        }
        } finally {
            runtimeRoot.deleteRecursively()
        }
    }
}
