package org.schabi.newpipe.local.offline

import kotlin.random.Random

/** Pure queue index transition logic; contains no Android dependencies. */
internal object OfflineQueueNavigator {
    enum class Mode { SEQUENTIAL, SHUFFLE, REPEAT_ONE }

    fun next(size: Int, current: Int, mode: Mode, history: List<Int>, random: Random = Random.Default): Int? {
        if (size <= 0 || current !in 0 until size) return null
        if (mode == Mode.REPEAT_ONE) return current
        if (mode == Mode.SEQUENTIAL) return (current + 1).takeIf { it < size }
        if (size == 1) return current
        val completedRound = history.size >= size
        val unseen = if (completedRound) emptyList() else (0 until size).filter { it != current && it !in history }
        val candidates = unseen.ifEmpty { (0 until size).filter { it != current } }
        return candidates[random.nextInt(candidates.size)]
    }

    fun previous(size: Int, current: Int): Int? = if (size > 0 && current in 0 until size) (current - 1).takeIf { it >= 0 } else null
}
