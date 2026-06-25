package com.relicrush.game

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The runner. Lives at the z = 0 plane; we only track its lane, vertical jump
 * height and slide state. Lane changes are animated via [laneFrac] for a smooth
 * slide between the three lanes (0 = left, 1 = centre, 2 = right).
 */
class Player {

    companion object {
        const val LANE_SLIDE_SPEED = 11f      // how fast we lerp between lanes
        const val JUMP_VELOCITY = 2.7f        // initial upward speed (world units/s)
        const val GRAVITY = -7.4f             // downward accel (world units/s^2)
        const val JUMP_CLEAR_HEIGHT = 0.16f   // height above which barriers are cleared
        const val SLIDE_DURATION = 0.55f      // seconds a slide lasts
    }

    var targetLane = 1
    var laneFrac = 1f

    var height = 0f          // 0 = grounded, positive = in the air
    private var vy = 0f
    var isJumping = false

    var isSliding = false
    private var slideTimer = 0f

    /** Lane used for collision tests — the lane we're committed to. */
    val collisionLane: Int get() = laneFrac.roundToInt().coerceIn(0, 2)

    fun reset() {
        targetLane = 1
        laneFrac = 1f
        height = 0f
        vy = 0f
        isJumping = false
        isSliding = false
        slideTimer = 0f
    }

    fun moveLeft() { if (targetLane > 0) targetLane-- }
    fun moveRight() { if (targetLane < 2) targetLane++ }

    fun jump() {
        if (!isJumping) {
            isJumping = true
            isSliding = false
            vy = JUMP_VELOCITY
        }
    }

    fun slide() {
        if (!isJumping && !isSliding) {
            isSliding = true
            slideTimer = SLIDE_DURATION
        }
    }

    fun update(dt: Float) {
        laneFrac += (targetLane - laneFrac) * min(1f, dt * LANE_SLIDE_SPEED)

        if (isJumping) {
            height += vy * dt
            vy += GRAVITY * dt
            if (height <= 0f) {
                height = 0f
                vy = 0f
                isJumping = false
            }
        }

        if (isSliding) {
            slideTimer -= dt
            if (slideTimer <= 0f) isSliding = false
        }
    }
}
