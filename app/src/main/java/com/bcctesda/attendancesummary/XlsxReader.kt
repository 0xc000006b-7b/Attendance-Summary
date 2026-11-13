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
            if (n.startsWith("xl/") && (n.endsWith(".xml") || n.endsWith(".rels"))) {
                files[n] = zip.readBytes()
            }
            e = zip.nextEntry
        }
    }
    val shared = mutableListOf<String>()
    files["xl/sharedStrings.xml"]?.let { parseSharedStrings(it, shared) }

    val rels = files["xl/_rels/workbook.xml.rels"]?.let { parseRels(it) }.orEmpty()
    val sheets = files["xl/workbook.xml"]?.let { parseSheets(it) }.orEmpty()

    val result = sheets.mapNotNull { (name, rId) ->
        val target = rels[rId] ?: return@mapNotNull null
        val path = "xl/" + target.removePrefix("/").removePrefix("xl/")
        files[path]?.let { name to parseSheet(it, shared) }
    }

    if (result.isNotEmpty()) return result

    // Fallback if workbook structure / rels differ: read all sheet XMLs directly in zip order
    val sheetFiles = files.keys.filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
        .sortedBy { k -> k.filter(Char::isDigit).toIntOrNull() ?: 0 }
    return sheetFiles.mapIndexed { idx, path ->
        "Sheet ${idx + 1}" to parseSheet(files[path]!!, shared)
    }
}

private fun parseRels(bytes: ByteArray): Map<String, String> {
    val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    val map = HashMap<String, String>()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && p.name.equals("Relationship", ignoreCase = true)) {
            var id: String? = null
            var target: String? = null
            for (i in 0 until p.attributeCount) {
                val name = p.getAttributeName(i)
                if (name.equals("Id", true)) id = p.getAttributeValue(i)
                else if (name.equals("Target", true)) target = p.getAttributeValue(i)
            }
            if (id != null && target != null) map[id] = target
        }
        ev = p.next()
    }
    return map
}

private fun parseSheets(bytes: ByteArray): List<Pair<String, String>> {
    val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    val list = mutableListOf<Pair<String, String>>()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        if (ev == XmlPullParser.START_TAG && p.name.equals("sheet", ignoreCase = true)) {
            var name: String? = null
            var rId: String? = null
            for (i in 0 until p.attributeCount) {
                val attrName = p.getAttributeName(i)
                if (attrName.equals("name", true)) name = p.getAttributeValue(i)
                else if (attrName.equals("id", true) || attrName.endsWith(":id") || attrName.equals("r:id", true))
                    rId = p.getAttributeValue(i)
            }
            if (name != null && rId != null) list.add(name to rId)
        }
        ev = p.next()
    }
    return list
}

private fun parseSharedStrings(bytes: ByteArray, out: MutableList<String>) {
    val p = Xml.newPullParser().apply { setInput(ByteArrayInputStream(bytes), "UTF-8") }
    var sb: StringBuilder? = null
    var inT = false
    var inPhonetic = false
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name?.lowercase()) {
                "si" -> sb = StringBuilder()
                "rph" -> inPhonetic = true
                "t" -> if (!inPhonetic) inT = true
            }
            XmlPullParser.TEXT -> if (inT) sb?.append(p.text)
            XmlPullParser.END_TAG -> when (p.name?.lowercase()) {
                "t" -> inT = false
                "rph" -> inPhonetic = false
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
    var col = -1
    var type = ""
    var inV = false
    var inT = false
    var value = StringBuilder()
    var ev = p.eventType
    while (ev != XmlPullParser.END_DOCUMENT) {
        when (ev) {
            XmlPullParser.START_TAG -> when (p.name?.lowercase()) {
                "row" -> row = mutableListOf()
                "c" -> {
                    col = -1
                    type = ""
                    for (i in 0 until p.attributeCount) {
                        val name = p.getAttributeName(i)
                        if (name.equals("r", true)) col = colIndex(p.getAttributeValue(i))
                        else if (name.equals("t", true)) type = p.getAttributeValue(i)
                    }
                    value = StringBuilder()
                }
                "v" -> inV = true
                "t" -> inT = true
            }
            XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
            XmlPullParser.END_TAG -> when (p.name?.lowercase()) {
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
