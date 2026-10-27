package com.bcctesda.attendancesummary

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bcctesda.attendancesummary.data.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale

/** marks: 8 = present (8 hrs), 0 = absent, null = blank cell. One entry per date column. */
data class ImportTrainee(val last: String, val first: String, val middle: String, val marks: List<Int?>)
data class ImportData(val dates: List<LocalDate>, val trainees: List<ImportTrainee>)

data class SubjectImportRow(val last: String, val first: String, val middle: String, val hours: List<Int>)
data class SubjectImport(
    val name: String, val units: Int, val hoursPerSession: Int,
    val dates: List<LocalDate>, val rows: List<SubjectImportRow>
)
data class ImportFile(
    val daily: Map<String, ImportData>,
    val subjects: List<SubjectImport>,
    val skipped: List<String> = emptyList()
)

private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()

@Volatile private var importYear = LocalDate.now().year

private val datePatterns = listOf(
    "uuuu-MM-dd", "M/d/uuuu", "M/d/uu", "d/M/uuuu", "d-MMM-uuuu", "d-MMM-uu",
    "MMM d, uuuu", "MMMM d, uuuu", "MMM d", "MMMM d", "d-MMM", "d MMM", "d MMMM"
)

private fun toDate(raw: String): LocalDate? {
    val s = clean(raw)
    if (s.isEmpty()) return null
    s.toDoubleOrNull()?.toLong()?.let {                      // Excel date number
        if (it in 30000..80000) return LocalDate.of(1899, 12, 30).plusDays(it)
    }
    return datePatterns.firstNotNullOfOrNull { p ->
        runCatching {
            val f = DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(p)
                .parseDefaulting(ChronoField.YEAR, importYear.toLong()).toFormatter(Locale.ENGLISH)
            LocalDate.parse(s, f)
        }.getOrNull()
    }
}

private fun norm(s: String) = s.uppercase().filter { it in 'A'..'Z' }
private fun isLastHeader(s: String) = norm(s) in setOf("LASTNAME", "SURNAME", "FAMILYNAME")
private fun isFirstHeader(s: String) = norm(s) in setOf("FIRSTNAME", "FIRSTNAMES", "GIVENNAME")
private fun isMiddleHeader(s: String) = norm(s) in setOf("MIDDLENAME", "MIDDLEINITIAL", "MI")

private fun preview(rows: List<List<String>>) = rows.filter { r -> r.any { it.isNotBlank() } }.take(2)
    .joinToString(" / ") { r -> r.map(::clean).filter { it.isNotEmpty() }.take(6).joinToString(", ") }

private fun toMark(raw: String): Int? {
    val s = clean(raw).uppercase()
    return when {
        s.isEmpty() -> null
        s == "ABSENT" || s == "A" -> 0
        s == "PRESENT" || s == "P" -> 8
        else -> s.toDoubleOrNull()?.toInt()?.coerceAtLeast(0)
    }
}

/** Handles quoted cells, commas and line breaks inside quotes. */
fun parseCsv(text: String): List<List<String>> {
    val s = text.removePrefix("\uFEFF")
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (inQuotes) {
            if (c == '"') {
                if (i + 1 < s.length && s[i + 1] == '"') { cell.append('"'); i++ } else inQuotes = false
            } else cell.append(c)
        } else when (c) {
            '"' -> inQuotes = true
            ',' -> { row.add(cell.toString()); cell.clear() }
            '\n' -> { row.add(cell.toString()); cell.clear(); rows.add(row); row = mutableListOf() }
            '\r' -> {}
            else -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) { row.add(cell.toString()); rows.add(row) }
    return rows
}

