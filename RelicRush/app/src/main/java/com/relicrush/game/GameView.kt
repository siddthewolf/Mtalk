package com.relicrush.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Owns all game state, the simulation step ([update]) and all drawing
 * ([render]). Uses a lightweight pseudo-3D projection: lane objects carry a
 * depth `z` (0 = at the player, larger = further away) which is projected to
 * screen position and scale so the world appears to rush toward the camera.
 *
 * All visuals are drawn procedurally with [Canvas] primitives — there are no
 * image assets, which keeps the game fully original and dependency-free.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    // ---- Tunable world constants ---------------------------------------------------------
    private val zFar = 1.25f                 // spawn depth
    private val zCull = -0.18f               // remove once behind the camera
    private val spawnGapZ = 0.42f            // depth between obstacle rows
    private val startSpeed = 0.62f           // z units per second
    private val maxSpeed = 1.7f
    private val accel = 0.014f               // speed gained per second
    private val coinValue = 10
    private val prefs = context.getSharedPreferences("relicrush", Context.MODE_PRIVATE)

    // ---- State ---------------------------------------------------------------------------
    private var state = GameState.READY
    private val player = Player()
    private val obstacles = ArrayList<Obstacle>()
    private val coins = ArrayList<Coin>()
    private val pillars = ArrayList<Pillar>()
    private val rng = Random(System.nanoTime())

    private var speed = startSpeed
    private var distanceToNextSpawn = 0f
    private var pillarTimer = 0f
    private var roadScroll = 0f
    private var runCycle = 0f
    private var score = 0
    private var coinCount = 0
    private var highScore = prefs.getInt("highScore", 0)
    private var screenW = 1f
    private var screenH = 1f

    // ---- Paints --------------------------------------------------------------------------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private var skyShader: Shader? = null

    private var thread: GameThread? = null
    private val gestureDetector: GestureDetector

    init {
        holder.addCallback(this)
        isFocusable = true
        gestureDetector = GestureDetector(context, GestureListener())
    }

    // ---- Surface lifecycle ---------------------------------------------------------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        startThread()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        skyShader = LinearGradient(
            0f, 0f, 0f, screenH * 0.45f,
            Color.rgb(38, 88, 120), Color.rgb(214, 156, 92),
            Shader.TileMode.CLAMP
        )
        textPaint.textSize = screenH * 0.032f
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopThread()
    }

    fun resume() = startThread()

    fun pause() = stopThread()

    private fun startThread() {
        if (thread?.running == true) return
        if (!holder.surface.isValid) return
        thread = GameThread(holder, this).also {
            it.running = true
            it.start()
        }
    }

    private fun stopThread() {
        val t = thread ?: return
        t.running = false
        while (true) {
            try {
                t.join()
                break
            } catch (_: InterruptedException) { /* retry */ }
        }
        thread = null
    }

    // ---- Projection ----------------------------------------------------------------------
    private val horizonFrac = 0.34f
    private fun horizonY() = screenH * horizonFrac
    private fun groundBottomY() = screenH * 1.04f
    private fun laneSpacing() = screenW * 0.27f

    /** Perspective scale: 1.0 at the camera (z=0), shrinking with depth. */
    private fun scaleAt(z: Float): Float = 0.34f / (z + 0.34f)

    private val sNear = scaleAt(0f)
    private val sFar = scaleAt(zFar)

    /** Screen y of the ground contact point at depth [z]. */
    private fun groundYAt(z: Float): Float {
        val t = (scaleAt(z) - sFar) / (sNear - sFar)   // 1 near .. 0 far
        return horizonY() + (groundBottomY() - horizonY()) * t
    }

    private fun laneXAt(laneFrac: Float, z: Float): Float =
        screenW / 2f + (laneFrac - 1f) * laneSpacing() * scaleAt(z)

    // ---- Simulation ----------------------------------------------------------------------
    fun update(dt: Float) {
        roadScroll = (roadScroll + speed * dt) % 1f
        runCycle += dt * 12f

        if (state != GameState.PLAYING) return

        speed = max(speed, startSpeed) + accel * dt
        if (speed > maxSpeed) speed = maxSpeed

        player.update(dt)

        // Spawn obstacle rows based on distance travelled.
        distanceToNextSpawn -= speed * dt
        if (distanceToNextSpawn <= 0f) {
            spawnRow()
            distanceToNextSpawn += spawnGapZ
        }

        // Roadside pillars for a sense of speed.
        pillarTimer -= speed * dt
        if (pillarTimer <= 0f) {
            pillars.add(Pillar(if (rng.nextBoolean()) -1 else 1, zFar, 0.7f + rng.nextFloat() * 0.5f))
            pillarTimer += 0.55f
        }

        advanceAndResolve(dt)

        // Distance score ticks up with speed.
        score += (speed * dt * 60f).toInt()
    }

    private fun spawnRow() {
        val openLane = rng.nextInt(3)
        when (rng.nextInt(6)) {
            0 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.BARRIER))
            1 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.OVERHANG))
            2 -> {
                obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.BLOCK))
            }
            3 -> {
                // Two blocks, leaving exactly one lane open.
                for (l in 0..2) if (l != openLane) {
                    obstacles.add(Obstacle(l, zFar, ObstacleType.BLOCK))
                }
            }
            4 -> {
                // Barrier on two lanes — jump or dodge.
                for (l in 0..2) if (l != openLane) {
                    obstacles.add(Obstacle(l, zFar, ObstacleType.BARRIER))
                }
            }
            else -> {
                obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.OVERHANG))
            }
        }

        // Drop a relic on a lane that has no block this row.
        val blockedLanes = obstacles.filter { it.z == zFar && it.type == ObstacleType.BLOCK }
            .map { it.lane }.toSet()
        val freeLanes = (0..2).filter { it !in blockedLanes }
        if (freeLanes.isNotEmpty()) {
            coins.add(Coin(freeLanes[rng.nextInt(freeLanes.size)], zFar))
        }
    }

    private fun advanceAndResolve(dt: Float) {
        val move = speed * dt

        val obstIt = obstacles.iterator()
        while (obstIt.hasNext()) {
            val o = obstIt.next()
            o.z -= move
            if (!o.resolved && o.z <= 0f) {
                o.resolved = true
                if (o.lane == player.collisionLane && !survives(o)) {
                    gameOver()
                }
            }
            if (o.z < zCull) obstIt.remove()
        }

        val coinIt = coins.iterator()
        while (coinIt.hasNext()) {
            val c = coinIt.next()
            c.z -= move
            if (!c.collected && c.z <= 0f && c.lane == player.collisionLane) {
                c.collected = true
                coinCount++
                score += coinValue
            }
            if (c.z < zCull) coinIt.remove()
        }

        val pillarIt = pillars.iterator()
        while (pillarIt.hasNext()) {
            val p = pillarIt.next()
            p.z -= move
            if (p.z < zCull) pillarIt.remove()
        }
    }

    /** True if the player's current pose clears obstacle [o]. */
    private fun survives(o: Obstacle): Boolean = when (o.type) {
        ObstacleType.BARRIER -> player.height > Player.JUMP_CLEAR_HEIGHT
        ObstacleType.OVERHANG -> player.isSliding
        ObstacleType.BLOCK -> false
    }

    // ---- Game flow -----------------------------------------------------------------------
    private fun startGame() {
        obstacles.clear()
        coins.clear()
        pillars.clear()
        player.reset()
        speed = startSpeed
        distanceToNextSpawn = spawnGapZ
        pillarTimer = 0f
        score = 0
        coinCount = 0
        state = GameState.PLAYING
    }

    private fun gameOver() {
        state = GameState.GAME_OVER
        if (score > highScore) {
            highScore = score
            prefs.edit().putInt("highScore", highScore).apply()
        }
    }

    private fun onTap() {
        when (state) {
            GameState.READY, GameState.GAME_OVER -> startGame()
            GameState.PLAYING -> player.jump() // a plain tap also jumps
        }
    }

    // ---- Input ---------------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            onTap()
            return true
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float
        ): Boolean {
            if (state != GameState.PLAYING) {
                onTap()
                return true
            }
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)
            if (abs(dx) > abs(dy)) {
                if (dx > 0) player.moveRight() else player.moveLeft()
            } else {
                if (dy > 0) player.slide() else player.jump()
            }
            return true
        }
    }

    // ---- Rendering -----------------------------------------------------------------------
    fun render(canvas: Canvas) {
        drawSky(canvas)
        drawRoad(canvas)
        drawScenery(canvas)
        drawWorldObjects(canvas)
        if (state != GameState.READY) drawPlayer(canvas)
        drawHud(canvas)
        drawOverlay(canvas)
    }

    private fun drawSky(canvas: Canvas) {
        paint.shader = skyShader
        canvas.drawRect(0f, 0f, screenW, horizonY(), paint)
        paint.shader = null
    }

    private fun drawRoad(canvas: Canvas) {
        // Trapezoidal road from horizon to bottom.
        val nearL = laneXAt(-0.5f, 0f)
        val nearR = laneXAt(2.5f, 0f)
        val farL = laneXAt(-0.5f, zFar)
        val farR = laneXAt(2.5f, zFar)
        val hy = horizonY()
        val by = groundBottomY()

        paint.color = Color.rgb(74, 54, 38)
        val road = Path().apply {
            moveTo(farL, hy); lineTo(farR, hy); lineTo(nearR, by); lineTo(nearL, by); close()
        }
        canvas.drawPath(road, paint)

        // Lane divider lines (between the 3 lanes).
        paint.color = Color.rgb(120, 96, 70)
        paint.strokeWidth = max(2f, screenW * 0.006f)
        for (divider in floatArrayOf(0.5f, 1.5f)) {
            canvas.drawLine(
                laneXAt(divider, zFar), hy,
                laneXAt(divider, 0f), by, paint
            )
        }

        // Scrolling rungs to convey speed.
        paint.color = Color.rgb(96, 74, 52)
        var z = zFar - (roadScroll * 0.18f)
        while (z > 0f) {
            val y = groundYAt(z)
            val thickness = max(1.5f, 7f * scaleAt(z))
            paint.strokeWidth = thickness
            canvas.drawLine(laneXAt(-0.5f, z), y, laneXAt(2.5f, z), y, paint)
            z -= 0.18f
        }
    }

    private fun drawScenery(canvas: Canvas) {
        // Draw pillars far-to-near (painter's algorithm).
        val sorted = pillars.sortedByDescending { it.z }
        for (p in sorted) {
            if (p.z <= 0f) continue
            val s = scaleAt(p.z)
            val baseY = groundYAt(p.z)
            val laneFrac = if (p.side < 0) -1.3f else 3.3f
            val x = laneXAt(laneFrac, p.z)
            val w = screenW * 0.10f * s
            val h = screenH * 0.5f * s * p.height
            paint.color = Color.rgb(58, 70, 58)
            canvas.drawRect(x - w / 2f, baseY - h, x + w / 2f, baseY, paint)
            paint.color = Color.rgb(44, 54, 44)
            canvas.drawRect(x - w / 2f, baseY - h, x - w / 2f + w * 0.3f, baseY, paint)
        }
    }

    /** Obstacles + coins, sorted far-to-near so nearer objects overlap correctly. */
    private fun drawWorldObjects(canvas: Canvas) {
        data class Drawable(val z: Float, val draw: () -> Unit)
        val list = ArrayList<Drawable>()
        for (o in obstacles) if (o.z > zCull) list.add(Drawable(o.z) { drawObstacle(canvas, o) })
        for (c in coins) if (!c.collected && c.z > zCull) list.add(Drawable(c.z) { drawCoin(canvas, c) })
        list.sortByDescending { it.z }
        for (d in list) d.draw()
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        canvas.drawText("Score: $score", screenW * 0.05f, screenH * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText("Relics: $coinCount", screenW * 0.95f, screenH * 0.07f, textPaint)
        textPaint.color = Color.rgb(255, 210, 74)
        canvas.drawText("Best: $highScore", screenW * 0.95f, screenH * 0.115f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
    }

    private fun drawObstacle(canvas: Canvas, o: Obstacle) {
        val s = scaleAt(o.z)
        val baseY = groundYAt(o.z)
        val cx = laneXAt(o.lane.toFloat(), o.z)
        val w = laneSpacing() * 0.7f * s
        when (o.type) {
            ObstacleType.BARRIER -> {
                val h = screenH * 0.10f * s
                paint.color = Color.rgb(206, 132, 46)
                canvas.drawRect(cx - w / 2f, baseY - h, cx + w / 2f, baseY, paint)
                paint.color = Color.rgb(150, 92, 28)
                canvas.drawRect(cx - w / 2f, baseY - h, cx + w / 2f, baseY - h * 0.7f, paint)
            }
            ObstacleType.OVERHANG -> {
                val gap = screenH * 0.13f * s          // gap to slide through
                val beamH = screenH * 0.10f * s
                val top = baseY - gap - beamH
                paint.color = Color.rgb(150, 150, 158)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, paint)
                // Side posts.
                paint.color = Color.rgb(110, 110, 118)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.12f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.12f, top, cx + w / 2f, baseY, paint)
            }
            ObstacleType.BLOCK -> {
                val h = screenH * 0.26f * s
                paint.color = Color.rgb(72, 60, 92)
                canvas.drawRect(cx - w / 2f, baseY - h, cx + w / 2f, baseY, paint)
                paint.color = Color.rgb(52, 42, 68)
                canvas.drawRect(cx - w / 2f, baseY - h, cx + w / 2f, baseY - h * 0.85f, paint)
            }
        }
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val s = scaleAt(c.z)
        val baseY = groundYAt(c.z)
        val cx = laneXAt(c.lane.toFloat(), c.z)
        val bob = sin((runCycle + c.z * 6f).toDouble()).toFloat() * screenH * 0.012f * s
        val cy = baseY - screenH * 0.09f * s + bob
        val r = screenW * 0.045f * s
        paint.color = Color.rgb(255, 210, 74)
        canvas.drawCircle(cx, cy, r, paint)
        paint.color = Color.rgb(214, 160, 40)
        canvas.drawCircle(cx, cy, r * 0.62f, paint)
    }

    private fun drawPlayer(canvas: Canvas) {
        val z = 0f
        val baseY = groundYAt(z) - screenH * 0.02f
        val cx = laneXAt(player.laneFrac, z)
        val jump = player.height * screenH * 0.30f
        val bodyW = screenW * 0.11f
        var bodyH = screenH * 0.16f
        if (player.isSliding) bodyH *= 0.55f

        val feetY = baseY - jump
        val topY = feetY - bodyH

        // Shadow shrinks as you rise.
        paint.color = Color.argb((90 * (1f - player.height).coerceIn(0.2f, 1f)).toInt(), 0, 0, 0)
        canvas.drawOval(
            RectF(cx - bodyW * 0.7f, baseY - bodyW * 0.18f, cx + bodyW * 0.7f, baseY + bodyW * 0.18f),
            paint
        )

        // Body.
        paint.color = Color.rgb(64, 200, 196)
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2f, topY, cx + bodyW / 2f, feetY),
            bodyW * 0.3f, bodyW * 0.3f, paint
        )

        // Head.
        val headR = bodyW * 0.42f
        paint.color = Color.rgb(244, 206, 160)
        canvas.drawCircle(cx, topY - headR * 0.7f, headR, paint)

        // Running legs (skip while sliding/jumping for a settled look).
        if (!player.isSliding && player.height < 0.02f) {
            val swing = sin(runCycle.toDouble()).toFloat() * bodyW * 0.5f
            paint.color = Color.rgb(40, 150, 150)
            paint.strokeWidth = bodyW * 0.22f
            canvas.drawLine(cx, feetY, cx + swing, feetY + bodyH * 0.22f, paint)
            canvas.drawLine(cx, feetY, cx - swing, feetY + bodyH * 0.22f, paint)
        }
    }

    private fun drawOverlay(canvas: Canvas) {
        when (state) {
            GameState.READY -> {
                dim(canvas)
                centerText.color = Color.rgb(255, 210, 74)
                centerText.textSize = screenH * 0.075f
                canvas.drawText("RELIC RUSH", screenW / 2f, screenH * 0.34f, centerText)
                centerText.color = Color.WHITE
                centerText.textSize = screenH * 0.034f
                canvas.drawText("Tap to run", screenW / 2f, screenH * 0.46f, centerText)
                centerText.textSize = screenH * 0.026f
                canvas.drawText("Swipe ← → to switch lanes", screenW / 2f, screenH * 0.55f, centerText)
                canvas.drawText("Swipe ↑ to jump  •  ↓ to slide", screenW / 2f, screenH * 0.59f, centerText)
            }
            GameState.GAME_OVER -> {
                dim(canvas)
                centerText.color = Color.rgb(236, 96, 80)
                centerText.textSize = screenH * 0.07f
                canvas.drawText("GAME OVER", screenW / 2f, screenH * 0.36f, centerText)
                centerText.color = Color.WHITE
                centerText.textSize = screenH * 0.04f
                canvas.drawText("Score  $score", screenW / 2f, screenH * 0.47f, centerText)
                centerText.color = Color.rgb(255, 210, 74)
                canvas.drawText("Best  $highScore", screenW / 2f, screenH * 0.53f, centerText)
                centerText.color = Color.WHITE
                centerText.textSize = screenH * 0.03f
                canvas.drawText("Tap to try again", screenW / 2f, screenH * 0.62f, centerText)
            }
            GameState.PLAYING -> { /* no overlay */ }
        }
    }

    private fun dim(canvas: Canvas) {
        paint.color = Color.argb(140, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
    }
}
