package com.surveycontroller.android.ui

import com.surveycontroller.android.ui.screens.updateAttachedSelectWeights
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachedSelectEditorTest {

    @Test
    fun update_attached_select_weights_changes_only_target_group() {
        val configs = listOf(
            mapOf("option_index" to 0, "weights" to listOf(10, 90)),
            mapOf("option_index" to 1, "weights" to listOf(50, 50)),
        )

        val updated = updateAttachedSelectWeights(configs, configIndex = 1, weights = listOf(20, 80))

        assertEquals(listOf(10, 90), updated[0]["weights"])
        assertEquals(listOf(20.0, 80.0), updated[1]["weights"])
        assertEquals(1, updated[1]["option_index"])
    }
}
