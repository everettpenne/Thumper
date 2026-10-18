package com.everettpenne.thumper

import com.everettpenne.thumper.playback.PiP
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure "does leaving the app float this episode?" rule, driven without the player or Android. */
class PiPTest {
    @Test fun `a video episode floats while it plays`() {
        assertTrue(PiP.shouldEnter(isVideo = true, isPlaying = true))
    }

    @Test fun `a paused video episode stays put`() {
        assertFalse(PiP.shouldEnter(isVideo = true, isPlaying = false))
    }

    @Test fun `an audio episode never floats, even while playing`() {
        assertFalse(PiP.shouldEnter(isVideo = false, isPlaying = true))
    }

    @Test fun `an idle audio episode does not float`() {
        assertFalse(PiP.shouldEnter(isVideo = false, isPlaying = false))
    }
}
