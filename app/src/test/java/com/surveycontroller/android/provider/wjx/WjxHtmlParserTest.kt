package com.surveycontroller.android.provider.wjx

import com.surveycontroller.android.data.SurveyConfigDraft
import com.surveycontroller.android.core.model.QuestionEntryType
import com.surveycontroller.android.provider.SurveyDefinition
import com.surveycontroller.android.provider.SurveyProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WjxHtmlParserTest {

    @Test
    fun detects_paused_or_stopped_pages_during_runtime_loading() {
        val paused = "<html><body>此问卷(123)已暂停，暂时不能填写</body></html>"
        val stopped = "<html><body>此问卷处于停止状态，无法作答</body></html>"

        assertThrows(WjxPageStateException::class.java) {
            WjxHtmlParser.assertAnswerable(paused)
        }
        assertThrows(WjxPageStateException::class.java) {
            WjxHtmlParser.assertAnswerable(stopped)
        }
    }

    @Test
    fun page_state_detection_does_not_misclassify_normal_question_text() {
        val html = """
            <html>
              <head><title>状态词问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">你是否了解未开放服务的停止状态提示？</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">了解</span></div>
                        <div><span class="label">不了解</span></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, title) = WjxHtmlParser.parse(html)

        assertEquals("状态词问卷", title)
        assertEquals(1, questions.single().num)
    }

    @Test
    fun detects_not_open_page_with_open_time_like_desktop() {
        val page = "<html><body><div id='divTip'>此问卷将于 2026/06/10 09:30 开放，请到时再进入此页面进行填写</div></body></html>"

        val error = assertThrows(WjxPageStateException::class.java) {
            WjxHtmlParser.assertAnswerable(page)
        }

        assertTrue(error.message.orEmpty().contains("尚未开放"))
        assertTrue(error.message.orEmpty().contains("2026-06-10 09:30"))
    }

    @Test
    fun extracts_survey_title_from_primary_header_and_strips_wjx_suffix() {
        val html = """
            <html>
              <head><title>备用标题 - 问卷星</title></head>
              <body>
                <div id="divTitle"><h1>正式标题 - 问卷星</h1></div>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span></div>
                        <div><span class="label">B</span></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (_, title) = WjxHtmlParser.parse(html)

        assertEquals("正式标题", title)
    }

    @Test
    fun parses_question_div_without_topic_when_id_contains_question_number() {
        val html = """
            <html>
              <head><title>兼容题号问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div id="div77" type="3">
                      <div class="field-label"><div class="topichtml">请选择颜色</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">红色</span><input type="radio" name="q77" /></div>
                        <div><span class="label">蓝色</span><input type="radio" name="q77" /></div>
                      </div>
                      <table id="divRefTab77">
                        <tr><td>内部结构</td></tr>
                      </table>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val question = questions.single()

        assertEquals(77, question.num)
        assertEquals(QuestionEntryType.SINGLE, question.entryType)
        assertEquals(listOf("红色", "蓝色"), question.optionTexts)
    }

    @Test
    fun parses_choice_attached_select_metadata() {
        val html = """
            <html>
              <head><title>测试问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择城市</div></div>
                      <div class="ui-controlgroup">
                        <div>
                          <span class="label">其他城市</span>
                          <select>
                            <option value="">请选择</option>
                            <option value="1">北京</option>
                            <option value="2">上海</option>
                          </select>
                        </div>
                        <div><span class="label">不方便透露</span></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, title) = WjxHtmlParser.parse(html)
        val question = questions.single()
        val attached = question.attachedOptionSelects.single()

        assertEquals("测试问卷", title)
        assertTrue(question.hasAttachedOptionSelect)
        assertEquals(0, attached["option_index"])
        assertEquals("其他城市", attached["option_text"])
        assertEquals(listOf("北京", "上海"), attached["select_options"])
        assertEquals(listOf("1", "2"), attached["select_values"])
        assertEquals(2, attached["select_option_count"])
    }

    @Test
    fun parses_choice_attached_select_option_text_from_attributes() {
        val html = """
            <html>
              <head><title>内嵌属性问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择补充城市</div></div>
                      <div class="ui-controlgroup">
                        <div title="其他城市">
                          <select>
                            <option value="">请选择</option>
                            <option value="1">北京</option>
                            <option value="2">上海</option>
                          </select>
                        </div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val attached = questions.single().attachedOptionSelects.single()

        assertEquals("其他城市", attached["option_text"])
        assertEquals(listOf("北京", "上海"), attached["select_options"])
    }

    @Test
    fun detects_required_markers_and_skips_select_placeholder_variants() {
        val html = """
            <html>
              <head><title>必答占位问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3" required="required">
                      <div class="field-label"><div class="topichtml">请选择颜色</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">红色</span></div>
                      </div>
                    </div>
                    <div topic="2" id="div2" type="3">
                      <div class="field-label"><span class="required"></span><div class="topichtml">请选择大小</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">大</span></div>
                      </div>
                    </div>
                    <div topic="3" id="div3" type="7">
                      <div class="field-label"><div class="topichtml">请选择城市</div></div>
                      <select>
                        <option value="0">请先选择省份</option>
                        <option value="1">北京</option>
                      </select>
                    </div>
                    <div topic="4" id="div4" type="7">
                      <div class="field-label"><div class="topichtml">请选择职业</div></div>
                      <select>
                        <option value="9"></option>
                        <option value="1">学生</option>
                      </select>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val byNum = questions.associateBy { it.num }

        assertTrue(byNum[1]?.required == true)
        assertTrue(byNum[2]?.required == true)
        assertEquals(listOf("北京"), byNum[3]?.optionTexts)
        assertEquals(listOf("学生"), byNum[4]?.optionTexts)
    }

    @Test
    fun parses_custom_dropdown_options_from_input_attributes() {
        val html = """
            <html>
              <head><title>自定义下拉问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="7">
                      <div class="field-label"><div class="topichtml">请选择水果</div></div>
                      <input id="q1" custom="请选择, 苹果,香蕉, 苹果" />
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, title) = WjxHtmlParser.parse(html)
        val question = questions.single()

        assertEquals("自定义下拉问卷", title)
        assertEquals(QuestionEntryType.DROPDOWN, question.entryType)
        assertEquals(2, question.options)
        assertEquals(listOf("苹果", "香蕉"), question.optionTexts)
    }

    @Test
    fun parses_choice_attached_custom_select_metadata() {
        val html = """
            <html>
              <head><title>内嵌自定义下拉问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择城市</div></div>
                      <div class="ui-controlgroup">
                        <div>
                          <span class="label">其他城市</span>
                          <input cusom="北京|上海|北京" />
                        </div>
                        <div><span class="label">不方便透露</span></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val question = questions.single()
        val attached = question.attachedOptionSelects.single()

        assertTrue(question.hasAttachedOptionSelect)
        assertEquals(0, attached["option_index"])
        assertEquals("其他城市", attached["option_text"])
        assertEquals(listOf("北京", "上海"), attached["select_options"])
        assertEquals(listOf("北京", "上海"), attached["select_values"])
        assertEquals(2, attached["select_option_count"])
    }

    @Test
    fun parses_dropdown_shared_other_text_input_as_last_fillable_option() {
        val html = """
            <html>
              <head><title>下拉其他问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="7">
                      <div class="field-label"><div class="topichtml">请选择城市</div></div>
                      <select>
                        <option value="">请选择</option>
                        <option value="1">北京</option>
                        <option value="2">其他城市</option>
                      </select>
                      <input id="other_city" type="text" />
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val question = questions.single()
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/dropdown-other.aspx", "下拉其他问卷", questions),
        ).questions.single()

        assertEquals(QuestionEntryType.DROPDOWN, question.entryType)
        assertEquals(listOf("北京", "其他城市"), question.optionTexts)
        assertEquals(listOf(1), question.fillableOptions)
        assertEquals(listOf(1), draft.fillableOptionIndices)
    }

    @Test
    fun parses_choice_option_texts_from_element_and_child_attributes() {
        val html = """
            <html>
              <head><title>属性选项问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择图片选项</div></div>
                      <div class="ui-controlgroup">
                        <div title="主标题"><img src="https://cdn.example.com/a.png" /></div>
                        <div><span aria-label="子标题"><img src="https://cdn.example.com/b.png" /></span></div>
                        <div data-val="备用值"></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)

        assertEquals(listOf("主标题", "子标题", "备用值"), questions.single().optionTexts)
    }

    @Test
    fun parses_choice_options_from_fallback_label_nodes_and_skips_blank_items() {
        val html = """
            <html>
              <head><title>兜底选项问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择颜色</div></div>
                      <p class="label">红色</p>
                      <p class="label">蓝色</p>
                    </div>
                    <div topic="2" id="div2" type="3">
                      <div class="field-label"><div class="topichtml">请选择补充项</div></div>
                      <div class="ui-controlgroup">
                        <div></div>
                        <div><span class="label">其他</span><input type="text" /></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)

        assertEquals(listOf("红色", "蓝色"), questions.first { it.num == 1 }.optionTexts)
        assertEquals(listOf("其他"), questions.first { it.num == 2 }.optionTexts)
        assertEquals(listOf(0), questions.first { it.num == 2 }.fillableOptions)
    }

    @Test
    fun detects_reorder_questions_from_static_sort_signatures() {
        val html = """
            <html>
              <head><title>排序问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请排序偏好</div></div>
                      <span class="order-number">1</span>
                      <ul>
                        <li>苹果</li>
                        <li>香蕉</li>
                      </ul>
                    </div>
                    <div topic="2" id="div2" type="3">
                      <div class="field-label"><div class="topichtml">请拖动排序</div></div>
                      <ul class="rank-sortable">
                        <li>红色</li>
                        <li>蓝色</li>
                      </ul>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)

        assertEquals(QuestionEntryType.ORDER, questions.first { it.num == 1 }.entryType)
        assertEquals(QuestionEntryType.ORDER, questions.first { it.num == 2 }.entryType)
        assertEquals("11", questions.first { it.num == 1 }.typeCode)
        assertEquals(listOf("苹果", "香蕉"), questions.first { it.num == 1 }.optionTexts)
    }

    @Test
    fun parses_shared_other_text_input_as_last_fillable_choice() {
        val html = """
            <html>
              <head><title>其他填空问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择原因</div></div>
                      <ul>
                        <li>选项一</li>
                        <li>其他</li>
                      </ul>
                      <div class="ui-other"><input type="text" /></div>
                    </div>
                    <div topic="2" id="div2" type="4">
                      <div class="field-label"><div class="topichtml">请选择补充项</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span></div>
                        <div><span class="label">B</span></div>
                      </div>
                      <textarea id="other_reason"></textarea>
                    </div>
                    <div topic="3" id="div3" type="3">
                      <div class="field-label"><div class="topichtml">请选择手机号选项</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">普通</span></div>
                        <div><span class="label">需要填写</span><input type="tel" /></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)

        assertEquals(listOf("选项一", "其他"), questions.first { it.num == 1 }.optionTexts)
        assertEquals(listOf(1), questions.first { it.num == 1 }.fillableOptions)
        assertEquals(listOf(1), questions.first { it.num == 2 }.fillableOptions)
        assertEquals(listOf(1), questions.first { it.num == 3 }.fillableOptions)
    }

    @Test
    fun parses_question_option_and_matrix_row_media_metadata() {
        val html = """
            <html>
              <head><title>图片问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请选择图片 <img data-src="//cdn.example.com/title.png" /></div></div>
                      <div class="ui-controlgroup">
                        <div>
                          <span class="label">图片 A</span>
                          <img src="https://cdn.example.com/option-a.jpg" />
                          <input type="radio" name="q1" />
                        </div>
                        <div><span class="label">图片 B</span><input type="radio" name="q1" /></div>
                      </div>
                    </div>
                    <div topic="2" id="div2" type="6">
                      <div class="field-label"><div class="topichtml">矩阵图片题</div></div>
                      <table id="divRefTab2">
                        <tr><th></th><th>满意</th><th>不满意</th></tr>
                        <tr rowindex="1">
                          <th>服务 <img data-original="//cdn.example.com/row-1.webp" /></th>
                          <td><input type="radio" name="q2_1" /></td>
                          <td><input type="radio" name="q2_1" /></td>
                        </tr>
                      </table>
                    </div>
                    <div topic="4" id="div4" type="6">
                      <div class="field-label"><div class="topichtml">drv 矩阵图片题</div></div>
                      <table id="divRefTab4">
                        <tr id="drv4_1"><td></td><td>满意 <img src="https://cdn.example.com/header.webp" /></td><td>不满意</td></tr>
                        <tr id="drv4_2">
                          <td>产品 <img src="https://cdn.example.com/product.webp" /></td>
                          <td><input type="radio" name="q4_1_1" /></td>
                          <td><input type="radio" name="q4_1_2" /></td>
                        </tr>
                      </table>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val choice = questions.first { it.num == 1 }
        val matrix = questions.first { it.num == 2 }
        val drvMatrix = questions.first { it.num == 4 }

        assertTrue(choice.questionMedia.any { it.scope == "title" && it.sourceUrl == "https://cdn.example.com/title.png" })
        assertTrue(choice.questionMedia.any { it.scope == "option" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/option-a.jpg" && it.label == "图片 A" })
        assertTrue(matrix.questionMedia.any { it.scope == "row" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/row-1.webp" && it.label.contains("服务") })
        assertEquals(listOf("产品"), drvMatrix.rowTexts)
        assertTrue(drvMatrix.questionMedia.none { it.sourceUrl == "https://cdn.example.com/header.webp" })
        assertTrue(drvMatrix.questionMedia.any { it.scope == "row" && it.index == 0 && it.sourceUrl == "https://cdn.example.com/product.webp" && it.label.contains("产品") })
    }

    @Test
    fun parses_matrix_fallback_rows_columns_and_slider_matrix_metadata() {
        val html = """
            <html>
              <head><title>矩阵问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="3" id="div3" type="6">
                      <div class="field-label"><div class="topichtml">请评价以下项目</div></div>
                      <table id="divRefTab3">
                        <tr id="drv3_1"><td></td><td>差</td><td>好</td></tr>
                        <tr id="drv3_2"><td>外观</td><td><input name="q3_1_1" type="radio" /></td><td><input name="q3_1_2" type="radio" /></td></tr>
                        <tr id="drv3_3"><td data-title="功能"></td><td><input name="q3_2_1" type="radio" /></td><td><input name="q3_2_2" type="radio" /></td></tr>
                      </table>
                    </div>
                    <div topic="5" id="div5" type="6">
                      <div class="field-label"><div class="topichtml">备用矩阵</div></div>
                      <span class="itemTitleSpan">行一</span>
                      <span class="itemTitleSpan">行二</span>
                      <input name="q5_1_1" type="radio" />
                      <input name="q5_1_2" type="radio" />
                      <input name="q5_2_1" type="radio" />
                      <input name="q5_2_2" type="radio" />
                    </div>
                    <div topic="8" id="div8" type="6">
                      <div class="field-label"><div class="topichtml">滑块矩阵</div></div>
                      <table>
                        <tr class="rowtitletr"><td class="title"><span class="itemTitleSpan">体验</span></td></tr>
                        <tr class="rowtitletr"><td class="title"><span class="itemTitleSpan">价格</span></td></tr>
                      </table>
                      <div class="ruler"><span class="cm" data-value="1"></span><span class="cm" data-value="5"></span></div>
                      <input class="ui-slider-input" rowid="1" min="1" max="5" step="2" />
                      <input class="ui-slider-input" rowid="2" min="1" max="5" step="2" />
                    </div>
                    <div topic="9" id="div9" type="9">
                      <div class="field-label"><div class="topichtml">移动滑块矩阵</div></div>
                      <div class="slider-row">
                        <span class="itemTitleSpan">速度</span>
                        <div class="rangeslider"></div>
                        <input class="ui-slider-input" rowid="1" min="0" max="10" step="5" />
                      </div>
                      <div class="slider-row">
                        <span class="itemTitleSpan">稳定性</span>
                        <div class="rangeslider"></div>
                        <input class="ui-slider-input" rowid="2" min="0" max="10" step="5" />
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val drvMatrix = questions.first { it.num == 3 }
        val inferredMatrix = questions.first { it.num == 5 }
        val sliderMatrix = questions.first { it.num == 8 }
        val mobileSliderMatrix = questions.first { it.num == 9 }

        assertEquals(2, drvMatrix.rows)
        assertEquals(listOf("外观", "功能"), drvMatrix.rowTexts)
        assertEquals(listOf("差", "好"), drvMatrix.optionTexts)
        assertEquals(2, inferredMatrix.rows)
        assertEquals(listOf("行一", "行二"), inferredMatrix.rowTexts)
        assertEquals(listOf("1", "2"), inferredMatrix.optionTexts)
        assertTrue(sliderMatrix.isSliderMatrix)
        assertEquals(2, sliderMatrix.rows)
        assertEquals(listOf("体验", "价格"), sliderMatrix.rowTexts)
        assertEquals(listOf("1", "5"), sliderMatrix.optionTexts)
        assertEquals(1.0, sliderMatrix.sliderMin!!, 0.0001)
        assertEquals(5.0, sliderMatrix.sliderMax!!, 0.0001)
        assertEquals(2.0, sliderMatrix.sliderStep!!, 0.0001)
        assertTrue(mobileSliderMatrix.isSliderMatrix)
        assertEquals(2, mobileSliderMatrix.rows)
        assertEquals(listOf("速度", "稳定性"), mobileSliderMatrix.rowTexts)
        assertEquals(listOf("0", "5", "10"), mobileSliderMatrix.optionTexts)
        assertEquals(QuestionEntryType.MATRIX, mobileSliderMatrix.entryType)
        assertEquals(0.0, mobileSliderMatrix.sliderMin!!, 0.0001)
        assertEquals(10.0, mobileSliderMatrix.sliderMax!!, 0.0001)
        assertEquals(5.0, mobileSliderMatrix.sliderStep!!, 0.0001)
    }

    @Test
    fun detects_location_questions_from_mobile_location_marker() {
        val html = """
            <html>
              <head><title>定位问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="1">
                      <div class="field-label"><div class="topichtml">请选择所在地区</div></div>
                      <div class="get_Local"></div>
                      <input type="text" id="q1" name="q1" />
                    </div>
                    <div topic="2" id="div2" type="1">
                      <div class="field-label"><div class="topichtml">请选择省市区</div></div>
                      <input type="text" verify="省市区" onclick="openCityBox(this,3,event,1);" readonly="readonly" />
                    </div>
                    <div topic="3" id="div3" type="1">
                      <div class="field-label"><div class="topichtml">请描述你的定位体验</div></div>
                      <textarea name="q3"></textarea>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val localMarker = questions.first { it.num == 1 }
        val cityBox = questions.first { it.num == 2 }
        val plainText = questions.first { it.num == 3 }

        assertTrue(localMarker.isLocation)
        assertEquals(QuestionEntryType.LOCATION, localMarker.entryType)
        assertEquals(1, localMarker.textInputs)
        assertTrue(cityBox.isLocation)
        assertEquals(QuestionEntryType.LOCATION, cityBox.entryType)
        assertEquals(0, cityBox.textInputs)
        assertTrue(!plainText.isLocation)
        assertEquals(QuestionEntryType.TEXT, plainText.entryType)
        assertEquals(1, plainText.textInputs)
    }

    @Test
    fun distinguishes_numeric_scale_from_star_rating_and_extracts_rating_options() {
        val html = """
            <html>
              <head><title>量表问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="5">
                      <div class="field-label"><div class="topichtml">推荐意愿</div></div>
                      <div class="scaleTitle_frist">不可能</div>
                      <div class="scaleTitle_last">非常可能</div>
                      <ul tp="d">
                        <li><a class="rate-off" dval="1"></a></li>
                        <li><a class="rate-off" dval="2"></a></li>
                        <li><a class="rate-off" dval="3"></a></li>
                        <li><a class="rate-off" dval="4"></a></li>
                        <li><a class="rate-off" dval="5"></a></li>
                      </ul>
                    </div>
                    <div topic="2" id="div2" type="5">
                      <div class="field-label"><div class="topichtml">服务评价</div></div>
                      <div class="evaluateTagWrap"></div>
                      <ul class="modlen5">
                        <li><a class="rate-off"></a></li>
                        <li><a class="rate-off"></a></li>
                        <li><a class="rate-off"></a></li>
                        <li><a class="rate-off"></a></li>
                        <li><a class="rate-off"></a></li>
                      </ul>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val scale = questions.first { it.num == 1 }
        val rating = questions.first { it.num == 2 }

        assertEquals(QuestionEntryType.SCALE, scale.entryType)
        assertEquals(false, scale.isRating)
        assertEquals(0, scale.ratingMax)
        assertEquals(listOf("1", "2", "3", "4", "5"), scale.optionTexts)
        assertEquals(QuestionEntryType.SCORE, rating.entryType)
        assertTrue(rating.isRating)
        assertEquals(5, rating.ratingMax)
        assertEquals(listOf("1", "2", "3", "4", "5"), rating.optionTexts)
    }

    @Test
    fun detects_forced_choice_from_text_label_and_index_prompts() {
        val html = """
            <html>
              <head><title>检测题问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">本题检测，请选择 非常满意。</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">非常不满意</span></div>
                        <div><span class="label">非常满意</span></div>
                      </div>
                    </div>
                    <div topic="2" id="div2" type="3">
                      <div class="field-label"><div class="topichtml">请务必选A项</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">(A) 苹果</span></div>
                        <div><span class="label">(B) 香蕉</span></div>
                      </div>
                    </div>
                    <div topic="3" id="div3" type="7">
                      <div class="field-label"><div class="topichtml">请直接选第2项</div></div>
                      <select>
                        <option value="">请选择</option>
                        <option value="1">甲</option>
                        <option value="2">乙</option>
                        <option value="3">丙</option>
                      </select>
                    </div>
                    <div topic="4" id="div4" type="3">
                      <div class="field-label"><div class="topichtml">请选择你的年龄段</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">18-25岁</span></div>
                        <div><span class="label">26-35岁</span></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val byNum = questions.associateBy { it.num }

        assertEquals(1, byNum[1]?.forcedOptionIndex)
        assertEquals("非常满意", byNum[1]?.forcedOptionText)
        assertEquals(0, byNum[2]?.forcedOptionIndex)
        assertEquals("(A) 苹果", byNum[2]?.forcedOptionText)
        assertEquals(1, byNum[3]?.forcedOptionIndex)
        assertEquals("乙", byNum[3]?.forcedOptionText)
        assertEquals(null, byNum[4]?.forcedOptionIndex)
        assertEquals(null, byNum[4]?.forcedOptionText)
    }

    @Test
    fun maps_dropdown_jump_rules_after_skipping_placeholder_option() {
        val html = """
            <html>
              <head><title>下拉跳题问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="7" hasjump="1">
                      <div class="field-label"><div class="topichtml">请选择城市</div></div>
                      <select>
                        <option value="">请选择</option>
                        <option value="1" jumpto="3">北京</option>
                        <option value="2">上海</option>
                      </select>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val question = questions.single()
        val rule = question.jumpRules.single()

        assertEquals(listOf("北京", "上海"), question.optionTexts)
        assertTrue(question.hasJump)
        assertEquals(0, rule.optionIndex)
        assertEquals(3, rule.targetQuestion)
        assertEquals("北京", rule.optionText)
    }

    @Test
    fun parses_multiple_choice_limits_from_attributes_json_and_hint_text() {
        val html = """
            <html>
              <head><title>多选限制问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="4" minselect="2" maxselect="4">
                      <div class="field-label"><div class="topichtml">属性限制题</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span></div>
                        <div><span class="label">B</span></div>
                        <div><span class="label">C</span></div>
                        <div><span class="label">D</span></div>
                      </div>
                    </div>
                    <div topic="2" id="div2" type="4" data-validate='{"rules":{"minCount":1,"maxCount":3}}'>
                      <div class="field-label"><div class="topichtml">JSON 限制题</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span></div>
                        <div><span class="label">B</span></div>
                        <div><span class="label">C</span></div>
                      </div>
                    </div>
                    <div topic="3" id="div3" type="4">
                      <div class="field-label"><div class="topichtml">文本限制题</div></div>
                      <div class="question-hint">请选择 2-4 项</div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span></div>
                        <div><span class="label">B</span></div>
                        <div><span class="label">C</span></div>
                        <div><span class="label">D</span></div>
                      </div>
                    </div>
                    <div topic="4" id="div4" type="4">
                      <div class="field-label"><div class="topichtml">精确限制题</div></div>
                      <div class="qtypetip">请勾选2项</div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span></div>
                        <div><span class="label">B</span></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val byNum = questions.associateBy { it.num }

        assertEquals(2, byNum[1]?.multiMinLimit)
        assertEquals(4, byNum[1]?.multiMaxLimit)
        assertEquals(1, byNum[2]?.multiMinLimit)
        assertEquals(3, byNum[2]?.multiMaxLimit)
        assertEquals(2, byNum[3]?.multiMinLimit)
        assertEquals(4, byNum[3]?.multiMaxLimit)
        assertEquals(2, byNum[4]?.multiMinLimit)
        assertEquals(2, byNum[4]?.multiMaxLimit)
    }

    @Test
    fun marks_plain_choice_shell_as_description_and_excludes_it_from_config_draft() {
        val html = """
            <html>
              <head><title>说明问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="3">
                      <div class="field-label"><div class="topichtml">请阅读下面材料后继续</div></div>
                      <p>这里是一段说明文字，没有可交互选项。</p>
                    </div>
                    <div topic="2" id="div2" type="3">
                      <div class="field-label"><div class="topichtml">正式题</div></div>
                      <div class="ui-controlgroup">
                        <div><span class="label">A</span><input type="radio" name="q2" /></div>
                        <div><span class="label">B</span><input type="radio" name="q2" /></div>
                      </div>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/demo.aspx", "说明问卷", questions),
        )

        assertTrue(questions.first { it.num == 1 }.isDescription)
        assertEquals(listOf(2), draft.questions.map { it.num })
    }

    @Test
    fun preserves_internal_question_num_but_uses_visible_display_number() {
        val html = """
            <html>
              <head><title>题号问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="23" id="div23" type="2">
                      <div class="field-label">
                        <span class="req">*</span>
                        <div class="topicnumber">1.</div>
                        <div class="topichtml">请您对培训和实习进行简要评价：<span>（最少30字）</span></div>
                      </div>
                      <textarea id="q23" minword="30" name="q23"></textarea>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val question = questions.single()

        assertEquals(23, question.num)
        assertEquals(1, question.displayNum)
        assertEquals("请您对培训和实习进行简要评价： （最少30字）", question.title)
    }

    @Test
    fun recalculates_display_numbers_after_hidden_questions_and_description_placeholders() {
        val html = """
            <html>
              <head><title>隐藏题问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <section style="display:none;">
                      <div topic="20" id="div20" type="5">
                        <div class="field-label">
                          <div class="topicnumber">20.</div>
                          <div class="topichtml">隐藏题</div>
                        </div>
                      </div>
                    </section>
                    <div topic="21" id="div21" type="4">
                      <div class="field-label">
                        <div class="topicnumber">21.</div>
                        <div class="topichtml">显示题一</div>
                      </div>
                      <div class="ui-controlgroup"><div><span class="label">A</span></div></div>
                    </div>
                    <div topic="22" id="div22" type="3">
                      <div class="field-label">
                        <div class="topicnumber">22.</div>
                        <div class="topichtml">显示题二</div>
                      </div>
                      <div class="ui-controlgroup"><div><span class="label">A</span></div></div>
                    </div>
                    <div topic="8" id="div8" style="display:none;" relation="-1" type="1">
                      <div class="field-label">
                        <div class="topicnumber">8.</div>
                        <div class="topichtml">（二）接触渠道与接触频率</div>
                      </div>
                      <div class="ui-input-text">
                        <input type="text" id="q8" name="q8" />
                      </div>
                    </div>
                    <div topic="23" id="div23" type="2">
                      <div class="field-label">
                        <div class="topicnumber">23.</div>
                        <div class="topichtml">显示题三</div>
                      </div>
                      <textarea id="q23" name="q23"></textarea>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val byNum = questions.associateBy { it.num }
        val draft = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/hidden.aspx", "隐藏题问卷", questions),
        )

        assertEquals(20, byNum[20]?.displayNum)
        assertEquals(1, byNum[21]?.displayNum)
        assertEquals(2, byNum[22]?.displayNum)
        assertTrue(byNum[8]?.isDescription == true)
        assertEquals(3, byNum[23]?.displayNum)
        assertEquals(listOf(20, 21, 22, 23), draft.questions.map { it.num })
        assertTrue(8 !in draft.questions.map { it.num })
    }

    @Test
    fun parses_multi_text_input_labels_and_infers_blank_modes() {
        val html = """
            <html>
              <head><title>多填空问卷</title></head>
              <body>
                <div id="divQuestion">
                  <fieldset>
                    <div topic="1" id="div1" type="1" gapfill="1">
                      <div class="field-label"><div class="topichtml">请填写你的资料</div></div>
                      <input type="text" placeholder="姓名" />
                      手机号：<input type="tel" />
                      <input type="number" data-label="年龄" />
                    </div>
                    <div topic="2" id="div2" type="9" gapfill="1">
                      <div class="field-label"><div class="topichtml">请填写指定编号</div></div>
                      <input type="text" placeholder="编号" />
                    </div>
                    <div topic="3" id="div3" type="9" gapfill="1">
                      <div class="field-label"><div class="topichtml">请填写移动模板资料</div></div>
                      项目评价<input id="q3_1" style="display: none" type="text" />
                      <label class="textEdit"><span class="textCont" contenteditable="true"></span></label>
                      <div>请输入手机号<input id="q3_2" style="visibility: hidden" type="text" />
                      <label class="textEdit"><span class="textCont" contenteditable="true"></span></label></div>
                    </div>
                    <div topic="4" id="div4" type="9" gapfill="1">
                      <div class="field-label"><div class="topichtml">请填写表格式资料</div></div>
                      <table>
                        <tr><th>字段</th><th>填写区</th></tr>
                        <tr><td>姓名</td><td><input id="q4_1" type="text" placeholder="姓名" /></td></tr>
                        <tr><td>年龄</td><td><input id="q4_2" type="number" placeholder="年龄" /></td></tr>
                      </table>
                    </div>
                  </fieldset>
                </div>
              </body>
            </html>
        """.trimIndent()

        val (questions, _) = WjxHtmlParser.parse(html)
        val question = questions.first { it.num == 1 }
        val singleInputGapfill = questions.first { it.num == 2 }
        val mobileGapfill = questions.first { it.num == 3 }
        val tableGapfill = questions.first { it.num == 4 }
        val drafts = SurveyConfigDraft.fromDefinition(
            SurveyDefinition(SurveyProviderType.WJX, "https://www.wjx.cn/vm/multi-text.aspx", "多填空问卷", questions),
        ).questions.associateBy { it.num }

        assertEquals(3, question.textInputs)
        assertEquals(listOf("姓名", "手机号", "年龄"), question.textInputLabels)
        assertTrue(question.isMultiText)
        assertEquals(QuestionEntryType.MULTI_TEXT, drafts[1]?.entryType)
        assertEquals(listOf("name", "mobile", "integer"), drafts[1]?.multiTextBlankModes)
        assertTrue(singleInputGapfill.isMultiText)
        assertEquals(QuestionEntryType.MULTI_TEXT, drafts[2]?.entryType)
        assertEquals(listOf("custom"), drafts[2]?.multiTextBlankModes)
        assertEquals(2, mobileGapfill.textInputs)
        assertEquals(listOf("项目评价", "请输入手机号"), mobileGapfill.textInputLabels)
        assertEquals(QuestionEntryType.MULTI_TEXT, drafts[3]?.entryType)
        assertEquals(listOf("custom", "mobile"), drafts[3]?.multiTextBlankModes)
        assertTrue(tableGapfill.isMultiText)
        assertEquals(2, tableGapfill.textInputs)
        assertEquals(1, tableGapfill.rows)
        assertEquals(0, tableGapfill.options)
        assertEquals(emptyList<String>(), tableGapfill.rowTexts)
        assertEquals(emptyList<String>(), tableGapfill.optionTexts)
        assertEquals(QuestionEntryType.MULTI_TEXT, drafts[4]?.entryType)
    }
}
