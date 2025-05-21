package com.olup.notable

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import com.olup.notable.db.AppDatabase
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job


val pressure = EpdController.getMaxTouchPressure()

// keep reference of the surface view presently associated to the singleton touchhelper
var referencedSurfaceView: String = ""


class DrawCanvas(
    val _context: Context,
    val coroutineScope: CoroutineScope,
    val state: EditorState,
    val page: PageView,
    val history: History
) : SurfaceView(_context) {

    private val strokeHistoryBatch = mutableListOf<String>()
    private val commitHistorySignal = MutableSharedFlow<Unit>()

    private var textToDraw: String? = null
    private var textProgress = 0f
    private var textAnimationJob: Job? = null

    private var isLoading = false
    private var rotationAngle = 0f
    private var loadingAnimationJob: Job? = null

    companion object {
        var forceUpdate = MutableSharedFlow<Rect?>()
        var refreshUi = MutableSharedFlow<Unit>()
        var isDrawing = MutableSharedFlow<Boolean>()
        var restartAfterConfChange = MutableSharedFlow<Unit>()
        var drawText = MutableSharedFlow<String>()
        var startLoading = MutableSharedFlow<Unit>()
        var stopLoading = MutableSharedFlow<Unit>()
    }

    fun getActualState(): EditorState {
        return this.state
    }

    private val inputCallback: RawInputCallback = object : RawInputCallback() {

        override fun onBeginRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawDrawing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointMoveReceived(p0: TouchPoint?) {
        }

        override fun onRawDrawingTouchPointListReceived(plist: TouchPointList) {
            thread(true) {
                if (getActualState().mode == Mode.Erase) {
                    handleErase(
                        this@DrawCanvas.page,
                        history,
                        plist.points.map { SimplePointF(it.x, it.y + page.scroll) },
                        eraser = getActualState().eraser
                    )
                    drawCanvasToView()
                    refreshUi()
                }

                if (getActualState().mode == Mode.Draw) {
                    handleDraw(
                        this@DrawCanvas.page,
                        strokeHistoryBatch,
                        getActualState().penSettings[getActualState().pen.penName]!!.strokeSize,
                        getActualState().pen,
                        plist.points
                    )
                    coroutineScope.launch {
                        commitHistorySignal.emit(Unit)
                    }
                }

                if (getActualState().mode == Mode.Select) {
                    handleSelect(coroutineScope,
                        this@DrawCanvas.page,
                        getActualState(),
                        plist.points.map { SimplePointF(it.x, it.y + page.scroll) })
                    drawCanvasToView()
                    refreshUi()
                }
            }
        }


        override fun onBeginRawErasing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onEndRawErasing(p0: Boolean, p1: TouchPoint?) {
        }

        override fun onRawErasingTouchPointListReceived(plist: TouchPointList?) {
            if (plist == null) return
            handleErase(
                this@DrawCanvas.page,
                history,
                plist.points.map { SimplePointF(it.x, it.y + page.scroll) },
                eraser = getActualState().eraser
            )
            drawCanvasToView()
            refreshUi()
        }

        override fun onRawErasingTouchPointMoveReceived(p0: TouchPoint?) {
        }
    }

    private val touchHelper by lazy {
        referencedSurfaceView = this.hashCode().toString()
        TouchHelper.create(this, inputCallback)
    }

    fun init() {
        Log.i(TAG, "Initializing")

        // set Z order
//        setZOrderOnTop(true)

        val surfaceView = this

        val surfaceCallback: SurfaceHolder.Callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "surface created ${holder}")
                // set up the drawing surface
                updateActiveSurface()

                // This is supposed to let the ui update while the old surface is being unmounted
                coroutineScope.launch {
                    forceUpdate.emit(null)
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                Log.i(TAG, "surface changed $holder")
                drawCanvasToView()
                updatePenAndStroke()
                refreshUi()
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(
                    TAG,
                    "surface destroyed ${
                        this@DrawCanvas.hashCode().toString()
                    } - ref ${referencedSurfaceView}"
                )
                holder.removeCallback(this)
                if (referencedSurfaceView == this@DrawCanvas.hashCode().toString()) {
                    touchHelper.closeRawDrawing()
                }
            }
        }

        this.holder.addCallback(surfaceCallback)

    }

    fun registerObservers() {
        // observe forceUpdate
        coroutineScope.launch {
            forceUpdate.collect { zoneAffected ->
                Log.i(TAG, "Force update zone $zoneAffected")

                if (zoneAffected != null) page.drawArea(
                    area = Rect(
                        zoneAffected.left,
                        zoneAffected.top - page.scroll,
                        zoneAffected.right,
                        zoneAffected.bottom - page.scroll
                    ),
                )

                refreshUi()
            }
        }

        // observe drawText
        coroutineScope.launch {
            drawText.collect { text ->
                Log.i(TAG, "Received text to draw: $text")
                startTextAnimation(text)
            }
        }

        // observe refreshUi
        coroutineScope.launch {
            refreshUi.collect {
                refreshUi()
            }
        }
        coroutineScope.launch {
            isDrawing.collect {
                state.isDrawing = it
            }
        }


        // observe restartcount
        coroutineScope.launch {
            restartAfterConfChange.collect {
                init()
                drawCanvasToView()
                refreshUi()
            }
        }

        // observe paen and stroke size
        coroutineScope.launch {
            snapshotFlow { state.pen }.drop(1).collect {
                Log.i(TAG, "pen change: ${state.pen}")
                updatePenAndStroke()
                refreshUi()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.penSettings.toMap() }.drop(1).collect {
                Log.i(TAG, "pen settings change: ${state.penSettings}")
                updatePenAndStroke()
                refreshUi()
            }
        }
        coroutineScope.launch {
            snapshotFlow { state.eraser }.drop(1).collect {
                Log.i(TAG, "eraser change: ${state.eraser}")
                updatePenAndStroke()
                refreshUi()
            }
        }

        // observe is drawing
        coroutineScope.launch {
            snapshotFlow { state.isDrawing }.drop(1).collect {
                Log.i(TAG, "isDrawing change: ${state.isDrawing}")
                updateIsDrawing()
            }
        }

        // observe toolbar open
        coroutineScope.launch {
            snapshotFlow { state.isToolbarOpen }.drop(1).collect {
                Log.i(TAG, "istoolbaropen change: ${state.isToolbarOpen}")
                updateActiveSurface()
            }
        }

        // observe mode
        coroutineScope.launch {
            snapshotFlow { getActualState().mode }.drop(1).collect {
                Log.i(TAG, "mode change: ${getActualState().mode}")
                updatePenAndStroke()
                refreshUi()
            }
        }

        coroutineScope.launch {
            commitHistorySignal.debounce(500).collect {
                Log.i(TAG, "Commiting")
                if (strokeHistoryBatch.size > 0) history.addOperationsToHistory(
                    operations = listOf(
                        Operation.DeleteStroke(strokeHistoryBatch.map { it })
                    )
                )
                strokeHistoryBatch.clear()
            }
        }

        // Add loading state observers
        coroutineScope.launch {
            startLoading.collect {
                isLoading = true
                startLoadingAnimation()
            }
        }

        coroutineScope.launch {
            stopLoading.collect {
                isLoading = false
                loadingAnimationJob?.cancel()
                refreshUi()
            }
        }
    }

    fun refreshUi() {
//        Log.i(TAG, "Refreshing ui. isDrawing : ${state.isDrawing}")
        drawCanvasToView()

        if (state.isDrawing) {
            // reset screen freeze
            touchHelper.setRawDrawingEnabled(false)
            touchHelper.setRawDrawingEnabled(true) // screen won't freeze until you actually stoke
        }
    }

    fun drawCanvasToView() {
//        Log.i(TAG, "Draw canvas")
        val canvas = this.holder.lockCanvas() ?: return
        canvas.drawBitmap(page.windowedBitmap, 0f, 0f, Paint());

        if (getActualState().mode == Mode.Select) {
            // render selection
            if (getActualState().selectionState.firstPageCut != null) {
                Log.i(TAG, "rendercut")

                val path = pointsToPath(getActualState().selectionState.firstPageCut!!.map {
                    SimplePointF(
                        it.x, it.y - page.scroll
                    )
                })
                canvas.drawPath(path, selectPaint)
            }
        }

        // Draw loading spinner if loading
        if (isLoading) {
            drawLoadingSpinner(canvas)
        }

        renderText(canvas)

        // finish rendering
        this.holder.unlockCanvasAndPost(canvas)
    }

    fun updateIsDrawing() {
        Log.i(TAG, "Update is drawing : ${state.isDrawing}")
        if (state.isDrawing) {
            touchHelper.setRawDrawingEnabled(true)
        } else {
            drawCanvasToView()
            touchHelper.setRawDrawingEnabled(false)
        }
    }

    fun updatePenAndStroke() {
        Log.i(TAG, "Update pen and stroke")
        when (state.mode) {
            Mode.Draw -> touchHelper.setStrokeStyle(penToStroke(state.pen))
                ?.setStrokeWidth(state.penSettings[state.pen.penName]!!.strokeSize)
                ?.setStrokeColor(state.penSettings[state.pen.penName]!!.color)
            Mode.Erase -> {
                when (state.eraser) {
                    Eraser.PEN -> touchHelper.setStrokeStyle(penToStroke(Pen.MARKER))
                        ?.setStrokeWidth(30f)
                        ?.setStrokeColor(Color.GRAY)
                    Eraser.SELECT -> touchHelper.setStrokeStyle(penToStroke(Pen.BALLPEN))
                        ?.setStrokeWidth(3f)
                        ?.setStrokeColor(Color.GRAY)
                }
            }
            Mode.Select -> touchHelper.setStrokeStyle(penToStroke(Pen.BALLPEN))?.setStrokeWidth(3f)
                ?.setStrokeColor(Color.GRAY)
        }
    }

    fun updateActiveSurface() {
        Log.i(TAG, "Update editable surface")

        //even if toolbar is not open, still exclude
        val exclusionHeight = convertDpToPixel(40.dp, context).toInt()

        touchHelper.setRawDrawingEnabled(false)
        touchHelper.closeRawDrawing()

        touchHelper.setLimitRect(
            mutableListOf(
                android.graphics.Rect(
                    0, 0, this.width, this.height
                )
            )
        ).setExcludeRect(listOf(android.graphics.Rect(0, 0, this.width, exclusionHeight)))
            .openRawDrawing()

        touchHelper.setRawDrawingEnabled(true)
        updatePenAndStroke()

        refreshUi()
    }


    private fun startTextAnimation(text: String) {
        // Instead of animating, just refresh UI to show all messages
        refreshUi()
    }

    private val markwon by lazy {
        Markwon.builder(_context)
            .usePlugin(TablePlugin.create(_context))
            .usePlugin(HtmlPlugin.create())
            .build()
    }

    private fun renderText(canvas: Canvas) {
        val messages = AppDatabase.getDatabase(_context).chatMessageDao().getMessagesForPage(page.id)
        if (messages.isEmpty()) return

        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 40f
            textAlign = Paint.Align.LEFT
            style = Paint.Style.FILL
            alpha = 255
        }

        val maxWidth = canvas.width - 80f
        var absoluteY = convertDpToPixel(60.dp, context)
        val lineHeight = paint.fontSpacing
        val messageSpacing = lineHeight * 0.5f
        val visibleRect = Rect(0, page.scroll, canvas.width, page.scroll + canvas.height)

        messages.forEach { message ->
            if (message.role == "system") return@forEach

            val prefix = if (message.role == "assistant") "Assistant: " else "You: "
            
            // Create a layout for the text
            val layout = if (message.role == "assistant") {
                // For assistant messages, use Markwon to create a layout
                val spannedText = markwon.toMarkdown(prefix + message.content)
                StaticLayout.Builder.obtain(spannedText, 0, spannedText.length, TextPaint(paint), maxWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(true)
                    .build()
            } else {
                // For user messages, create a regular layout
                val text = prefix + message.content
                StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), maxWidth.toInt())
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1f)
                    .setIncludePad(true)
                    .build()
            }

            val messageHeight = layout.height.toFloat()
            val messageRect = RectF(0f, absoluteY, canvas.width.toFloat(), absoluteY + messageHeight)

            if (messageRect.intersects(visibleRect.left.toFloat(), visibleRect.top.toFloat(),
                                    visibleRect.right.toFloat(), visibleRect.bottom.toFloat())) {
                canvas.save()
                canvas.translate(40f, absoluteY - page.scroll)
                layout.draw(canvas)
                canvas.restore()
            }

            absoluteY += messageHeight + messageSpacing
        }
    }

    private fun startLoadingAnimation() {
        loadingAnimationJob?.cancel()
        loadingAnimationJob = coroutineScope.launch {
            while (isLoading) {
                rotationAngle = (rotationAngle + 10) % 360
                refreshUi()
                delay(50) // Control animation speed
            }
        }
    }

    private fun drawLoadingSpinner(canvas: Canvas) {
        if (!isLoading) return

        val centerX = canvas.width / 2f
        val centerY = canvas.height / 2f
        val radius = 50f

        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        
        // Draw spinning arc
        val rect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        canvas.drawArc(rect, 0f, 270f, false, loadingPaint)
        
        canvas.restore()
    }

    // Add loading animation paint
    private val loadingPaint = Paint().apply {
        color = Color.GRAY
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
    }
}
