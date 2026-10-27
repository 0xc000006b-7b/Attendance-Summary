package com.bcctesda.attendancesummary

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Returns (sheet name, rows) for every worksheet in an .xlsx, in workbook order. */
fun readXlsxSheets(bytes: ByteArray): List<Pair<String, List<List<String>>>> {
    val files = HashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var e = zip.nextEntry
        while (e != null) {
            val n = e.name
            if (n.startsWith("xl/") && (n.endsWith(".xml") || n.endsWith(".rels"))) files[n] = zip.readBytes()
            e = zip.nextEntry
        }
    }
    val shared = mutableListOf<String>()
    files["xl/sharedStrings.xml"]?.let { parseSharedStrings(it, shared) }

    val rels = files["xl/_rels/workbook.xml.rels"]
        ?.let { attrs(it, "Relationship", "Id", "Target") }.orEmpty()
        .mapNotNull { r -> if (r[0] != null && r[1] != null) r[0]!! to r[1]!! else null }.toMap()
    val sheets = files["xl/workbook.xml"]?.let { attrs(it, "sheet", "name", "r:id") }.orEmpty()

    return sheets.mapNotNull { s ->
        val name = s[0] ?: return@mapNotNull null
        val target = rels[s[1]] ?: return@mapNotNull null
        val path = "xl/" + target.removePrefix("/").removePrefix("xl/")
        files[path]?.let { name to parseSheet(it, shared) }
    }
}

private fun attrs(bytes: ByteArray, tag: String, vararg names: String): List<List<String?>> {
    val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    val out = mutableListOf<List<String?>>()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && p.name == tag) out.add(names.map { p.getAttributeValue(null, it) })
        ev = p.next()
    }
    return out
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
                "t" -> inT = true
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
