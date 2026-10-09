package com.clipboardsync.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Luminescent Connection Orb for Wi-Fi Clipboard Sync.
 * Inspired by 21st.dev's "AI Thinking Orb and Input".
 *
 * States:
 * 1. SEARCHING: Amber (#F59E0B) & Cyan (#06B6D4) rotating orbital satellites & expanding radar rings.
 * 2. CONNECTED: Luminous Emerald (#10B981) breathing aura (gentle pulse every ~2.5s).
 * 3. SYNCING: Quick cyan-white shockwave ripple & flash across the container.
 *
 * Strict Battery Optimization:
 * Animators are automatically paused in onPause / background lifecycle to guarantee 0.0% battery drain.
 */
class LuminescentOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        SEARCHING,
        CONNECTED,
        SYNCING
    }

    private var currentState: State = State.SEARCHING
    private var baseState: State = State.SEARCHING
    private var isSyncing: Boolean = false

    // Animation progress values
    private var orbitalAngle: Float = 0f
    private var breathingPulse: Float = 0f
    private var shockwaveProgress: Float = 0f

    // ValueAnimators
    private var rotationAnimator: ValueAnimator? = null
    private var breathingAnimator: ValueAnimator? = null
    private var shockwaveAnimator: ValueAnimator? = null

    private var isPaused: Boolean = false

    // Color definitions
    private val colorEmerald = 0xFF10B981.toInt()
    private val colorEmeraldMint = 0xFF34D399.toInt()
    private val colorEmeraldCore = 0xFFECFDF5.toInt()
    private val colorAmber = 0xFFF59E0B.toInt()
    private val colorAmberCore = 0xFFFEF3C7.toInt()
    private val colorCyan = 0xFF06B6D4.toInt()
    private val colorCyanCore = 0xFFE0F2FE.toInt()

    // Paints
    private val auraPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satellitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val shockwavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    init {
        // Hardware acceleration is standard, ensure paint flags
        initAnimators()
    }

    private fun initAnimators() {
        // 1. Orbital rotation for SEARCHING state
        rotationAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 3000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { anim ->
                orbitalAngle = anim.animatedValue as Float
                if (currentState == State.SEARCHING || isSyncing) {
                    invalidate()
                }
            }
        }

        // 2. Breathing pulse for CONNECTED state (~2.5s cycle period)
        breathingAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2500L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                breathingPulse = anim.animatedValue as Float
                if (currentState == State.CONNECTED || isSyncing) {
                    invalidate()
                }
            }
        }

        // 3. Shockwave transmission ripple (450ms quick flash)
        shockwaveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 450L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { anim ->
                shockwaveProgress = anim.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    isSyncing = false
                    currentState = baseState
                    invalidate()
                }
            })
        }
    }

    fun setState(state: State) {
        if (state == State.SYNCING) {
            triggerSync()
            return
        }

        baseState = state
        if (!isSyncing) {
            currentState = state
        }

        updateAnimatorStates()
        invalidate()
    }

    fun getState(): State = currentState

    fun triggerSync() {
        isSyncing = true
        currentState = State.SYNCING
        shockwaveAnimator?.cancel()
        shockwaveAnimator?.start()
    }

    private fun updateAnimatorStates() {
        if (isPaused || !isAttachedToWindow || visibility != VISIBLE) {
            stopAllAnimators()
            return
        }

        when (currentState) {
            State.SEARCHING -> {
                breathingAnimator?.cancel()
                if (rotationAnimator?.isRunning != true) {
                    rotationAnimator?.start()
                }
            }
            State.CONNECTED -> {
                rotationAnimator?.cancel()
                if (breathingAnimator?.isRunning != true) {
                    breathingAnimator?.start()
                }
            }
            State.SYNCING -> {
                if (baseState == State.SEARCHING && rotationAnimator?.isRunning != true) {
                    rotationAnimator?.start()
                } else if (baseState == State.CONNECTED && breathingAnimator?.isRunning != true) {
                    breathingAnimator?.start()
                }
            }
        }
    }

    // Pre-computed cached shaders for zero allocations in onDraw()
    private var cachedBaseRadius = 0f
    private var fusionShader: Shader? = null
    private var amberGlowShader: Shader? = null
    private var cyanGlowShader: Shader? = null
    private var connectedAuraShader: Shader? = null
    private var innerCoronaShader: Shader? = null
    private var flashShader: Shader? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val baseRadius = minOf(w.toFloat(), h.toFloat()) / 2.7f
        cachedBaseRadius = baseRadius

        // 1. Searching shaders
        val fusionRadius = baseRadius * 0.5f
        val fusionColors = intArrayOf(0x6606B6D4, 0x44F59E0B.toInt(), 0x00000000)
        val fusionStops = floatArrayOf(0.0f, 0.55f, 1.0f)
        fusionShader = RadialGradient(0f, 0f, fusionRadius, fusionColors, fusionStops, Shader.TileMode.CLAMP)

        val satelliteGlowRadius = baseRadius * 0.28f
        amberGlowShader = RadialGradient(0f, 0f, satelliteGlowRadius, intArrayOf(0xBBF59E0B.toInt(), 0x00F59E0B), null, Shader.TileMode.CLAMP)
        cyanGlowShader = RadialGradient(0f, 0f, satelliteGlowRadius, intArrayOf(0xBB06B6D4.toInt(), 0x0006B6D4), null, Shader.TileMode.CLAMP)

        // 2. Connected shaders
        val auraColors = intArrayOf(0xDD10B981.toInt(), 0x7710B981, 0x0010B981)
        val auraStops = floatArrayOf(0.0f, 0.55f, 1.0f)
        connectedAuraShader = RadialGradient(0f, 0f, baseRadius, auraColors, auraStops, Shader.TileMode.CLAMP)

        val innerColors = intArrayOf(0xFF10B981.toInt(), 0xDD059669.toInt(), 0x0010B981)
        innerCoronaShader = RadialGradient(0f, 0f, baseRadius * 0.48f, innerColors, null, Shader.TileMode.CLAMP)

        // 3. Shockwave flash bloom shader
        val flashColors = intArrayOf(0xFFFFFFFF.toInt(), 0x8806B6D4.toInt(), 0x0006B6D4)
        flashShader = RadialGradient(0f, 0f, baseRadius, flashColors, null, Shader.TileMode.CLAMP)
    }

    fun pause() {
        isPaused = true
        rotationAnimator?.pause()
        breathingAnimator?.pause()
        shockwaveAnimator?.pause()
    }

    fun resume() {
        if (!isPaused) return
        isPaused = false
        if (rotationAnimator?.isPaused == true) rotationAnimator?.resume()
        if (breathingAnimator?.isPaused == true) breathingAnimator?.resume()
        if (shockwaveAnimator?.isPaused == true) shockwaveAnimator?.resume()
        updateAnimatorStates()
    }

    private fun stopAllAnimators() {
        rotationAnimator?.cancel()
        breathingAnimator?.cancel()
        shockwaveAnimator?.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!isPaused && visibility == VISIBLE) {
            updateAnimatorStates()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAllAnimators()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (this.visibility == VISIBLE && !isPaused) {
            updateAnimatorStates()
        } else {
            stopAllAnimators()
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && this.visibility == VISIBLE && !isPaused) {
            updateAnimatorStates()
        } else {
            stopAllAnimators()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val cx = w / 2f
        val cy = h / 2f
        val baseRadius = if (cachedBaseRadius > 0f) cachedBaseRadius else minOf(w, h) / 2.7f

        val effectiveState = if (isSyncing) baseState else currentState

        when (effectiveState) {
            State.SEARCHING -> drawSearchingState(canvas, cx, cy, baseRadius)
            State.CONNECTED -> drawConnectedState(canvas, cx, cy, baseRadius)
            State.SYNCING -> drawConnectedState(canvas, cx, cy, baseRadius)
        }

        // Draw shockwave transmission ripple over base state
        if (isSyncing) {
            drawShockwave(canvas, cx, cy, baseRadius)
        }
    }

    private fun drawSearchingState(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        val radAngle = Math.toRadians(orbitalAngle.toDouble())
        val orbitRadius = baseRadius * 0.78f

        // 1. Gentle Expanding Concentric Radar Rings
        val phase = (orbitalAngle / 360f)
        val ring1 = (phase * 2f) % 1f
        val ring2 = (phase * 2f + 0.5f) % 1f

        val r1 = baseRadius * 0.35f + ring1 * (baseRadius * 0.75f)
        val a1 = ((1f - ring1) * 110).toInt().coerceIn(0, 255)
        ringPaint.strokeWidth = 2f
        ringPaint.color = (a1 shl 24) or (colorCyan and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, r1, ringPaint)

        val r2 = baseRadius * 0.35f + ring2 * (baseRadius * 0.75f)
        val a2 = ((1f - ring2) * 110).toInt().coerceIn(0, 255)
        ringPaint.color = (a2 shl 24) or (colorAmber and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, r2, ringPaint)

        // 2. Center Ambient Fusion Glow (zero runtime allocation)
        val fusionRadius = baseRadius * 0.5f
        if (fusionShader != null) {
            auraPaint.shader = fusionShader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.drawCircle(0f, 0f, fusionRadius, auraPaint)
            canvas.restore()
            auraPaint.shader = null
        }

        // 3. Central Core Node
        corePaint.color = 0xAA09090B.toInt()
        canvas.drawCircle(cx, cy, baseRadius * 0.26f, corePaint)
        ringPaint.strokeWidth = 1.5f
        ringPaint.color = 0x8823263B.toInt()
        canvas.drawCircle(cx, cy, baseRadius * 0.26f, ringPaint)

        // 4. Rotating Orbital Satellites (Amber & Cyan)
        // Satellite 1: Amber
        val aX = cx + (orbitRadius * cos(radAngle)).toFloat()
        val aY = cy + (orbitRadius * sin(radAngle)).toFloat()
        val aGlowRadius = baseRadius * 0.28f
        if (amberGlowShader != null) {
            satellitePaint.shader = amberGlowShader
            canvas.save()
            canvas.translate(aX, aY)
            canvas.drawCircle(0f, 0f, aGlowRadius, satellitePaint)
            canvas.restore()
            satellitePaint.shader = null
        }

        satellitePaint.color = colorAmber
        canvas.drawCircle(aX, aY, baseRadius * 0.09f, satellitePaint)
        satellitePaint.color = colorAmberCore
        canvas.drawCircle(aX, aY, baseRadius * 0.045f, satellitePaint)

        // Satellite 2: Cyan (opposite phase PI)
        val cX = cx + (orbitRadius * cos(radAngle + Math.PI)).toFloat()
        val cY = cy + (orbitRadius * sin(radAngle + Math.PI)).toFloat()
        val cGlowRadius = baseRadius * 0.28f
        if (cyanGlowShader != null) {
            satellitePaint.shader = cyanGlowShader
            canvas.save()
            canvas.translate(cX, cY)
            canvas.drawCircle(0f, 0f, cGlowRadius, satellitePaint)
            canvas.restore()
            satellitePaint.shader = null
        }

        satellitePaint.color = colorCyan
        canvas.drawCircle(cX, cY, baseRadius * 0.09f, satellitePaint)
        satellitePaint.color = colorCyanCore
        canvas.drawCircle(cX, cY, baseRadius * 0.045f, satellitePaint)
    }

    private fun drawConnectedState(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        val pulse = breathingPulse // 0.0 to 1.0

        // 1. Outer Breathing Luminous Emerald Aura (scaled via canvas matrix, zero allocation)
        if (connectedAuraShader != null) {
            val scale = 0.75f + 0.35f * pulse
            val auraAlpha = (0x66 + (0x66 * pulse).toInt()).coerceIn(0, 255)
            auraPaint.shader = connectedAuraShader
            auraPaint.alpha = auraAlpha
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(scale, scale)
            canvas.drawCircle(0f, 0f, baseRadius, auraPaint)
            canvas.restore()
            auraPaint.shader = null
            auraPaint.alpha = 255
        }

        // 2. Harmonic Resonance Ring
        val ringR = baseRadius * (0.80f + 0.08f * pulse)
        val ringAlpha = (0x55 + (0x66 * pulse).toInt()).coerceIn(0, 255)
        ringPaint.strokeWidth = 2f
        ringPaint.color = (ringAlpha shl 24) or (colorEmeraldMint and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, ringR, ringPaint)

        // 3. Dense Inner Emerald Corona
        if (innerCoronaShader != null) {
            val innerScale = (0.42f + 0.06f * pulse) / 0.48f
            corePaint.shader = innerCoronaShader
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(innerScale, innerScale)
            canvas.drawCircle(0f, 0f, baseRadius * 0.48f, corePaint)
            canvas.restore()
            corePaint.shader = null
        }

        // 4. Luminous Mint Trust Anchor Core
        val coreR = baseRadius * 0.22f
        corePaint.color = colorEmeraldMint
        canvas.drawCircle(cx, cy, coreR, corePaint)

        // Specular highlight center point
        corePaint.color = colorEmeraldCore
        canvas.drawCircle(cx, cy, coreR * 0.5f, corePaint)
    }

    private fun drawShockwave(canvas: Canvas, cx: Float, cy: Float, baseRadius: Float) {
        val t = shockwaveProgress // 0.0 to 1.0
        val waveRadius = baseRadius * 0.3f + t * (baseRadius * 0.95f)
        val alpha = ((1.0f - t) * 230).toInt().coerceIn(0, 255)

        // Rapid Cyan-White Shockwave Ripple
        shockwavePaint.strokeWidth = (1.0f - t) * 6f + 2f
        shockwavePaint.color = (alpha shl 24) or (colorCyanCore and 0x00FFFFFF)
        canvas.drawCircle(cx, cy, waveRadius, shockwavePaint)

        // Central Flash Bloom that dissipates quickly (zero runtime allocation)
        if (t < 0.6f && flashShader != null) {
            val flashAlpha = ((0.6f - t) / 0.6f * 180).toInt().coerceIn(0, 255)
            val flashScale = 0.55f * (1f + t)
            auraPaint.shader = flashShader
            auraPaint.alpha = flashAlpha
            canvas.save()
            canvas.translate(cx, cy)
            canvas.scale(flashScale, flashScale)
            canvas.drawCircle(0f, 0f, baseRadius, auraPaint)
            canvas.restore()
            auraPaint.shader = null
            auraPaint.alpha = 255
        }
    }
}
