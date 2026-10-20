package com.example.audio

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * High-performance AES-GCM (Galois/Counter Mode) encryption helper.
 * Provides authenticated encryption with associated data (AEAD).
 * Ensures both CONFIDENTIALITY (cannot be eavesdropped) and INTEGRITY (cannot be tampered with).
 */
object CryptoHelper {

    private const val ALGORITHM = "AES"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12 // 96 bits standard for GCM
    private const val GCM_TAG_LENGTH = 128 // 128-bit authentication tag

    private val secureRandom = SecureRandom()

    /**
     * Generates a cryptographically strong 256-bit AES key.
     */
    fun generateKey(): SecretKey {
        val keyGen = KeyGenerator.getInstance(ALGORITHM)
        keyGen.init(256, secureRandom)
        return keyGen.generateKey()
    }

    /**
     * Converts a raw 32-byte key into a SecretKeySpec.
     */
    fun keyFromBytes(keyBytes: ByteArray): SecretKey {
        return SecretKeySpec(keyBytes, ALGORITHM)
    }

    /**
     * Encrypts plaintext bytes using AES-GCM.
     * Output format: [12-byte IV] + [Ciphertext + 16-byte GCM Tag]
     */
    fun encrypt(plainBytes: ByteArray, length: Int, key: SecretKey): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        secureRandom.nextBytes(iv)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)

        val cipherBytes = cipher.doFinal(plainBytes, 0, length)
        val result = ByteArray(GCM_IV_LENGTH + cipherBytes.size)

        System.arraycopy(iv, 0, result, 0, GCM_IV_LENGTH)
        System.arraycopy(cipherBytes, 0, result, GCM_IV_LENGTH, cipherBytes.size)

        return result
    }

    /**
     * Decrypts encrypted bytes using AES-GCM.
     * Expects input format: [12-byte IV] + [Ciphertext + 16-byte GCM Tag]
     * Returns null if decryption fails (e.g., packet was tampered with or key is invalid).
     */
    fun decrypt(encryptedBytes: ByteArray, offset: Int, length: Int, key: SecretKey): ByteArray? {
        if (length <= GCM_IV_LENGTH) return null

        return try {
            val iv = ByteArray(GCM_IV_LENGTH)
            System.arraycopy(encryptedBytes, offset, iv, 0, GCM_IV_LENGTH)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val cipherLength = length - GCM_IV_LENGTH
            cipher.doFinal(encryptedBytes, offset + GCM_IV_LENGTH, cipherLength)
        } catch (e: Exception) {
            // Authentication tag failed or payload was corrupted -> reject packet!
            null
        }
    }
}
