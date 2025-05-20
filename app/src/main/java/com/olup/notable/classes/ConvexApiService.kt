package com.olup.notable.classes

import retrofit2.Call
import retrofit2.http.*

interface ConvexApiService {
    // Chat endpoints
    @POST("api/chat/createThread")
    fun createThread(@Body request: CreateThreadRequest): Call<CreateThreadResponse>
    
    @POST("api/chat/continueThread")
    fun continueThread(@Body request: ContinueThreadRequest): Call<ContinueThreadResponse>
    
    @GET("api/chat/getThreadMessages")
    fun getThreadMessages(@Query("threadId") threadId: String): Call<GetThreadMessagesResponse>
    
    @GET("api/chat/getUserThreads")
    fun getUserThreads(): Call<GetUserThreadsResponse>
    
    // Authentication endpoint for anonymous users
    @POST("api/auth/anonymous")
    fun authenticateAnonymous(@Body request: AnonymousAuthRequest): Call<AuthResponse>
}

// Request models
data class CreateThreadRequest(
    val prompt: String,
    val userId: String? = null
)

data class ContinueThreadRequest(
    val prompt: String,
    val threadId: String,
    val userId: String? = null
)

data class AnonymousAuthRequest(
    val anonymousId: String,
    val deviceId: String? = null
)

// Response models
data class CreateThreadResponse(
    val threadId: String,
    val initialResponse: String,
    val messageId: String
)

data class ContinueThreadResponse(
    val response: String,
    val messageId: String
)

data class Message(
    val id: String,
    val content: String,
    val sender: String,
    val timestamp: Long,
    val status: String?
)

data class GetThreadMessagesResponse(
    val messages: List<Message>
)

data class Thread(
    val _id: String,
    val title: String?,
    val lastActivityTime: Long
)

data class GetUserThreadsResponse(
    val threads: List<Thread>
)

data class AuthResponse(
    val success: Boolean,
    val token: String?,
    val error: String?
)
