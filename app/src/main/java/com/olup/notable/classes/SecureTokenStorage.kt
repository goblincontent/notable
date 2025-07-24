package com.olup.notable.classes

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureTokenStorage(private val context: Context) {
    
    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_ALIAS = "ClerkTokenKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TOKEN_PREFS = "secure_tokens"
        private const val ENCRYPTED_TOKEN_KEY = "encrypted_clerk_token"
        private const val IV_KEY = "token_iv"
        private const val TAG = "SecureTokenStorage"
    }
    
    private val keyStore: KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply {
        load(null)
    }
    
    init {
        generateSecretKey()
    }
    
    private fun generateSecretKey() {
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            
            try {
                // Try to create a key with user authentication (more secure)
                val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(true) // Requires device unlock
                    .setUserAuthenticationValidityDurationSeconds(300) // 5 minutes
                    .setRandomizedEncryptionRequired(true)
                    .build()
                
                keyGenerator.init(keyGenParameterSpec)
                keyGenerator.generateKey()
                Log.d(TAG, "Created secure key with user authentication")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create key with user authentication, falling back to hardware-only security", e)
                
                // Fallback: Create key without user authentication requirement
                val fallbackKeyGenParameterSpec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                
                keyGenerator.init(fallbackKeyGenParameterSpec)
                keyGenerator.generateKey()
                Log.d(TAG, "Created secure key without user authentication (device has no secure lock screen)")
            }
        }
    }
    
    fun storeToken(token: String): Boolean {
        return try {
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            
            val encryptedToken = cipher.doFinal(token.toByteArray())
            val iv = cipher.iv
            
            // Store encrypted token and IV in SharedPreferences
            val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(ENCRYPTED_TOKEN_KEY, Base64.encodeToString(encryptedToken, Base64.DEFAULT))
                .putString(IV_KEY, Base64.encodeToString(iv, Base64.DEFAULT))
                .apply()
            
            Log.d(TAG, "Token stored successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store token", e)
            false
        }
    }
    
    fun retrieveToken(): String? {
        return try {
            val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
            val encryptedTokenString = prefs.getString(ENCRYPTED_TOKEN_KEY, null) ?: return null
            val ivString = prefs.getString(IV_KEY, null) ?: return null
            
            val encryptedToken = Base64.decode(encryptedTokenString, Base64.DEFAULT)
            val iv = Base64.decode(ivString, Base64.DEFAULT)
            
            val secretKey = keyStore.getKey(KEY_ALIAS, null) as SecretKey
            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            
            val decryptedToken = cipher.doFinal(encryptedToken)
            val token = String(decryptedToken)
            Log.d(TAG, "Token retrieved successfully")
            token
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve token", e)
            null
        }
    }
    
    fun clearToken() {
        val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(ENCRYPTED_TOKEN_KEY)
            .remove(IV_KEY)
            .apply()
        Log.d(TAG, "Token cleared")
    }
    
    fun hasValidToken(): Boolean {
        val token = retrieveToken()
        return token != null && isTokenValid(token)
    }
    
    private fun isTokenValid(token: String): Boolean {
        return try {
            // Decode JWT payload to check expiration
            val parts = token.split(".")
            if (parts.size != 3) return false
            
            val payload = parts[1]
            val paddedPayload = payload + "=".repeat((4 - payload.length % 4) % 4)
            val decodedPayload = String(Base64.decode(paddedPayload, Base64.DEFAULT))
            val jsonPayload = JSONObject(decodedPayload)
            
            val exp = jsonPayload.optLong("exp", 0)
            val currentTime = System.currentTimeMillis() / 1000
            
            exp > currentTime
        } catch (e: Exception) {
            Log.w(TAG, "Token validation failed", e)
            false
        }
    }
}
