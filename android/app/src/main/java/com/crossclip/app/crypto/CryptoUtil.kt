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

    /**
     * 加密任意二进制数据（用于文件分块传输）
     */
    fun encryptBytes(data: ByteArray, keyStr: String): String {
        val keyBytes = deriveKey(keyStr)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertextAndTag = cipher.doFinal(data)
        val payload = ByteArray(iv.size + ciphertextAndTag.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertextAndTag, 0, payload, iv.size, ciphertextAndTag.size)

        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    /**
     * 解密任意二进制数据（用于文件分块传输）
     */
    fun decryptBytes(encryptedB64: String, keyStr: String): ByteArray {
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

        return cipher.doFinal(ciphertextAndTag)
    }

    /**
     * 计算字节数组的 SHA-256 哈希
     */
    fun computeHashBytes(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    /**
     * 流式计算文件的 SHA-256 哈希。
     *
     * 与 [computeHashBytes] 的区别：这里按 64KB 缓冲区边读边算，**不会把整个文件载入内存**，
     * 因此可以安全地对 GB 级大文件求哈希，避免 OOM。
     */
    fun computeHashFile(file: java.io.File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ==================== 二进制分块传输专用（免 Base64，省 33% 传输量） ====================

    /**
     * 加密二进制数据并**直接返回字节数组**（不做 Base64 编码）。
     *
     * 返回结构：`nonce(12B) || ciphertext || tag(16B)`，与 [encryptBytes] 的字节布局完全一致，
     * 区别仅在于不做 Base64、减少 33% 的体积膨胀，供文件分块以二进制 body 直接上传。
     */
    fun encryptToBytes(data: ByteArray, keyStr: String): ByteArray {
        val keyBytes = deriveKey(keyStr)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        val ciphertextAndTag = cipher.doFinal(data)
        val payload = ByteArray(iv.size + ciphertextAndTag.size)
        System.arraycopy(iv, 0, payload, 0, iv.size)
        System.arraycopy(ciphertextAndTag, 0, payload, iv.size, ciphertextAndTag.size)
        return payload
    }

    /**
     * 从二进制密文（`nonce||ciphertext||tag`）解密出原始字节，与 [encryptToBytes] 配对使用。
     */
    fun decryptFromBytes(payload: ByteArray, keyStr: String): ByteArray {
        if (payload.size < 12 + 16) {
            throw IllegalArgumentException("密文长度不足: ${payload.size}")
        }
        val keyBytes = deriveKey(keyStr)
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val iv = payload.copyOfRange(0, 12)
        val ciphertextAndTag = payload.copyOfRange(12, payload.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        return cipher.doFinal(ciphertextAndTag)
    }
}
