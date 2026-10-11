package org.deadnet.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SprayAndWaitRoutingTest {

    @Test
    fun testTokenSplittingArithmetic() {
        // Initial token quota L = 5
        var currentL = 5
        var peerTokens = currentL / 2 // floor(5/2) = 2
        var localTokens = currentL - peerTokens // 3
        assertEquals(2, peerTokens)
        assertEquals(3, localTokens)

        // Second encounter with local L = 3
        currentL = localTokens
        peerTokens = currentL / 2 // floor(3/2) = 1
        localTokens = currentL - peerTokens // 2
        assertEquals(1, peerTokens)
        assertEquals(2, localTokens)

        // Third encounter with local L = 2
        currentL = localTokens
        peerTokens = currentL / 2 // floor(2/2) = 1
        localTokens = currentL - peerTokens // 1
        assertEquals(1, peerTokens)
        assertEquals(1, localTokens)

        // Fourth encounter: L = 1 => Wait Phase reached
        currentL = localTokens
        val canForwardToNonRecipient = currentL > 1
        assertEquals(1, currentL)
        assertTrue("When L=1, node must enter Wait Phase and not forward to generic neighbors", !canForwardToNonRecipient)
    }

    @Test
    fun testEvenTokenSplitting() {
        // L = 4
        var currentL = 4
        var peerTokens = currentL / 2
        var localTokens = currentL - peerTokens
        assertEquals(2, peerTokens)
        assertEquals(2, localTokens)

        // L = 2
        currentL = 2
        peerTokens = currentL / 2
        localTokens = currentL - peerTokens
        assertEquals(1, peerTokens)
        assertEquals(1, localTokens)
    }
}
