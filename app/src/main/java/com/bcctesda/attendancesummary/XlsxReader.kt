package com.bcctesda.attendancesummary

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import com.bcctesda.attendancesummary.data.*

/** Reads every worksheet of an .xlsx file into rows of text cells. */
fun readXlsxSheets(bytes: ByteArray): List<List<List<String>>> {
    var sharedXml: ByteArray? = null
    val sheetXml = sortedMapOf<Int, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var e = zip.nextEntry
        while (e != null) {
            val n = e.name
            if (n == "xl/sharedStrings.xml") sharedXml = zip.readBytes()
            else if (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"))
                sheetXml[n.filter(Char::isDigit).toIntOrNull() ?: 0] = zip.readBytes()
            e = zip.nextEntry
        }
    }
    val shared = mutableListOf<String>()
    sharedXml?.let { parseSharedStrings(it, shared) }
    return sheetXml.values.map { parseSheet(it, shared) }
}

private fun parseSharedStrings(bytes: ByteArray, out: MutableList<String>) {
    val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    var sb: StringBuilder? = null
    var inT = false; var inPhonetic = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name) {
                "si" -> sb = StringBuilder()
                "rPh" -> inPhonetic = true
                "t" -> inT = !inPhonetic
            }
            XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "t" -> inT = false
                "rPh" -> inPhonetic = false
                "si" -> { out.add(sb?.toString() ?: ""); sb = null }
            }
        }
        ev = p.next()
    }
}

private fun colIndex(ref: String): Int =
    if (ref.isBlank()) -1
    else ref.takeWhile { it.isLetter() }.uppercase().fold(0) { a, c -> a * 26 + (c - 'A' + 1) } - 1

private fun parseSheet(bytes: ByteArray, shared: List<String>): List<List<String>> {
    val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    val rows = mutableListOf<List<String>>()
    var row: MutableList<String>? = null
    var col = -1; var type = ""
    var inV = false; var inT = false
    var value = StringBuilder()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name) {
                "row" -> row = mutableListOf()
                "c" -> {
                    col = colIndex(p.getAttributeValue(null, "r") ?: "")
                    type = p.getAttributeValue(null, "t") ?: ""
                    value = StringBuilder()
                }
                "v" -> inV = true
                "t" -> inT = true            // inline strings
            }
            XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
            XmlPullParser.END_TAG -> when (p.name) {
                "v" -> inV = false
                "t" -> inT = false
                "c" -> row?.let { r ->
                    val target = if (col < 0) r.size else col
                    while (r.size < target) r.add("")
                    val raw = value.toString()
                    r.add(when (type) {
                        "s" -> shared.getOrElse(raw.trim().toIntOrNull() ?: -1) { "" }
                        "b" -> if (raw.trim() == "1") "TRUE" else "FALSE"
                        else -> raw
                    })
                }
                "row" -> { row?.let { rows.add(it) }; row = null }
            }
        }
        ev = p.next()
    }
    return rows
}

/** Accepts .xlsx or .csv content (detected from the file bytes, not the file name). */
fun parseAttendanceFile(bytes: ByteArray): CsvData = when {
    bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> {
        val rows = readXlsxSheets(bytes).firstOrNull { sheet ->
            sheet.any { r -> r.any { it.trim().equals("LAST NAME", true) } }
        }
        requireNotNull(rows) { "No sheet with a \"LAST NAME\" header row was found in this workbook." }
        parseAttendanceRows(rows)
    }
    bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte() ->
        throw IllegalArgumentException(
            "This is an old binary .xls file. Open it in Excel and use Save As > .xlsx (or CSV UTF-8), then import that.")
    else -> parseAttendanceCsv(String(bytes, Charsets.UTF_8))
}
