package com.lagradost

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object IteraPlayCrypto {
    const val PRIMARY_KEY = "iteraplay_super_secret_enc_key_2026_v2"
    const val FALLBACK_KEY = "default_secret_key_if_env_missing"

    fun decrypt(encryptedB64: String, password: String = PRIMARY_KEY): String? {
        return runCatching {
            val raw = try {
                android.util.Base64.decode(encryptedB64, android.util.Base64.DEFAULT)
            } catch (e: Throwable) {
                java.util.Base64.getDecoder().decode(encryptedB64)
            }
            if (raw.size < 16) return null
            val header = String(raw, 0, 8, Charsets.US_ASCII)
            if (header != "Salted__") return null
            val salt = raw.copyOfRange(8, 16)
            val ciphertext = raw.copyOfRange(16, raw.size)

            val (key, iv) = evpBytesToKey(password.toByteArray(Charsets.UTF_8), salt, 32, 16)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ciphertext)
            String(decryptedBytes, Charsets.UTF_8)
        }.getOrNull() ?: if (password != FALLBACK_KEY) decrypt(encryptedB64, FALLBACK_KEY) else null
    }

    private fun evpBytesToKey(password: ByteArray, salt: ByteArray, keyLen: Int, ivLen: Int): Pair<ByteArray, ByteArray> {
        val totalLen = keyLen + ivLen
        val md = MessageDigest.getInstance("MD5")
        var derived = ByteArray(0)
        var di = ByteArray(0)

        while (derived.size < totalLen) {
            md.reset()
            if (di.isNotEmpty()) {
                md.update(di)
            }
            md.update(password)
            md.update(salt)
            di = md.digest()
            derived += di
        }

        val key = derived.copyOfRange(0, keyLen)
        val iv = derived.copyOfRange(keyLen, totalLen)
        return Pair(key, iv)
    }
}