fun parseAttendanceRows(rows: List<List<String>>): ImportData {
    val h = rows.indexOfFirst { r -> r.any { isLastHeader(it) } }
    require(h >= 0) { "No LAST NAME header row found. The first rows read: ${preview(rows)}" }
    val header = rows[h].map(::clean)
    val iLast = header.indexOfFirst { isLastHeader(it) }
    val iFirst = header.indexOfFirst { isFirstHeader(it) }
    val iMid = header.indexOfFirst { isMiddleHeader(it) }
    require(iFirst >= 0) { "No FIRST NAME column found." }

    // every header cell that is a date becomes a training day
    val dateCols = header.mapIndexedNotNull { i, name -> toDate(name)?.let { i to it } }
    require(dateCols.isNotEmpty()) {
        "Found the name columns but no date columns. Headers seen: " +
            header.filter { it.isNotEmpty() }.take(8).joinToString() +
            ". If the dates are formulas, open the file in Excel, save it once, then try again."
    }

    val trainees = rows.drop(h + 1).mapNotNull { r ->
        fun cell(i: Int) = if (i in r.indices) clean(r[i]) else ""
        val last = cell(iLast); val first = cell(iFirst)
        if (last.isBlank() || first.isBlank()) null
        else ImportTrainee(last, first, cell(iMid).takeUnless { it == "." } ?: "",
            dateCols.map { (i, _) -> toMark(cell(i)) })
    }
    require(trainees.isNotEmpty()) { "No trainee rows found under the header." }
    return ImportData(dateCols.map { it.second }, trainees)
}

private val titleRx = Regex("^(.*?)\\s*[-–—]\\s*(\\d+)\\s*units?\\s*$", RegexOption.IGNORE_CASE)

/** A subject tab has a title ("Chem for Industrial Tech - 3 units") above the header row. */
private fun sheetTitle(rows: List<List<String>>): String? {
    val h = rows.indexOfFirst { r -> r.any { isLastHeader(it) } }
    if (h < 1) return null
    return rows.take(h).flatten().map(::clean).firstOrNull { it.isNotEmpty() }
}

fun parseSubjectRows(rows: List<List<String>>, title: String): SubjectImport {
    val h = rows.indexOfFirst { r -> r.any { isLastHeader(it) } }
    require(h >= 0) { "No LAST NAME header row found." }
    val header = rows[h].map(::clean)
    val iLast = header.indexOfFirst { isLastHeader(it) }
    val iFirst = header.indexOfFirst { isFirstHeader(it) }
    val iMid = header.indexOfFirst { isMiddleHeader(it) }
    require(iFirst >= 0) { "No FIRST NAME column found." }
    val dateCols = header.mapIndexedNotNull { i, name -> toDate(name)?.let { i to it } }
    require(dateCols.isNotEmpty()) { "No date columns found." }

    val parsed = rows.drop(h + 1).mapNotNull { r ->
        fun cell(i: Int) = if (i in r.indices) clean(r[i]) else ""
        val last = cell(iLast); val first = cell(iFirst)
        if (last.isBlank() || first.isBlank()) null
        else SubjectImportRow(last, first, cell(iMid).takeUnless { it == "." } ?: "",
            dateCols.map { (i, _) -> cell(i).toDoubleOrNull()?.toInt()?.coerceAtLeast(0) ?: 0 })  // blank = present = 0
    }
    require(parsed.isNotEmpty()) { "No trainee rows found." }

    val m = titleRx.find(title)
    val name = (m?.groupValues?.get(1) ?: title).ifBlank { title }
    val units = m?.groupValues?.get(2)?.toIntOrNull() ?: 3
    // usual hours missed when a whole session is missed = the most common number on the sheet
    val perSession = parsed.flatMap { it.hours }.filter { it > 0 }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 3
    return SubjectImport(name, units, perSession, dateCols.map { it.second }, parsed)
}

