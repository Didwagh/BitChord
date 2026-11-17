package com.music.bitchord.playback

import androidx.media3.common.Format
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StatsFormatTest {
    private val sourceMediaId = "src:a054489d1a::B079VDQRPX"

    private fun format(mime: String) = Format.Builder()
        .setSampleMimeType(mime)
        .setSampleRate(44_100)
        .setChannelCount(2)
        .build()

    @Test
    fun `remembered decoder format is used when live getter is null`() {
        val remembered = format("audio/mp4a-latm")

        val result = statsFormat(
            live = null,
            remembered = remembered,
            rememberedMediaId = sourceMediaId,
            mediaId = sourceMediaId,
        )

        assertSame(remembered, result)
    }

    @Test
    fun `remembered format from previous track is never used`() {
        val result = statsFormat(
            live = null,
            remembered = format("audio/flac"),
            rememberedMediaId = "src:a054489d1a::PREVIOUS",
            mediaId = sourceMediaId,
        )

        assertNull(result)
    }

    @Test
    fun `live format is used before decoder listener reports one`() {
        val live = format("audio/opus")

        val result = statsFormat(
            live = live,
            remembered = null,
            rememberedMediaId = null,
            mediaId = sourceMediaId,
        )

        assertSame(live, result)
    }

    @Test
    fun `listener format for current track wins over stale live getter`() {
        val live = format("audio/opus")
        val remembered = format("audio/mp4a-latm")

        val result = statsFormat(
            live = live,
            remembered = remembered,
            rememberedMediaId = sourceMediaId,
            mediaId = sourceMediaId,
        )

        assertSame(remembered, result)
    }
}
