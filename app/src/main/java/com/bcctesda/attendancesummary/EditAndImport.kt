package com.bcctesda.attendancesummary

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bcctesda.attendancesummary.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/* ================= Reusable trainee form (add + edit) ================= */
@Composable
fun TraineeForm(initial: Trainee?, classId: Int, submitLabel: String, onSubmit: (Trainee) -> Unit) {
    var last by remember { mutableStateOf(initial?.lastName ?: "") }
    var first by remember { mutableStateOf(initial?.firstName ?: "") }
    var middle by remember { mutableStateOf(initial?.middleName ?: "") }
    var birthday by remember { mutableStateOf(initial?.birthday ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var photo by remember { mutableStateOf(initial?.photoPath) }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val saved = withContext(Dispatchers.IO) { savePhoto(ctx, uri) }
            if (saved == null) {
                Toast.makeText(ctx, "Couldn't use that image.", Toast.LENGTH_SHORT).show()
            } else {
                photo?.takeIf { it != initial?.photoPath }?.let { File(it).delete() }
                photo = saved
            }
        }
    }
    val birthdayValid = birthday.isBlank() || runCatching { LocalDate.parse(birthday.trim()) }.isSuccess

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(photo, 96.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text(if (photo == null) "Add photo" else "Change photo") }
                if (photo != null) TextButton(onClick = { photo = null }) { Text("Remove photo") }
            }
        }
        OutlinedTextField(last, { last = it }, label = @Composable { Text("Last name *") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(first, { first = it }, label = @Composable { Text("First name *") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(middle, { middle = it }, label = @Composable { Text("Middle name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(birthday, { birthday = it }, label = @Composable { Text("Birthday (YYYY-MM-DD, optional)") },
            isError = !birthdayValid, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(email, { email = it }, label = @Composable { Text("Email (optional)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
        OutlinedTextField(phone, { phone = it }, label = @Composable { Text("Phone (optional)") }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(address, { address = it }, label = @Composable { Text("Address (optional)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = last.isNotBlank() && first.isNotBlank() && birthdayValid, onClick = {
            onSubmit((initial ?: Trainee(classId = classId, lastName = "", firstName = "")).copy(
                lastName = last.trim().uppercase(), firstName = first.trim().uppercase(),
                middleName = middle.trim().uppercase(),
                birthday = birthday.trim().ifBlank { null }, email = email.trim().ifBlank { null },
                phone = phone.trim().ifBlank { null }, address = address.trim().ifBlank { null },
                photoPath = photo))
        }) { Text(submitLabel) }
    }
}

/* ================= Edit screen ================= */
@Composable
fun EditTraineeScreen(vm: AttendanceViewModel, id: Int, onBack: () -> Unit) {
    val t by remember(id) { vm.trainee(id) }.collectAsState(null)
    Page("Edit trainee", onBack) { pad ->
        val trainee = t ?: return@Page
        Column(modifier = Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState())) {
            key(trainee.id) {
                TraineeForm(trainee, trainee.classId, "Save changes") { updated ->
                    if (updated.photoPath != trainee.photoPath) trainee.photoPath?.let { File(it).delete() }
                    vm.updateTrainee(updated); onBack()
                }
            }
        }
    }
}

/* ================= CSV parsing ================= */
data class CsvTrainee(
    val last: String, val first: String, val middle: String,
    val birthday: String?, val email: String?, val phone: String?, val address: String?,
    val present: List<Int?>
)
data class CsvSubject(val name: String, val sessions: Int)
data class CsvData(val subjects: List<CsvSubject>, val trainees: List<CsvTrainee>)

private fun clean(s: String) = s.replace(Regex("\\s+"), " ").trim()
private val sessionRx = Regex("\\((\\d+)\\s*d\\)", RegexOption.IGNORE_CASE)

/** Handles quoted cells, commas and line breaks inside quotes (like "Math Modern\nWorld (6d)"). */
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

fun parseAttendanceCsv(text: String) = parseAttendanceRows(parseCsv(text))

fun parseAttendanceRows(rows: List<List<String>>): CsvData {
    val h = rows.indexOfFirst { r -> r.any { clean(it).equals("LAST NAME", true) } }
    require(h >= 0) { "Couldn't find a header row with \"LAST NAME\"." }
    val header = rows[h].map(::clean)
    fun col(name: String) = header.indexOfFirst { it.equals(name, true) }
    val iLast = col("LAST NAME"); val iFirst = col("FIRST NAME"); val iMid = col("MIDDLE NAME")
    val iBday = col("BIRTHDAY"); val iEmail = col("EMAIL"); val iPhone = col("PHONE"); val iAddr = col("ADDRESS")
    require(iFirst >= 0) { "Couldn't find a \"FIRST NAME\" column." }

    // Any column whose header ends in "(6d)", "(7d)", ... is a subject
    val subjectCols = header.mapIndexedNotNull { index, name ->
        sessionRx.find(name)?.let { m -> Triple(index, clean(name.replace(sessionRx, "")), m.groupValues[1].toInt()) }
    }
    require(subjectCols.isNotEmpty()) { "No subject columns found. Headers need the session count, e.g. \"Math Modern World (6d)\"." }

    val trainees = rows.drop(h + 1).mapNotNull { r ->
        fun cell(index: Int) = if (index >= 0 && index < r.size) clean(r[index]) else ""
        val last = cell(iLast); val first = cell(iFirst)
        if (last.isBlank() || first.isBlank()) null else CsvTrainee(
            last, first, cell(iMid),
            toBirthday(cell(iBday)),
            cell(iEmail).ifBlank { null }, cell(iPhone).ifBlank { null }, cell(iAddr).ifBlank { null },
            subjectCols.map { (index, _, _) -> cell(index).toDoubleOrNull()?.toInt() }
        )
    }
    require(trainees.isNotEmpty()) { "No trainee rows found under the header." }
    return CsvData(subjectCols.map { (_, n, s) -> CsvSubject(n, s) }, trainees)
}

/** Accepts "yyyy-MM-dd" text, or an Excel date serial number (how .xlsx stores dates). */
private fun toBirthday(s: String): String? {
    if (s.isBlank()) return null
    runCatching { return LocalDate.parse(s).toString() }
    val serial = s.toDoubleOrNull()?.toLong() ?: return null
    return if (serial in 1..80000) LocalDate.of(1899, 12, 30).plusDays(serial).toString() else null
}

/* ================= Import tab (optional) ================= */
@Composable
fun ImportTab(vm: AttendanceViewModel, classId: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<CsvData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            parseAttendanceFile(bytes)
        }.onSuccess { data = it; error = null }
         .onFailure { data = null; error = it.message ?: "Couldn't read that file." }
    }

    Column(modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Import from CSV (optional)", style = MaterialTheme.typography.titleMedium)
        Text("Works with .xlsx and .csv files. For CSV, use Save As > CSV UTF-8 so names like AZAÑES keep their letters.")
        Text("Expected columns: LAST NAME, FIRST NAME, MIDDLE NAME, then one column per subject with its " +
            "session count in the header, e.g. \"Math Modern World (6d)\". BIRTHDAY (YYYY-MM-DD), EMAIL, PHONE and " +
            "ADDRESS columns are optional. Title rows above the header and columns like Total Present or Overall % are ignored.",
            style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }) {
            Text("Choose Excel (.xlsx) or CSV file")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        data?.let { d ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Preview", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("${d.trainees.size} trainees")
                    Text("Subjects: " + d.subjects.joinToString { "${it.name} (${it.sessions}d)" })
                    Text("Total sessions: ${d.subjects.sumOf { it.sessions }}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val msg = runCatching { vm.importCsv(classId, d) }.getOrElse { "Import failed: ${it.message}" }
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        data = null
                    }
                }) { Text("Import") }
                TextButton(onClick = { data = null }) { Text("Cancel") }
            }
        }
    }
}