/** Returns sheet name -> data for every usable sheet (CSV gives one sheet named "CSV"). */
fun parseAttendanceFile(bytes: ByteArray): ImportFile {
    importYear = LocalDate.now().year
    val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    val isOldXls = bytes.size > 4 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()
    if (isOldXls) throw IllegalArgumentException(
        "This is an old binary .xls file. Open it in Excel and use Save As > .xlsx (or CSV UTF-8).")

    val daily = LinkedHashMap<String, ImportData>()
    val subjects = mutableListOf<SubjectImport>()
    val problems = mutableListOf<String>()

    fun classify(name: String, rows: List<List<String>>) {
        val h = rows.indexOfFirst { r -> r.any { isLastHeader(it) } }
        val title = sheetTitle(rows)
        // a daily sheet has the word ABSENT in its cells; a subject sheet only has numbers and blanks
        val hasAbsentText = h >= 0 && rows.drop(h + 1).any { r -> r.any { clean(it).equals("ABSENT", true) } }
        runCatching {
            if (title != null && !hasAbsentText) subjects.add(parseSubjectRows(rows, title)) else daily[name] = parseAttendanceRows(rows)
        }.onFailure { problems.add("• $name: ${it.message}") }
    }

    if (isZip) readXlsxSheets(bytes).forEach { (name, rows) -> classify(name, rows) }
    else classify("CSV", parseCsv(String(bytes, Charsets.UTF_8)))

    require(daily.isNotEmpty() || subjects.isNotEmpty()) {
        "Nothing to import. What each sheet looked like:\n" + problems.joinToString("\n")
    }
    return ImportFile(daily, subjects, problems)
}

fun fileNameOf(ctx: Context, uri: Uri): String =
    runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "Unknown file"

@Composable
fun ImportTab(vm: AttendanceViewModel, classId: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var file by remember { mutableStateOf<ImportFile?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var fileName by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun done(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        file = null; fileName = null
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = fileNameOf(ctx, uri)
        fileName = name
        runCatching { parseAttendanceFile(ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }) }
            .onSuccess { file = it; chosen = it.daily.keys.firstOrNull(); error = null }
            .onFailure { file = null; error = "$name: ${it.message ?: "Couldn't read that file."}" }
    }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import from Excel / CSV (optional)", style = MaterialTheme.typography.titleMedium)
        Text("Daily sheets: LAST NAME, FIRST NAME, MIDDLE NAME, then date columns (8 = present, ABSENT = absent). " +
            "Subject sheets: a title row like \"Chem for Industrial Tech - 3 units\", then the same name columns and weekly " +
            "date columns (blank = present, a number = hours absent). Sheets like \"Sum of all\" are skipped; the app recalculates them.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton({ picker.launch(arrayOf("*/*")) }) { Text("Choose .xlsx or .csv file") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val f = file
        if (f != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("File:", style = MaterialTheme.typography.labelMedium)
                    SelectionContainer { Text(fileName ?: "") }
                    if (f.skipped.isNotEmpty()) {
                        Text("Skipped sheets:", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                        Text(f.skipped.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (f.subjects.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Subjects and absences (${f.subjects.size})", fontWeight = FontWeight.Bold)
                        f.subjects.forEach { s ->
                            val total = s.rows.sumOf { r -> r.hours.sum() }
                            Text("• ${s.name} (${s.units} units): ${s.dates.size} sessions, $total hrs absent in total",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Replaces the absences already recorded on these subjects' dates.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button({
                    scope.launch {
                        done(runCatching { vm.importSubjects(classId, f.subjects) }.getOrElse { "Import failed: ${it.message}" })
                    }
                }) { Text("Import subjects & absences") }
            }

            val data = chosen?.let { f.daily[it] }
            if (data != null) {
                if (f.daily.size > 1) Picker("Daily sheet to import", f.daily.keys.toList(), chosen, { it }) { chosen = it }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Daily attendance: $chosen", fontWeight = FontWeight.Bold)
                        Text("${data.trainees.size} trainees")
                        Text("${data.dates.size} training days (${fmtRange(data.dates.min().toString(), data.dates.max().toString())})")
                        Text("Required hours: ${data.dates.size * 8}")
                    }
                }
                Button({
                    scope.launch {
                        done(runCatching { vm.importAttendance(classId, data) }.getOrElse { "Import failed: ${it.message}" })
                    }
                }) { Text("Import daily attendance") }
            }
            TextButton({ file = null; fileName = null }) { Text("Cancel") }
        }
    }
}
