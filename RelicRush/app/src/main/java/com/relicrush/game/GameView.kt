package com.relicrush.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
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

    /** A short-lived screen-space spark, used for relic-pickup bursts. */
    private class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float,
        val color: Int, val size: Float
    )

    // ---- Tunable world constants ---------------------------------------------------------
    private val zFar = 1.25f                 // spawn depth
    private val zCull = -0.18f               // remove once behind the camera
    private val spawnGapZ = 0.44f            // depth between obstacle rows

    // Speed: starts slow and ramps up gently for gradual progression
    // (~0.40 -> 1.55 over roughly two and a half minutes).
    private val startSpeed = 0.40f           // z units per second
    private val maxSpeed = 1.55f
    private val accel = 0.0075f              // speed gained per second
    private val graceTime = 2.2f             // no obstacles for the first moments
    private val tutorialTime = 10f           // in-game guide duration

    private val coinValue = 10
    private val prefs = context.getSharedPreferences("relicrush", Context.MODE_PRIVATE)

    // ---- State ---------------------------------------------------------------------------
    private var state = GameState.READY
    private val player = Player()
    private val obstacles = ArrayList<Obstacle>()
    private val coins = ArrayList<Coin>()
    private val pillars = ArrayList<Pillar>()
    private val particles = ArrayList<Particle>()
    private val rng = Random(System.nanoTime())

    private var speed = startSpeed
    private var distanceToNextSpawn = 0f
    private var pillarTimer = 0f
    private var roadScroll = 0f
    private var runCycle = 0f
    private var uiTime = 0f          // always-advancing clock for UI pulsing
    private var gameTime = 0f        // seconds since the current run started
    private var crashFlash = 0f
    private var score = 0
    private var coinCount = 0
    private var highScore = prefs.getInt("highScore", 0)
    private var screenW = 1f
    private var screenH = 1f

    // ---- Paints --------------------------------------------------------------------------
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(90, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
    }
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
    }
    private var skyShader: Shader? = null
    private var groundShader: Shader? = null
    private var vignetteShader: Shader? = null

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
            0f, 0f, 0f, horizonY(),
            intArrayOf(
                Color.rgb(26, 42, 78),    // deep sky
                Color.rgb(72, 96, 134),
                Color.rgb(232, 170, 104)  // warm haze at horizon
            ),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        groundShader = LinearGradient(
            0f, horizonY(), 0f, screenH,
            Color.rgb(46, 92, 56), Color.rgb(28, 60, 38),
            Shader.TileMode.CLAMP
        )
        vignetteShader = RadialGradient(
            screenW / 2f, screenH * 0.55f, max(screenW, screenH) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(110, 0, 0, 0)),
            floatArrayOf(0.62f, 1f),
            Shader.TileMode.CLAMP
        )
        textPaint.textSize = screenH * 0.030f
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
    private val horizonFrac = 0.36f
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
        uiTime += dt
        roadScroll = (roadScroll + speed * dt) % 1f
        runCycle += dt * (8f + speed * 6f)
        if (crashFlash > 0f) crashFlash = max(0f, crashFlash - dt * 1.6f)
        updateParticles(dt)

        if (state != GameState.PLAYING) return

        gameTime += dt
        speed = (speed + accel * dt).coerceIn(startSpeed, maxSpeed)

        player.update(dt)

        // Spawn obstacle rows based on distance travelled (after a short grace).
        distanceToNextSpawn -= speed * dt
        if (distanceToNextSpawn <= 0f) {
            if (gameTime > graceTime) spawnRow()
            distanceToNextSpawn += spawnGapZ
        }

        // Roadside pillars for a sense of speed.
        pillarTimer -= speed * dt
        if (pillarTimer <= 0f) {
            pillars.add(Pillar(if (rng.nextBoolean()) -1 else 1, zFar, 0.7f + rng.nextFloat() * 0.6f))
            pillarTimer += 0.5f
        }

        advanceAndResolve(dt)

        // Distance score ticks up with speed.
        score += (speed * dt * 60f).toInt()
    }

    private fun spawnRow() {
        val openLane = rng.nextInt(3)
        // Ease players in: only single, simple obstacles during the early game.
        val easy = gameTime < graceTime + 7f
        val roll = if (easy) rng.nextInt(3) else rng.nextInt(6)
        when (roll) {
            0 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.BARRIER))
            1 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.OVERHANG))
            2 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.BLOCK))
            3 -> for (l in 0..2) if (l != openLane) obstacles.add(Obstacle(l, zFar, ObstacleType.BLOCK))
            4 -> for (l in 0..2) if (l != openLane) obstacles.add(Obstacle(l, zFar, ObstacleType.BARRIER))
            else -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.OVERHANG))
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
                spawnPickupBurst(laneXAt(c.lane.toFloat(), 0f), groundYAt(0f) - screenH * 0.10f)
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

    private fun spawnPickupBurst(x: Float, y: Float) {
        repeat(10) {
            val ang = rng.nextFloat() * 6.2832f
            val sp = (0.6f + rng.nextFloat()) * screenH * 0.45f
            particles.add(
                Particle(
                    x, y,
                    cos(ang.toDouble()).toFloat() * sp,
                    sin(ang.toDouble()).toFloat() * sp - screenH * 0.2f,
                    0.55f, 0.55f,
                    if (rng.nextBoolean()) Color.rgb(255, 224, 130) else Color.rgb(255, 196, 64),
                    screenW * (0.008f + rng.nextFloat() * 0.01f)
                )
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += screenH * 1.6f * dt   // gravity
        }
    }

    // ---- Game flow -----------------------------------------------------------------------
    private fun startGame() {
        obstacles.clear()
        coins.clear()
        pillars.clear()
        particles.clear()
        player.reset()
        speed = startSpeed
        distanceToNextSpawn = spawnGapZ
        pillarTimer = 0f
        gameTime = 0f
        score = 0
        coinCount = 0
        state = GameState.PLAYING
    }

    private fun gameOver() {
        state = GameState.GAME_OVER
        crashFlash = 1f
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
        drawParticles(canvas)
        drawVignette(canvas)
        if (state == GameState.PLAYING && gameTime < tutorialTime) drawTutorial(canvas)
        drawHud(canvas)
        if (crashFlash > 0f) {
            paint.color = Color.argb((crashFlash * 130).toInt(), 200, 40, 40)
            canvas.drawRect(0f, 0f, screenW, screenH, paint)
        }
        drawOverlay(canvas)
    }

    private fun drawSky(canvas: Canvas) {
        val hy = horizonY()
        paint.shader = skyShader
        canvas.drawRect(0f, 0f, screenW, hy, paint)
        paint.shader = null

        // Sun with a soft halo.
        val sx = screenW * 0.72f
        val sy = hy * 0.42f
        paint.color = Color.argb(60, 255, 220, 150)
        canvas.drawCircle(sx, sy, hy * 0.34f, paint)
        paint.color = Color.rgb(255, 232, 176)
        canvas.drawCircle(sx, sy, hy * 0.16f, paint)

        // Distant temple / mountain silhouette along the horizon.
        paint.color = Color.rgb(40, 58, 70)
        val ridge = Path().apply {
            moveTo(0f, hy)
            var x = 0f
            val step = screenW / 8f
            var i = 0
            while (x <= screenW) {
                val peak = hy - (0.10f + 0.06f * (1 + sin((i * 1.3f).toDouble()).toFloat())) * screenH
                lineTo(x + step * 0.5f, peak)
                lineTo(x + step, hy)
                x += step
                i++
            }
            lineTo(screenW, hy)
            close()
        }
        canvas.drawPath(ridge, paint)

        // A central stepped pyramid for a "temple" landmark.
        paint.color = Color.rgb(54, 70, 78)
        val px = screenW * 0.30f
        val baseW = screenW * 0.22f
        var ty = hy
        var tw = baseW
        repeat(4) {
            val th = hy * 0.07f
            canvas.drawRect(px - tw / 2f, ty - th, px + tw / 2f, ty, paint)
            ty -= th
            tw *= 0.74f
        }
    }

    private fun drawRoad(canvas: Canvas) {
        val hy = horizonY()
        val by = groundBottomY()

        // Jungle ground fills everything below the horizon.
        paint.shader = groundShader
        canvas.drawRect(0f, hy, screenW, screenH, paint)
        paint.shader = null

        // Trapezoidal stone road.
        val nearL = laneXAt(-0.5f, 0f)
        val nearR = laneXAt(2.5f, 0f)
        val farL = laneXAt(-0.5f, zFar)
        val farR = laneXAt(2.5f, zFar)
        paint.color = Color.rgb(86, 66, 48)
        val road = Path().apply {
            moveTo(farL, hy); lineTo(farR, hy); lineTo(nearR, by); lineTo(nearL, by); close()
        }
        canvas.drawPath(road, paint)

        // Slightly lighter centre lane for depth.
        paint.color = Color.rgb(98, 76, 56)
        val cL = laneXAt(0.5f, 0f); val cR = laneXAt(1.5f, 0f)
        val cFL = laneXAt(0.5f, zFar); val cFR = laneXAt(1.5f, zFar)
        canvas.drawPath(Path().apply {
            moveTo(cFL, hy); lineTo(cFR, hy); lineTo(cR, by); lineTo(cL, by); close()
        }, paint)

        // Glowing curbs along both road edges.
        paint.color = Color.rgb(150, 120, 80)
        paint.strokeWidth = max(3f, screenW * 0.012f)
        canvas.drawLine(farL, hy, nearL, by, paint)
        canvas.drawLine(farR, hy, nearR, by, paint)

        // Lane divider lines.
        paint.color = Color.argb(140, 200, 170, 120)
        paint.strokeWidth = max(2f, screenW * 0.005f)
        for (divider in floatArrayOf(0.5f, 1.5f)) {
            canvas.drawLine(laneXAt(divider, zFar), hy, laneXAt(divider, 0f), by, paint)
        }

        // Scrolling rungs to convey speed.
        paint.color = Color.argb(90, 60, 44, 30)
        var z = zFar - (roadScroll * 0.16f)
        while (z > 0f) {
            val y = groundYAt(z)
            paint.strokeWidth = max(1.5f, 8f * scaleAt(z))
            canvas.drawLine(laneXAt(-0.5f, z), y, laneXAt(2.5f, z), y, paint)
            z -= 0.16f
        }
    }

    private fun drawScenery(canvas: Canvas) {
        // Draw pillars far-to-near (painter's algorithm).
        for (p in pillars.sortedByDescending { it.z }) {
            if (p.z <= 0f) continue
            val s = scaleAt(p.z)
            val baseY = groundYAt(p.z)
            val x = laneXAt(if (p.side < 0) -1.35f else 3.35f, p.z)
            val w = screenW * 0.11f * s
            val h = screenH * 0.55f * s * p.height
            // Pillar body with a shaded side + mossy cap.
            paint.color = Color.rgb(120, 112, 96)
            canvas.drawRect(x - w / 2f, baseY - h, x + w / 2f, baseY, paint)
            paint.color = Color.rgb(92, 86, 72)
            canvas.drawRect(x - w / 2f, baseY - h, x - w / 2f + w * 0.32f, baseY, paint)
            paint.color = Color.rgb(74, 116, 70)
            canvas.drawRect(x - w / 2f * 1.15f, baseY - h, x + w / 2f * 1.15f, baseY - h + h * 0.08f, paint)
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

    private fun drawObstacle(canvas: Canvas, o: Obstacle) {
        val s = scaleAt(o.z)
        val baseY = groundYAt(o.z)
        val cx = laneXAt(o.lane.toFloat(), o.z)
        val w = laneSpacing() * 0.72f * s
        outline.strokeWidth = max(1.5f, 2.5f * s)
        when (o.type) {
            ObstacleType.BARRIER -> {
                // Caution-striped hurdle on two legs — jump it.
                val h = screenH * 0.11f * s
                val top = baseY - h
                paint.color = Color.rgb(60, 48, 36)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.1f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.1f, top, cx + w / 2f, baseY, paint)
                val barTop = top
                val barBot = top + h * 0.5f
                paint.color = Color.rgb(240, 196, 64)
                canvas.drawRect(cx - w / 2f, barTop, cx + w / 2f, barBot, paint)
                paint.color = Color.rgb(40, 36, 32)
                var sx = cx - w / 2f
                val stripeW = w * 0.16f
                var i = 0
                while (sx < cx + w / 2f) {
                    if (i % 2 == 0) canvas.drawRect(sx, barTop, (sx + stripeW).coerceAtMost(cx + w / 2f), barBot, paint)
                    sx += stripeW; i++
                }
                canvas.drawRect(cx - w / 2f, barTop, cx + w / 2f, barBot, outline)
            }
            ObstacleType.OVERHANG -> {
                // Stone arch with a clear gap underneath — slide through.
                val gap = screenH * 0.14f * s
                val beamH = screenH * 0.11f * s
                val top = baseY - gap - beamH
                paint.color = Color.rgb(168, 162, 150)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, paint)
                paint.color = Color.rgb(132, 126, 116)
                canvas.drawRect(cx - w / 2f, top + beamH * 0.6f, cx + w / 2f, top + beamH, paint)
                paint.color = Color.rgb(120, 114, 104)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.14f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.14f, top, cx + w / 2f, baseY, paint)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, outline)
            }
            ObstacleType.BLOCK -> {
                // Tall stone block — cannot pass, change lanes.
                val h = screenH * 0.27f * s
                val top = baseY - h
                paint.color = Color.rgb(96, 80, 120)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, baseY, paint)
                paint.color = Color.rgb(70, 56, 92)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.32f, baseY, paint)
                paint.color = Color.rgb(126, 110, 150)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + h * 0.12f, paint)
                // Brick seams.
                paint.color = Color.argb(80, 40, 30, 56)
                paint.strokeWidth = max(1f, 1.6f * s)
                canvas.drawLine(cx - w / 2f, top + h * 0.5f, cx + w / 2f, top + h * 0.5f, paint)
                canvas.drawLine(cx, top + h * 0.12f, cx, top + h * 0.5f, paint)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, baseY, outline)
            }
        }
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val s = scaleAt(c.z)
        val baseY = groundYAt(c.z)
        val cx = laneXAt(c.lane.toFloat(), c.z)
        val bob = sin((uiTime * 3f + c.z * 6f).toDouble()).toFloat() * screenH * 0.012f * s
        val cy = baseY - screenH * 0.10f * s + bob
        val r = screenW * 0.05f * s
        // Spin: horizontal radius oscillates to fake a rotating gem.
        val spin = abs(cos((uiTime * 4f + c.z * 8f).toDouble()).toFloat())
        val rx = r * (0.25f + 0.75f * spin)

        // Glow halo.
        paint.color = Color.argb(70, 255, 220, 120)
        canvas.drawCircle(cx, cy, r * 1.5f, paint)

        // Diamond relic.
        paint.color = Color.rgb(255, 214, 74)
        val gem = Path().apply {
            moveTo(cx, cy - r); lineTo(cx + rx, cy); lineTo(cx, cy + r); lineTo(cx - rx, cy); close()
        }
        canvas.drawPath(gem, paint)
        paint.color = Color.rgb(255, 240, 180)
        val facet = Path().apply {
            moveTo(cx, cy - r); lineTo(cx + rx, cy); lineTo(cx, cy); lineTo(cx - rx, cy); close()
        }
        canvas.drawPath(facet, paint)
    }

    private fun drawPlayer(canvas: Canvas) {
        val baseY = groundYAt(0f) - screenH * 0.02f
        val cx = laneXAt(player.laneFrac, 0f)
        val jump = player.height * screenH * 0.30f
        val bodyW = screenW * 0.11f
        var bodyH = screenH * 0.17f
        if (player.isSliding) bodyH *= 0.5f

        val feetY = baseY - jump
        val topY = feetY - bodyH
        val swing = sin(runCycle.toDouble()).toFloat()

        // Soft shadow that shrinks as the player rises.
        paint.color = Color.argb((110 * (1f - player.height).coerceIn(0.25f, 1f)).toInt(), 0, 0, 0)
        canvas.drawOval(
            RectF(cx - bodyW * 0.75f, baseY - bodyW * 0.16f, cx + bodyW * 0.75f, baseY + bodyW * 0.18f),
            paint
        )

        // Back arm (drawn first so it sits behind the body).
        paint.strokeWidth = bodyW * 0.26f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Color.rgb(40, 150, 150)
        val shoulderY = topY + bodyH * 0.32f
        canvas.drawLine(cx, shoulderY, cx - swing * bodyW * 0.5f, shoulderY + bodyH * 0.32f, paint)

        // Legs (running stride, tucked while sliding/jumping).
        if (!player.isSliding && player.height < 0.04f) {
            canvas.drawLine(cx, feetY, cx + swing * bodyW * 0.55f, feetY + bodyH * 0.2f, paint)
            canvas.drawLine(cx, feetY, cx - swing * bodyW * 0.55f, feetY + bodyH * 0.2f, paint)
        }

        // Body.
        paint.color = Color.rgb(72, 210, 200)
        canvas.drawRoundRect(
            RectF(cx - bodyW / 2f, topY, cx + bodyW / 2f, feetY), bodyW * 0.32f, bodyW * 0.32f, paint
        )
        // Chest sash for a bit of character.
        paint.color = Color.rgb(236, 132, 72)
        canvas.drawRect(cx - bodyW / 2f, topY + bodyH * 0.32f, cx + bodyW / 2f, topY + bodyH * 0.46f, paint)

        // Front arm.
        paint.color = Color.rgb(60, 188, 184)
        canvas.drawLine(cx, shoulderY, cx + swing * bodyW * 0.6f, shoulderY + bodyH * 0.34f, paint)

        // Head with a simple cap.
        val headR = bodyW * 0.42f
        val headCy = topY - headR * 0.6f
        paint.color = Color.rgb(244, 206, 160)
        canvas.drawCircle(cx, headCy, headR, paint)
        paint.color = Color.rgb(180, 96, 60)
        canvas.drawArc(
            RectF(cx - headR, headCy - headR, cx + headR, headCy + headR),
            180f, 180f, true, paint
        )
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = Color.argb((a * 255).toInt(), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(p.x, p.y, p.size * (0.4f + a), paint)
        }
    }

    private fun drawVignette(canvas: Canvas) {
        paint.shader = vignetteShader
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
        paint.shader = null
    }

    // ---- In-game tutorial (first ~10 seconds) --------------------------------------------
    private fun drawTutorial(canvas: Canvas) {
        data class Tip(val start: Float, val end: Float, val text: String)
        val tips = listOf(
            Tip(0f, 3f, "◀  Swipe left / right to change lanes  ▶"),
            Tip(3f, 6f, "▲  Swipe up to JUMP the striped hurdles"),
            Tip(6f, 9f, "▼  Swipe down to SLIDE under stone arches"),
            Tip(9f, tutorialTime, "Dodge tall blocks • grab relics • survive!")
        )
        val tip = tips.firstOrNull { gameTime >= it.start && gameTime < it.end } ?: return
        val local = gameTime - tip.start
        val span = tip.end - tip.start
        val alpha = (minOf(local, span - local, 0.4f) / 0.4f).coerceIn(0f, 1f)

        // Banner background.
        val by = screenH * 0.20f
        val bh = screenH * 0.075f
        paint.color = Color.argb((alpha * 165).toInt(), 12, 22, 18)
        canvas.drawRoundRect(
            RectF(screenW * 0.06f, by, screenW * 0.94f, by + bh), bh * 0.3f, bh * 0.3f, paint
        )
        centerText.color = Color.argb((alpha * 255).toInt(), 255, 240, 200)
        centerText.textSize = screenH * 0.028f
        canvas.drawText(tip.text, screenW / 2f, by + bh * 0.64f, centerText)

        // Persistent mini control legend at the bottom during the guide.
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = screenH * 0.024f
        textPaint.color = Color.argb(200, 230, 230, 230)
        canvas.drawText("← →  lanes      ↑  jump      ↓  slide", screenW / 2f, screenH * 0.93f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = screenH * 0.030f
        textPaint.color = Color.WHITE
    }

    private fun drawHud(canvas: Canvas) {
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
        canvas.drawText("Score  $score", screenW * 0.05f, screenH * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.rgb(255, 214, 74)
        canvas.drawText("◆ $coinCount", screenW * 0.95f, screenH * 0.07f, textPaint)
        textPaint.color = Color.argb(200, 230, 230, 230)
        textPaint.textSize = screenH * 0.024f
        canvas.drawText("Best $highScore", screenW * 0.95f, screenH * 0.108f, textPaint)
        textPaint.textSize = screenH * 0.030f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.WHITE
    }

    private fun drawOverlay(canvas: Canvas) {
        when (state) {
            GameState.READY -> {
                dim(canvas)
                // Title with drop shadow.
                centerText.textSize = screenH * 0.08f
                centerText.color = Color.argb(150, 0, 0, 0)
                canvas.drawText("RELIC RUSH", screenW / 2f + 4f, screenH * 0.26f + 4f, centerText)
                centerText.color = Color.rgb(255, 214, 74)
                canvas.drawText("RELIC RUSH", screenW / 2f, screenH * 0.26f, centerText)

                // How-to-play card.
                val cardTop = screenH * 0.34f
                val cardBot = screenH * 0.62f
                paint.color = Color.argb(150, 12, 24, 18)
                canvas.drawRoundRect(
                    RectF(screenW * 0.10f, cardTop, screenW * 0.90f, cardBot),
                    screenW * 0.04f, screenW * 0.04f, paint
                )
                centerText.color = Color.WHITE
                centerText.textSize = screenH * 0.032f
                canvas.drawText("HOW TO PLAY", screenW / 2f, cardTop + screenH * 0.045f, centerText)
                centerText.textSize = screenH * 0.027f
                centerText.color = Color.rgb(220, 224, 220)
                val lx = cardTop + screenH * 0.095f
                val gap = screenH * 0.042f
                canvas.drawText("← →   switch lanes", screenW / 2f, lx, centerText)
                canvas.drawText("↑   jump over hurdles", screenW / 2f, lx + gap, centerText)
                canvas.drawText("↓   slide under arches", screenW / 2f, lx + gap * 2, centerText)
                canvas.drawText("◆   collect relics, dodge blocks", screenW / 2f, lx + gap * 3, centerText)

                // Pulsing start prompt.
                val pulse = 0.6f + 0.4f * sin(uiTime * 3.0).toFloat()
                centerText.color = Color.argb((pulse * 255).toInt(), 255, 255, 255)
                centerText.textSize = screenH * 0.038f
                canvas.drawText("TAP TO START", screenW / 2f, screenH * 0.72f, centerText)
                centerText.color = Color.argb(180, 255, 214, 74)
                centerText.textSize = screenH * 0.026f
                canvas.drawText("Best  $highScore", screenW / 2f, screenH * 0.78f, centerText)
            }
            GameState.GAME_OVER -> {
                dim(canvas)
                centerText.color = Color.rgb(236, 96, 80)
                centerText.textSize = screenH * 0.072f
                canvas.drawText("GAME OVER", screenW / 2f, screenH * 0.36f, centerText)
                centerText.color = Color.WHITE
                centerText.textSize = screenH * 0.04f
                canvas.drawText("Score  $score", screenW / 2f, screenH * 0.47f, centerText)
                canvas.drawText("Relics  $coinCount", screenW / 2f, screenH * 0.525f, centerText)
                centerText.color = Color.rgb(255, 214, 74)
                canvas.drawText("Best  $highScore", screenW / 2f, screenH * 0.58f, centerText)
                val pulse = 0.6f + 0.4f * sin(uiTime * 3.0).toFloat()
                centerText.color = Color.argb((pulse * 255).toInt(), 255, 255, 255)
                centerText.textSize = screenH * 0.032f
                canvas.drawText("TAP TO TRY AGAIN", screenW / 2f, screenH * 0.68f, centerText)
            }
            GameState.PLAYING -> { /* no overlay */ }
        }
    }

    private fun dim(canvas: Canvas) {
        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, screenW, screenH, paint)
    }
}
