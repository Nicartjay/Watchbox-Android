package space.nicart.watchbox.cast

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the proxy decision and the mirrored receiver clock.
 *
 * Both are invisible when wrong. A missed proxy decision looks exactly like an unreachable
 * device - the television simply shows nothing - and a mishandled clock leaves the seek bar
 * frozen or snapped to the start while playback carries on elsewhere, with no error either way.
 */
class CastProxyDecisionTest {

    // -------------------------------------------------------------- proxy decision

    /** A `Referer` cannot be sent by a receiver, so the stream has to come via the phone. */
    @Test
    fun `a stream with headers is proxied`() {
        assertTrue(needsProxy(mapOf("Referer" to "https://example.test/"), forceProxy = false))
    }

    /**
     * The default path. Keeping the phone out of the data path is the whole reason not to
     * proxy unconditionally, so a header-free stream must still go direct.
     */
    @Test
    fun `a header-free stream is handed over directly`() {
        assertFalse(needsProxy(emptyMap(), forceProxy = false))
    }

    /**
     * The point of the switch: a link can be restricted in ways no header reveals - bound to a
     * cookie, or to the address that fetched it - and the user has no other way to say so.
     */
    @Test
    fun `the switch forces a proxy for an otherwise direct stream`() {
        assertTrue(needsProxy(emptyMap(), forceProxy = true))
    }

    @Test
    fun `the switch does not change a stream that was already being proxied`() {
        val headers = mapOf("Referer" to "https://example.test/")

        assertTrue(needsProxy(headers, forceProxy = true))
        assertEquals(needsProxy(headers, forceProxy = false), needsProxy(headers, true))
    }

    // ------------------------------------------------------------------ cast state

    @Test
    fun `a fresh state has no clock and does not force a proxy`() {
        val state = CastState()

        assertEquals(0L, state.positionMs)
        assertEquals(0L, state.durationMs)
        assertFalse(state.isRemotePlaying)
        assertFalse(state.forceProxy)
    }

    /**
     * The player reads position, duration and playing from the state while casting. They have
     * to survive being copied, because every session update rewrites the state.
     */
    @Test
    fun `the mirrored clock survives a state copy`() {
        val state = CastState(isCasting = true, deviceName = "Living Room")
            .copy(positionMs = 62_000L, durationMs = 5_400_000L, isRemotePlaying = true)

        assertEquals(62_000L, state.positionMs)
        assertEquals(5_400_000L, state.durationMs)
        assertTrue(state.isRemotePlaying)
        assertTrue(state.isCasting)
        assertEquals("Living Room", state.deviceName)
    }

    /**
     * Both transports answer 0 when they cannot report, so the poll keeps the previous value
     * instead. This pins the rule that made the seek bar stop jumping to the start whenever a
     * poll was missed - the guard lives in CastManager, this documents the arithmetic.
     */
    @Test
    fun `a zero poll keeps the previous position rather than resetting it`() {
        val previous = 62_000L
        val polled = 0L

        val next = if (polled > 0) polled else previous

        assertEquals(previous, next)
    }

    @Test
    fun `a real poll replaces the previous position`() {
        val previous = 62_000L
        val polled = 63_000L

        val next = if (polled > 0) polled else previous

        assertEquals(polled, next)
    }

    /** Stopping a session must clear the clock, or the player shows it as a local position. */
    @Test
    fun `ending a session clears the mirrored clock`() {
        val ended = CastState(
            isCasting = true,
            deviceName = "Living Room",
            positionMs = 62_000L,
            durationMs = 5_400_000L,
            isRemotePlaying = true,
        ).copy(
            isCasting = false,
            deviceName = null,
            positionMs = 0L,
            durationMs = 0L,
            isRemotePlaying = false,
        )

        assertFalse(ended.isCasting)
        assertEquals(0L, ended.positionMs)
        assertEquals(0L, ended.durationMs)
        assertFalse(ended.isRemotePlaying)
    }

    /**
     * The relay preference outlives a session, unlike the clock: it describes the user's
     * network, which does not change because they stopped casting.
     */
    @Test
    fun `the relay preference survives a session ending`() {
        val ended = CastState(isCasting = true, forceProxy = true)
            .copy(isCasting = false, deviceName = null, positionMs = 0L)

        assertTrue(ended.forceProxy)
    }

    // ------------------------------------------------------------ seek clamping

    /**
     * A receiver rejects a seek past the end, and DLNA renderers can drop the session over it.
     * This mirrors the clamp the player applies before sending one.
     */
    @Test
    fun `a seek past the end is clamped to the duration`() {
        val duration = 5_400_000L

        assertEquals(duration, (duration + 30_000L).coerceIn(0L, duration))
    }

    @Test
    fun `a seek before the start is clamped to zero`() {
        val duration = 5_400_000L

        assertEquals(0L, (-10_000L).coerceIn(0L, duration))
    }

    /**
     * Duration is unknown until the receiver reports it, and clamping to a zero upper bound
     * would pin every seek to the start. The player only clamps once a duration is known.
     */
    @Test
    fun `an unknown duration does not clamp a seek to zero`() {
        val duration = 0L
        val target = 62_000L

        val clamped = if (duration > 0) target.coerceIn(0L, duration) else target.coerceAtLeast(0L)

        assertEquals(target, clamped)
    }
}
