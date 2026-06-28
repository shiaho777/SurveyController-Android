package com.surveycontroller.android.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurveyProviderTypeTest {
    @Test
    fun detects_supported_platform_urls_like_desktop() {
        assertEquals(
            SurveyProviderType.CREDAMO,
            SurveyProviderType.detect("https://www.credamo.com/answer.html#/s/demoano"),
        )
        assertEquals(
            SurveyProviderType.CREDAMO,
            SurveyProviderType.detect("https://www.credamo.com/s/demoano"),
        )
        assertEquals(
            SurveyProviderType.QQ,
            SurveyProviderType.detect("https://wj.qq.com/s2/123/abc"),
        )
        assertEquals(
            SurveyProviderType.WJX,
            SurveyProviderType.detect("https://www.wjx.cn/vm/demo.aspx"),
        )
    }

    @Test
    fun supports_urls_without_scheme_and_case_variants() {
        assertTrue(SurveyProviderType.isSupportedUrl("www.wjx.cn/vm/demo.aspx"))
        assertTrue(SurveyProviderType.isSupportedUrl("HTTPS://WWW.WJX.CN/vm/demo.aspx"))
        assertTrue(SurveyProviderType.isSupportedUrl("WWW.CREDAMO.COM/s/demoano"))
        assertFalse(SurveyProviderType.isSupportedUrl("https://example.com/form"))
    }

    @Test
    fun normalizes_parse_urls_and_preserves_request_parts() {
        assertEquals(
            "https://www.credamo.com/answer.html?x=1#/s/demoano",
            SurveyProviderType.normalizeParseUrl("WWW.CREDAMO.COM/s/demoano?x=1"),
        )
        assertEquals(
            "https://www.credamo.com/answer.html#/s/demoano",
            SurveyProviderType.normalizeParseUrl("HTTPS://WWW.CREDAMO.COM/answer.html#/s/demoano"),
        )
        assertEquals(
            "https://www.wjx.cn:8443/vm/demo.aspx?x=1#frag",
            SurveyProviderType.normalizeParseUrl("HTTPS://WWW.WJX.CN:8443/vm/demo.aspx?x=1#frag"),
        )
    }

    @Test
    fun does_not_rewrite_credamo_urls_that_already_have_a_fragment() {
        assertEquals(
            "https://www.credamo.com/s/demoano#keep",
            SurveyProviderType.normalizeParseUrl("https://www.credamo.com/s/demoano#keep"),
        )
    }
}
