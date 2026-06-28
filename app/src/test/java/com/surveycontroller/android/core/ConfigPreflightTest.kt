package com.surveycontroller.android.core

import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.core.model.SurveyQuestionMeta
import com.surveycontroller.android.core.questions.ReverseFillAnswer
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_FORMAT_WJX_TEXT
import com.surveycontroller.android.core.reverse_fill.REVERSE_FILL_STATUS_FALLBACK
import com.surveycontroller.android.core.reverse_fill.ReverseFillPreviewRow
import com.surveycontroller.android.core.reverse_fill.ReverseFillQuestionPlan
import com.surveycontroller.android.core.reverse_fill.ReverseFillSample
import com.surveycontroller.android.core.reverse_fill.ReverseFillSpec
import com.surveycontroller.android.data.ConfigPreflight
import com.surveycontroller.android.data.RunParamsDraft
import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigPreflightTest {

    @Test
    fun blocks_unsupported_question_metadata_before_start() {
        val draft = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "上传截图",
                typeCode = "99",
                unsupported = true,
                unsupportedReason = "上传题暂未支持",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("暂不支持"))
        assertTrue(result.blockingMessage().contains("上传题暂未支持"))
    }

    @Test
    fun blocks_all_zero_single_weights() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(distributionMode = "custom", optionWeights = mutableListOf(0.0, 0.0)) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("配比无效"))
    }

    @Test
    fun blocks_single_weight_count_that_no_longer_matches_question_options() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 3, optionTexts = listOf("A", "B", "C")),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(distributionMode = "custom", optionWeights = mutableListOf(80.0, 20.0)) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("配比数量不一致"))
        assertTrue(result.blockingMessage().contains("3 个选项"))
    }

    @Test
    fun blocks_multiple_when_positive_options_less_than_minimum_limit() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "至少选两项",
                typeCode = "4",
                options = 3,
                optionTexts = listOf("A", "B", "C"),
                multiMinLimit = 2,
            ),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(multiProbabilities = mutableListOf(100.0, 0.0, 0.0)) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("最少选择 2 项"))
    }

    @Test
    fun blocks_multiple_probability_count_that_no_longer_matches_question_options() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "多选", typeCode = "4", options = 3, optionTexts = listOf("A", "B", "C")),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(multiRandomCount = false, multiProbabilities = mutableListOf(50.0, 50.0)) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("多选概率数量不一致"))
        assertTrue(result.blockingMessage().contains("3 个选项"))
    }

    @Test
    fun allows_random_count_multiple_even_when_stale_probability_array_is_ignored() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "多选", typeCode = "4", options = 3, optionTexts = listOf("A", "B", "C")),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(multiRandomCount = true, multiProbabilities = mutableListOf(0.0)) },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.blockingMessage().isBlank())
    }

    @Test
    fun blocks_matrix_row_count_that_would_shift_runtime_config_indexes() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "矩阵",
                typeCode = "6",
                rows = 2,
                options = 3,
                rowTexts = listOf("R1", "R2"),
                optionTexts = listOf("A", "B", "C"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(matrixRowWeights = mutableListOf(mutableListOf(1.0, 1.0, 1.0)))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("矩阵行数不一致"))
        assertTrue(result.blockingMessage().contains("2 行"))
    }

    @Test
    fun blocks_matrix_column_count_when_custom_row_weights_do_not_match_options() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "矩阵",
                typeCode = "6",
                rows = 2,
                options = 3,
                rowTexts = listOf("R1", "R2"),
                optionTexts = listOf("A", "B", "C"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(
                    distributionMode = "custom",
                    matrixRowWeights = mutableListOf(
                        mutableListOf(1.0, 1.0, 1.0),
                        mutableListOf(1.0, 1.0),
                    ),
                )
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("矩阵列数不一致"))
        assertTrue(result.blockingMessage().contains("3 个列选项"))
    }

    @Test
    fun blocks_text_weight_count_that_would_make_runtime_fall_back_to_uniform() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "填空", typeCode = "1", isTextLike = true, textInputs = 1),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textCandidates = mutableListOf("甲", "乙"), optionWeights = mutableListOf(100.0))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("填空权重数量不一致"))
        assertTrue(result.blockingMessage().contains("2 条"))
    }

    @Test
    fun blocks_custom_slider_target_outside_runtime_bounds_before_clamping() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "滑块", typeCode = "8", sliderMin = 10.0, sliderMax = 20.0),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(distributionMode = "custom", sliderTarget = 99.0) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("滑块目标值无效"))
        assertTrue(result.blockingMessage().contains("10.0-20.0"))
    }

    @Test
    fun allows_custom_slider_target_inside_runtime_bounds() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "滑块", typeCode = "8", sliderMin = 10.0, sliderMax = 20.0),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(distributionMode = "custom", sliderTarget = 12.5) },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.blockingMessage().isBlank())
    }

    @Test
    fun allows_random_slider_even_when_stale_target_is_outside_bounds() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "滑块", typeCode = "8", sliderMin = 10.0, sliderMax = 20.0),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(distributionMode = "random", sliderTarget = 99.0) },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.blockingMessage().isBlank())
    }

    @Test
    fun blocks_empty_custom_text_answers_before_runtime_default_fill() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "填空", typeCode = "1", isTextLike = true, textInputs = 1),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textCandidates = mutableListOf("", "   "), optionWeights = mutableListOf(1.0, 1.0))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("填空答案为空"))
        assertTrue(result.blockingMessage().contains("默认文本"))
    }

    @Test
    fun allows_random_text_mode_without_custom_candidates() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "姓名", typeCode = "1", isTextLike = true, textInputs = 1),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textMode = "name", textCandidates = mutableListOf(), optionWeights = mutableListOf())
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.blockingMessage().isBlank())
    }

    @Test
    fun blocks_reversed_random_integer_text_range_before_runtime_swap() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "年龄", typeCode = "1", isTextLike = true, textInputs = 1),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textMode = "integer", textIntMin = 60, textIntMax = 18)
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("随机整数范围无效"))
        assertTrue(result.blockingMessage().contains("自动调换范围"))
    }

    @Test
    fun blocks_empty_custom_multi_text_answer_groups_before_runtime_default_fill() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "多项填空",
                typeCode = "1",
                isTextLike = true,
                isMultiText = true,
                textInputs = 2,
                textInputLabels = listOf("优点", "缺点"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textCandidates = mutableListOf(""), optionWeights = mutableListOf(1.0))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("多项填空答案为空"))
    }

    @Test
    fun blocks_missing_custom_multi_text_blank_before_runtime_padding() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "多项填空",
                typeCode = "1",
                isTextLike = true,
                isMultiText = true,
                textInputs = 2,
                textInputLabels = listOf("优点", "缺点"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(textCandidates = mutableListOf("整体不错||"), optionWeights = mutableListOf(1.0))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("多项填空答案不完整"))
        assertTrue(result.blockingMessage().contains("缺点"))
    }

    @Test
    fun blocks_reversed_multi_text_integer_blank_range_before_runtime_swap() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "多项填空",
                typeCode = "1",
                isTextLike = true,
                isMultiText = true,
                textInputs = 2,
                textInputLabels = listOf("姓名", "年龄"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(
                    textCandidates = mutableListOf("张三||"),
                    optionWeights = mutableListOf(1.0),
                    multiTextBlankModes = mutableListOf("custom", "integer"),
                    multiTextBlankIntRanges = mutableListOf(mutableListOf(0, 100), mutableListOf(60, 18)),
                )
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("多项填空随机整数范围无效"))
        assertTrue(result.blockingMessage().contains("年龄"))
    }

    @Test
    fun allows_random_multi_text_blank_without_candidate_value_for_that_blank() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "多项填空",
                typeCode = "1",
                isTextLike = true,
                isMultiText = true,
                textInputs = 2,
                textInputLabels = listOf("姓名", "手机号"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(
                    textCandidates = mutableListOf("张三||"),
                    optionWeights = mutableListOf(1.0),
                    multiTextBlankModes = mutableListOf("custom", "mobile"),
                )
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.blockingMessage().isBlank())
    }

    @Test
    fun ignores_stale_integer_range_for_non_integer_multi_text_blank() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "多项填空",
                typeCode = "1",
                isTextLike = true,
                isMultiText = true,
                textInputs = 2,
                textInputLabels = listOf("姓名", "手机号"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(
                    textCandidates = mutableListOf("张三||"),
                    optionWeights = mutableListOf(1.0),
                    multiTextBlankModes = mutableListOf("custom", "mobile"),
                    multiTextBlankIntRanges = mutableListOf(mutableListOf(0, 100), mutableListOf(60, 18)),
                )
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.blockingMessage().isBlank())
    }

    @Test
    fun warns_when_tencent_option_fill_text_uses_verified_blank_payload_path() {
        val base = draftOfProvider(
            SurveyProviderType.QQ,
            SurveyQuestionMeta(
                num = 1,
                title = "其他说明",
                typeCode = "3",
                provider = "qq",
                providerType = "radio",
                options = 2,
                optionTexts = listOf("A", "其他"),
                fillableOptions = listOf(1),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(optionFillTexts = mutableListOf(null, "补充说明"))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("选项填空") && it.detail.contains("blank_setting") })
    }

    @Test
    fun does_not_warn_for_wjx_option_fill_text_because_submit_codec_supports_it() {
        val base = draftOfProvider(
            SurveyProviderType.WJX,
            SurveyQuestionMeta(
                num = 1,
                title = "其他说明",
                typeCode = "3",
                provider = "wjx",
                options = 2,
                optionTexts = listOf("A", "其他"),
                fillableOptions = listOf(1),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map {
                it.copy(optionFillTexts = mutableListOf(null, "补充说明"))
            },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.warnings.none { it.title.contains("选项填空") })
    }

    @Test
    fun blocks_short_text_candidates_when_question_requires_min_length() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "请写不少于 5 个字",
                typeCode = "1",
                isTextLike = true,
                textInputs = 1,
            ),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(textCandidates = mutableListOf("短")) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("最少 5 字"))
    }

    @Test
    fun blocks_short_multi_text_blank_when_question_requires_min_length() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "请每项填写不少于 4 个字",
                typeCode = "1",
                isTextLike = true,
                isMultiText = true,
                textInputs = 2,
                textInputLabels = listOf("优点", "缺点"),
            ),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(textCandidates = mutableListOf("整体不错||短")) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("最少 4 字"))
    }

    @Test
    fun blocks_custom_ai_missing_runtime_fields_when_ai_text_is_used() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "请填写",
                typeCode = "1",
                isTextLike = true,
                textInputs = 1,
            ),
        )
        val draft = base.copy(questions = base.questions.map { it.copy(useAiText = true) })

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(customAiEnabled = true, customAiBaseUrl = "https://api.example.com/v1"),
        )

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("API Key"))
        assertTrue(result.blockingMessage().contains("模型名称"))
    }

    @Test
    fun blocks_legacy_custom_ai_completions_endpoint_before_start() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "请填写",
                typeCode = "1",
                isTextLike = true,
                textInputs = 1,
            ),
        )
        val draft = base.copy(questions = base.questions.map { it.copy(useAiText = true) })

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(
                customAiEnabled = true,
                customAiBaseUrl = "https://api.example.com/v1/completions",
                customAiApiKey = "sk-test",
                customAiModel = "model-x",
            ),
        )

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("/completions"))
    }

    @Test
    fun warns_when_custom_ai_protocol_is_overridden_by_explicit_endpoint() {
        val base = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "请填写",
                typeCode = "1",
                isTextLike = true,
                textInputs = 1,
            ),
        )
        val draft = base.copy(questions = base.questions.map { it.copy(useAiText = true) })

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(
                customAiEnabled = true,
                customAiBaseUrl = "https://api.example.com/v1/chat/completions",
                customAiApiKey = "sk-test",
                customAiModel = "model-x",
                customAiApiProtocol = "responses",
            ),
        )

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("协议选择") && it.detail.contains("Chat") })
    }

    @Test
    fun default_draft_uses_forced_texts_and_skips_description_questions() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "说明", typeCode = "0", isDescription = true),
            SurveyQuestionMeta(
                num = 2,
                title = "请填写苹果",
                typeCode = "1",
                isTextLike = true,
                textInputs = 1,
                forcedTexts = listOf("苹果"),
            ),
        )

        assertTrue(draft.questions.none { it.num == 1 })
        assertTrue(draft.questions.single { it.num == 2 }.textCandidates == listOf("苹果"))
        assertTrue(ConfigPreflight.validate(draft).canStart)
    }

    @Test
    fun default_draft_locks_forced_single_choice_as_custom_one_hot_weights() {
        val draft = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "请选 B",
                typeCode = "3",
                options = 3,
                optionTexts = listOf("A", "B", "C"),
                forcedOptionIndex = 1,
            ),
        )
        val question = draft.questions.single()

        assertTrue(question.distributionMode == "custom")
        assertTrue(question.optionWeights == listOf(0.0, 100.0, 0.0))
        assertTrue(ConfigPreflight.validate(draft).canStart)
    }

    @Test
    fun warns_when_desktop_reverse_fill_file_was_imported_but_not_loaded_on_android() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            preserved = base.preserved.copy(
                reverseFillEnabled = true,
                reverseFillSourcePath = "C:/desktop/reverse.xlsx",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("重新选择") })
    }

    @Test
    fun blocks_credamo_answer_datetime_window_when_incomplete() {
        val base = draftOfProvider(
            SurveyProviderType.CREDAMO,
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            params = base.params.copy(
                answerDatetimeStart = "2026-06-01 10:00",
                answerDatetimeEnd = "",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("未配完整"))
    }

    @Test
    fun blocks_credamo_answer_datetime_window_when_format_is_invalid() {
        val base = draftOfProvider(
            SurveyProviderType.CREDAMO,
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            params = base.params.copy(
                answerDatetimeStart = "2026-06-01T10:00",
                answerDatetimeEnd = "2026-06-01 11:00",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("格式无效"))
    }

    @Test
    fun blocks_credamo_answer_datetime_window_when_end_is_not_after_start() {
        val base = draftOfProvider(
            SurveyProviderType.CREDAMO,
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            params = base.params.copy(
                answerDatetimeStart = "2026-06-01 11:00",
                answerDatetimeEnd = "2026-06-01 10:00",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("必须晚于"))
    }

    @Test
    fun blocks_credamo_answer_datetime_window_when_too_narrow_for_max_duration() {
        val base = draftOfProvider(
            SurveyProviderType.CREDAMO,
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            params = base.params.copy(
                answerDurationMax = 180,
                answerDatetimeStart = "2026-06-01 10:00",
                answerDatetimeEnd = "2026-06-01 10:02",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("太窄"))
    }

    @Test
    fun warns_non_credamo_answer_datetime_window_without_blocking_start() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            params = base.params.copy(
                answerDatetimeStart = "2026-06-01 10:00",
                answerDatetimeEnd = "2026-06-01 11:00",
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("不使用作答时间窗") })
    }

    @Test
    fun blocks_invalid_count_thread_and_fail_threshold_before_compiler_clamps_them() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )
        val draft = base.copy(
            params = base.params.copy(
                targetNum = 0,
                numThreads = 99,
                failThreshold = 0,
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("目标份数无效"))
        assertTrue(result.blockingMessage().contains("并发线程数超出范围"))
        assertTrue(result.blockingMessage().contains("连续失败阈值无效"))
    }

    @Test
    fun blocks_invalid_answer_duration_range_before_runtime_truncation() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )

        val reversed = ConfigPreflight.validate(
            base.copy(params = base.params.copy(answerDurationMin = 120, answerDurationMax = 60)),
        )
        val tooLarge = ConfigPreflight.validate(
            base.copy(params = base.params.copy(answerDurationMin = 60, answerDurationMax = 30 * 60 + 1)),
        )

        assertFalse(reversed.canStart)
        assertTrue(reversed.blockingMessage().contains("作答时长区间无效"))
        assertFalse(tooLarge.canStart)
        assertTrue(tooLarge.blockingMessage().contains("作答时长超出上限"))
    }

    @Test
    fun blocks_invalid_submit_interval_range_before_runtime_truncation() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        )

        val negative = ConfigPreflight.validate(
            base.copy(params = base.params.copy(submitIntervalMin = -1, submitIntervalMax = 10)),
        )
        val reversed = ConfigPreflight.validate(
            base.copy(params = base.params.copy(submitIntervalMin = 10, submitIntervalMax = 5)),
        )
        val tooLarge = ConfigPreflight.validate(
            base.copy(params = base.params.copy(submitIntervalMin = 0, submitIntervalMax = 301)),
        )

        assertFalse(negative.canStart)
        assertTrue(negative.blockingMessage().contains("提交间隔不能为负"))
        assertFalse(reversed.canStart)
        assertTrue(reversed.blockingMessage().contains("提交间隔区间无效"))
        assertFalse(tooLarge.canStart)
        assertTrue(tooLarge.blockingMessage().contains("提交间隔超出上限"))
    }

    @Test
    fun warns_empty_location_parts_use_default_without_blocking_start() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "地区", typeCode = "1", isTextLike = true, isLocation = true),
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("地区未填写") && it.detail.contains("默认地区") })
    }

    @Test
    fun blocks_partially_configured_location_parts_before_runtime_fallback() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "地区", typeCode = "1", isTextLike = true, isLocation = true),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(locationParts = mutableListOf("上海", "上海", "")) },
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("地区未填完整"))
        assertTrue(result.blockingMessage().contains("省 / 市 / 区县"))
    }

    @Test
    fun allows_complete_location_parts_without_default_warning() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "地区", typeCode = "1", isTextLike = true, isLocation = true),
        )
        val draft = base.copy(
            questions = base.questions.map { it.copy(locationParts = mutableListOf("上海", "上海", "浦东新区")) },
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertTrue(result.warnings.none { it.title.contains("地区未") })
    }

    @Test
    fun allows_valid_single_attached_option_select_instead_of_blocking_question() {
        val draft = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "选择",
                typeCode = "3",
                options = 2,
                optionTexts = listOf("A", "B"),
                attachedOptionSelects = listOf(
                    mapOf(
                        "option_index" to 1,
                        "option_text" to "B",
                        "select_options" to listOf("x", "y"),
                        "weights" to listOf(20, 80),
                    ),
                ),
                hasAttachedOptionSelect = true,
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertFalse(result.blockingMessage().contains("嵌入式下拉"))
    }

    @Test
    fun blocks_attached_option_select_when_all_child_weights_are_zero() {
        val draft = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "选择",
                typeCode = "3",
                options = 2,
                optionTexts = listOf("A", "B"),
                attachedOptionSelects = listOf(
                    mapOf(
                        "option_index" to 1,
                        "option_text" to "B",
                        "select_options" to listOf("x", "y"),
                        "weights" to listOf(0, 0),
                    ),
                ),
                hasAttachedOptionSelect = true,
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("嵌入式下拉配比无效"))
    }

    @Test
    fun blocks_attached_option_select_when_shape_no_longer_matches_child_options() {
        val draft = draftOf(
            SurveyQuestionMeta(
                num = 1,
                title = "选择",
                typeCode = "3",
                options = 2,
                optionTexts = listOf("A", "B"),
                attachedOptionSelects = listOf(
                    mapOf(
                        "option_index" to 1,
                        "option_text" to "B",
                        "select_options" to listOf("x", "y", "z"),
                        "weights" to listOf(20, 80),
                    ),
                ),
                hasAttachedOptionSelect = true,
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("嵌入式下拉配比数量不一致"))
    }

    @Test
    fun blocks_answer_rule_when_target_option_or_row_is_out_of_range() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "入口", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
            SurveyQuestionMeta(
                num = 2,
                title = "矩阵",
                typeCode = "6",
                rows = 1,
                options = 2,
                rowTexts = listOf("R1"),
                optionTexts = listOf("同意", "不同意"),
            ),
        )
        val draft = base.copy(
            answerRules = listOf(
                mapOf(
                    "condition_question_num" to 1,
                    "condition_mode" to "selected",
                    "condition_option_indices" to listOf(0),
                    "target_question_num" to 2,
                    "target_row_index" to 3,
                    "action_mode" to "must_select",
                    "target_option_indices" to listOf(2),
                ),
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("条件规则行号越界"))
        assertTrue(result.blockingMessage().contains("条件规则选项越界"))
    }

    @Test
    fun allows_valid_answer_rule_preflight() {
        val base = draftOf(
            SurveyQuestionMeta(num = 1, title = "入口", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
            SurveyQuestionMeta(num = 2, title = "多选", typeCode = "4", options = 3, optionTexts = listOf("A", "B", "C")),
        )
        val draft = base.copy(
            answerRules = listOf(
                mapOf(
                    "condition_question_num" to 1,
                    "condition_mode" to "selected",
                    "condition_option_indices" to listOf(0),
                    "target_question_num" to 2,
                    "action_mode" to "must_not_select",
                    "target_option_indices" to listOf(1),
                ),
            ),
        )

        val result = ConfigPreflight.validate(draft)

        assertTrue(result.canStart)
        assertFalse(result.blockingMessage().contains("条件规则"))
    }

    @Test
    fun blocks_reverse_fill_when_enabled_without_parsed_spec() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        ).copy(params = RunParamsDraft(targetNum = 1))

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1),
        )

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("未完成解析"))
    }

    @Test
    fun blocks_reverse_fill_when_selected_rows_are_less_than_target() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        ).copy(params = RunParamsDraft(targetNum = 2))
        val spec = ReverseFillSpec(
            format = REVERSE_FILL_FORMAT_WJX_TEXT,
            totalSamples = 1,
            samples = listOf(
                ReverseFillSample(
                    rowNumber = 1,
                    answers = mapOf(1 to ReverseFillAnswer(kind = "choice", choiceIndex = 0)),
                ),
            ),
        )

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1, reverseFillSpec = spec),
        )

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("样本少于目标份数"))
    }

    @Test
    fun blocks_reverse_fill_for_non_wjx_provider_even_when_samples_are_loaded() {
        val draft = draftOfProvider(
            SurveyProviderType.QQ,
            SurveyQuestionMeta(
                num = 1,
                title = "选择",
                typeCode = "3",
                provider = "qq",
                providerType = "radio",
                options = 2,
                optionTexts = listOf("A", "B"),
            ),
        ).copy(params = RunParamsDraft(targetNum = 1))
        val spec = ReverseFillSpec(
            format = REVERSE_FILL_FORMAT_WJX_TEXT,
            totalSamples = 1,
            samples = listOf(
                ReverseFillSample(
                    rowNumber = 1,
                    answers = mapOf(1 to ReverseFillAnswer(kind = "choice", choiceIndex = 0)),
                ),
            ),
        )

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1, reverseFillSpec = spec),
        )

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("反向填充暂仅支持问卷星"))
        assertTrue(result.blockingMessage().contains("腾讯问卷"))
    }

    @Test
    fun blocks_reverse_fill_spec_without_any_selected_rows() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        ).copy(params = RunParamsDraft(targetNum = 1))
        val spec = ReverseFillSpec(
            format = REVERSE_FILL_FORMAT_WJX_TEXT,
            totalSamples = 0,
            samples = emptyList(),
            questionPlans = listOf(
                ReverseFillQuestionPlan(
                    questionNum = 1,
                    title = "选择",
                    entryType = QuestionEntryType.SINGLE,
                    columnIndexes = listOf(0),
                    columnHeaders = listOf("选择"),
                    matchedByHeader = true,
                ),
            ),
            previewRows = listOf(ReverseFillPreviewRow(rowNumber = 1, answeredQuestions = 0, totalQuestions = 1)),
        )

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1, reverseFillSpec = spec),
        )

        assertFalse(result.canStart)
        assertTrue(result.blockingMessage().contains("没有可用样本"))
    }

    @Test
    fun warns_reverse_fill_spec_without_replayable_answers_but_keeps_selected_rows_available() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        ).copy(params = RunParamsDraft(targetNum = 1))
        val spec = ReverseFillSpec(
            format = REVERSE_FILL_FORMAT_WJX_TEXT,
            totalSamples = 1,
            samples = listOf(ReverseFillSample(rowNumber = 1, answers = emptyMap())),
            questionPlans = listOf(
                ReverseFillQuestionPlan(
                    questionNum = 1,
                    title = "选择",
                    entryType = QuestionEntryType.SINGLE,
                    columnIndexes = listOf(0),
                    columnHeaders = listOf("选择"),
                    matchedByHeader = true,
                ),
            ),
            previewRows = listOf(ReverseFillPreviewRow(rowNumber = 1, answeredQuestions = 0, totalQuestions = 1)),
        )

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1, reverseFillSpec = spec),
        )

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("未识别到可回放答案") })
        assertTrue(result.warnings.any { it.title.contains("空答案行") && it.detail.contains("常规配置兜底") })
    }

    @Test
    fun warns_when_reverse_fill_preview_has_partial_coverage_and_sequence_fallback() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "性别", typeCode = "3", options = 2, optionTexts = listOf("男", "女")),
            SurveyQuestionMeta(num = 2, title = "姓名", typeCode = "1", isTextLike = true, textInputs = 1),
        ).copy(params = RunParamsDraft(targetNum = 1))
        val spec = ReverseFillSpec(
            format = REVERSE_FILL_FORMAT_WJX_TEXT,
            totalSamples = 1,
            samples = listOf(
                ReverseFillSample(
                    rowNumber = 1,
                    answers = mapOf(1 to ReverseFillAnswer(kind = "choice", choiceIndex = 0)),
                ),
            ),
            questionPlans = listOf(
                ReverseFillQuestionPlan(
                    questionNum = 1,
                    title = "性别",
                    entryType = QuestionEntryType.SINGLE,
                    columnIndexes = listOf(0),
                    columnHeaders = listOf("第一列"),
                    matchedByHeader = false,
                ),
            ),
            previewRows = listOf(ReverseFillPreviewRow(rowNumber = 1, answeredQuestions = 1, totalQuestions = 2)),
        )

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1, reverseFillSpec = spec),
        )

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("漏题") })
        assertTrue(result.warnings.any { it.title.contains("未识别完整") })
        assertTrue(result.warnings.any { it.title.contains("顺序兜底") })
    }

    @Test
    fun warns_when_reverse_fill_question_was_degraded_to_regular_config() {
        val draft = draftOf(
            SurveyQuestionMeta(num = 1, title = "选择", typeCode = "3", options = 2, optionTexts = listOf("A", "B")),
        ).copy(params = RunParamsDraft(targetNum = 1))
        val spec = ReverseFillSpec(
            format = REVERSE_FILL_FORMAT_WJX_TEXT,
            totalSamples = 1,
            samples = listOf(
                ReverseFillSample(
                    rowNumber = 1,
                    answers = mapOf(1 to ReverseFillAnswer(kind = "choice", choiceIndex = 0)),
                ),
            ),
            questionPlans = listOf(
                ReverseFillQuestionPlan(
                    questionNum = 1,
                    title = "选择",
                    entryType = QuestionEntryType.SINGLE,
                    columnIndexes = listOf(0),
                    columnHeaders = listOf("选择"),
                    matchedByHeader = true,
                    status = REVERSE_FILL_STATUS_FALLBACK,
                    detail = "检测到复合值",
                    sampleRows = listOf(2),
                ),
            ),
            previewRows = listOf(ReverseFillPreviewRow(rowNumber = 1, answeredQuestions = 1, totalQuestions = 1)),
        )

        val result = ConfigPreflight.validate(
            draft,
            ConfigPreflight.Options(reverseFillEnabled = true, reverseFillSampleCount = 1, reverseFillSpec = spec),
        )

        assertTrue(result.canStart)
        assertTrue(result.warnings.any { it.title.contains("降级") && it.detail.contains("检测到复合值") })
    }

    private fun draftOf(vararg questions: SurveyQuestionMeta): SurveyConfigDraft =
        draftOfProvider(SurveyProviderType.WJX, *questions)

    private fun draftOfProvider(provider: SurveyProviderType, vararg questions: SurveyQuestionMeta): SurveyConfigDraft =
        SurveyConfigDraft.fromDefinition(
            SurveyDefinition(
                provider,
                when (provider) {
                    SurveyProviderType.CREDAMO -> "https://www.credamo.com/answer.html#test"
                    SurveyProviderType.QQ -> "https://wj.qq.com/s2/123/abc/"
                    SurveyProviderType.WJX -> "https://www.wjx.cn/vm/test.aspx"
                },
                "测试问卷",
                questions.toList(),
            ),
        )
}
