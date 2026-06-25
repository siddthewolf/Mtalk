package com.relicrush.game

import android.graphics.Canvas
import android.view.SurfaceHolder

/**
 * Dedicated render/update thread. Runs a simple variable-timestep loop, capping
 * dt so a hitch (e.g. GC pause) can't teleport the player through an obstacle.
 */
class GameThread(
    private val surfaceHolder: SurfaceHolder,
    private val gameView: GameView
) : Thread() {

    @Volatile
    var running = false

    private val targetFps = 60
    private val frameTimeMs = 1000L / targetFps

    override fun run() {
        var lastTime = System.nanoTime()
        while (running) {
            val frameStart = System.currentTimeMillis()

            val now = System.nanoTime()
            var dt = (now - lastTime) / 1_000_000_000f
            lastTime = now
            if (dt > 0.05f) dt = 0.05f   // clamp to 50 ms

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    synchronized(surfaceHolder) {
                        gameView.update(dt)
                        gameView.render(canvas)
                    }
                }
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (_: Exception) { /* surface gone */ }
                }
            }

            val elapsed = System.currentTimeMillis() - frameStart
            val sleep = frameTimeMs - elapsed
            if (sleep > 0) {
                try {
                    sleep(sleep)
                } catch (_: InterruptedException) { /* ignore */ }
            }
        }
    }
}
