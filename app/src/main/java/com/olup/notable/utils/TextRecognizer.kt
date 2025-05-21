package com.olup.notable

import android.util.Log
import com.google.mlkit.vision.digitalink.*
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TextRecognizer {
    companion object {
        private const val TAG = "TextRecognizer"

        suspend fun recognizeText(
            scope: CoroutineScope,
            pageView: PageView,
            onStart: suspend () -> Unit,
            onComplete: suspend () -> Unit,
            onTextRecognized: suspend (String) -> Unit
        ) {
            try {
                onStart()
                
                // Get strokes from the page and convert them to MLKit format
                val inkBuilder = Ink.builder()
                
                // Ensure strokes are added in left-to-right order
                
                pageView.strokes.forEach { stroke ->
                    val strokeBuilder = Ink.Stroke.builder()
                    stroke.points.forEach { point ->
                        strokeBuilder.addPoint(Ink.Point.create(point.x, point.y))
                    }
                    inkBuilder.addStroke(strokeBuilder.build())
                }
                
                // Get the model identifier
                val modelIdentifier = try {
                    DigitalInkRecognitionModelIdentifier.fromLanguageTag("en-US")
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting model identifier", e)
                    null
                } ?: throw Exception("Model identifier not found")
                
                val model = DigitalInkRecognitionModel.builder(modelIdentifier).build()
                
                val pageWidth = pageView.computeWidth().toFloat()
                val estimatedLineHeight = 50f
                
                // Create recognition context
                val recognitionContext = RecognitionContext.builder()
                    .setWritingArea(WritingArea(pageWidth, estimatedLineHeight))
                    .setPreContext("")
                    .build()
                
                // Check if model is already downloaded
                val modelManager = RemoteModelManager.getInstance()
                modelManager.isModelDownloaded(model)
                    .addOnSuccessListener { isDownloaded ->
                        if (!isDownloaded) {
                            Log.i(TAG, "Starting model download...")
                        }
                        
                        // Download model if needed
                        modelManager.download(model, DownloadConditions.Builder().build())
                            .addOnSuccessListener {
                                scope.launch {
                                    try {
                                        // Create recognizer
                                        val recognizer = DigitalInkRecognition.getClient(
                                            DigitalInkRecognizerOptions.builder(model).build()
                                        )

                                        // Build ink
                                        val ink = inkBuilder.build()

                                        // Perform recognition
                                        recognizer.recognize(ink, recognitionContext)
                                            .addOnSuccessListener { result ->
                                                scope.launch {
                                                    if (result.candidates.isNotEmpty()) {
                                                        val recognizedText = result.candidates[0].text
                                                        
                                                        // Show loading indicator
                                                        DrawCanvas.startLoading.emit(Unit)
                                                        
                                                        try {
                                                            // Create ConvexChatService instance with context
                                                            val convexChatService = com.olup.notable.classes.ConvexChatService(pageView.context)
                                                            
                                                            // Get completion from Convex with pageId
                                                            val response = convexChatService.getCompletion(pageView.id, recognizedText)
                                                            
                                                            // Clear the screen
                                                            pageView.clear()
                                                            DrawCanvas.refreshUi.emit(Unit)
                                                            
                                                            // Call onTextRecognized first with empty string to prevent override
                                                            onTextRecognized("")
                                                            
                                                            // Show the API response
                                                            DrawCanvas.drawText.emit(response)
                                                            
                                                        } catch (e: Exception) {
                                                            Log.e(TAG, "Error getting completion", e)
                                                            // In case of error, show the original text
                                                            onTextRecognized(recognizedText)
                                                        } finally {
                                                            // Hide loading indicator
                                                            DrawCanvas.stopLoading.emit(Unit)
                                                        }
                                                    } else {
                                                        Log.e(TAG, "No text candidates found")
                                                    }
                                                    onComplete()
                                                }
                                            }
                                            .addOnFailureListener { e ->
                                                scope.launch {
                                                    Log.e(TAG, "Recognition failed", e)
                                                    onComplete()
                                                }
                                            }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Error creating recognizer or processing ink", e)
                                        onComplete()
                                    }
                                }
                            }
                            .addOnFailureListener { e ->
                                scope.launch {
                                    Log.e(TAG, "Model download failed", e)
                                    onComplete()
                                }
                            }
                    }
                    .addOnFailureListener { e ->
                        scope.launch {
                            Log.e(TAG, "Error checking model download status", e)
                            onComplete()
                        }
                    }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in text recognition", e)
                e.printStackTrace()
                onComplete()
            }
        }
    }
}
