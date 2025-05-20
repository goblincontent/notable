package com.olup.notable.classes

import okhttp3.Interceptor
import okhttp3.Response

class AuthInterceptor : Interceptor {
    private var authToken: String? = null
    
    fun setToken(token: String) {
        authToken = token
    }
    
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        
        return if (authToken != null) {
            val request = original.newBuilder()
                .header("Authorization", "Bearer $authToken")
                .method(original.method, original.body)
                .build()
            
            chain.proceed(request)
        } else {
            chain.proceed(original)
        }
    }
}
