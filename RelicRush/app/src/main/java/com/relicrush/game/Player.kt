package com.relicrush.game

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The runner. Lives at the z = 0 plane; we only track its lane, vertical jump
 * height and slide state. Lane changes are animated via [laneFrac] for a smooth
 * glide between the two lanes (0 = left, 1 = right).
 */
class Player {

    companion object {
        const val MAX_LANE = 1                 // two lanes: 0 and 1
        const val LANE_SLIDE_SPEED = 12f       // how fast we lerp between lanes
        const val JUMP_VELOCITY = 2.7f         // initial upward speed (world units/s)
        const val GRAVITY = -7.4f              // downward accel (world units/s^2)
        const val JUMP_CLEAR_HEIGHT = 0.16f    // height above which barriers are cleared
        const val SLIDE_DURATION = 0.55f       // seconds a slide lasts
    }

    var targetLane = 0
    var laneFrac = 0f

    var height = 0f          // 0 = grounded, positive = in the air
    private var vy = 0f
    var isJumping = false

    var isSliding = false
    private var slideTimer = 0f

    /** Lane used for collision tests — the lane we're committed to. */
    val collisionLane: Int get() = laneFrac.roundToInt().coerceIn(0, MAX_LANE)

    fun reset() {
        targetLane = 0
        laneFrac = 0f
        height = 0f
        vy = 0f
        isJumping = false
        isSliding = false
        slideTimer = 0f
    }

    fun moveLeft() { if (targetLane > 0) targetLane-- }
    fun moveRight() { if (targetLane < MAX_LANE) targetLane++ }

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
