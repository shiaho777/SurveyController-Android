package com.surveycontroller.android.ui

import com.surveycontroller.android.ui.components.normalizeTo100
import com.surveycontroller.android.ui.components.redistribute
import org.junit.Assert.assertEquals
import org.junit.Test

class RatioSlidersTest {

    @Test
    fun redistribute_keeps_total_100() {
        val r = redistribute(listOf(34, 33, 33), changed = 0, newValueRaw = 60)
        assertEquals(100, r.sum())
        assertEquals(60, r[0])
        // 其余两项按原比例分配剩余 40
        assertEquals(40, r[1] + r[2])
    }

    @Test
    fun redistribute_handles_zero_others() {
        val r = redistribute(listOf(100, 0, 0), changed = 0, newValueRaw = 40)
        assertEquals(100, r.sum())
        assertEquals(40, r[0])
    }

    @Test
    fun normalize_to_100_from_uniform_weights() {
        // 4 项均为 1 → 归一化成总和 100
        val r = normalizeTo100(listOf(1, 1, 1, 1))
        assertEquals(100, r.sum())
    }

    @Test
    fun single_option_is_100() {
        assertEquals(listOf(100), redistribute(listOf(100), 0, 50))
    }

    @Test
    fun locked_item_stays_fixed() {
        // 三项 [50,30,20]，锁定第2项(30)，把第1项拖到 60：
        // 第2项保持 30，第3项 = 100-60-30 = 10
        val r = redistribute(listOf(50, 30, 20), changed = 0, newValueRaw = 60, locked = setOf(1))
        assertEquals(100, r.sum())
        assertEquals(60, r[0])
        assertEquals(30, r[1]) // 锁定不变
        assertEquals(10, r[2])
    }

    @Test
    fun changed_capped_by_locked_budget() {
        // 锁定第2项=70，拖第1项到 90 → 第1项最多只能到 30（100-70）
        val r = redistribute(listOf(20, 70, 10), changed = 0, newValueRaw = 90, locked = setOf(1))
        assertEquals(100, r.sum())
        assertEquals(30, r[0])
        assertEquals(70, r[1])
    }
}
