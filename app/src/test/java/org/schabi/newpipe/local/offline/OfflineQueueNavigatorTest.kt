package org.schabi.newpipe.local.offline

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineQueueNavigatorTest {
    @Test fun sequentialStopsAtEndAndEmptyQueue() {
        assertEquals(1, OfflineQueueNavigator.next(3, 0, OfflineQueueNavigator.Mode.SEQUENTIAL, emptyList()))
        assertNull(OfflineQueueNavigator.next(3, 2, OfflineQueueNavigator.Mode.SEQUENTIAL, emptyList()))
        assertNull(OfflineQueueNavigator.next(0, 0, OfflineQueueNavigator.Mode.SEQUENTIAL, emptyList()))
    }

    @Test fun shuffleAvoidsImmediateRepeatAndHandlesSingleItem() {
        assertNotEquals(1, OfflineQueueNavigator.next(3, 1, OfflineQueueNavigator.Mode.SHUFFLE, listOf(1), Random(1)))
        assertEquals(0, OfflineQueueNavigator.next(1, 0, OfflineQueueNavigator.Mode.SHUFFLE, listOf(0)))
    }

    @Test fun shuffleCompletesRoundBeforeRepeatingHistory() {
        val visited = listOf(0, 2)
        assertEquals(1, OfflineQueueNavigator.next(3, 2, OfflineQueueNavigator.Mode.SHUFFLE, visited, Random(7)))
    }

    @Test fun repeatOneRetainsCurrentAndPreviousBoundaryStops() {
        assertEquals(2, OfflineQueueNavigator.next(4, 2, OfflineQueueNavigator.Mode.REPEAT_ONE, emptyList()))
        assertNull(OfflineQueueNavigator.previous(4, 0))
        assertEquals(1, OfflineQueueNavigator.previous(4, 2))
    }
}
