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
 * ([render]). A two-lane endless runner set in a stylised city, using a
 * lightweight pseudo-3D projection: lane objects carry a depth `z` (0 = at the
 * player, larger = further away) which is projected to screen position and
 * scale so the world appears to rush toward the camera.
 *
 * All visuals are drawn procedurally with [Canvas] primitives — there are no
 * image assets, which keeps the game fully original and dependency-free.
 */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    // ====================================================================================
    //  Value types
    // ====================================================================================
    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float
    )
    private class Drop(var x: Float, var y: Float, var len: Float, var speed: Float)
    private class Flake(var x: Float, var y: Float, var r: Float, var speed: Float, var phase: Float)
    private class Star(val x: Float, val y: Float, val phase: Float, val r: Float)
    private class Building(val left: Float, val width: Float, val height: Float, val color: Int, val seed: Int)

    private class Weather(
        val name: String,
        val skyTop: Int, val skyMid: Int, val skyHorizon: Int,
        val groundTop: Int, val groundBottom: Int,
        val buildingTint: Int,
        val sunColor: Int, val sunAlpha: Float, val sunRadius: Float,
        val night: Float,          // 0 day .. 1 night (lights windows, shows stars)
        val precip: Int,           // 0 none, 1 rain, 2 snow
        val fog: Float,
        val ambient: Int
    )

    // ====================================================================================
    //  Tunables
    // ====================================================================================
    private val zFar = 1.25f
    private val zCull = -0.18f
    private val baseGapZ = 0.5f
    private val startSpeed = 0.42f
    private val maxSpeed = 1.5f
    private val accel = 0.0072f
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

    private var demoTimer = 0f
    private var demoIndex = 0
    private var demoCaption = ""

    private lateinit var palette: List<Weather>
    private var wA = dayWeather()
    private var wB = wA
    private var weatherBlend = 1f
    private var weatherTimer = 0f
    private var transitioning = false

    private var rain: Array<Drop> = arrayOf()
    private var snow: Array<Flake> = arrayOf()
    private var stars: Array<Star> = arrayOf()
    private var skyline: Array<Building> = arrayOf()

    // ====================================================================================
    //  Paints
    // ====================================================================================
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(110, 0, 0, 0) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.LEFT }
    private val centerText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
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
            screenW / 2f, screenH * 0.55f, max(screenW, screenH) * 0.78f,
            intArrayOf(Color.TRANSPARENT, Color.argb(125, 0, 0, 0)),
            floatArrayOf(0.58f, 1f), Shader.TileMode.CLAMP
        )
        textPaint.textSize = screenH * 0.030f
        rain = Array(170) { Drop(rng.nextFloat() * screenW, rng.nextFloat() * screenH, screenH * (0.03f + rng.nextFloat() * 0.04f), screenH * (1.2f + rng.nextFloat() * 0.7f)) }
        snow = Array(110) { Flake(rng.nextFloat() * screenW, rng.nextFloat() * screenH, screenW * (0.004f + rng.nextFloat() * 0.008f), screenH * (0.12f + rng.nextFloat() * 0.12f), rng.nextFloat() * 6.28f) }
        stars = Array(70) { Star(rng.nextFloat() * screenW, rng.nextFloat() * horizonY() * 0.9f, rng.nextFloat() * 6.28f, screenW * (0.002f + rng.nextFloat() * 0.004f)) }
        buildSkyline()
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
        while (true) { try { t.join(); break } catch (_: InterruptedException) {} }
        thread = null
    }

    // ====================================================================================
    //  Projection (two lanes: 0 = left, 1 = right)
    // ====================================================================================
    private val horizonFrac = 0.40f
    private fun horizonY() = screenH * horizonFrac
    private fun groundBottomY() = screenH * 1.04f
    private fun laneSpacing() = screenW * 0.30f
    private fun scaleAt(z: Float): Float = 0.34f / (z + 0.34f)
    private val sNear = scaleAt(0f)
    private val sFar = scaleAt(zFar)

    private fun groundYAt(z: Float): Float {
        val t = (scaleAt(z) - sFar) / (sNear - sFar)
        return horizonY() + (groundBottomY() - horizonY()) * t
    }

    private fun laneXAt(laneFrac: Float, z: Float): Float =
        screenW / 2f + (laneFrac - 0.5f) * laneSpacing() * scaleAt(z)

    // ====================================================================================
    //  Weather / city
    // ====================================================================================
    private fun dayWeather() = Weather(
        "Day", Color.rgb(74, 134, 206), Color.rgb(132, 178, 222), Color.rgb(208, 220, 226),
        Color.rgb(96, 104, 116), Color.rgb(62, 70, 80), Color.rgb(120, 130, 146),
        Color.rgb(255, 244, 200), 1f, 0.16f, 0f, 0, 0f, 0
    )

    private fun buildPalette() {
        palette = listOf(
            dayWeather(),
            Weather("Sunset", Color.rgb(58, 42, 96), Color.rgb(214, 104, 96), Color.rgb(252, 182, 104),
                Color.rgb(96, 78, 84), Color.rgb(54, 42, 50), Color.rgb(120, 92, 104),
                Color.rgb(255, 170, 96), 1f, 0.22f, 0.15f, 0, 0f, Color.argb(36, 255, 120, 40)),
            Weather("Night", Color.rgb(8, 12, 32), Color.rgb(18, 24, 54), Color.rgb(40, 48, 86),
                Color.rgb(26, 30, 46), Color.rgb(14, 18, 30), Color.rgb(30, 36, 58),
                Color.rgb(236, 240, 250), 0.95f, 0.13f, 1f, 0, 0f, Color.argb(90, 10, 16, 48)),
            Weather("Dawn", Color.rgb(82, 104, 160), Color.rgb(200, 156, 184), Color.rgb(255, 214, 176),
                Color.rgb(86, 96, 110), Color.rgb(52, 62, 76), Color.rgb(108, 112, 134),
                Color.rgb(255, 226, 200), 0.9f, 0.15f, 0.3f, 0, 0f, Color.argb(28, 255, 180, 150)),
            Weather("Rain", Color.rgb(54, 62, 74), Color.rgb(78, 88, 100), Color.rgb(116, 126, 136),
                Color.rgb(58, 66, 72), Color.rgb(32, 40, 46), Color.rgb(64, 72, 84),
                Color.rgb(200, 210, 220), 0.4f, 0.14f, 0.45f, 1, 0.25f, Color.argb(60, 30, 40, 55)),
            Weather("Snow", Color.rgb(150, 168, 196), Color.rgb(186, 200, 220), Color.rgb(224, 232, 242),
                Color.rgb(180, 190, 204), Color.rgb(138, 152, 168), Color.rgb(150, 162, 182),
                Color.rgb(255, 252, 245), 0.7f, 0.15f, 0.2f, 2, 0.2f, Color.argb(26, 220, 235, 255)),
            Weather("Fog", Color.rgb(150, 154, 158), Color.rgb(176, 180, 184), Color.rgb(204, 206, 208),
                Color.rgb(120, 126, 130), Color.rgb(84, 92, 96), Color.rgb(132, 138, 144),
                Color.rgb(230, 230, 230), 0.3f, 0.14f, 0.2f, 0, 0.7f, Color.argb(40, 200, 205, 210))
        )
    }

    private fun buildSkyline() {
        val list = ArrayList<Building>()
        var x = -screenW * 0.05f
        while (x < screenW * 1.05f) {
            val w = screenW * (0.07f + rng.nextFloat() * 0.09f)
            val h = horizonY() * (0.25f + rng.nextFloat() * 0.62f)
            val g = 60 + rng.nextInt(40)
            list.add(Building(x, w, h, Color.rgb(g, g + 6, g + 16), rng.nextInt(9999)))
            x += w * (1.02f + rng.nextFloat() * 0.15f)
        }
        skyline = list.toTypedArray()
    }

    private fun randomizeWeather() {
        val w = palette[rng.nextInt(palette.size)]
        wA = w; wB = w; weatherBlend = 1f; transitioning = false
        weatherTimer = 20f + rng.nextFloat() * 14f
    }

    private fun updateWeather(dt: Float) {
        if (transitioning) {
            weatherBlend += dt / weatherTransition
            if (weatherBlend >= 1f) { weatherBlend = 1f; wA = wB; transitioning = false; weatherTimer = 20f + rng.nextFloat() * 14f }
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
    private fun nightFactor() = lf(wA.night, wB.night)
    private fun rainStrength() = (if (wA.precip == 1) 1 - weatherBlend else 0f) + (if (wB.precip == 1) weatherBlend else 0f)
    private fun snowStrength() = (if (wA.precip == 2) 1 - weatherBlend else 0f) + (if (wB.precip == 2) weatherBlend else 0f)

    private fun updatePrecip(dt: Float) {
        if (rainStrength() > 0.01f) for (d in rain) { d.y += d.speed * dt; if (d.y > screenH) { d.y = -d.len; d.x = rng.nextFloat() * screenW } }
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
            else -> {}
        }
    }

    private fun updatePlaying(dt: Float) {
        gameTime += dt
        speed = (speed + accel * dt).coerceIn(startSpeed, maxSpeed)
        player.update(dt)
        distanceToNextSpawn -= speed * dt
        if (distanceToNextSpawn <= 0f) {
            if (gameTime > graceTime) spawnRow()
            distanceToNextSpawn += baseGapZ * (0.85f + rng.nextFloat() * 0.6f)  // randomised spacing
        }
        emitPillars(dt)
        advanceAndResolve(dt)
        score += (speed * dt * 60f).toInt()
    }

    private fun updateDemo(dt: Float) {
        speed = 0.42f
        player.update(dt)
        emitPillars(dt)
        demoTimer -= speed * dt
        if (demoTimer <= 0f) {
            val lane = player.collisionLane
            when (demoIndex % 3) {
                0 -> { obstacles.add(Obstacle(0, zFar, ObstacleType.BARRIER)); obstacles.add(Obstacle(1, zFar, ObstacleType.BARRIER)) }
                1 -> { obstacles.add(Obstacle(0, zFar, ObstacleType.OVERHANG)); obstacles.add(Obstacle(1, zFar, ObstacleType.OVERHANG)) }
                else -> obstacles.add(Obstacle(lane, zFar, ObstacleType.BLOCK))
            }
            coins.add(Coin(rng.nextInt(2), zFar))
            demoIndex++
            demoTimer = 0.7f
        }
        autopilot()
        advanceAndResolve(dt)
    }

    private fun autopilot() {
        val o = obstacles.filter { !it.resolved && it.z > 0f }.minByOrNull { it.z } ?: run {
            demoCaption = "Race through the city — survive!"; return
        }
        if (o.z < 0.32f) when (o.type) {
            ObstacleType.BARRIER -> { player.jump(); demoCaption = "Swipe UP to JUMP barriers" }
            ObstacleType.OVERHANG -> { player.slide(); demoCaption = "Swipe DOWN to SLIDE under signs" }
            ObstacleType.BLOCK -> { player.targetLane = 1 - o.lane; demoCaption = "Swipe LEFT / RIGHT to dodge cars" }
        }
    }

    private fun emitPillars(dt: Float) {
        pillarTimer -= speed * dt
        if (pillarTimer <= 0f) { pillars.add(Pillar(if (rng.nextBoolean()) -1 else 1, zFar, 0.8f + rng.nextFloat() * 0.7f)); pillarTimer += 0.42f }
    }

    /** Weighted toward jump/slide (often spanning BOTH lanes), with cars rarer. */
    private fun spawnRow() {
        val roll = rng.nextFloat()
        when {
            roll < 0.45f -> {                          // jump
                if (rng.nextFloat() < 0.6f) { obstacles.add(Obstacle(0, zFar, ObstacleType.BARRIER)); obstacles.add(Obstacle(1, zFar, ObstacleType.BARRIER)) }
                else obstacles.add(Obstacle(rng.nextInt(2), zFar, ObstacleType.BARRIER))
            }
            roll < 0.80f -> {                          // slide
                if (rng.nextFloat() < 0.6f) { obstacles.add(Obstacle(0, zFar, ObstacleType.OVERHANG)); obstacles.add(Obstacle(1, zFar, ObstacleType.OVERHANG)) }
                else obstacles.add(Obstacle(rng.nextInt(2), zFar, ObstacleType.OVERHANG))
            }
            else -> obstacles.add(Obstacle(rng.nextInt(2), zFar, ObstacleType.BLOCK))  // dodge a car
        }
        val blocked = obstacles.filter { it.z == zFar && it.type == ObstacleType.BLOCK }.map { it.lane }.toSet()
        val free = (0..1).filter { it !in blocked }
        if (free.isNotEmpty() && rng.nextFloat() < 0.85f) coins.add(Coin(free[rng.nextInt(free.size)], zFar))
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
        while (pi.hasNext()) { val p = pi.next(); p.z -= move; if (p.z < zCull) pi.remove() }
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
            particles.add(Particle(x, y, cos(ang.toDouble()).toFloat() * sp, sin(ang.toDouble()).toFloat() * sp - screenH * 0.2f,
                0.55f, 0.55f, if (rng.nextBoolean()) Color.rgb(255, 224, 130) else Color.rgb(255, 196, 64), screenW * (0.008f + rng.nextFloat() * 0.01f)))
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next(); p.life -= dt
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
        speed = startSpeed; distanceToNextSpawn = baseGapZ
        pillarTimer = 0f; gameTime = 0f; score = 0; coinCount = 0
        state = GameState.PLAYING
    }

    private fun gameOver() {
        state = GameState.GAME_OVER; crashFlash = 1f
        if (score > highScore) { highScore = score; prefs.edit().putInt("highScore", highScore).apply() }
    }

    private fun onTap() {
        when (state) {
            GameState.PLAYING -> player.jump()
            else -> startGame()
        }
    }

    // ====================================================================================
    //  Input — clean swipes only (no drag), so steering stays controllable
    // ====================================================================================
    override fun onTouchEvent(event: MotionEvent): Boolean = gestureDetector.onTouchEvent(event)

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (state != GameState.PLAYING) { onTap(); return true }
            val dx = e2.x - (e1?.x ?: e2.x)
            val dy = e2.y - (e1?.y ?: e2.y)
            // Require a deliberate swipe to avoid accidental triggers.
            val threshold = screenW * 0.04f
            if (abs(dx) < threshold && abs(dy) < threshold) return true
            if (abs(dx) > abs(dy)) { if (dx > 0) player.moveRight() else player.moveLeft() }
            else { if (dy > 0) player.slide() else player.jump() }
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
        if (crashFlash > 0f) { paint.color = Color.argb((crashFlash * 130).toInt(), 200, 40, 40); canvas.drawRect(0f, 0f, screenW, screenH, paint) }
        drawOverlay(canvas)
    }

    private fun drawSky(canvas: Canvas) {
        val hy = horizonY()
        paint.shader = LinearGradient(0f, 0f, 0f, hy,
            intArrayOf(lc(wA.skyTop, wB.skyTop), lc(wA.skyMid, wB.skyMid), lc(wA.skyHorizon, wB.skyHorizon)),
            floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, screenW, hy, paint); paint.shader = null

        val nf = nightFactor()
        if (nf > 0.05f) for (s in stars) {
            val tw = 0.5f + 0.5f * sin((uiTime * 2f + s.phase).toDouble()).toFloat()
            paint.color = Color.argb((nf * tw * 230).toInt(), 255, 255, 255); canvas.drawCircle(s.x, s.y, s.r, paint)
        }

        val sunA = lf(wA.sunAlpha, wB.sunAlpha)
        if (sunA > 0.02f) {
            val sx = screenW * 0.74f; val sy = hy * 0.34f; val r = hy * lf(wA.sunRadius, wB.sunRadius)
            paint.color = withAlpha(lc(wA.sunColor, wB.sunColor), (sunA * 70).toInt()); canvas.drawCircle(sx, sy, r * 2.1f, paint)
            paint.color = withAlpha(lc(wA.sunColor, wB.sunColor), (sunA * 255).toInt()); canvas.drawCircle(sx, sy, r, paint)
        }

        // City skyline along the horizon, with lit windows at night.
        val tint = lc(wA.buildingTint, wB.buildingTint)
        for (b in skyline) {
            val top = hy - b.height
            paint.color = lerpColor(b.color, tint, 0.5f)
            canvas.drawRect(b.left, top, b.left + b.width, hy, paint)
            drawWindows(canvas, b.left, top, b.left + b.width, hy, b.seed, nf, b.width * 0.18f, b.width * 0.28f)
            // rooftop highlight
            paint.color = Color.argb(50, 255, 255, 255)
            canvas.drawRect(b.left, top, b.left + b.width, top + b.height * 0.03f, paint)
        }
    }

    private fun drawWindows(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, seed: Int, night: Float, cw: Float, ch: Float) {
        val cols = max(1, ((r - l) / cw).toInt())
        val rows = max(2, ((b - t) / ch).toInt())
        val gw = (r - l) / cols; val gh = (b - t) / rows
        val wM = gw * 0.28f; val hM = gh * 0.28f
        for (cc in 0 until cols) for (rr in 0 until rows) {
            val lit = ((seed + cc * 7 + rr * 13) % 7) < 3
            paint.color = if (night > 0.25f && lit)
                Color.argb((night * 230).toInt(), 255, 224, 150)
            else Color.argb(70, 20, 26, 36)
            val wl = l + cc * gw + wM; val wt = t + rr * gh + hM
            canvas.drawRect(wl, wt, wl + gw - 2 * wM, wt + gh - 2 * hM, paint)
        }
    }

    private fun drawGroundAndRoad(canvas: Canvas) {
        val hy = horizonY(); val by = groundBottomY()
        paint.shader = LinearGradient(0f, hy, 0f, screenH, lc(wA.groundTop, wB.groundTop), lc(wA.groundBottom, wB.groundBottom), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, hy, screenW, screenH, paint); paint.shader = null

        val nearL = laneXAt(-0.6f, 0f); val nearR = laneXAt(1.6f, 0f)
        val farL = laneXAt(-0.6f, zFar); val farR = laneXAt(1.6f, zFar)
        // Asphalt.
        paint.color = Color.rgb(46, 48, 54)
        canvas.drawPath(Path().apply { moveTo(farL, hy); lineTo(farR, hy); lineTo(nearR, by); lineTo(nearL, by); close() }, paint)
        // Kerb lines.
        paint.color = Color.rgb(210, 210, 214); paint.strokeWidth = max(3f, screenW * 0.012f)
        canvas.drawLine(farL, hy, nearL, by, paint); canvas.drawLine(farR, hy, nearR, by, paint)
        // Dashed yellow centre line between the two lanes.
        paint.color = Color.rgb(232, 196, 64); paint.strokeCap = Paint.Cap.ROUND
        var z = zFar - (roadScroll * 0.22f)
        while (z > 0f) {
            val z2 = z - 0.07f
            if (z2 > 0f) {
                val y1 = groundYAt(z); val y2 = groundYAt(z2)
                paint.strokeWidth = max(2f, screenW * 0.018f * scaleAt(z))
                canvas.drawLine(laneXAt(0.5f, z), y1, laneXAt(0.5f, z2), y2, paint)
            }
            z -= 0.22f
        }
        paint.strokeCap = Paint.Cap.BUTT
    }

    private fun drawScenery(canvas: Canvas) {
        // Side buildings approach the camera, forming a city corridor.
        for (p in pillars.sortedByDescending { it.z }) {
            if (p.z <= 0f) continue
            val s = scaleAt(p.z); val baseY = groundYAt(p.z)
            val x = laneXAt(if (p.side < 0) -1.5f else 2.5f, p.z)
            val w = screenW * 0.34f * s; val h = screenH * 0.78f * s * p.height
            val left = if (p.side < 0) x - w else x
            val right = left + w; val top = baseY - h
            val tint = lc(wA.buildingTint, wB.buildingTint)
            paint.color = tint; canvas.drawRect(left, top, right, baseY, paint)
            paint.color = Color.argb(60, 0, 0, 0)
            canvas.drawRect(if (p.side < 0) right - w * 0.3f else left, top, if (p.side < 0) right else left + w * 0.3f, baseY, paint)
            drawWindows(canvas, left, top, right, baseY, (p.z * 1000).toInt(), nightFactor(), w * 0.22f, screenH * 0.06f * s)
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
        val w = laneSpacing() * 0.82f * s
        outline.strokeWidth = max(1.5f, 2.5f * s)
        when (o.type) {
            ObstacleType.BARRIER -> {
                val h = screenH * 0.11f * s; val top = baseY - h
                paint.color = Color.rgb(70, 60, 52)
                canvas.drawRect(cx - w / 2f, top + h * 0.5f, cx - w / 2f + w * 0.1f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.1f, top + h * 0.5f, cx + w / 2f, baseY, paint)
                val barBot = top + h * 0.5f
                paint.color = Color.rgb(244, 130, 40); canvas.drawRect(cx - w / 2f, top, cx + w / 2f, barBot, paint)
                paint.color = Color.rgb(238, 238, 240)
                var sx = cx - w / 2f; val stripeW = w * 0.16f; var i = 0
                while (sx < cx + w / 2f) { if (i % 2 == 0) canvas.drawRect(sx, top, (sx + stripeW).coerceAtMost(cx + w / 2f), barBot, paint); sx += stripeW; i++ }
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, barBot, outline)
            }
            ObstacleType.OVERHANG -> {
                val gap = screenH * 0.14f * s; val beamH = screenH * 0.115f * s; val top = baseY - gap - beamH
                paint.color = Color.rgb(74, 84, 96); canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, paint)
                paint.color = Color.rgb(40, 120, 180); canvas.drawRect(cx - w * 0.32f, top + beamH * 0.18f, cx + w * 0.32f, top + beamH * 0.82f, paint)
                paint.color = Color.rgb(90, 100, 112)
                canvas.drawRect(cx - w / 2f, top, cx - w / 2f + w * 0.1f, baseY, paint)
                canvas.drawRect(cx + w / 2f - w * 0.1f, top, cx + w / 2f, baseY, paint)
                canvas.drawRect(cx - w / 2f, top, cx + w / 2f, top + beamH, outline)
            }
            ObstacleType.BLOCK -> drawCar(canvas, cx, baseY, w, s)
        }
    }

    /** A parked car seen from behind — the dodge obstacle. */
    private fun drawCar(canvas: Canvas, cx: Float, baseY: Float, w: Float, s: Float) {
        val bodyH = screenH * 0.10f * s
        val cabinH = screenH * 0.075f * s
        val carW = w * 1.02f
        val top = baseY - bodyH - cabinH
        val wheelR = bodyH * 0.42f
        paint.color = Color.rgb(30, 30, 34)
        canvas.drawCircle(cx - carW * 0.32f, baseY, wheelR, paint)
        canvas.drawCircle(cx + carW * 0.32f, baseY, wheelR, paint)
        // Cabin.
        paint.color = Color.rgb(196, 64, 60)
        canvas.drawRoundRect(RectF(cx - carW * 0.34f, top, cx + carW * 0.34f, top + cabinH), carW * 0.06f, carW * 0.06f, paint)
        // Rear window.
        paint.color = Color.rgb(150, 200, 225)
        canvas.drawRoundRect(RectF(cx - carW * 0.27f, top + cabinH * 0.18f, cx + carW * 0.27f, top + cabinH * 0.78f), carW * 0.03f, carW * 0.03f, paint)
        // Body.
        paint.color = Color.rgb(214, 74, 68)
        canvas.drawRoundRect(RectF(cx - carW / 2f, top + cabinH * 0.7f, cx + carW / 2f, baseY - wheelR * 0.3f), carW * 0.08f, carW * 0.08f, paint)
        paint.color = Color.rgb(160, 48, 44)
        canvas.drawRect(cx - carW / 2f, baseY - wheelR * 0.9f, cx + carW / 2f, baseY - wheelR * 0.3f, paint)
        // Tail lights.
        paint.color = Color.rgb(255, 90, 70)
        canvas.drawRoundRect(RectF(cx - carW * 0.46f, top + cabinH * 0.95f, cx - carW * 0.34f, top + cabinH * 1.25f), 3f, 3f, paint)
        canvas.drawRoundRect(RectF(cx + carW * 0.34f, top + cabinH * 0.95f, cx + carW * 0.46f, top + cabinH * 1.25f), 3f, 3f, paint)
        outline.strokeWidth = max(1.5f, 2.5f * s)
        canvas.drawRoundRect(RectF(cx - carW / 2f, top + cabinH * 0.7f, cx + carW / 2f, baseY - wheelR * 0.3f), carW * 0.08f, carW * 0.08f, outline)
    }

    private fun drawCoin(canvas: Canvas, c: Coin) {
        val s = scaleAt(c.z); val baseY = groundYAt(c.z); val cx = laneXAt(c.lane.toFloat(), c.z)
        val bob = sin((uiTime * 3f + c.z * 6f).toDouble()).toFloat() * screenH * 0.012f * s
        val cy = baseY - screenH * 0.10f * s + bob; val r = screenW * 0.05f * s
        val spin = abs(cos((uiTime * 4f + c.z * 8f).toDouble()).toFloat()); val rx = r * (0.2f + 0.8f * spin)
        paint.color = Color.argb(70, 255, 220, 120); canvas.drawOval(RectF(cx - rx * 1.5f, cy - r * 1.5f, cx + rx * 1.5f, cy + r * 1.5f), paint)
        paint.color = Color.rgb(247, 196, 52); canvas.drawOval(RectF(cx - rx, cy - r, cx + rx, cy + r), paint)
        paint.color = Color.rgb(255, 226, 120); canvas.drawOval(RectF(cx - rx * 0.6f, cy - r * 0.6f, cx + rx * 0.6f, cy + r * 0.6f), paint)
        paint.color = Color.argb(220, 255, 255, 255); canvas.drawOval(RectF(cx - rx * 0.3f, cy - r * 0.5f, cx + rx * 0.05f, cy - r * 0.05f), paint)
    }

    /** The skater: a leaning rider on a board with spinning wheels. */
    private fun drawPlayer(canvas: Canvas) {
        if (state == GameState.READY) return
        val baseY = groundYAt(0f) - screenH * 0.02f
        val cx = laneXAt(player.laneFrac, 0f)
        val jump = player.height * screenH * 0.30f
        val lean = (player.targetLane - player.laneFrac)
        val bodyW = screenW * 0.115f
        var bodyH = screenH * 0.18f
        if (player.isSliding) bodyH *= 0.5f
        val feetY = baseY - jump; val topY = feetY - bodyH
        val sway = sin(runCycle.toDouble()).toFloat()

        paint.color = Color.argb((120 * (1f - player.height).coerceIn(0.25f, 1f)).toInt(), 0, 0, 0)
        canvas.drawOval(RectF(cx - bodyW, baseY - bodyW * 0.14f, cx + bodyW, baseY + bodyW * 0.2f), paint)

        canvas.save()
        canvas.rotate(lean * 14f, cx, feetY)

        // Skateboard.
        val deckW = bodyW * 1.8f; val deckH = bodyH * 0.11f; val deckY = feetY + deckH * 0.2f
        paint.color = Color.rgb(60, 200, 220)
        canvas.drawRoundRect(RectF(cx - deckW / 2f, deckY, cx + deckW / 2f, deckY + deckH), deckH, deckH, paint)
        paint.color = Color.rgb(36, 150, 168)
        canvas.drawRoundRect(RectF(cx - deckW / 2f, deckY + deckH * 0.55f, cx + deckW / 2f, deckY + deckH), deckH, deckH, paint)
        val wheelR = deckH * 0.7f; paint.color = Color.rgb(245, 235, 220)
        for (wx in floatArrayOf(cx - deckW * 0.33f, cx + deckW * 0.33f)) {
            canvas.drawCircle(wx, deckY + deckH + wheelR * 0.6f, wheelR, paint)
            paint.color = Color.rgb(110, 110, 110)
            canvas.drawCircle(wx + cos(wheelSpin.toDouble()).toFloat() * wheelR * 0.5f, deckY + deckH + wheelR * 0.6f + sin(wheelSpin.toDouble()).toFloat() * wheelR * 0.5f, wheelR * 0.25f, paint)
            paint.color = Color.rgb(245, 235, 220)
        }

        // Back limbs.
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeWidth = bodyW * 0.26f
        paint.color = Color.rgb(36, 40, 52)
        canvas.drawLine(cx, topY + bodyH * 0.58f, cx - bodyW * 0.42f, feetY, paint)              // back leg
        paint.color = Color.rgb(214, 90, 60)
        canvas.drawLine(cx, topY + bodyH * 0.34f, cx - sway * bodyW * 0.4f - bodyW * 0.22f, topY + bodyH * 0.62f, paint) // back arm

        // Torso (hoodie) with zipper highlight.
        paint.color = Color.rgb(228, 96, 64)
        canvas.drawRoundRect(RectF(cx - bodyW / 2f, topY, cx + bodyW / 2f, feetY), bodyW * 0.36f, bodyW * 0.36f, paint)
        paint.color = Color.rgb(255, 150, 110); paint.strokeWidth = bodyW * 0.08f
        canvas.drawLine(cx, topY + bodyH * 0.18f, cx, feetY - bodyH * 0.1f, paint)

        // Front limbs.
        paint.strokeWidth = bodyW * 0.26f; paint.color = Color.rgb(50, 54, 68)
        canvas.drawLine(cx, topY + bodyH * 0.58f, cx + bodyW * 0.5f, feetY, paint)               // front leg
        paint.color = Color.rgb(232, 110, 78)
        canvas.drawLine(cx, topY + bodyH * 0.34f, cx + sway * bodyW * 0.4f + bodyW * 0.42f, topY + bodyH * 0.22f, paint) // front arm

        // Head + hair + face.
        val headR = bodyW * 0.44f; val headCy = topY - headR * 0.5f
        paint.color = Color.rgb(244, 206, 160); canvas.drawCircle(cx, headCy, headR, paint)
        paint.color = Color.rgb(58, 42, 34)
        canvas.drawArc(RectF(cx - headR, headCy - headR, cx + headR, headCy + headR), 165f, 210f, true, paint)
        paint.color = Color.rgb(30, 30, 36)
        canvas.drawCircle(cx + headR * 0.3f, headCy, headR * 0.12f, paint)                        // eye

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

    private fun drawWeatherFront(canvas: Canvas) {
        val rs = rainStrength()
        if (rs > 0.01f) {
            paint.color = Color.argb((rs * 150).toInt(), 200, 215, 235); paint.strokeWidth = max(1.5f, screenW * 0.004f)
            for (i in 0 until (rain.size * rs).toInt()) { val d = rain[i]; canvas.drawLine(d.x, d.y, d.x - d.len * 0.18f, d.y + d.len, paint) }
        }
        val ss = snowStrength()
        if (ss > 0.01f) for (i in 0 until (snow.size * ss).toInt()) {
            val f = snow[i]; paint.color = Color.argb((ss * 230).toInt(), 255, 255, 255); canvas.drawCircle(f.x, f.y, f.r, paint)
        }
        val fog = lf(wA.fog, wB.fog)
        if (fog > 0.01f) {
            paint.shader = LinearGradient(0f, horizonY(), 0f, screenH,
                Color.argb((fog * 200).toInt(), 210, 214, 218), Color.argb((fog * 40).toInt(), 210, 214, 218), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, horizonY(), screenW, screenH, paint); paint.shader = null
        }
        val amb = lerpColor(wA.ambient, wB.ambient, weatherBlend)
        if (Color.alpha(amb) > 0) { paint.color = amb; canvas.drawRect(0f, 0f, screenW, screenH, paint) }
    }

    private fun drawVignette(canvas: Canvas) { paint.shader = vignetteShader; canvas.drawRect(0f, 0f, screenW, screenH, paint); paint.shader = null }

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
            Tip(0f, 3f, "◀ ▶  Swipe left / right to switch lane"),
            Tip(3f, 6f, "▲  Swipe up to JUMP barriers"),
            Tip(6f, 9f, "▼  Swipe down to SLIDE under signs"),
            Tip(9f, tutorialTime, "Grab coins • dodge cars • survive!")
        )
        val tip = tips.firstOrNull { gameTime >= it.s && gameTime < it.e } ?: return
        val local = gameTime - tip.s; val span = tip.e - tip.s
        val alpha = (min(local, min(span - local, 0.4f)) / 0.4f).coerceIn(0f, 1f)
        val by = screenH * 0.20f; val bh = screenH * 0.075f
        paint.color = Color.argb((alpha * 170).toInt(), 12, 22, 18)
        canvas.drawRoundRect(RectF(screenW * 0.06f, by, screenW * 0.94f, by + bh), bh * 0.3f, bh * 0.3f, paint)
        centerText.color = Color.argb((alpha * 255).toInt(), 255, 240, 200); centerText.textSize = screenH * 0.027f
        canvas.drawText(tip.t, screenW / 2f, by + bh * 0.64f, centerText)
        textPaint.textAlign = Paint.Align.CENTER; textPaint.textSize = screenH * 0.023f; textPaint.color = Color.argb(200, 230, 230, 230)
        canvas.drawText("← → lane    ↑ jump    ↓ slide", screenW / 2f, screenH * 0.93f, textPaint)
        textPaint.textAlign = Paint.Align.LEFT; textPaint.textSize = screenH * 0.030f; textPaint.color = Color.WHITE
    }

    private fun drawHud(canvas: Canvas) {
        if (state == GameState.DEMO || state == GameState.READY) return
        textPaint.textAlign = Paint.Align.LEFT; textPaint.color = Color.WHITE
        canvas.drawText("Score  $score", screenW * 0.05f, screenH * 0.07f, textPaint)
        textPaint.textAlign = Paint.Align.RIGHT; textPaint.color = Color.rgb(255, 214, 74)
        canvas.drawText("● $coinCount", screenW * 0.95f, screenH * 0.07f, textPaint)
        textPaint.color = Color.argb(200, 230, 230, 230); textPaint.textSize = screenH * 0.022f
        canvas.drawText("Best $highScore  •  ${currentWeatherName()}", screenW * 0.95f, screenH * 0.105f, textPaint)
        textPaint.textSize = screenH * 0.030f; textPaint.textAlign = Paint.Align.LEFT; textPaint.color = Color.WHITE
    }

    private fun currentWeatherName() = if (transitioning && weatherBlend > 0.5f) wB.name else wA.name

    private fun drawOverlay(canvas: Canvas) {
        if (state == GameState.GAME_OVER) {
            dim(canvas)
            centerText.color = Color.rgb(236, 96, 80); centerText.textSize = screenH * 0.072f
            canvas.drawText("GAME OVER", screenW / 2f, screenH * 0.36f, centerText)
            centerText.color = Color.WHITE; centerText.textSize = screenH * 0.04f
            canvas.drawText("Score  $score", screenW / 2f, screenH * 0.47f, centerText)
            canvas.drawText("Coins  $coinCount", screenW / 2f, screenH * 0.525f, centerText)
            centerText.color = Color.rgb(255, 214, 74)
            canvas.drawText("Best  $highScore", screenW / 2f, screenH * 0.58f, centerText)
            val pulse = 0.6f + 0.4f * sin(uiTime * 3.0).toFloat()
            centerText.color = Color.argb((pulse * 255).toInt(), 255, 255, 255); centerText.textSize = screenH * 0.032f
            canvas.drawText("TAP TO TRY AGAIN", screenW / 2f, screenH * 0.68f, centerText)
        }
    }

    private fun dim(canvas: Canvas) { paint.color = Color.argb(150, 0, 0, 0); canvas.drawRect(0f, 0f, screenW, screenH, paint) }
    private fun withAlpha(color: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))
}
