package com.booxin.launcher.ui.launch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Chrome-style offline dinosaur runner for the launch loading screen.
 * Tap / hold to jump. Runs only while [running] is true.
 */
class DinoGameView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val groundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF90A4AE.toInt()
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val dinoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFECEFF1.toInt()
        style = Paint.Style.FILL
    }
    private val cactusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF81C784.toInt()
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0F7FA.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFB0BEC5.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val skyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF15202B.toInt()
        style = Paint.Style.FILL
    }
    private val dinoPath = Path()
    private val cactusPath = Path()
    private val dinoRect = RectF()
    private val obstacleRect = RectF()

    private var running = false
    private var alive = true
    private var lastFrameMs = 0L
    private var groundY = 0f
    private var dinoX = 0f
    private var dinoY = 0f
    private var dinoW = 0f
    private var dinoH = 0f
    private var velocityY = 0f
    private var onGround = true
    private var scrollX = 0f
    private var speed = 380f
    private var score = 0
    private var highScore = 0
    private var spawnCooldown = 0f
    private val obstacles = ArrayList<Obstacle>()
    private var animPhase = 0f

    private data class Obstacle(var x: Float, val w: Float, val h: Float)

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            val dt = ((now - lastFrameMs).coerceIn(8L, 33L)) / 1000f
            lastFrameMs = now
            step(dt)
            invalidate()
            postOnAnimation(this)
        }
    }

    fun startGame() {
        if (width <= 0 || height <= 0) {
            post { startGame() }
            return
        }
        if (running) return
        resetRound(keepHighScore = true)
        running = true
        lastFrameMs = SystemClock.uptimeMillis()
        postOnAnimation(ticker)
    }

    fun stopGame() {
        running = false
        removeCallbacks(ticker)
    }

    fun pauseGame() {
        running = false
        removeCallbacks(ticker)
    }

    fun resumeGame() {
        if (!isShown || width <= 0) return
        if (!alive) {
            // Stay stopped until tap restarts.
            invalidate()
            return
        }
        if (running) return
        running = true
        lastFrameMs = SystemClock.uptimeMillis()
        postOnAnimation(ticker)
    }

    override fun onDetachedFromWindow() {
        stopGame()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutMetrics(w, h)
        if (dinoY == 0f) {
            dinoY = groundY - dinoH
        }
    }

    private fun layoutMetrics(w: Int, h: Int) {
        groundY = h * 0.78f
        dinoW = min(w, h) * 0.11f
        dinoH = dinoW * 1.15f
        dinoX = w * 0.12f
        textPaint.textSize = h * 0.09f
        hintPaint.textSize = h * 0.065f
    }

    private fun resetRound(keepHighScore: Boolean) {
        if (!keepHighScore) highScore = max(highScore, score)
        else highScore = max(highScore, score)
        score = 0
        speed = 380f
        velocityY = 0f
        onGround = true
        alive = true
        scrollX = 0f
        spawnCooldown = 1.2f
        obstacles.clear()
        animPhase = 0f
        if (height > 0) {
            layoutMetrics(width, height)
            dinoY = groundY - dinoH
        }
    }

    private fun jump() {
        if (!alive) {
            resetRound(keepHighScore = true)
            if (!running) {
                running = true
                lastFrameMs = SystemClock.uptimeMillis()
                postOnAnimation(ticker)
            }
            return
        }
        if (onGround) {
            velocityY = -height * 1.55f
            onGround = false
        }
    }

    private fun step(dt: Float) {
        if (!alive) return
        animPhase += dt * 10f
        speed = min(720f, speed + dt * 8f)
        scrollX += speed * dt
        score = (scrollX / 18f).toInt()

        velocityY += height * 4.2f * dt
        dinoY += velocityY * dt
        val floor = groundY - dinoH
        if (dinoY >= floor) {
            dinoY = floor
            velocityY = 0f
            onGround = true
        }

        spawnCooldown -= dt
        if (spawnCooldown <= 0f) {
            val h = dinoH * (0.55f + Random.nextFloat() * 0.55f)
            val w = dinoW * (0.35f + Random.nextFloat() * 0.35f)
            obstacles += Obstacle(width + 20f, w, h)
            spawnCooldown = 0.9f + Random.nextFloat() * 1.1f
        }

        val iter = obstacles.iterator()
        while (iter.hasNext()) {
            val o = iter.next()
            o.x -= speed * dt
            if (o.x + o.w < -20f) {
                iter.remove()
                continue
            }
            dinoRect.set(dinoX + dinoW * 0.15f, dinoY + dinoH * 0.1f, dinoX + dinoW * 0.85f, dinoY + dinoH)
            obstacleRect.set(o.x, groundY - o.h, o.x + o.w, groundY)
            if (RectF.intersects(dinoRect, obstacleRect)) {
                alive = false
                highScore = max(highScore, score)
                break
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), skyPaint)
        canvas.drawLine(0f, groundY, width.toFloat(), groundY, groundPaint)

        // Ground dashes
        var gx = -((scrollX * 0.5f) % 40f)
        while (gx < width) {
            canvas.drawLine(gx, groundY + 6f, gx + 18f, groundY + 6f, groundPaint)
            gx += 40f
        }

        for (o in obstacles) {
            drawCactus(canvas, o.x, groundY - o.h, o.w, o.h)
        }
        drawDino(canvas, dinoX, dinoY, dinoW, dinoH)

        canvas.drawText("HI $highScore  $score", width * 0.5f, height * 0.18f, textPaint)
        val tip = when {
            !alive -> "撞到了 · 点一下再来"
            !running -> "点一下开始跳"
            else -> "点屏幕跳跃"
        }
        canvas.drawText(tip, width * 0.5f, height * 0.32f, hintPaint)
    }

    private fun drawDino(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        dinoPath.reset()
        // Body
        dinoPath.addRoundRect(RectF(x + w * 0.15f, y + h * 0.25f, x + w * 0.75f, y + h * 0.85f), 4f, 4f, Path.Direction.CW)
        // Head
        dinoPath.addRoundRect(RectF(x + w * 0.45f, y, x + w * 0.95f, y + h * 0.4f), 4f, 4f, Path.Direction.CW)
        // Tail
        dinoPath.moveTo(x + w * 0.15f, y + h * 0.45f)
        dinoPath.lineTo(x, y + h * 0.55f)
        dinoPath.lineTo(x + w * 0.15f, y + h * 0.65f)
        dinoPath.close()
        // Legs (run cycle)
        val leg = if (onGround && alive) {
            if ((animPhase.toInt() % 2) == 0) 0f else h * 0.06f
        } else 0f
        dinoPath.addRect(x + w * 0.25f, y + h * 0.8f, x + w * 0.38f, y + h + leg, Path.Direction.CW)
        dinoPath.addRect(x + w * 0.48f, y + h * 0.8f, x + w * 0.61f, y + h - leg, Path.Direction.CW)
        canvas.drawPath(dinoPath, dinoPaint)
        // Eye
        canvas.drawCircle(x + w * 0.78f, y + h * 0.16f, w * 0.05f, skyPaint)
    }

    private fun drawCactus(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        cactusPath.reset()
        cactusPath.addRoundRect(RectF(x + w * 0.3f, y, x + w * 0.7f, y + h), 3f, 3f, Path.Direction.CW)
        cactusPath.addRoundRect(RectF(x, y + h * 0.25f, x + w * 0.4f, y + h * 0.45f), 3f, 3f, Path.Direction.CW)
        cactusPath.addRoundRect(RectF(x + w * 0.6f, y + h * 0.35f, x + w, y + h * 0.55f), 3f, 3f, Path.Direction.CW)
        canvas.drawPath(cactusPath, cactusPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                jump()
                return true
            }
        }
        return true
    }
}
