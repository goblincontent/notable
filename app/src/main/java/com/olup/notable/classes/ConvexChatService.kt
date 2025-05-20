package com.olup.notable.classes

import android.content.Context
import android.util.Log
import com.olup.notable.db.ChatMessageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ConvexChatService(private val context: Context) {
    companion object {
        private const val TAG = "ConvexChatService"
    }

    private val chatRepository: ChatMessageRepository = ChatMessageRepository(context)
    private val anonymousUserManager = AnonymousUserManager(context)
    private val apiService = ConvexApiClient.apiService
    private val authInterceptor = (ConvexApiClient.httpClient.interceptors.find { it is AuthInterceptor } as? AuthInterceptor)
    
    private var isInitialized = false
    private val threadIdMap = mutableMapOf<String, String>() // Maps pageId to threadId

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        
        try {
            val anonymousId = anonymousUserManager.getAnonymousId()
            val deviceId = android.os.Build.MODEL
            
            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                apiService.authenticateAnonymous(AnonymousAuthRequest(anonymousId, deviceId))
                    .enqueue(object : Callback<AuthResponse> {
                        override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
                            val authResponse = response.body()
                            if (authResponse?.success == true && authResponse.token != null) {
                                authInterceptor?.setToken(authResponse.token)
                                continuation.resume(true)
                            } else {
                                continuation.resume(false)
                            }
                        }
                        
                        override fun onFailure(call: Call<AuthResponse>, t: Throwable) {
                            Log.e(TAG, "Authentication failed", t)
                            continuation.resume(false)
                        }
                    })
            }
            
            isInitialized = result
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error initializing ConvexChatService", e)
            return@withContext false
        }
    }

    suspend fun getCompletion(pageId: String, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            // Initialize if not already done
            if (!isInitialized && !initialize()) {
                return@withContext "Error: Failed to initialize chat service"
            }

            // Add user message to local repository
            chatRepository.addMessage(pageId, "user", prompt)
            
            val threadId = threadIdMap[pageId]
            
            if (threadId == null) {
                // Create a new thread
                return@withContext createNewThread(pageId, prompt)
            } else {
                // Continue existing thread
                return@withContext continueThread(pageId, threadId, prompt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in getCompletion", e)
            return@withContext "Error: ${e.message}"
        }
    }
    
    private suspend fun createNewThread(pageId: String, prompt: String): String {
        return suspendCancellableCoroutine { continuation ->
            val anonymousId = anonymousUserManager.getAnonymousId()
            
            apiService.createThread(CreateThreadRequest(prompt, anonymousId))
                .enqueue(object : Callback<CreateThreadResponse> {
                    override fun onResponse(call: Call<CreateThreadResponse>, response: Response<CreateThreadResponse>) {
                        val createResponse = response.body()
                        if (createResponse != null) {
                            // Store the thread ID for future use
                            threadIdMap[pageId] = createResponse.threadId
                            
                            // Add the response to the chat repository
                            chatRepository.addMessage(pageId, "assistant", createResponse.initialResponse)
                            
                            continuation.resume(createResponse.initialResponse)
                        } else {
                            val errorMsg = "Error: Failed to create thread"
                            Log.e(TAG, errorMsg)
                            continuation.resume(errorMsg)
                        }
                    }
                    
                    override fun onFailure(call: Call<CreateThreadResponse>, t: Throwable) {
                        Log.e(TAG, "Failed to create thread", t)
                        continuation.resume("Error: ${t.message}")
                    }
                })
        }
    }
    
    private suspend fun continueThread(pageId: String, threadId: String, prompt: String): String {
        return suspendCancellableCoroutine { continuation ->
            val anonymousId = anonymousUserManager.getAnonymousId()
            
            apiService.continueThread(ContinueThreadRequest(prompt, threadId, anonymousId))
                .enqueue(object : Callback<ContinueThreadResponse> {
                    override fun onResponse(call: Call<ContinueThreadResponse>, response: Response<ContinueThreadResponse>) {
                        val continueResponse = response.body()
                        if (continueResponse != null) {
                            // Add the response to the chat repository
                            chatRepository.addMessage(pageId, "assistant", continueResponse.response)
                            
                            continuation.resume(continueResponse.response)
                        } else {
                            val errorMsg = "Error: Failed to continue thread"
                            Log.e(TAG, errorMsg)
                            continuation.resume(errorMsg)
                        }
                    }
                    
                    override fun onFailure(call: Call<ContinueThreadResponse>, t: Throwable) {
                        Log.e(TAG, "Failed to continue thread", t)
                        continuation.resume("Error: ${t.message}")
                    }
                })
        }
    }
    
    // Utility method to get all messages for a thread
    suspend fun getThreadMessages(threadId: String): List<Message>? = withContext(Dispatchers.IO) {
        try {
            return@withContext suspendCancellableCoroutine<List<Message>?> { continuation ->
                apiService.getThreadMessages(threadId)
                    .enqueue(object : Callback<GetThreadMessagesResponse> {
                        override fun onResponse(call: Call<GetThreadMessagesResponse>, response: Response<GetThreadMessagesResponse>) {
                            continuation.resume(response.body()?.messages)
                        }
                        
                        override fun onFailure(call: Call<GetThreadMessagesResponse>, t: Throwable) {
                            Log.e(TAG, "Failed to get thread messages", t)
                            continuation.resume(null)
                        }
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting thread messages", e)
            return@withContext null
        }
    }
    
    // Utility method to get all threads for the current user
    suspend fun getUserThreads(): List<Thread>? = withContext(Dispatchers.IO) {
        try {
            return@withContext suspendCancellableCoroutine<List<Thread>?> { continuation ->
                apiService.getUserThreads()
                    .enqueue(object : Callback<GetUserThreadsResponse> {
                        override fun onResponse(call: Call<GetUserThreadsResponse>, response: Response<GetUserThreadsResponse>) {
                            continuation.resume(response.body()?.threads)
                        }
                        
                        override fun onFailure(call: Call<GetUserThreadsResponse>, t: Throwable) {
                            Log.e(TAG, "Failed to get user threads", t)
                            continuation.resume(null)
                        }
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user threads", e)
            return@withContext null
        }
    }
}
