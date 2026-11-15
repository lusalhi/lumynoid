package dev.lusalhi.lumynoid.data

import android.content.Context
import android.util.AtomicFile
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dev.lusalhi.lumynoid.core.SecretPort
import dev.lusalhi.lumynoid.core.SecretRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets encrypted with AES-GCM under a non-exportable Android Keystore key and kept in files
 * apart from Room (AD-15). The alias is fixed and never derived from the package name (AD-25).
 */
internal class KeystoreSecretStore(context: Context) : SecretPort {

    private val dir = File(context.filesDir, "secrets").apply { mkdirs() }

    override suspend fun put(ref: SecretRef, secret: String) = withContext(Dispatchers.IO) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        val sealed = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        // AtomicFile keeps the previous secret intact until the new one is fully on disk.
        val file = AtomicFile(fileFor(ref))
        val out = file.startWrite()
        try {
            out.write(byteArrayOf(iv.size.toByte()) + iv + sealed)
            file.finishWrite(out)
        } catch (e: Throwable) {
            file.failWrite(out)
            throw e
        }
    }

    /** Returns null for a missing, truncated or undecryptable secret; errors never cross the port. */
    override suspend fun get(ref: SecretRef): String? = withContext(Dispatchers.IO) {
        val bytes = try {
            AtomicFile(fileFor(ref)).readFully()
        } catch (e: IOException) {
            return@withContext null
        }
        val ivSize = bytes.firstOrNull()?.toInt() ?: return@withContext null
        if (ivSize <= 0 || bytes.size <= 1 + ivSize) return@withContext null
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes.copyOfRange(1, 1 + ivSize)))
            }
            String(cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size)), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: ProviderException) {
            null
        }
    }

    private fun fileFor(ref: SecretRef): File {
        require(ref.value.matches(SAFE_NAME)) { "Unsafe secret reference" }
        return File(dir, "${ref.value}.bin")
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ALIAS = "lumynoid_secret_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        val SAFE_NAME = Regex("[A-Za-z0-9_-]{1,100}")
    }
}
