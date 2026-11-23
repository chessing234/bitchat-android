package com.bitchat.android.features.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PromoteSingleSegmentTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `reopen without a new segment keeps the existing replay file`() {
        val replay = folder.newFile("video_abc.mp4")
        replay.writeText("segment")

        val kept = promoteSingleSegment(replay, replay)

        assertEquals(replay.absolutePath, kept.absolutePath)
        assertTrue(kept.exists())
        assertEquals("segment", kept.readText())
    }

    @Test
    fun `a distinct segment replaces the replay path`() {
        val segment = folder.newFile("segment.mp4")
        segment.writeText("new")
        val target = folder.newFile("video_abc.mp4")
        target.writeText("old")

        val promoted = promoteSingleSegment(segment, target)

        assertEquals(target.absolutePath, promoted.absolutePath)
        assertEquals("new", promoted.readText())
        assertTrue(!segment.exists())
    }
}
