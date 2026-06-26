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
import kotlin.math.min
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

    // ====================================================================================
    //  Small value types
    // ====================================================================================

    /** A short-lived screen-space spark, used for relic-pickup bursts. */
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float
    )

    private class Drop(var x: Float, var y: Float, var len: Float, var speed: Float)
    private class Flake(var x: Float, var y: Float, var r: Float, var speed: Float, var phase: Float)
    private class Star(val x: Float, val y: Float, val phase: Float, val r: Float)

    /** A full environment look: sky, ground, celestial body, precipitation, mood. */
    private class Weather(
        val name: String,
        val skyTop: Int, val skyMid: Int, val skyHorizon: Int,
        val groundTop: Int, val groundBottom: Int,
        val sunColor: Int, val sunAlpha: Float, val sunRadius: Float,
        val starAlpha: Float,
        val precip: Int,            // 0 none, 1 rain, 2 snow
        val fog: Float,             // 0..1 haze strength
        val ambient: Int            // overlay tint (with alpha); 0 = none
    )

    // ====================================================================================
    //  Tunable constants
    // ====================================================================================
    private val zFar = 1.25f
    private val zCull = -0.18f
    private val spawnGapZ = 0.44f
    private val startSpeed = 0.40f
    private val maxSpeed = 1.55f
    private val accel = 0.0075f
    private val graceTime = 2.2f
    private val tutorialTime = 10f
    private val weatherTransition = 5f
    private val coinValue = 10
    private val prefs = context.getSharedPreferences("relicrush", Context.MODE_PRIVATE)

    // ====================================================================================
    //  State
    // ====================================================================================
    private var state = GameState.DEMO
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
    private var wheelSpin = 0f
    private var uiTime = 0f
    private var gameTime = 0f
    private var crashFlash = 0f
    private var score = 0
    private var coinCount = 0
    private var highScore = prefs.getInt("highScore", 0)
    private var screenW = 1f
    private var screenH = 1f

    // Demo choreography
    private var demoTimer = 0f
    private var demoIndex = 0
    private var demoCaption = ""

    // Weather
    private lateinit var palette: List<Weather>
    private var wA = neutralWeather()
    private var wB = wA
    private var weatherBlend = 1f
    private var weatherTimer = 0f
    private var transitioning = false

    // Weather effect buffers (sized once we know the screen)
    private var rain: Array<Drop> = arrayOf()
    private var snow: Array<Flake> = arrayOf()
    private var stars: Array<Star> = arrayOf()

    // ====================================================================================
    //  Paints / shaders
    // ====================================================================================
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Color.argb(90, 0, 0, 0)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.LEFT
    }
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
    }
    private var vignetteShader: Shader? = null

    private var thread: GameThread? = null
    private val gestureDetector: GestureDetector

    init {
        holder.addCallback(this)
        isFocusable = true
        gestureDetector = GestureDetector(context, GestureListener())
        buildPalette()
        randomizeWeather()
    }

    // ====================================================================================
    //  Surface lifecycle
    // ====================================================================================
    override fun surfaceCreated(holder: SurfaceHolder) = startThread()

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        screenW = width.toFloat()
        screenH = height.toFloat()
        vignetteShader = RadialGradient(
            screenW / 2f, screenH * 0.55f, max(screenW, screenH) * 0.75f,
            intArrayOf(Color.TRANSPARENT, Color.argb(120, 0, 0, 0)),
            floatArrayOf(0.6f, 1f), Shader.TileMode.CLAMP
        )
        textPaint.textSize = screenH * 0.030f
        rain = Array(160) { Drop(rng.nextFloat() * screenW, rng.nextFloat() * screenH, screenH * (0.03f + rng.nextFloat() * 0.04f), screenH * (1.1f + rng.nextFloat() * 0.7f)) }
        snow = Array(110) { Flake(rng.nextFloat() * screenW, rng.nextFloat() * screenH, screenW * (0.004f + rng.nextFloat() * 0.008f), screenH * (0.12f + rng.nextFloat() * 0.12f), rng.nextFloat() * 6.28f) }
        stars = Array(70) { Star(rng.nextFloat() * screenW, rng.nextFloat() * horizonY() * 0.95f, rng.nextFloat() * 6.28f, screenW * (0.002f + rng.nextFloat() * 0.004f)) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = stopThread()

    fun resume() = startThread()
    fun pause() = stopThread()

    private fun startThread() {
        if (thread?.running == true) return
        if (!holder.surface.isValid) return
        thread = GameThread(holder, this).also { it.running = true; it.start() }
    }

    private fun stopThread() {
        val t = thread ?: return
        t.running = false
        while (true) {
            try { t.join(); break } catch (_: InterruptedException) { /* retry */ }
        }
        thread = null
    }

    // ====================================================================================
    //  Projection
    // ====================================================================================
    private val horizonFrac = 0.38f
    private fun horizonY() = screenH * horizonFrac
    private fun groundBottomY() = screenH * 1.04f
    private fun laneSpacing() = screenW * 0.27f
    private fun scaleAt(z: Float): Float = 0.34f / (z + 0.34f)
    private val sNear = scaleAt(0f)
    private val sFar = scaleAt(zFar)

    private fun groundYAt(z: Float): Float {
        val t = (scaleAt(z) - sFar) / (sNear - sFar)
        return horizonY() + (groundBottomY() - horizonY()) * t
    }

    private fun laneXAt(laneFrac: Float, z: Float): Float =
        screenW / 2f + (laneFrac - 1f) * laneSpacing() * scaleAt(z)

    // ====================================================================================
    //  Weather
    // ====================================================================================
    private fun neutralWeather() = Weather(
        "Day", Color.rgb(58, 110, 190), Color.rgb(120, 170, 220), Color.rgb(220, 224, 210),
        Color.rgb(56, 96, 60), Color.rgb(30, 62, 40),
        Color.rgb(255, 240, 190), 1f, 0.16f, 0f, 0, 0f, 0
    )

    private fun buildPalette() {
        palette = listOf(
            neutralWeather(),
            Weather("Sunset", Color.rgb(60, 40, 96), Color.rgb(206, 96, 92), Color.rgb(250, 178, 96),
                Color.rgb(96, 70, 60), Color.rgb(52, 38, 44),
                Color.rgb(255, 168, 96), 1f, 0.22f, 0.15f, 0, 0f, Color.argb(40, 255, 120, 40)),
            Weather("Night", Color.rgb(8, 12, 34), Color.rgb(20, 26, 58), Color.rgb(48, 54, 96),
                Color.rgb(30, 44, 50), Color.rgb(14, 24, 30),
                Color.rgb(232, 236, 245), 0.95f, 0.13f, 1f, 0, 0f, Color.argb(95, 10, 16, 48)),
            Weather("Dawn", Color.rgb(70, 92, 150), Color.rgb(196, 150, 180), Color.rgb(255, 210, 170),
                Color.rgb(64, 100, 70), Color.rgb(36, 66, 46),
                Color.rgb(255, 224, 200), 0.9f, 0.15f, 0.25f, 0, 0f, Color.argb(30, 255, 180, 150)),
            Weather("Rain", Color.rgb(58, 66, 78), Color.rgb(82, 92, 104), Color.rgb(120, 130, 140),
                Color.rgb(48, 64, 54), Color.rgb(26, 40, 34),
                Color.rgb(200, 210, 220), 0.2f, 0.14f, 0f, 1, 0.25f, Color.argb(60, 30, 40, 55)),
            Weather("Snow", Color.rgb(150, 168, 196), Color.rgb(186, 200, 220), Color.rgb(226, 234, 244),
                Color.rgb(196, 208, 220), Color.rgb(150, 168, 186),
                Color.rgb(255, 252, 245), 0.7f, 0.15f, 0f, 2, 0.2f, Color.argb(30, 220, 235, 255)),
            Weather("Fog", Color.rgb(150, 154, 158), Color.rgb(176, 180, 184), Color.rgb(206, 208, 210),
                Color.rgb(120, 138, 120), Color.rgb(86, 104, 90),
                Color.rgb(230, 230, 230), 0.3f, 0.14f, 0f, 0, 0.7f, Color.argb(40, 200, 205, 210))
        )
    }

    private fun randomizeWeather() {
        val w = palette[rng.nextInt(palette.size)]
        wA = w; wB = w; weatherBlend = 1f; transitioning = false
        weatherTimer = 20f + rng.nextFloat() * 14f
    }

    private fun updateWeather(dt: Float) {
        if (transitioning) {
            weatherBlend += dt / weatherTransition
            if (weatherBlend >= 1f) {
                weatherBlend = 1f; wA = wB; transitioning = false
                weatherTimer = 20f + rng.nextFloat() * 14f
            }
        } else {
            weatherTimer -= dt
            if (weatherTimer <= 0f) {
                var next = palette[rng.nextInt(palette.size)]
                if (next.name == wA.name) next = palette[(palette.indexOf(next) + 1) % palette.size]
                wB = next; weatherBlend = 0f; transitioning = true
            }
        }
        updatePrecip(dt)
    }

    private fun lc(a: Int, b: Int) = lerpColor(a, b, weatherBlend)
    private fun lf(a: Float, b: Float) = a + (b - a) * weatherBlend

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * tt).toInt(),
            (Color.red(a) + (Color.red(b) - Color.red(a)) * tt).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * tt).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * tt).toInt()
        )
    }

    private fun rainStrength() = (if (wA.precip == 1) 1 - weatherBlend else 0f) + (if (wB.precip == 1) weatherBlend else 0f)
    private fun snowStrength() = (if (wA.precip == 2) 1 - weatherBlend else 0f) + (if (wB.precip == 2) weatherBlend else 0f)

    private fun updatePrecip(dt: Float) {
        if (rainStrength() > 0.01f) for (d in rain) {
            d.y += d.speed * dt
            if (d.y > screenH) { d.y = -d.len; d.x = rng.nextFloat() * screenW }
        }
        if (snowStrength() > 0.01f) for (f in snow) {
            f.y += f.speed * dt
            f.x += sin((uiTime * 1.5f + f.phase).toDouble()).toFloat() * screenW * 0.04f * dt
            if (f.y > screenH) { f.y = -f.r; f.x = rng.nextFloat() * screenW }
        }
    }

    // ====================================================================================
    //  Simulation
    // ====================================================================================
    fun update(dt: Float) {
        uiTime += dt
        roadScroll = (roadScroll + speed * dt) % 1f
        runCycle += dt * (8f + speed * 6f)
        wheelSpin += dt * speed * 40f
        if (crashFlash > 0f) crashFlash = max(0f, crashFlash - dt * 1.6f)
        updateWeather(dt)
        updateParticles(dt)

        when (state) {
            GameState.DEMO -> updateDemo(dt)
            GameState.PLAYING -> updatePlaying(dt)
            else -> { /* READY / GAME_OVER: world is frozen */ }
        }
    }

    private fun updatePlaying(dt: Float) {
        gameTime += dt
        speed = (speed + accel * dt).coerceIn(startSpeed, maxSpeed)
        player.update(dt)

        distanceToNextSpawn -= speed * dt
        if (distanceToNextSpawn <= 0f) {
            if (gameTime > graceTime) spawnRow(false)
            distanceToNextSpawn += spawnGapZ
        }
        emitPillars(dt)
        advanceAndResolve(dt)
        score += (speed * dt * 60f).toInt()
    }

    /** Auto-playing showcase: a perfect AI clears a slow, scripted sequence. */
    private fun updateDemo(dt: Float) {
        speed = 0.42f
        player.update(dt)
        emitPillars(dt)

        // Spawn one clearly-telegraphed obstacle at a time, cycling the move types.
        demoTimer -= speed * dt
        if (demoTimer <= 0f) {
            val lane = player.collisionLane
            when (demoIndex % 3) {
                0 -> obstacles.add(Obstacle(lane, zFar, ObstacleType.BARRIER))
                1 -> obstacles.add(Obstacle(lane, zFar, ObstacleType.OVERHANG))
                else -> obstacles.add(Obstacle(lane, zFar, ObstacleType.BLOCK))
            }
            val relicLane = (0..2).filter { it != lane }.random(rng)
            coins.add(Coin(relicLane, zFar))
            demoIndex++
            demoTimer = 0.62f
        }
        autopilot()
        advanceAndResolve(dt)
    }

    /** Reacts to the nearest hazard with the correct move — used by the demo. */
    private fun autopilot() {
        val o = obstacles.filter { !it.resolved && it.z > 0f }.minByOrNull { it.z } ?: run {
            demoCaption = "Skate, dodge and survive!"; return
        }
        if (o.z < 0.30f) {
            when (o.type) {
                ObstacleType.BARRIER -> { player.jump(); demoCaption = "Swipe UP to jump hurdles" }
                ObstacleType.OVERHANG -> { player.slide(); demoCaption = "Swipe DOWN to slide under arches" }
                ObstacleType.BLOCK -> {
                    val free = (0..2).firstOrNull { l ->
                        obstacles.none { it.lane == l && it.z in 0f..0.5f && it.type == ObstacleType.BLOCK }
                    } ?: player.targetLane
                    player.targetLane = free
                    demoCaption = "Swipe LEFT / RIGHT to change lanes"
                }
            }
        }
    }

    private fun emitPillars(dt: Float) {
        pillarTimer -= speed * dt
        if (pillarTimer <= 0f) {
            pillars.add(Pillar(if (rng.nextBoolean()) -1 else 1, zFar, 0.7f + rng.nextFloat() * 0.6f))
            pillarTimer += 0.5f
        }
    }

    private fun spawnRow(easyOverride: Boolean) {
        val openLane = rng.nextInt(3)
        val easy = easyOverride || gameTime < graceTime + 7f
        val roll = if (easy) rng.nextInt(3) else rng.nextInt(6)
        when (roll) {
            0 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.BARRIER))
            1 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.OVERHANG))
            2 -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.BLOCK))
            3 -> for (l in 0..2) if (l != openLane) obstacles.add(Obstacle(l, zFar, ObstacleType.BLOCK))
            4 -> for (l in 0..2) if (l != openLane) obstacles.add(Obstacle(l, zFar, ObstacleType.BARRIER))
            else -> obstacles.add(Obstacle(rng.nextInt(3), zFar, ObstacleType.OVERHANG))
        }
        val blocked = obstacles.filter { it.z == zFar && it.type == ObstacleType.BLOCK }.map { it.lane }.toSet()
        val free = (0..2).filter { it !in blocked }
        if (free.isNotEmpty()) coins.add(Coin(free[rng.nextInt(free.size)], zFar))
    }

    private fun advanceAndResolve(dt: Float) {
        val move = speed * dt
        val oi = obstacles.iterator()
        while (oi.hasNext()) {
            val o = oi.next()
            o.z -= move
            if (!o.resolved && o.z <= 0f) {
                o.resolved = true
                if (state == GameState.PLAYING && o.lane == player.collisionLane && !survives(o)) gameOver()
            }
            if (o.z < zCull) oi.remove()
        }
        val ci = coins.iterator()
        while (ci.hasNext()) {
            val c = ci.next()
            c.z -= move
            if (!c.collected && c.z <= 0f && c.lane == player.collisionLane) {
                c.collected = true
                if (state == GameState.PLAYING) { coinCount++; score += coinValue }
                spawnPickupBurst(laneXAt(c.lane.toFloat(), 0f), groundYAt(0f) - screenH * 0.10f)
            }
            if (c.z < zCull) ci.remove()
        }
        val pi = pillars.iterator()
        while (pi.hasNext()) {
            val p = pi.next(); p.z -= move; if (p.z < zCull) pi.remove()
        }
    }

    private fun survives(o: Obstacle): Boolean = when (o.type) {
        ObstacleType.BARRIER -> player.height > Player.JUMP_CLEAR_HEIGHT
        ObstacleType.OVERHANG -> player.isSliding
        ObstacleType.BLOCK -> false
    }

    private fun spawnPickupBurst(x: Float, y: Float) {
        repeat(12) {
            val ang = rng.nextFloat() * 6.2832f
            val sp = (0.6f + rng.nextFloat()) * screenH * 0.5f
            particles.add(
                Particle(x, y, cos(ang.toDouble()).toFloat() * sp, sin(ang.toDouble()).toFloat() * sp - screenH * 0.2f,
                    0.55f, 0.55f, if (rng.nextBoolean()) Color.rgb(255, 224, 130) else Color.rgb(255, 196, 64),
                    screenW * (0.008f + rng.nextFloat() * 0.01f))
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            p.x += p.vx * dt; p.y += p.vy * dt; p.vy += screenH * 1.6f * dt
        }
    }

    // ====================================================================================
    //  Game flow
    // ====================================================================================
    private fun startGame() {
        obstacles.clear(); coins.clear(); pillars.clear(); particles.clear()
        player.reset()
        speed = startSpeed
        distanceToNextSpawn = spawnGapZ
        pillarTimer = 0f; gameTime = 0f; score = 0; coinCount = 0
        state = GameState.PLAYING
    }

    private fun gameOver() {
        state = GameState.GAME_OVER
        crashFlash = 1f
        if (score > highScore) { highScore = score; prefs.edit().putInt("highScore", highScore).apply() }
    }

    private fun onTap() {
        when (state) {
            GameState.DEMO, GameState.READY, GameState.GAME_OVER -> startGame()
            GameState.PLAYING -> player.jump()
        }
    }

    // ====================================================================================
    //  Input — drag to steer (cursor-like), swipe up/down to jump/slide
    // ====================================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (state == GameState.PLAYING && event.actionMasked == MotionEvent.ACTION_MOVE) {
            // The board follows your thumb to whichever lane third you point at.
            val lane = ((event.x / screenW) * 3f).toInt().coerceIn(0, 2)
            player.targetLane = lane
        }
        return true
    }

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (state != GameState.PLAYING) { onTap(); return true }
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

    // ====================================================================================
    //  Rendering
    // ====================================================================================
    fun render(canvas: Canvas) {
        drawSky(canvas)
        drawGroundAndRoad(canvas)
        drawScenery(canvas)
        drawWorldObjects(canvas)
        drawPlayer(canvas)
        drawParticles(canvas)
        drawWeatherFront(canvas)
        drawVignette(canvas)
        if (state == GameState.DEMO) drawDemoCaption(canvas)
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
        paint.shader = LinearGradient(
            0f, 0f, 0f, hy,
            intArrayOf(lc(wA.skyTop, wB.skyTop), lc(wA.skyMid, wB.skyMid), lc(wA.skyHorizon, wB.skyHorizon)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, screenW, hy, paint)
        paint.shader = null

        // Stars (night).
        val sa = lf(wA.starAlpha, wB.starAlpha)
        if (sa > 0.02f) for (s in stars) {
            val tw = 0.5f + 0.5f * sin((uiTime * 2f + s.phase).toDouble()).toFloat()
            paint.color = Color.argb((sa * tw * 230).toInt(), 255, 255, 255)
            canvas.drawCircle(s.x, s.y, s.r, paint)
        }

        // Sun / moon.
        val sunA = lf(wA.sunAlpha, wB.sunAlpha)
        if (sunA > 0.02f) {
            val sx = screenW * 0.72f
            val sy = hy * 0.40f
            val r = hy * lf(wA.sunRadius, wB.sunRadius)
            paint.color = withAlpha(lc(wA.sunColor, wB.sunColor), (sunA * 70).toInt())
            canvas.drawCircle(sx, sy, r * 2.1f, paint)
            paint.color = withAlpha(lc(wA.sunColor, wB.sunColor), (sunA * 255).toInt())
            canvas.drawCircle(sx, sy, r, paint)
        }

        // Mountain ridge + temple landmark.
        paint.color = Color.argb(235, 38, 52, 64)
        val ridge = Path().apply {
            moveTo(0f, hy); var x = 0f; val step = screenW / 8f; var i = 0
            while (x <= screenW) {
                val peak = hy - (0.10f + 0.06f * (1 + sin((i * 1.3f).toDouble()).toFloat())) * screenH
                lineTo(x + step * 0.5f, peak); lineTo(x + step, hy); x += step; i++
            }
            lineTo(screenW, hy); close()
        }
        canvas.drawPath(ridge, paint)
        paint.color = Color.argb(235, 50, 64, 74)
        val px = screenW * 0.30f; var ty = hy; var tw = screenW * 0.22f
        repeat(4) { val th = hy * 0.07f; canvas.drawRect(px - tw / 2f, ty - th, px + tw / 2f, ty, paint); ty -= th; tw *= 0.74f }
    }

    private fun drawGroundAndRoad(canvas: Canvas) {
        val hy = horizonY(); val by = groundBottomY()
        paint.shader = LinearGradient(0f, hy, 0f, screenH,
            lc(wA.groundTop, wB.groundTop), lc(wA.groundBottom, wB.groundBottom), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, hy, screenW, screenH, paint)
        paint.shader = null

        val nearL = laneXAt(-0.5f, 0f); val nearR = laneXAt(2.5f, 0f)
        val farL = laneXAt(-0.5f, zFar); val farR = laneXAt(2.5f, zFar)
        paint.color = Color.rgb(86, 66, 48)
        canvas.drawPath(Path().apply { moveTo(farL, hy); lineTo(farR, hy); lineTo(nearR, by); lineTo(nearL, by); close() }, paint)

        paint.color = Color.rgb(98, 76, 56)
        canvas.drawPath(Path().apply {
            moveTo(laneXAt(0.5f, zFar), hy); lineTo(laneXAt(1.5f, zFar), hy)
            lineTo(laneXAt(1.5f, 0f), by); lineTo(laneXAt(0.5f, 0f), by); close()
        }, paint)

        paint.color = Color.rgb(150, 120, 80)
        paint.strokeWidth = max(3f, screenW * 0.012f)
        canvas.drawLine(farL, hy, nearL, by, paint)
        canvas.drawLine(farR, hy, nearR, by, paint)

        paint.color = Color.argb(140, 200, 170, 120)
        paint.strokeWidth = max(2f, screenW * 0.005f)
        for (d in floatArrayOf(0.5f, 1.5f)) canvas.drawLine(laneXAt(d, zFar), hy, laneXAt(d, 0f), by, paint)

        paint.color = Color.argb(90, 60, 44, 30)
        var z = zFar - (roadScroll * 0.16f)
        while (z > 0f) {
            val y = groundYAt(z); paint.strokeWidth = max(1.5f, 8f * scaleAt(z))
            canvas.drawLine(laneXAt(-0.5f, z), y, laneXAt(2.5f, z), y, paint); z -= 0.16f
        }
    }

    private fun drawScenery(canvas: Canvas) {
        for (p in pillars.sortedByDescending { it.z }) {
            if (p.z <= 0f) continue
            val s = scaleAt(p.z); val baseY = groundYAt(p.z)
            val x = laneXAt(if (p.side < 0) -1.35f else 3.35f, p.z)
            val w = screenW * 0.11f * s; val h = screenH * 0.55f * s * p.height
            paint.color = Color.rgb(120, 112, 96); canvas.drawRect(x - w / 2f, baseY - h, x + w / 2f, baseY, paint)
            paint.color = Color.rgb(92, 86, 72); canvas.drawRect(x - w / 2f, baseY - h, x - w / 2f + w * 0.32f, baseY, paint)
            paint.color = Color.rgb(74, 116, 70); canvas.drawRect(x - w / 2f * 1.15f, baseY - h, x + w / 2f * 1.15f, baseY - h + h * 0.08f, paint)
        }
    }

    private fun drawWorldObjects(canvas: Canvas) {
        data class D(val z: Float, val draw: () -> Unit)
        val list = ArrayList<D>()
        for (o in obstacles) if (o.z > zCull) list.add(D(o.z) { drawObstacle(canvas, o) })
        for (c in coins) if (!c.collected && c.z > zCull) list.add(D(c.z) { drawCoin(canvas, c) })
        list.sortByDescending { it.z }
        for (d in list) d.draw()
    }

    private fun drawObstacle(canvas: Canvas, o: Obstacle) {
        val s = scaleAt(o.z); val baseY = groundYAt(o.z); val cx = laneXAt(o.lane.toFloat(), o.z)
        val w = laneSpacing() * 0.72f * s
        outline.strokeWidth = max(1.5f, 2.5f * s)
        when (o.type) {
            ObstacleType.BARRIER -> {
                val h = screenH * 0.11f * s; val top = baseY - h
                paint.color = Color.rgb(60, 48, 36)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.1f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.1f, top, cx + w / 2f, baseY, paint)
                val barBot = top + h * 0.5f
                paint.color = Color.rgb(240, 196, 64); canvas.drawRect(cx - w / 2f, top, cx + w / 2f, barBot, paint)
                paint.color = Color.rgb(40, 36, 32)
                var sx = cx - w / 2f; val stripeW = w * 0.16f; var i = 0
                while (sx < cx + w / 2f) {
                    if (i % 2 == 0) canvas.drawRect(sx, top, (sx + stripeW).coerceAtMost(cx + w / 2f), barBot, paint)
                    sx += stripeW; i++
                }
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, barBot, outline)
            }
            ObstacleType.OVERHANG -> {
                val gap = screenH * 0.14f * s; val beamH = screenH * 0.11f * s; val top = baseY - gap - beamH
                paint.color = Color.rgb(168, 162, 150); canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, paint)
                paint.color = Color.rgb(132, 126, 116); canvas.drawRect(cx - w / 2f, top + beamH * 0.6f, cx + w / 2f, top + beamH, paint)
                paint.color = Color.rgb(120, 114, 104)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.14f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.14f, top, cx + w / 2f, baseY, paint)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, outline)
            }
            ObstacleType.BLOCK -> {
                val h = screenH * 0.27f * s; val top = baseY - h
                paint.color = Color.rgb(96, 80, 120); canvas.drawRect(cx - w / 2f, top, cx + w / 2f, baseY, paint)
                paint.color = Color.rgb(70, 56, 92); canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.32f, baseY, paint)
                paint.color = Color.rgb(126, 110, 150); canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + h * 0.12f, paint)
                paint.color = Color.argb(80, 40, 30, 56); paint.strokeWidth = max(1f, 1.6f * s)
                canvas.drawLine(cx - w / 2f, top + h * 0.5f, cx + w / 2f, top + h * 0.5f, paint)
                canvas.drawLine(cx, top + h * 0.12f, cx, top + h * 0.5f, paint)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, baseY, outline)
            }
        }
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val s = scaleAt(c.z); val baseY = groundYAt(c.z); val cx = laneXAt(c.lane.toFloat(), c.z)
        val bob = sin((uiTime * 3f + c.z * 6f).toDouble()).toFloat() * screenH * 0.012f * s
        val cy = baseY - screenH * 0.10f * s + bob; val r = screenW * 0.05f * s
        val spin = abs(cos((uiTime * 4f + c.z * 8f).toDouble()).toFloat()); val rx = r * (0.25f + 0.75f * spin)
        paint.color = Color.argb(70, 255, 220, 120); canvas.drawCircle(cx, cy, r * 1.5f, paint)
        paint.color = Color.rgb(255, 214, 74)
        canvas.drawPath(Path().apply { moveTo(cx, cy - r); lineTo(cx + rx, cy); lineTo(cx, cy + r); lineTo(cx - rx, cy); close() }, paint)
        paint.color = Color.rgb(255, 240, 180)
        canvas.drawPath(Path().apply { moveTo(cx, cy - r); lineTo(cx + rx, cy); lineTo(cx, cy); lineTo(cx - rx, cy); close() }, paint)
    }

    /** The skater: a leaning rider on a board with spinning wheels. */
    private fun drawPlayer(canvas: Canvas) {
        if (state == GameState.READY) return
        val baseY = groundYAt(0f) - screenH * 0.02f
        val cx = laneXAt(player.laneFrac, 0f)
        val jump = player.height * screenH * 0.30f
        val lean = (player.targetLane - player.laneFrac) * 0.5f   // tilt into turns
        val bodyW = screenW * 0.11f
        var bodyH = screenH * 0.17f
        if (player.isSliding) bodyH *= 0.5f
        val feetY = baseY - jump
        val topY = feetY - bodyH
        val sway = sin(runCycle.toDouble()).toFloat()

        // Shadow.
        paint.color = Color.argb((120 * (1f - player.height).coerceIn(0.25f, 1f)).toInt(), 0, 0, 0)
        canvas.drawOval(RectF(cx - bodyW * 0.95f, baseY - bodyW * 0.14f, cx + bodyW * 0.95f, baseY + bodyW * 0.2f), paint)

        canvas.save()
        canvas.rotate(lean * 16f, cx, feetY)

        // Skateboard: deck + two spinning wheels.
        val deckW = bodyW * 1.7f; val deckH = bodyH * 0.12f; val deckY = feetY + deckH * 0.2f
        paint.color = Color.rgb(232, 90, 70)
        canvas.drawRoundRect(RectF(cx - deckW / 2f, deckY, cx + deckW / 2f, deckY + deckH), deckH, deckH, paint)
        paint.color = Color.rgb(180, 60, 48)
        canvas.drawRoundRect(RectF(cx - deckW / 2f, deckY + deckH * 0.55f, cx + deckW / 2f, deckY + deckH), deckH, deckH, paint)
        val wheelR = deckH * 0.7f
        paint.color = Color.rgb(245, 235, 220)
        for (wx in floatArrayOf(cx - deckW * 0.33f, cx + deckW * 0.33f)) {
            canvas.drawCircle(wx, deckY + deckH + wheelR * 0.6f, wheelR, paint)
            paint.color = Color.rgb(120, 120, 120)
            val sxp = wx + cos(wheelSpin.toDouble()).toFloat() * wheelR * 0.6f
            val syp = deckY + deckH + wheelR * 0.6f + sin(wheelSpin.toDouble()).toFloat() * wheelR * 0.6f
            canvas.drawCircle(sxp, syp, wheelR * 0.25f, paint)
            paint.color = Color.rgb(245, 235, 220)
        }

        // Back leg + arm.
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = bodyW * 0.24f
        paint.color = Color.rgb(40, 150, 150)
        canvas.drawLine(cx, topY + bodyH * 0.55f, cx - bodyW * 0.45f, feetY, paint)
        canvas.drawLine(cx, topY + bodyH * 0.34f, cx - sway * bodyW * 0.5f - bodyW * 0.2f, topY + bodyH * 0.6f, paint)

        // Torso.
        paint.color = Color.rgb(72, 210, 200)
        canvas.drawRoundRect(RectF(cx - bodyW / 2f, topY, cx + bodyW / 2f, feetY), bodyW * 0.34f, bodyW * 0.34f, paint)
        paint.color = Color.rgb(236, 132, 72)
        canvas.drawRect(cx - bodyW / 2f, topY + bodyH * 0.30f, cx + bodyW / 2f, topY + bodyH * 0.44f, paint)

        // Front leg + arm (lifted forward for balance).
        paint.color = Color.rgb(60, 188, 184)
        canvas.drawLine(cx, topY + bodyH * 0.55f, cx + bodyW * 0.5f, feetY, paint)
        canvas.drawLine(cx, topY + bodyH * 0.34f, cx + sway * bodyW * 0.5f + bodyW * 0.45f, topY + bodyH * 0.2f, paint)

        // Head + helmet + goggles.
        val headR = bodyW * 0.42f; val headCy = topY - headR * 0.55f
        paint.color = Color.rgb(244, 206, 160); canvas.drawCircle(cx, headCy, headR, paint)
        paint.color = Color.rgb(60, 80, 200)
        canvas.drawArc(RectF(cx - headR, headCy - headR, cx + headR, headCy + headR), 180f, 180f, true, paint)
        paint.color = Color.rgb(150, 220, 255)
        canvas.drawRect(cx - headR * 0.7f, headCy - headR * 0.1f, cx + headR * 0.7f, headCy + headR * 0.25f, paint)

        paint.strokeCap = Paint.Cap.BUTT
        canvas.restore()
    }

    private fun drawParticles(canvas: Canvas) {
        for (p in particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = Color.argb((a * 255).toInt(), Color.red(p.color), Color.green(p.color), Color.blue(p.color))
            canvas.drawCircle(p.x, p.y, p.size * (0.4f + a), paint)
        }
    }

    /** Foreground weather: rain streaks, snow, fog haze, ambient mood tint. */
    private fun drawWeatherFront(canvas: Canvas) {
        val rs = rainStrength()
        if (rs > 0.01f) {
            paint.color = Color.argb((rs * 150).toInt(), 200, 215, 235)
            paint.strokeWidth = max(1.5f, screenW * 0.004f)
            val n = (rain.size * rs).toInt()
            for (i in 0 until n) { val d = rain[i]; canvas.drawLine(d.x, d.y, d.x - d.len * 0.18f, d.y + d.len, paint) }
        }
        val ss = snowStrength()
        if (ss > 0.01f) {
            for (i in 0 until (snow.size * ss).toInt()) {
                val f = snow[i]; paint.color = Color.argb((ss * 230).toInt(), 255, 255, 255)
                canvas.drawCircle(f.x, f.y, f.r, paint)
            }
        }
        val fog = lf(wA.fog, wB.fog)
        if (fog > 0.01f) {
            paint.shader = LinearGradient(0f, horizonY(), 0f, screenH,
                Color.argb((fog * 200).toInt(), 210, 214, 218), Color.argb((fog * 40).toInt(), 210, 214, 218),
                Shader.TileMode.CLAMP)
            canvas.drawRect(0f, horizonY(), screenW, screenH, paint); paint.shader = null
        }
        val amb = lerpColor(wA.ambient, wB.ambient, weatherBlend)
        if (Color.alpha(amb) > 0) { paint.color = amb; canvas.drawRect(0f, 0f, screenW, screenH, paint) }
    }

    private fun drawVignette(canvas: Canvas) {
        paint.shader = vignetteShader; canvas.drawRect(0f, 0f, screenW, screenH, paint); paint.shader = null
    }

    private fun drawDemoCaption(canvas: Canvas) {
        val by = screenH * 0.13f; val bh = screenH * 0.07f
        paint.color = Color.argb(160, 10, 18, 14)
        canvas.drawRoundRect(RectF(screenW * 0.08f, by, screenW * 0.92f, by + bh), bh * 0.3f, bh * 0.3f, paint)
        centerText.color = Color.rgb(255, 214, 74); centerText.textSize = screenH * 0.024f
        canvas.drawText("▶ DEMO", screenW / 2f, by + bh * 0.36f, centerText)
        centerText.color = Color.WHITE; centerText.textSize = screenH * 0.028f
        canvas.drawText(demoCaption, screenW / 2f, by + bh * 0.82f, centerText)

        val pulse = 0.6f + 0.4f * sin(uiTime * 3.0).toFloat()
        centerText.color = Color.argb((pulse * 255).toInt(), 255, 255, 255); centerText.textSize = screenH * 0.034f
        canvas.drawText("TAP TO PLAY", screenW / 2f, screenH * 0.9f, centerText)
    }

    private fun drawTutorial(canvas: Canvas) {
        data class Tip(val s: Float, val e: Float, val t: String)
        val tips = listOf(
            Tip(0f, 3f, "Drag your thumb ◀ ▶ to steer between lanes"),
            Tip(3f, 6f, "▲  Swipe up to JUMP the striped hurdles"),
            Tip(6f, 9f, "▼  Swipe down to SLIDE under arches"),
            Tip(9f, tutorialTime, "Dodge tall blocks • grab relics • survive!")
        )
        val tip = tips.firstOrNull { gameTime >= it.s && gameTime < it.e } ?: return
        val local = gameTime - tip.s; val span = tip.e - tip.s
        val alpha = (min(local, min(span - local, 0.4f)) / 0.4f).coerceIn(0f, 1f)
        val by = screenH * 0.20f; val bh = screenH * 0.075f
        paint.color = Color.argb((alpha * 165).toInt(), 12, 22, 18)
        canvas.drawRoundRect(RectF(screenW * 0.06f, by, screenW * 0.94f, by + bh), bh * 0.3f, bh * 0.3f, paint)
        centerText.color = Color.argb((alpha * 255).toInt(), 255, 240, 200); centerText.textSize = screenH * 0.027f
        canvas.drawText(tip.t, screenW / 2f, by + bh * 0.64f, centerText)
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = screenH * 0.023f
        textPaint.color = Color.argb(200, 230, 230, 230)
        canvas.drawText("drag to steer    ↑ jump    ↓ slide", screenW / 2f, screenH * 0.93f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = screenH * 0.030f; textPaint.color = Color.WHITE
    }

    private fun drawHud(canvas: Canvas) {
        if (state == GameState.DEMO || state == GameState.READY) return
        textPaint.textAlign = Paint.Align.LEFT; textPaint.color = Color.WHITE
        canvas.drawText("Score  $score", screenW * 0.05f, screenH * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT; textPaint.color = Color.rgb(255, 214, 74)
        canvas.drawText("◆ $coinCount", screenW * 0.95f, screenH * 0.07f, textPaint)
        textPaint.color = Color.argb(200, 230, 230, 230); textPaint.textSize = screenH * 0.022f
        canvas.drawText("Best $highScore  •  ${currentWeatherName()}", screenW * 0.95f, screenH * 0.105f, textPaint)
        textPaint.textSize = screenH * 0.030f; textPaint.textAlign = Paint.Align.LEFT; textPaint.color = Color.WHITE
    }

    private fun currentWeatherName() = if (transitioning && weatherBlend > 0.5f) wB.name else wA.name

    private fun drawOverlay(canvas: Canvas) {
        when (state) {
            GameState.GAME_OVER -> {
                dim(canvas)
                centerText.color = Color.rgb(236, 96, 80); centerText.textSize = screenH * 0.072f
                canvas.drawText("GAME OVER", screenW / 2f, screenH * 0.36f, centerText)
                centerText.color = Color.WHITE; centerText.textSize = screenH * 0.04f
                canvas.drawText("Score  $score", screenW / 2f, screenH * 0.47f, centerText)
                canvas.drawText("Relics  $coinCount", screenW / 2f, screenH * 0.525f, centerText)
                centerText.color = Color.rgb(255, 214, 74)
                canvas.drawText("Best  $highScore", screenW / 2f, screenH * 0.58f, centerText)
                val pulse = 0.6f + 0.4f * sin(uiTime * 3.0).toFloat()
                centerText.color = Color.argb((pulse * 255).toInt(), 255, 255, 255); centerText.textSize = screenH * 0.032f
                canvas.drawText("TAP TO TRY AGAIN", screenW / 2f, screenH * 0.68f, centerText)
            }
            else -> { /* DEMO has its own caption; PLAYING none */ }
        }
    }

    private fun dim(canvas: Canvas) {
        paint.color = Color.argb(150, 0, 0, 0); canvas.drawRect(0f, 0f, screenW, screenH, paint)
    }

    private fun withAlpha(color: Int, a: Int) =
        Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
