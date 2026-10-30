package com.notflix.tv

import com.notflix.tv.playback.Vtt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VttTest {
    private val sample = listOf(
        "WEBVTT",
        "",
        "1",
        "00:00:01.000 --> 00:00:03.500 line:90%",
        "<i>Hello</i> &amp; welcome",
        "",
        "00:01:02.250 --> 00:01:04.000",
        "Two",
        "lines",
        "",
        "01:00:00.000 --> 01:00:01.000",
        "An hour in",
    ).joinToString("\n")

    @Test fun parsesTimingsTextAndEntities() {
        val cues = Vtt.parse(sample)
        assertEquals(3, cues.size)
        assertEquals(1.0, cues[0].start, 1e-9)
        assertEquals(3.5, cues[0].end, 1e-9)
        assertEquals("Hello & welcome", cues[0].text)
        assertEquals(62.25, cues[1].start, 1e-9)
        assertEquals("Two\nlines", cues[1].text)
        assertEquals(3600.0, cues[2].start, 1e-9)
    }

    @Test fun activeCueUsesSourceTimeline() {
        val cues = Vtt.parse(sample)
        assertEquals("Hello & welcome", Vtt.active(cues, 2.0, 0.0))
        assertNull(Vtt.active(cues, 4.0, 0.0))
        // A file whose timeline starts at 1.4 s (MPEG-TS): position 0.2 is source time 1.6.
        assertEquals("Hello & welcome", Vtt.active(cues, 0.2, 1.4))
        assertNull(Vtt.active(cues, 0.2, 0.0))
    }

    @Test fun ignoresHeaderAndBrokenBlocks() {
        assertEquals(0, Vtt.parse("WEBVTT\n\nNOTE nothing here\n\nnot --> a time\ntext").size)
    }
}
