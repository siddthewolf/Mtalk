package com.relicrush.game

/** High-level screens the game can be in. */
enum class GameState { DEMO, READY, PLAYING, GAME_OVER }

/** What kind of hazard a lane object is, and therefore how you survive it. */
enum class ObstacleType {
    /** Low hurdle on the ground — jump over it. */
    BARRIER,
    /** Beam up high with a gap underneath — slide under it. */
    OVERHANG,
    /** Tall solid block — you cannot pass it, switch lanes. */
    BLOCK
}

/**
 * A hazard travelling toward the camera. [z] starts far (large) and decreases
 * to 0 as it reaches the player's plane.
 */
class Obstacle(var lane: Int, var z: Float, val type: ObstacleType) {
    var resolved = false
}

/** A collectible relic on a lane. Same depth model as [Obstacle]. */
class Coin(var lane: Int, var z: Float) {
    var collected = false
}

/** Decorative roadside pillar — pure visual depth cue, no collision. */
class Pillar(val side: Int, var z: Float, val height: Float)
