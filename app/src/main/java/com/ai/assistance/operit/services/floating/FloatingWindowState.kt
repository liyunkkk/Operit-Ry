package com.ai.assistance.operit.services.floating

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.ui.floating.FloatingMode

class FloatingWindowState(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("floating_chat_prefs", Context.MODE_PRIVATE)
    private val screenWidthDp: Dp
    private val screenHeightDp: Dp
    private val maxWidth: Float
    private val minWidth: Float
    private val maxHeight: Float

    // Window position
    var x: Int = 200
    var y: Int = 200

    // Window size
    val windowWidth = mutableStateOf(300.dp)
    val windowHeight = mutableStateOf(400.dp)
    val windowScale = mutableStateOf(1f)
    var lastWindowScale: Float = 1f

    // Mode state
    val currentMode = mutableStateOf(FloatingMode.WINDOW)
    var previousMode: FloatingMode = FloatingMode.WINDOW
    val ballSize = mutableStateOf(60.dp)
    val isAtEdge = mutableStateOf(false)

    // Transition state
    var lastWindowPositionX: Int = 0
    var lastWindowPositionY: Int = 0
    var lastBallPositionX: Int = 0
    var lastBallPositionY: Int = 0
    var isTransitioning = false
    val petModeTransition = mutableStateOf(false)
    val transitionDebounceTime = 500L // 防抖时间
    
    // Ball explosion animation state
    val ballExploding = mutableStateOf(false)

    // Whether system-level cross-window blur is actually active for fullscreen
    val fullscreenSystemBlurActive = mutableStateOf(false)

    init {
        val displayMetrics = context.resources.displayMetrics
        screenWidthDp = (displayMetrics.widthPixels / displayMetrics.density).dp
        screenHeightDp = (displayMetrics.heightPixels / displayMetrics.density).dp
        maxWidth = (screenWidthDp.value - 16f).takeIf { it.isFinite() }?.coerceAtLeast(1f) ?: 300f
        minWidth = minOf(300f, maxWidth)
        maxHeight = (screenHeightDp.value * 0.8f).takeIf { it.isFinite() }?.coerceAtLeast(250f) ?: 400f
        restoreState()
    }

    fun saveState() {
        prefs.edit().apply {
            putInt("window_x", x)
            putInt("window_y", y)
            putFloat(
                "window_width",
                (windowWidth.value.value.takeIf { it.isFinite() } ?: maxWidth).coerceIn(minWidth, maxWidth)
            )
            putFloat(
                "window_height",
                (windowHeight.value.value.takeIf { it.isFinite() } ?: 400f).coerceIn(250f, maxHeight)
            )
            putString("current_mode", currentMode.value.name)
            putString("previous_mode", previousMode.name)
            putFloat("window_scale", (windowScale.value.takeIf { it.isFinite() } ?: 1f).coerceIn(0.3f, 1.0f))
            putFloat("last_window_scale", (lastWindowScale.takeIf { it.isFinite() } ?: 1f).coerceIn(0.3f, 1.0f))
            apply()
        }
    }

    fun restoreState() {
        val defaultX = 200
        val defaultY = 200
        x = prefs.getInt("window_x", defaultX)
        y = prefs.getInt("window_y", defaultY)

        val defaultWidth = maxWidth
        val defaultHeight = (screenHeightDp.value * 0.5f).takeIf { it.isFinite() }?.coerceIn(250f, maxHeight)
            ?: 400f.coerceIn(250f, maxHeight)
        val storedWidth = prefs.getFloat("window_width", defaultWidth)
        val storedHeight = prefs.getFloat("window_height", defaultHeight)
        windowWidth.value = (storedWidth.takeIf { it.isFinite() } ?: defaultWidth).coerceIn(minWidth, maxWidth).dp
        windowHeight.value = (storedHeight.takeIf { it.isFinite() } ?: defaultHeight).coerceIn(250f, maxHeight).dp

        val modeName = prefs.getString("current_mode", FloatingMode.WINDOW.name)
        currentMode.value = try {
            FloatingMode.valueOf(modeName ?: FloatingMode.WINDOW.name)
        } catch (_: Exception) {
            FloatingMode.WINDOW
        }

        val prevModeName = prefs.getString("previous_mode", FloatingMode.WINDOW.name)
        previousMode = try {
            FloatingMode.valueOf(prevModeName ?: FloatingMode.WINDOW.name)
        } catch (_: Exception) {
            FloatingMode.WINDOW
        }

        windowScale.value = 1f
        lastWindowScale = 1f
    }
}
