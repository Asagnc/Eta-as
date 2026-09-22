package io.github.asagnc.sta.agent.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaScreenContextStateReducerTest {
    private val available = StaScreenContextUiState(
        phase = StaScreenContextPhase.AVAILABLE,
        previewDataUrl = "data:image/png;base64,cHJldmlldw==",
    )

    @Test
    fun `select and remove preserve the prepared preview`() {
        val selected = StaScreenContextStateReducer.select(
            state = available,
            enabled = true,
            hasAttachment = true,
        )
        assertTrue(selected.selected)
        assertEquals(available.previewDataUrl, selected.previewDataUrl)

        val removed = StaScreenContextStateReducer.remove(selected, enabled = true)
        assertFalse(removed.selected)
        assertEquals(available.previewDataUrl, removed.previewDataUrl)
    }

    @Test
    fun `busy unavailable or missing attachment cannot be selected`() {
        assertEquals(
            available,
            StaScreenContextStateReducer.select(available, enabled = false, hasAttachment = true),
        )
        assertEquals(
            available,
            StaScreenContextStateReducer.select(available, enabled = true, hasAttachment = false),
        )
        val capturing = StaScreenContextUiState(phase = StaScreenContextPhase.CAPTURING)
        assertEquals(
            capturing,
            StaScreenContextStateReducer.select(capturing, enabled = true, hasAttachment = true),
        )
    }

    @Test
    fun `consume clears selection and preview`() {
        assertEquals(
            StaScreenContextUiState(phase = StaScreenContextPhase.CONSUMED),
            StaScreenContextStateReducer.consume(),
        )
    }
}
