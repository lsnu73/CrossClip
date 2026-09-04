package com.crossclip.app.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoUtil {

    private fun deriveKey(keyStr: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(keyStr.toByteArray(Charsets.UTF_8))
    }

    fun computeHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun encrypt(plaintext: String, keyStr: String): String {
        val keyBytes = deriveKey(keyStr)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertextAndTag = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val payload = ByteArray(iv.size + ciphertextAndTag.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertextAndTag, 0, payload, iv.size, ciphertextAndTag.size)

        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    fun decrypt(encryptedB64: String, keyStr: String): String {
        val keyBytes = deriveKey(keyStr)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val payload = Base64.decode(encryptedB64, Base64.NO_WRAP)

        if (payload.size < 28) {
            throw IllegalArgumentException("密文长度不足")
        }

        val iv = payload.copyOfRange(0, 12)
        val ciphertextAndTag = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decrypted = cipher.doFinal(ciphertextAndTag)
        return String(decrypted, Charsets.UTF_8)
    }
}
