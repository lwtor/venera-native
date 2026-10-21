package dev.veneranative.data.source

import android.annotation.SuppressLint
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.veneranative.core.model.SourceId
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Encrypted persistence for per-source setting overrides. Values are JSON primitives. */
interface SourcePrivateSettingsStore {
    fun read(sourceId: SourceId): Map<String, String>
    fun write(sourceId: SourceId, values: Map<String, String>)
    fun clear(sourceId: SourceId)
}

class InMemorySourcePrivateSettingsStore : SourcePrivateSettingsStore {
    private val values = mutableMapOf<SourceId, Map<String, String>>()
    @Synchronized override fun read(sourceId: SourceId) = values[sourceId].orEmpty()
    @Synchronized override fun write(sourceId: SourceId, values: Map<String, String>) { this.values[sourceId] = values.toMap() }
    @Synchronized override fun clear(sourceId: SourceId) { values.remove(sourceId) }
}

class AndroidSourcePrivateSettingsStore(context: Context) : SourcePrivateSettingsStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized
    override fun read(sourceId: SourceId): Map<String, String> {
        val key = preferenceKey(sourceId)
        val encoded = preferences.getString(key, null) ?: return emptyMap()
        return runCatching {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            require(blob.size > NONCE_BYTES)
            val nonce = blob.copyOfRange(0, NONCE_BYTES)
            val ciphertext = blob.copyOfRange(NONCE_BYTES, blob.size)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad(sourceId))
            }
            val json = JSONObject(String(cipher.doFinal(ciphertext), Charsets.UTF_8))
            buildMap {
                val names = json.keys()
                while (names.hasNext()) {
                    val name = names.next()
                    if (SETTING_KEY.matches(name)) put(name, json.get(name).toString())
                }
            }
        }.getOrElse {
            clear(sourceId)
            emptyMap()
        }
    }

    @Synchronized
    override fun write(sourceId: SourceId, values: Map<String, String>) {
        require(values.keys.all(SETTING_KEY::matches))
        val json = JSONObject()
        values.forEach { (key, value) ->
            val parsed = org.json.JSONTokener(value).nextValue()
            require(parsed == JSONObject.NULL || parsed is String || parsed is Number || parsed is Boolean) {
                "Only JSON primitive setting values can be stored."
            }
            json.put(key, parsed)
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
            updateAAD(aad(sourceId))
        }
        val nonce = cipher.iv
        val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val blob = ByteArray(nonce.size + encrypted.size)
        nonce.copyInto(blob)
        encrypted.copyInto(blob, nonce.size)
        check(preferences.edit().putString(preferenceKey(sourceId), Base64.encodeToString(blob, Base64.NO_WRAP)).commit()) {
            "Could not save source settings."
        }
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    override fun clear(sourceId: SourceId) {
        check(preferences.edit().remove(preferenceKey(sourceId)).commit()) { "Could not clear source settings." }
    }

    private fun preferenceKey(sourceId: SourceId): String = "source_${
        java.security.MessageDigest.getInstance("SHA-256").digest(sourceId.value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }"

    private fun aad(sourceId: SourceId) = "venera-native-source-settings-v1:${sourceId.value}".toByteArray(Charsets.UTF_8)

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "source_private_settings"
        const val KEY_ALIAS = "venera_native_source_settings"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val NONCE_BYTES = 12
        val SETTING_KEY = Regex("^[A-Za-z0-9_.-]{1,80}$")
    }
}
