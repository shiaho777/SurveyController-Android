package com.surveycontroller.android.core.reverse_fill

import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

/**
 * 轻量 XLSX 读取器：直接解析 OOXML（zip + XML），避免引入重型 POI 依赖。
 * 仅读取第一个工作表，返回逐行单元格字符串（按列索引对齐）。
 */
object XlsxReader {

    private enum class DateStyleKind {
        DateTime,
        Duration,
    }

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val dateTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val timeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val builtInDateStyleIds = setOf(14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 30, 36, 45, 46, 47, 50, 57)

    /** 返回每行的 (列索引 -> 单元格文本)。行号与列号均为 0-based。 */
    fun read(input: InputStream): List<Map<Int, String>> {
        var sharedStrings: List<String> = emptyList()
        var workbookXml: ByteArray? = null
        var workbookRelsXml: ByteArray? = null
        var stylesXml: ByteArray? = null
        val worksheetXmlByPath = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                val bytes = zip.readBytes()
                when {
                    name == "xl/sharedStrings.xml" -> sharedStrings = parseSharedStrings(bytes)
                    name == "xl/workbook.xml" -> workbookXml = bytes
                    name == "xl/_rels/workbook.xml.rels" -> workbookRelsXml = bytes
                    name == "xl/styles.xml" -> stylesXml = bytes
                    name.startsWith("xl/worksheets/") && name.endsWith(".xml") -> worksheetXmlByPath[name] = bytes
                }
                entry = zip.nextEntry
            }
        }
        val use1904DateSystem = workbookXml?.let { workbookUses1904Dates(it) } ?: false
        val sheetXml = firstWorksheetPath(workbookXml, workbookRelsXml)
            ?.let { worksheetXmlByPath[it] }
            ?: worksheetXmlByPath["xl/worksheets/sheet1.xml"]
            ?: worksheetXmlByPath.entries.minByOrNull { it.key }?.value
        val xml = sheetXml ?: return emptyList()
        return parseSheet(xml, sharedStrings, stylesXml?.let { parseDateStyleKinds(it) } ?: emptyMap(), use1904DateSystem)
    }

    private fun parseXml(bytes: ByteArray, handler: DefaultHandler) {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.newSAXParser().parse(bytes.inputStream(), handler)
    }

    private fun parseSharedStrings(bytes: ByteArray): List<String> {
        val result = mutableListOf<String>()
        parseXml(bytes, object : DefaultHandler() {
            var inSi = false
            var inText = false
            var phoneticDepth = 0
            val sb = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tagName(localName, qName)) {
                    "si" -> {
                        inSi = true
                        inText = false
                        phoneticDepth = 0
                        sb.setLength(0)
                    }
                    "rPh" -> if (inSi) phoneticDepth++
                    "t" -> if (inSi && phoneticDepth == 0) inText = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inText && phoneticDepth == 0) sb.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tagName(localName, qName)) {
                    "t" -> inText = false
                    "rPh" -> if (phoneticDepth > 0) phoneticDepth--
                    "si" -> {
                        result.add(sb.toString())
                        inSi = false
                        inText = false
                        phoneticDepth = 0
                    }
                }
            }
        })
        return result
    }

    private fun firstWorksheetPath(workbookXml: ByteArray?, relsXml: ByteArray?): String? {
        if (workbookXml == null || relsXml == null) return null
        val firstRelId = firstSheetRelationshipId(workbookXml) ?: return null
        val target = workbookRelationshipTargets(relsXml)[firstRelId]?.trim().orEmpty()
        if (target.isEmpty()) return null
        val normalized = target.replace('\\', '/')
        return when {
            normalized.startsWith("/") -> normalized.trimStart('/')
            normalized.startsWith("xl/") -> normalized
            else -> normalizeZipPath("xl/$normalized")
        }
    }

    private fun normalizeZipPath(path: String): String {
        val parts = ArrayDeque<String>()
        path.split('/').forEach { part ->
            when {
                part.isEmpty() || part == "." -> Unit
                part == ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun firstSheetRelationshipId(bytes: ByteArray): String? {
        var result: String? = null
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (result != null || tagName(localName, qName) != "sheet") return
                result = attributes?.getValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")
                    ?: attributes?.getValue("r:id")
                    ?: attributes?.getValue("id")
            }
        })
        return result
    }

    private fun workbookRelationshipTargets(bytes: ByteArray): Map<String, String> {
        val result = HashMap<String, String>()
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (tagName(localName, qName) != "Relationship") return
                val id = attributes?.getValue("Id") ?: ""
                val target = attributes?.getValue("Target") ?: ""
                if (id.isNotBlank() && target.isNotBlank()) result[id] = target
            }
        })
        return result
    }

    private fun workbookUses1904Dates(bytes: ByteArray): Boolean {
        var result = false
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                if (tagName(localName, qName) != "workbookPr") return
                val value = attributes?.getValue("date1904")?.trim()?.lowercase().orEmpty()
                result = value == "1" || value == "true"
            }
        })
        return result
    }

    private fun parseDateStyleKinds(bytes: ByteArray): Map<Int, DateStyleKind> {
        val customFormats = HashMap<Int, String>()
        val dateStyleKinds = HashMap<Int, DateStyleKind>()
        var inCellXfs = false
        var xfIndex = 0
        parseXml(bytes, object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tagName(localName, qName)) {
                    "numFmt" -> {
                        val id = attributes?.getValue("numFmtId")?.toIntOrNull() ?: return
                        val code = attributes.getValue("formatCode") ?: return
                        customFormats[id] = code
                    }
                    "cellXfs" -> {
                        inCellXfs = true
                        xfIndex = 0
                    }
                    "xf" -> if (inCellXfs) {
                        val numFmtId = attributes?.getValue("numFmtId")?.toIntOrNull() ?: 0
                        val customFormat = customFormats[numFmtId]
                        val kind = dateStyleKind(numFmtId, customFormat)
                        if (kind != null) {
                            dateStyleKinds[xfIndex] = kind
                        }
                        xfIndex++
                    }
                }
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                if (tagName(localName, qName) == "cellXfs") inCellXfs = false
            }
        })
        return dateStyleKinds
    }

    private fun dateStyleKind(numFmtId: Int, format: String?): DateStyleKind? {
        if (numFmtId in builtInDateStyleIds) return DateStyleKind.DateTime
        return customDateStyleKind(format)
    }

    private fun customDateStyleKind(format: String?): DateStyleKind? {
        val withoutLiterals = format
            ?.replace(Regex("\"[^\"]*\""), "")
            ?.lowercase()
            ?: return null
        if (Regex("\\[(?:h|hh|m|mm|s|ss)]").containsMatchIn(withoutLiterals)) return DateStyleKind.Duration
        val cleaned = withoutLiterals.replace(Regex("\\[[^]]*]"), "")
        if (cleaned.isBlank()) return null
        return if (
            cleaned.any { it in listOf('y', 'd') } ||
            (cleaned.contains('m') && cleaned.contains('h')) ||
            cleaned.contains("am/pm")
        ) {
            DateStyleKind.DateTime
        } else {
            null
        }
    }

    private fun parseSheet(
        bytes: ByteArray,
        shared: List<String>,
        dateStyleKinds: Map<Int, DateStyleKind>,
        use1904DateSystem: Boolean,
    ): List<Map<Int, String>> {
        val rows = mutableListOf<Map<Int, String>>()
        parseXml(bytes, object : DefaultHandler() {
            var currentRow: MutableMap<Int, String>? = null
            var cellType = ""
            var cellStyle = -1
            var cellColumnIndex = -1
            var inValue = false
            var inInlineStr = false
            var nextWorksheetRow = 1
            var nextColumnIndex = 0
            var inlinePhoneticDepth = 0
            val valueSb = StringBuilder()

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (tagName(localName, qName)) {
                    "row" -> {
                        val worksheetRow = attributes?.getValue("r")?.toIntOrNull()
                        if (worksheetRow != null) {
                            while (nextWorksheetRow < worksheetRow) {
                                rows.add(emptyMap())
                                nextWorksheetRow++
                            }
                            nextWorksheetRow = worksheetRow + 1
                        } else {
                            nextWorksheetRow++
                        }
                        currentRow = HashMap()
                        nextColumnIndex = 0
                    }
                    "c" -> {
                        cellType = attributes?.getValue("t") ?: ""
                        cellStyle = attributes?.getValue("s")?.toIntOrNull() ?: -1
                        cellColumnIndex = columnIndex(attributes?.getValue("r") ?: "")
                            .takeIf { it >= 0 }
                            ?: nextColumnIndex
                        nextColumnIndex = cellColumnIndex + 1
                        inlinePhoneticDepth = 0
                        valueSb.setLength(0)
                    }
                    "v" -> inValue = true
                    "rPh" -> if (cellType == "inlineStr") inlinePhoneticDepth++
                    "t" -> if (cellType == "inlineStr" && inlinePhoneticDepth == 0) inInlineStr = true
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (inValue || inInlineStr) valueSb.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (tagName(localName, qName)) {
                    "v" -> inValue = false
                    "t" -> inInlineStr = false
                    "rPh" -> if (inlinePhoneticDepth > 0) inlinePhoneticDepth--
                    "c" -> {
                        val raw = valueSb.toString()
                        val text = cellText(raw, cellType, dateStyleKinds[cellStyle], shared, use1904DateSystem)
                        if (cellColumnIndex >= 0 && text.isNotEmpty()) currentRow?.put(cellColumnIndex, text)
                    }
                    "row" -> { currentRow?.let { rows.add(it) }; currentRow = null }
                }
            }
        })
        return rows
    }

    private fun cellText(
        raw: String,
        cellType: String,
        dateStyleKind: DateStyleKind?,
        shared: List<String>,
        use1904DateSystem: Boolean,
    ): String {
        val value = raw.trim()
        return when (cellType) {
            "s" -> shared.getOrElse(value.toIntOrNull() ?: -1) { "" }.trim()
            "b" -> when (value.lowercase()) {
                "1", "true" -> "1"
                "0", "false" -> "0"
                else -> value
            }
            "d" -> isoDateText(value) ?: value
            else -> when (dateStyleKind) {
                DateStyleKind.DateTime -> excelDateText(value, use1904DateSystem) ?: normalizeNumberText(value)
                DateStyleKind.Duration -> excelDurationText(value) ?: normalizeNumberText(value)
                null -> normalizeNumberText(value)
            }
        }
    }

    private fun isoDateText(value: String): String? {
        val text = value.trim()
        if (text.isEmpty()) return ""
        val normalized = if (' ' in text && 'T' !in text && 't' !in text) text.replace(' ', 'T') else text
        parseOffsetDateTime(normalized)?.let { return it.format(dateTimeFormatter) }
        parseLocalDateTime(normalized)?.let { return it.format(dateTimeFormatter) }
        parseLocalDate(normalized)?.let { return it.format(dateFormatter) }
        parseLocalTime(normalized)?.let { return it.format(timeFormatter) }
        return null
    }

    private fun parseOffsetDateTime(value: String): LocalDateTime? =
        try {
            OffsetDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME).toLocalDateTime()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun parseLocalDateTime(value: String): LocalDateTime? =
        try {
            LocalDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun parseLocalDate(value: String): LocalDate? =
        try {
            LocalDate.parse(value, DateTimeFormatter.ISO_DATE)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun parseLocalTime(value: String): LocalTime? =
        try {
            LocalTime.parse(value, DateTimeFormatter.ISO_TIME)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun excelDateText(value: String, use1904DateSystem: Boolean): String? {
        val serial = value.toDoubleOrNull() ?: return null
        if (serial.isNaN() || serial.isInfinite() || serial < 0.0) return null
        val wholeDays = kotlin.math.floor(serial).toLong()
        val seconds = kotlin.math.round((serial - wholeDays) * 86_400.0).toLong()
        if (wholeDays <= 0L) {
            return LocalTime.MIDNIGHT.plusSeconds(seconds).format(timeFormatter)
        }
        val date = if (use1904DateSystem) {
            LocalDate.of(1904, 1, 1).plusDays(wholeDays)
        } else {
            LocalDate.of(1899, 12, 31).plusDays(wholeDays - if (wholeDays >= 60) 1 else 0)
        }
        if (seconds <= 0L) return date.format(dateFormatter)
        val dateTime = LocalDateTime.of(date, LocalTime.MIDNIGHT).plusSeconds(seconds)
        return dateTime.format(dateTimeFormatter)
    }

    private fun excelDurationText(value: String): String? {
        val serial = value.toDoubleOrNull() ?: return null
        if (serial.isNaN() || serial.isInfinite() || serial < 0.0) return null
        val totalSeconds = kotlin.math.round(serial * 86_400.0).toLong()
        val hours = totalSeconds / 3_600
        val minutes = (totalSeconds % 3_600) / 60
        val seconds = totalSeconds % 60
        return "%02d:%02d:%02d".format(java.util.Locale.US, hours, minutes, seconds)
    }

    private fun normalizeNumberText(value: String): String {
        if (value.isEmpty()) return ""
        val decimal = value.toDoubleOrNull() ?: return value
        if (decimal.isNaN() || decimal.isInfinite()) return value
        val longValue = decimal.toLong()
        if (decimal == longValue.toDouble()) return longValue.toString()
        return "%.12f".format(java.util.Locale.US, decimal).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    private fun tagName(localName: String?, qName: String?): String =
        localName?.takeIf { it.isNotEmpty() } ?: qName?.substringAfterLast(':').orEmpty()

    /** 从单元格引用（如 "B12"）提取 0-based 列索引。 */
    private fun columnIndex(ref: String): Int {
        var col = 0
        var seen = false
        for (ch in ref) {
            if (ch in 'A'..'Z') { col = col * 26 + (ch - 'A' + 1); seen = true }
            else if (ch in 'a'..'z') { col = col * 26 + (ch - 'a' + 1); seen = true }
            else break
        }
        return if (seen) col - 1 else -1
    }
}
