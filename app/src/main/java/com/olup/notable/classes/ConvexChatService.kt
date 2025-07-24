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
    private val authManager = AuthManager(context)
    private val secureTokenStorage = SecureTokenStorage(context)
    private val apiService = ConvexApiClient.apiService
    private val authInterceptor = (ConvexApiClient.httpClient.interceptors.find { it is AuthInterceptor } as? AuthInterceptor)
    
    private var isInitialized = false
    private val sharedPreferences = context.getSharedPreferences("convex_chat_prefs", Context.MODE_PRIVATE)
    // Key prefix for thread IDs in SharedPreferences
    private val THREAD_ID_PREFIX = "thread_id_"

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext true
        
        try {
            // First check if we have a valid stored token
            if (secureTokenStorage.hasValidToken()) {
                val storedToken = secureTokenStorage.retrieveToken()
                if (storedToken != null) {
                    Log.d(TAG, "Using stored secure token")
                    authInterceptor?.setToken(storedToken)
                    isInitialized = true
                    return@withContext true
                }
            }
            
            // Check if we have a token from auth manager
            val token = authManager.getAuthToken()
            if (!token.isNullOrEmpty()) {
                Log.d(TAG, "Using auth manager token")
                // Store the token securely for future use
                secureTokenStorage.storeToken(token)
                authInterceptor?.setToken(token)
                isInitialized = true
                return@withContext true
            }
            
            // Fall back to anonymous authentication
            val anonymousId = anonymousUserManager.getAnonymousId()
            val deviceId = android.os.Build.MODEL
            
            Log.d(TAG, "Using anonymous authentication with ID: $anonymousId")
            val result = suspendCancellableCoroutine<Boolean> { continuation ->
                apiService.authenticateAnonymous(AnonymousAuthRequest(anonymousId, deviceId))
                    .enqueue(object : Callback<AuthResponse> {
                        override fun onResponse(call: Call<AuthResponse>, response: Response<AuthResponse>) {
                            val authResponse = response.body()
                            if (authResponse?.success == true && authResponse.token != null) {
                                Log.d(TAG, "Anonymous authentication successful")
                                // Store the anonymous token securely
                                secureTokenStorage.storeToken(authResponse.token)
                                authInterceptor?.setToken(authResponse.token)
                                continuation.resume(true)
                            } else {
                                Log.e(TAG, "Anonymous authentication failed: ${response.errorBody()?.string()}")
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

    /**
     * Clear stored authentication token (useful for logout)
     */
    fun clearAuthToken() {
        secureTokenStorage.clearToken()
        isInitialized = false
        Log.d(TAG, "Authentication token cleared")
    }

    // Helper method to save threadId to SharedPreferences
    private fun saveThreadId(pageId: String, threadId: String) {
        val key = THREAD_ID_PREFIX + pageId
        Log.d(TAG, "Saving thread ID: $threadId for pageId: $pageId with key: $key")
        sharedPreferences.edit().putString(key, threadId).apply()
    }
    
    // Helper method to retrieve threadId from SharedPreferences
    private fun getThreadId(pageId: String): String? {
        val key = THREAD_ID_PREFIX + pageId
        val threadId = sharedPreferences.getString(key, null)
        Log.d(TAG, "Retrieved thread ID: $threadId for pageId: $pageId with key: $key")
        return threadId
    }
    
    suspend fun getCompletion(pageId: String, prompt: String): String = withContext(Dispatchers.IO) {
        try {
            // Initialize if not already done
            if (!isInitialized && !initialize()) {
                return@withContext "Error: Failed to initialize chat service"
            }

            // Check if we have a valid token (either from secure storage or auth manager)
            val hasValidToken = secureTokenStorage.hasValidToken() || !authManager.getAuthToken().isNullOrEmpty()
            Log.d(TAG, "Authentication status: hasValidToken=$hasValidToken")

            // Add user message to local repository
            chatRepository.addMessage(pageId, "user", prompt)
            
            val threadId = getThreadId(pageId)
            Log.d(TAG, "Thread ID for page $pageId: $threadId")
            
            if (threadId == null) {
                Log.d(TAG, "No existing thread found for page $pageId, creating new thread")
                // Create a new thread
                return@withContext createNewThread(pageId, prompt)
            } else {
                Log.d(TAG, "Continuing existing thread $threadId for page $pageId")
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
            // Get the auth token directly instead of relying on isAuthenticated()
            val token = authManager.getAuthToken()
            
            // Use authenticated user ID if available, otherwise use anonymous ID
            val userId = if (!token.isNullOrEmpty() && token.startsWith("user_")) {
                Log.d(TAG, "Using authenticated user ID for new thread: $token")
                token
            } else {
                val anonId = anonymousUserManager.getAnonymousId()
                Log.d(TAG, "Using anonymous ID for new thread: $anonId")
                anonId
            }
            
            Log.d(TAG, "Creating new thread with pageId: $pageId, userId: $userId")
            apiService.createThread(CreateThreadRequest(prompt, userId))
                .enqueue(object : Callback<CreateThreadResponse> {
                    override fun onResponse(call: Call<CreateThreadResponse>, response: Response<CreateThreadResponse>) {
                        val createResponse = response.body()
                        if (createResponse != null) {
                            Log.d(TAG, "Thread created with ID: ${createResponse.threadId}")
                            // Store the thread ID for future use
                            saveThreadId(pageId, createResponse.threadId)
                            
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
            // Get the auth token directly instead of relying on isAuthenticated()
            val token = authManager.getAuthToken()
            
            // Use authenticated user ID if available, otherwise use anonymous ID
            val userId = if (!token.isNullOrEmpty() && token.startsWith("user_")) {
                Log.d(TAG, "Using authenticated user ID for continuing thread: $token")
                token
            } else {
                val anonId = anonymousUserManager.getAnonymousId()
                Log.d(TAG, "Using anonymous ID for continuing thread: $anonId")
                anonId
            }
            
            Log.d(TAG, "Continuing thread $threadId for pageId: $pageId, userId: $userId")
            apiService.continueThread(ContinueThreadRequest(prompt, threadId, userId))
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
