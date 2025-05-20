package com.olup.notable.classes

import android.content.Context
import android.content.SharedPreferences
import java.util.*

class AnonymousUserManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("convex_prefs", Context.MODE_PRIVATE)
    private val anonymousIdKey = "anonymous_user_id"
    
    fun getAnonymousId(): String {
        var anonymousId = prefs.getString(anonymousIdKey, null)
        
        if (anonymousId == null) {
            // Create a new anonymous ID with the format "anon-{timestamp}-{random string}"
            val timestamp = System.currentTimeMillis()
            val randomString = UUID.randomUUID().toString().substring(0, 8)
            anonymousId = "anon-$timestamp-$randomString"
            
            // Save the new ID
            prefs.edit().putString(anonymousIdKey, anonymousId).apply()
        }
        
        return anonymousId
    }
}
