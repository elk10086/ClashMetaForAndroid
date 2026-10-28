package com.github.kr328.clash.service

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts subscription configurations at rest.
 *
 * Plaintext is only used in the processing/cache directories while the core validates or loads a
 * profile. The imported and pending profile directories always receive the encrypted form.
 */
object ProfileStorage {
    private const val KEY_ALIAS = "cmfa_profile_storage_v1"
    private const val FALLBACK_STORE = "profile_storage"
    private const val FALLBACK_KEY = "profile_storage_secret_v1"
    private const val NONCE_SIZE = 12
    private const val TAG_BITS = 128
    private val prefix = "CMFA_PROFILE_V1:".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun isEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < prefix.size + NONCE_SIZE + 1) return false
        return prefix.indices.all { bytes[it] == prefix[it] }
    }

    fun read(file: File, context: Context): ByteArray {
        val stored = file.readBytes()
        if (!isEncrypted(stored)) return stored

        val nonceStart = prefix.size
        val payloadStart = nonceStart + NONCE_SIZE
        val nonce = stored.copyOfRange(nonceStart, payloadStart)
        val payload = stored.copyOfRange(payloadStart, stored.size)
        val keys = buildList {
            add(deviceKey(context))
            legacyAndroidKey()?.let { add(it) }
        }
        var failure: Exception? = null
        keys.forEach { key ->
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
                return cipher.doFinal(payload)
            } catch (e: Exception) {
                failure = e
            }
        }
        throw failure ?: IllegalStateException("Unable to decrypt profile")
    }

    fun write(file: File, content: ByteArray, context: Context) {
        file.parentFile?.mkdirs()

        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deviceKey(context), GCMParameterSpec(TAG_BITS, nonce))
        val encrypted = prefix + nonce + cipher.doFinal(content)
        val temporary = File(
            file.parentFile,
            ".${file.name}.${UUID.randomUUID()}.encrypted",
        )

        try {
            FileOutputStream(temporary).use {
                it.write(encrypted)
                it.fd.sync()
            }
            if (!temporary.renameTo(file)) {
                temporary.copyTo(file, overwrite = true)
            }
        } finally {
            temporary.delete()
        }
    }

    fun decryptProfileFiles(directory: File, context: Context) {
        configurationFiles(directory).forEach { file ->
            if (!file.isFile) return@forEach
            val stored = file.readBytes()
            if (isEncrypted(stored)) {
                file.writeBytes(read(file, context))
            }
        }
    }

    /**
     * URL profiles are force-downloaded immediately after this preparation. If an encrypted
     * temporary copy can no longer be decrypted (for example after Android restored app files
     * without the old Keystore key), keeping it would prevent the network request from ever
     * starting. Reset only the disposable processing copy; the imported profile remains intact
     * until the replacement has downloaded and passed validation.
     */
    fun decryptProfileFilesForDownload(directory: File, context: Context) {
        try {
            decryptProfileFiles(directory, context)
        } catch (_: Exception) {
            configurationFiles(directory).forEach { it.delete() }
        }
    }

    fun encryptProfileFiles(directory: File, context: Context) {
        configurationFiles(directory).forEach { file ->
            if (!file.isFile) return@forEach
            val stored = file.readBytes()
            if (!isEncrypted(stored)) {
                val modifiedAt = file.lastModified()
                write(file, stored, context)
                if (modifiedAt > 0) file.setLastModified(modifiedAt)
            }
        }
    }

    fun copyEncryptedConfiguration(source: File, target: File, context: Context) {
        configurationFiles(source).zip(configurationFiles(target)).forEach { (input, output) ->
            if (input.isFile) {
                val content = read(input, context)
                val current = if (output.isFile) runCatching { read(output, context) }.getOrNull() else null
                if (current == null || !current.contentEquals(content)) {
                    val modifiedAt = output.lastModified()
                    write(output, content, context)
                    if (modifiedAt > 0) output.setLastModified(modifiedAt)
                }
            } else {
                output.delete()
            }
        }
    }

    fun clearPlaintextCache(context: Context) {
        context.cacheDir.resolve("profiles-runtime").deleteRecursively()
        context.cacheDir.resolve("profile-documents").deleteRecursively()
        context.filesDir.resolve("processing").deleteRecursively()
    }

    fun migrateExistingProfiles(context: Context) {
        listOf(
            context.filesDir.resolve("imported"),
            context.filesDir.resolve("pending"),
        ).forEach { root ->
            root.listFiles()
                ?.filter { it.isDirectory }
                ?.forEach { profile ->
                    runCatching { encryptProfileFiles(profile, context) }
                }
        }
    }

    private fun configurationFiles(directory: File): List<File> = listOf(
        directory.resolve("config.yaml"),
        directory.resolve("config.yaml.origin.yaml"),
    )

    /**
     * Keep the data key in the app-private sandbox. Some Android 10 vendor Keystore providers
     * reject AES/GCM key generation; that previously made profile creation fail before download.
     */
    private fun deviceKey(context: Context): SecretKey {
        val preferences = context.getSharedPreferences(FALLBACK_STORE, Context.MODE_PRIVATE)
        val encoded = preferences.getString(FALLBACK_KEY, null)
        val bytes = if (encoded.isNullOrEmpty()) {
            ByteArray(32).also(random::nextBytes).also {
                preferences.edit()
                    .putString(FALLBACK_KEY, Base64.encodeToString(it, Base64.NO_WRAP))
                    .commit()
            }
        } else {
            Base64.decode(encoded, Base64.NO_WRAP)
        }
        return SecretKeySpec(bytes, "AES")
    }

    /** Read compatibility for profiles encrypted by the first Keystore-based implementation. */
    private fun legacyAndroidKey(): SecretKey? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        return runCatching {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            store.getKey(KEY_ALIAS, null) as? SecretKey
        }.getOrNull()
    }
}
