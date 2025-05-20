package com.olup.notable.classes

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages authentication state and tokens for the app
 */
class AuthManager(private val context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("auth_prefs", Context.MODE_PRIVATE)
    private val tokenKey = "auth_token"
    private val anonymousUserManager = AnonymousUserManager(context)
    
    /**
     * Check if the user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return !getAuthToken().isNullOrEmpty()
    }
    
    /**
     * Get the stored authentication token
     */
    fun getAuthToken(): String? {
        return prefs.getString(tokenKey, null)
    }
    
    /**
     * Save an authentication token
     */
    fun saveAuthToken(token: String) {
        prefs.edit().putString(tokenKey, token).apply()
    }
    
    /**
     * Clear the authentication token (logout)
     */
    fun clearAuthToken() {
        prefs.edit().remove(tokenKey).apply()
    }
    
    /**
     * Get the anonymous user ID
     */
    fun getAnonymousId(): String {
        return anonymousUserManager.getAnonymousId()
    }
}
