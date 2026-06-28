package com.surveycontroller.android.core

import com.surveycontroller.android.core.reverse_fill.XlsxReader
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    @Test
    fun reads_first_workbook_sheet_even_when_target_is_not_sheet1() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                mapOf(
                    "xl/worksheets/sheet1.xml" to sheetXml("Wrong"),
                    "xl/worksheets/sheet2.xml" to sheetXml("Right"),
                ),
                workbookXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets>
                        <sheet name="Export" sheetId="7" r:id="rId7"/>
                        <sheet name="Other" sheetId="1" r:id="rId1"/>
                      </sheets>
                    </workbook>
                """.trimIndent(),
                relsXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                      <Relationship Id="rId7" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
                    </Relationships>
                """.trimIndent(),
            ),
        )

        assertEquals("Right", rows.first()[0])
    }

    @Test
    fun falls_back_to_sheet1_when_workbook_relationships_are_missing() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                mapOf("xl/worksheets/sheet1.xml" to sheetXml("Fallback")),
                workbookXml = null,
                relsXml = null,
            ),
        )

        assertEquals("Fallback", rows.first()[0])
    }

    @Test
    fun normalizes_relative_workbook_relationship_targets() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                mapOf("xl/worksheets/sheet2.xml" to sheetXml("Relative")),
                workbookXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                      <sheets><sheet name="Export" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>
                """.trimIndent(),
                relsXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="../worksheets/sheet2.xml"/>
                    </Relationships>
                """.trimIndent(),
            ),
        )

        assertEquals("Relative", rows.first()[0])
    }

    @Test
    fun normalizes_numeric_and_boolean_cell_values_like_desktop_reverse_fill() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1"><v>2.0</v></c>
                              <c r="B1"><v>3.2500</v></c>
                              <c r="C1" t="b"><v>1</v></c>
                              <c r="D1" t="b"><v>0</v></c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = null,
                relsXml = null,
            ),
        )

        assertEquals("2", rows.first()[0])
        assertEquals("3.25", rows.first()[1])
        assertEquals("1", rows.first()[2])
        assertEquals("0", rows.first()[3])
    }

    @Test
    fun reads_rich_shared_and_inline_strings_without_xml_noise_or_phonetics() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                worksheets = mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1" t="s"><v>0</v></c>
                              <c r="B1" t="inlineStr">
                                <is>
                                  <r><t>内</t></r>
                                  <r><t>联</t></r>
                                  <rPh sb="0" eb="1"><t>inline-phonetic</t></rPh>
                                </is>
                              </c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = null,
                relsXml = null,
                sharedStringsXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <si>
                        <r><t>富</t></r>
                        <r><t>文本</t></r>
                        <rPh sb="0" eb="1"><t>shared-phonetic</t></rPh>
                      </si>
                    </sst>
                """.trimIndent(),
            ),
        )

        assertEquals("富文本", rows.first()[0])
        assertEquals("内联", rows.first()[1])
    }

    @Test
    fun formats_excel_date_cells_using_cell_styles() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                worksheets = mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1" s="1"><v>45292</v></c>
                              <c r="B1" s="2"><v>45292.5</v></c>
                              <c r="C1"><v>45292</v></c>
                              <c r="D1" s="3"><v>0.5</v></c>
                              <c r="E1"><v>0.5</v></c>
                              <c r="F1" s="4"><v>0.75</v></c>
                              <c r="G1" s="5"><v>0.75</v></c>
                              <c r="H1" s="6"><v>1.25</v></c>
                              <c r="I1" s="7"><v>12.5</v></c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = null,
                relsXml = null,
                stylesXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <numFmts count="3">
                        <numFmt numFmtId="165" formatCode="yyyy-mm-dd h:mm:ss"/>
                        <numFmt numFmtId="166" formatCode="[h]:mm:ss"/>
                        <numFmt numFmtId="167" formatCode="[Red]0.00"/>
                      </numFmts>
                      <cellXfs count="8">
                        <xf numFmtId="0"/>
                        <xf numFmtId="14"/>
                        <xf numFmtId="165"/>
                        <xf numFmtId="45"/>
                        <xf numFmtId="20"/>
                        <xf numFmtId="21"/>
                        <xf numFmtId="166"/>
                        <xf numFmtId="167"/>
                      </cellXfs>
                    </styleSheet>
                """.trimIndent(),
            ),
        )

        assertEquals("2024-01-01", rows.first()[0])
        assertEquals("2024-01-01 12:00:00", rows.first()[1])
        assertEquals("45292", rows.first()[2])
        assertEquals("12:00:00", rows.first()[3])
        assertEquals("0.5", rows.first()[4])
        assertEquals("18:00:00", rows.first()[5])
        assertEquals("18:00:00", rows.first()[6])
        assertEquals("30:00:00", rows.first()[7])
        assertEquals("12.5", rows.first()[8])
    }

    @Test
    fun respects_workbook_1904_date_system() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                worksheets = mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1" s="1"><v>1</v></c>
                              <c r="B1" s="2"><v>1.5</v></c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <workbookPr date1904="1"/>
                    </workbook>
                """.trimIndent(),
                relsXml = null,
                stylesXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                      <numFmts count="1">
                        <numFmt numFmtId="165" formatCode="yyyy-mm-dd h:mm:ss"/>
                      </numFmts>
                      <cellXfs count="3">
                        <xf numFmtId="0"/>
                        <xf numFmtId="14"/>
                        <xf numFmtId="165"/>
                      </cellXfs>
                    </styleSheet>
                """.trimIndent(),
            ),
        )

        assertEquals("1904-01-02", rows.first()[0])
        assertEquals("1904-01-02 12:00:00", rows.first()[1])
    }

    @Test
    fun normalizes_iso_date_typed_cells() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                worksheets = mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1" t="d"><v>2024-01-02</v></c>
                              <c r="B1" t="d"><v>2024-01-02T03:04:05</v></c>
                              <c r="C1" t="d"><v>2024-01-02T03:04:05.750</v></c>
                              <c r="D1" t="d"><v>18:30:15</v></c>
                              <c r="E1" t="d"><v>2024-01-02T03:04:05+08:00</v></c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = null,
                relsXml = null,
            ),
        )

        assertEquals("2024-01-02", rows.first()[0])
        assertEquals("2024-01-02 03:04:05", rows.first()[1])
        assertEquals("2024-01-02 03:04:05", rows.first()[2])
        assertEquals("18:30:15", rows.first()[3])
        assertEquals("2024-01-02 03:04:05", rows.first()[4])
    }

    @Test
    fun preserves_missing_blank_rows_by_worksheet_row_number() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                worksheets = mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c r="A1" t="inlineStr"><is><t>题1</t></is></c>
                            </row>
                            <row r="3">
                              <c r="A3" t="inlineStr"><is><t>答案</t></is></c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = null,
                relsXml = null,
            ),
        )

        assertEquals(3, rows.size)
        assertEquals("题1", rows[0][0])
        assertEquals(emptyMap<Int, String>(), rows[1])
        assertEquals("答案", rows[2][0])
    }

    @Test
    fun infers_cell_columns_when_cell_references_are_omitted() {
        val rows = XlsxReader.read(
            minimalWorkbook(
                worksheets = mapOf(
                    "xl/worksheets/sheet1.xml" to """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                          <sheetData>
                            <row r="1">
                              <c t="inlineStr"><is><t>A</t></is></c>
                              <c t="inlineStr"><is><t>B</t></is></c>
                              <c t="inlineStr"><is><t>C</t></is></c>
                            </row>
                            <row r="2">
                              <c r="C2" t="inlineStr"><is><t>C2</t></is></c>
                              <c t="inlineStr"><is><t>D2</t></is></c>
                            </row>
                          </sheetData>
                        </worksheet>
                    """.trimIndent(),
                ),
                workbookXml = null,
                relsXml = null,
            ),
        )

        assertEquals("A", rows[0][0])
        assertEquals("B", rows[0][1])
        assertEquals("C", rows[0][2])
        assertEquals("C2", rows[1][2])
        assertEquals("D2", rows[1][3])
    }

    private fun minimalWorkbook(
        worksheets: Map<String, String>,
        workbookXml: String?,
        relsXml: String?,
        stylesXml: String? = null,
        sharedStringsXml: String? = null,
    ): ByteArrayInputStream {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            workbookXml?.let { zip.writeEntry("xl/workbook.xml", it) }
            relsXml?.let { zip.writeEntry("xl/_rels/workbook.xml.rels", it) }
            stylesXml?.let { zip.writeEntry("xl/styles.xml", it) }
            sharedStringsXml?.let { zip.writeEntry("xl/sharedStrings.xml", it) }
            worksheets.forEach { (name, xml) -> zip.writeEntry(name, xml) }
        }
        return ByteArrayInputStream(output.toByteArray())
    }

    private fun ZipOutputStream.writeEntry(name: String, contents: String) {
        putNextEntry(ZipEntry(name))
        write(contents.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(text: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
          <sheetData>
            <row r="1">
              <c r="A1" t="inlineStr"><is><t>$text</t></is></c>
            </row>
          </sheetData>
        </worksheet>
    """.trimIndent()
}
