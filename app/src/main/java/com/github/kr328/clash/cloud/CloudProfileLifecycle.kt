package com.github.kr328.clash.cloud

import com.github.kr328.clash.service.model.Profile
import com.github.kr328.clash.service.remote.IProfileManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val CLOUD_PROFILE_NAME = "云端订阅"
private val cloudProfileSyncMutex = Mutex()

internal suspend fun <T> withCloudProfileSync(block: suspend () -> T): T {
    return cloudProfileSyncMutex.withLock { block() }
}

private fun Profile.isCloudManaged(): Boolean {
    return type == Profile.Type.Url && name == CLOUD_PROFILE_NAME
}

/**
 * Resolve the one profile owned by cloud subscription synchronization.
 *
 * Older builds only remembered a UUID. If that preference was lost, another profile was created
 * even though a usable cloud profile was already present. Falling back to the newest managed
 * profile repairs that state without requiring another download.
 */
internal suspend fun IProfileManager.resolveCloudProfile(
    session: CloudSession,
    requireReady: Boolean,
): Profile? {
    val profiles = queryAll()
    val remembered = session.profileUuid
        ?.let { value -> runCatching { java.util.UUID.fromString(value) }.getOrNull() }
        ?.let { uuid -> profiles.firstOrNull { it.uuid == uuid } }
    val candidates = profiles.filter { profile ->
        profile.isCloudManaged() && (!requireReady || (profile.imported && !profile.pending))
    }
    val ready = candidates.filter { it.imported && !it.pending }
    val activeManaged = profiles.firstOrNull { it.active && it.isCloudManaged() }
    val selected = activeManaged?.takeIf { it.imported && !it.pending }
        ?: remembered?.takeIf { it.imported && !it.pending }
        ?: ready.maxByOrNull { it.updatedAt }
        ?: activeManaged?.takeIf { !requireReady }
        ?: remembered?.takeIf { !requireReady }
        ?: candidates.maxByOrNull { it.updatedAt }

    if (selected != null && session.profileUuid != selected.uuid.toString()) {
        session.profileUuid = selected.uuid.toString()
    }
    return selected
}

/** Activate the successful cloud profile first, then remove cloud profiles left by old races. */
internal suspend fun IProfileManager.activateAndPruneCloudProfile(
    session: CloudSession,
    profile: Profile,
) {
    // Committing an active profile already triggers a core reload.
    if (!profile.active) setActive(profile)
    session.profileUuid = profile.uuid.toString()
    queryAll()
        .filter { it.uuid != profile.uuid && it.isCloudManaged() }
        .forEach { delete(it.uuid) }
}

/** Keep a manually selected test/debug profile, but repair a missing active profile. */
internal suspend fun IProfileManager.adoptAndPruneCloudProfile(
    session: CloudSession,
    profile: Profile,
) {
    if (queryActive() == null) setActive(profile)
    session.profileUuid = profile.uuid.toString()
    queryAll()
        .filter { it.uuid != profile.uuid && it.isCloudManaged() }
        .forEach { delete(it.uuid) }
}

internal suspend fun IProfileManager.deleteCloudProfiles() {
    queryAll()
        .filter { it.isCloudManaged() }
        .forEach { delete(it.uuid) }
}
