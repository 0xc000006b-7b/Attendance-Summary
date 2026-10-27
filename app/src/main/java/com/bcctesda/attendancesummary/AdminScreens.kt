package com.bcctesda.attendancesummary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bcctesda.attendancesummary.data.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val digits = KeyboardOptions(keyboardType = KeyboardType.Number)

@Composable
fun rememberUndoSnackbar(vm: AttendanceViewModel): SnackbarHostState {
    val pending by vm.pending.collectAsState()
    val host = remember { SnackbarHostState() }
    LaunchedEffect(pending) {
        val p = pending
        if (p == null) {
            host.currentSnackbarData?.dismiss()
        } else {
            val result = host.showSnackbar(p.label, actionLabel = "Undo", duration = SnackbarDuration.Indefinite)
            if (result == SnackbarResult.ActionPerformed) vm.undoDelete()
        }
    }
    return host
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = if (value.isBlank()) "" else fmtDay(value) + ", " + value.take(4),
        onValueChange = {}, readOnly = true, label = @Composable { Text(label) },
        trailingIcon = { IconButton({ open = true }) { Icon(Icons.Default.DateRange, "Pick date") } },
        modifier = modifier
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = value.toDateOrNull()?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    open = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton({ open = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}

class TsfDraft(g: ClassGroup? = null) {
    var days by mutableStateOf((g?.semesterDays ?: 100).toString())
    var rate by mutableStateOf((g?.dailyRate ?: 100).toString())
    // % of the total TSF each tranche pays
    var s1 by mutableStateOf((g?.share1Pct ?: 20).toString())
    var s2 by mutableStateOf((g?.share2Pct ?: 40).toString())
    var s3 by mutableStateOf((g?.share3Pct ?: 40).toString())
    // % of the training that must be completed before each tranche is released
    var p1 by mutableStateOf((g?.tranche1Pct ?: 20).toString())
    var p2 by mutableStateOf((g?.tranche2Pct ?: 50).toString())
    var p3 by mutableStateOf((g?.tranche3Pct ?: 80).toString())
    var qualification by mutableStateOf(g?.qualification ?: "")
    var rqm by mutableStateOf(g?.rqmNumber ?: "")
    var provider by mutableStateOf(g?.trainingProvider ?: "")
    var office by mutableStateOf(g?.tesdaOffice ?: "TESDA Provincial Office")

    private fun plan(): List<Pair<Int, Int>>? {
        val s = listOf(s1, s2, s3).map { it.toIntOrNull() ?: return null }
        val m = listOf(p1, p2, p3).map { it.toIntOrNull() ?: return null }
        return s.zip(m).filter { it.first > 0 }
    }

    val valid: Boolean get() {
        val plan = plan() ?: return false
        if (plan.isEmpty()) return false
        val m = plan.map { it.second }
        return (days.toIntOrNull() ?: 0) > 0 && rate.toIntOrNull() != null &&
            plan.sumOf { it.first } == 100 && m.first() >= 1 && m.last() <= 100 &&
            m.zipWithNext().all { (a, b) -> a < b }
    }

    /** Advisory No. 20-OE: over 2 months = 20/40/40 at 20/50/80; under 2 months = 50/50 at 20/80. */
    fun preset(over2Months: Boolean) {
        if (over2Months) { s1 = "20"; s2 = "40"; s3 = "40"; p1 = "20"; p2 = "50"; p3 = "80" }
        else { s1 = "50"; s2 = "50"; s3 = "0"; p1 = "20"; p2 = "80"; p3 = "100" }
    }

    fun applyTo(c: ClassGroup) = c.copy(
        semesterDays = days.toIntOrNull() ?: c.semesterDays, dailyRate = rate.toIntOrNull() ?: c.dailyRate,
        share1Pct = s1.toIntOrNull() ?: c.share1Pct, share2Pct = s2.toIntOrNull() ?: c.share2Pct,
        share3Pct = s3.toIntOrNull() ?: c.share3Pct,
        tranche1Pct = p1.toIntOrNull() ?: c.tranche1Pct, tranche2Pct = p2.toIntOrNull() ?: c.tranche2Pct,
        tranche3Pct = p3.toIntOrNull() ?: c.tranche3Pct,
        qualification = qualification.trim(), rqmNumber = rqm.trim(), trainingProvider = provider.trim(),
        tesdaOffice = office.trim().ifEmpty { "TESDA Provincial Office" }
    )
}

@Composable
fun TsfFields(d: TsfDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Training support fund (TSF)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(d.days, { d.days = it.filter(Char::isDigit) }, label = @Composable { Text("Days / semester") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits)
            OutlinedTextField(d.rate, { d.rate = it.filter(Char::isDigit) }, label = @Composable { Text("₱ per day") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ d.preset(true) }) { Text("Over 2 months", style = MaterialTheme.typography.labelSmall) }
            OutlinedButton({ d.preset(false) }) { Text("Under 2 months", style = MaterialTheme.typography.labelSmall) }
        }
        Text("Each tranche pays this % of the TSF:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(d.s1, { d.s1 = it.filter(Char::isDigit) }, label = @Composable { Text("1st %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits, isError = !d.valid)
            OutlinedTextField(d.s2, { d.s2 = it.filter(Char::isDigit) }, label = @Composable { Text("2nd %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits, isError = !d.valid)
            OutlinedTextField(d.s3, { d.s3 = it.filter(Char::isDigit) }, label = @Composable { Text("3rd %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits, isError = !d.valid)
        }
        Text("Released once this % of the training is completed:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(d.p1, { d.p1 = it.filter(Char::isDigit) }, label = @Composable { Text("1st at %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits, isError = !d.valid)
            OutlinedTextField(d.p2, { d.p2 = it.filter(Char::isDigit) }, label = @Composable { Text("2nd at %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits, isError = !d.valid)
            OutlinedTextField(d.p3, { d.p3 = it.filter(Char::isDigit) }, label = @Composable { Text("3rd at %") },
                modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = digits, isError = !d.valid)
        }
        if (d.valid) {
            val g = d.applyTo(ClassGroup(programId = 0, section = "", yearLevel = 1, semester = 1))
            Text(trancheDefs(g).joinToString(" • ") {
                "${ordinal(it.number)}: ${peso(trancheGross(g, it))} at day ${it.toDay}"
            }, style = MaterialTheme.typography.bodySmall)
        } else {
            Text("Shares must add up to 100%, and release points must increase (for example 20, 50, 80).",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Text("Payroll details (optional)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(d.qualification, { d.qualification = it },
            label = @Composable { Text("Qualification (e.g. PQF LEVEL 5 (DIPLOMA) IN AUTOMOTIVE TECHNOLOGY)") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.rqm, { d.rqm = it }, label = @Composable { Text("RQM number") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.provider, { d.provider = it }, label = @Composable { Text("Training provider") },
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.office, { d.office = it }, label = @Composable { Text("TESDA office") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/* ---- Programs and classes ---- */
@Composable
fun AdminHome(vm: AttendanceViewModel, onManageClass: (Int) -> Unit, onBack: () -> Unit) {
    val programs by vm.programs.collectAsState()
    val classes by vm.classes.collectAsState()
    val snack = rememberUndoSnackbar(vm)

    var programName by remember { mutableStateOf("") }
    var program by remember { mutableStateOf<Program?>(null) }
    var section by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("1") }
    var sem by remember { mutableStateOf("1") }
    var scholarship by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("") }
    var end by remember { mutableStateOf("") }
    val tsf = remember { TsfDraft() }
    val startD = start.toDateOrNull(); val endD = end.toDateOrNull()
    val rangeOk = startD != null && endD != null && !endD.isBefore(startD)

    var editProgram by remember { mutableStateOf<Program?>(null) }
    var deleteProgram by remember { mutableStateOf<Program?>(null) }
    var editClass by remember { mutableStateOf<ClassInfo?>(null) }
    var deleteClass by remember { mutableStateOf<ClassInfo?>(null) }

    // duplicate checks
    val programDup = programs.any { it.name.trim().equals(programName.trim(), ignoreCase = true) }
    val prog = program
    val y = year.toIntOrNull(); val s = sem.toIntOrNull()
    val classDup = prog != null && y != null && s != null && section.isNotBlank() && classes.any {
        it.group.programId == prog.id && it.group.section.equals(section.trim(), true) &&
            it.group.yearLevel == y && it.group.semester == s
    }

    Page("Admin", onBack, snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad).padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {

            Text("1. Add a TESDA program", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(programName, { programName = it },
                label = @Composable { Text("Program name (e.g. Automotive Technology)") },
                isError = programDup,
                supportingText = @Composable { if (programDup) Text("This program already exists.") },
                modifier = Modifier.fillMaxWidth())
            Button(enabled = programName.isNotBlank() && !programDup, onClick = {
                vm.addProgram(programName); programName = ""
            }) { Text("Add TESDA program") }
            programs.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(p.name, Modifier.weight(1f))
                    IconButton({ editProgram = p }) { Icon(Icons.Default.Edit, "Rename program") }
                    IconButton({ deleteProgram = p }) { Icon(Icons.Default.Delete, "Delete program") }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("2. Add a class (section)", style = MaterialTheme.typography.titleMedium)
            Picker("Program", programs, program, { it.name }) { program = it }
            OutlinedTextField(section, { section = it.uppercase() }, label = @Composable { Text("Section (A, B, ...)") },
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit) }, label = @Composable { Text("Year") },
                    modifier = Modifier.weight(1f), keyboardOptions = digits)
                OutlinedTextField(sem, { sem = it.filter(Char::isDigit) }, label = @Composable { Text("Semester") },
                    modifier = Modifier.weight(1f), keyboardOptions = digits)
            }
            OutlinedTextField(scholarship, { scholarship = it }, label = @Composable { Text("Scholarship (e.g. UAQTEA)") },
                modifier = Modifier.fillMaxWidth())
            DateField("Training start date", start, Modifier.fillMaxWidth()) { start = it }
            DateField("Training end date", end, Modifier.fillMaxWidth()) { end = it }
            TsfFields(tsf)
            if (rangeOk) {
                val n = trainingDatesBetween(startD, endD).size
                Text("$n training days (Mon–Sat) • ${n * 8} required hours. You can remove holidays later.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (classDup) Text("This class already exists (same program, section, year and semester).",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(enabled = prog != null && section.isNotBlank() && y != null && s != null && !classDup && rangeOk && tsf.valid, onClick = {
                vm.addClass(tsf.applyTo(ClassGroup(programId = prog!!.id, section = section.trim(), yearLevel = y!!, semester = s!!,
                    scholarship = scholarship.trim(), startDate = start, endDate = end)))
                section = ""
            }) { Text("Add class") }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("3. Tap a class to manage it", style = MaterialTheme.typography.titleMedium)
            classes.forEach { c ->
                Row(Modifier.fillMaxWidth().clickable { onManageClass(c.group.id) },
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.programName)
                        Text(c.label, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton({ editClass = c }) { Icon(Icons.Default.Edit, "Edit class") }
                    IconButton({ deleteClass = c }) { Icon(Icons.Default.Delete, "Delete class") }
                }
            }
        }
    }

    /* ---- dialogs ---- */
    editProgram?.let { p ->
        RenameProgramDialog(p, programs, onDismiss = { editProgram = null }) {
            vm.updateProgram(p.copy(name = it)); editProgram = null
        }
    }
    deleteProgram?.let { p ->
        val n = classes.count { it.group.programId == p.id }
        ConfirmDeleteDialog(
            title = "Delete \"${p.name}\"?",
            message = "This also deletes its $n class(es) and ALL their trainees, subjects, photos and attendance. You'll have a few seconds to undo.",
            onDismiss = { deleteProgram = null }
        ) { vm.requestDelete(Pending.Prog(p)); deleteProgram = null }
    }
    editClass?.let { c ->
        EditClassDialog(c, programs, classes, onDismiss = { editClass = null }) {
            vm.updateClass(it); editClass = null
        }
    }
    deleteClass?.let { c ->
        ConfirmDeleteDialog(
            title = "Delete ${c.programName} – Section ${c.group.section}?",
            message = "This also deletes ALL its trainees, subjects, photos and attendance. You'll have a few seconds to undo.",
            onDismiss = { deleteClass = null }
        ) { vm.requestDelete(Pending.Cls(c)); deleteClass = null }
    }
}

@Composable
fun RenameProgramDialog(program: Program, existing: List<Program>, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(program.name) }
    val dup = existing.any { it.id != program.id && it.name.trim().equals(name.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename program") },
        text = {
            OutlinedTextField(name, { name = it }, label = @Composable { Text("Program name") }, singleLine = true,
                isError = dup, supportingText = @Composable { if (dup) Text("This program already exists.") },
                modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !dup, onClick = { onSave(name.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun EditClassDialog(info: ClassInfo, programs: List<Program>, allClasses: List<ClassInfo>,
                    onDismiss: () -> Unit, onSave: (ClassGroup) -> Unit) {
    var program by remember { mutableStateOf(programs.firstOrNull { it.id == info.group.programId }) }
    var section by remember { mutableStateOf(info.group.section) }
    var year by remember { mutableStateOf(info.group.yearLevel.toString()) }
    var sem by remember { mutableStateOf(info.group.semester.toString()) }
    var scholarship by remember { mutableStateOf(info.group.scholarship) }
    var start by remember { mutableStateOf(info.group.startDate) }
    var end by remember { mutableStateOf(info.group.endDate) }
    val tsf = remember(info.group.id) { TsfDraft(info.group) }
    val y = year.toIntOrNull(); val s = sem.toIntOrNull()
    val dup = program != null && y != null && s != null && section.isNotBlank() && allClasses.any {
        it.group.id != info.group.id && it.group.programId == program!!.id &&
            it.group.section.equals(section.trim(), true) && it.group.yearLevel == y && it.group.semester == s
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit class") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Picker("Program", programs, program, { it.name }) { program = it }
                OutlinedTextField(section, { section = it.uppercase() }, label = @Composable { Text("Section") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it.filter(Char::isDigit) }, label = @Composable { Text("Year") },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = digits)
                    OutlinedTextField(sem, { sem = it.filter(Char::isDigit) }, label = @Composable { Text("Semester") },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = digits)
                }
                OutlinedTextField(scholarship, { scholarship = it }, label = @Composable { Text("Scholarship") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                DateField("Training start date", start, Modifier.fillMaxWidth()) { start = it }
                DateField("Training end date", end, Modifier.fillMaxWidth()) { end = it }
                TsfFields(tsf)
                if (dup) Text("Another class already has this program, section, year and semester.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = program != null && section.isNotBlank() && y != null && s != null && !dup && tsf.valid, onClick = {
                onSave(tsf.applyTo(info.group.copy(programId = program!!.id, section = section.trim(),
                    yearLevel = y!!, semester = s!!, scholarship = scholarship.trim(),
                    startDate = start, endDate = end)))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

/* ---- One class: trainees / attendance / days / subjects / absences / import ---- */
@Composable
fun AdminClassScreen(vm: AttendanceViewModel, classId: Int, onEditTrainee: (Int) -> Unit, onBack: () -> Unit) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    var tab by remember { mutableIntStateOf(0) }
    val snack = rememberUndoSnackbar(vm)
    val holder = rememberSaveableStateHolder()

    Page(info?.let { "${it.programName} – ${it.group.section}" } ?: "Class", onBack,
        snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(tab, edgePadding = 0.dp) {
                listOf("Trainees", "Attendance", "Days", "Subjects", "Absences", "Import").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(t) })
                }
            }
            holder.SaveableStateProvider(tab) {
                when (tab) {
                    0 -> TraineesTab(vm, classId, onEditTrainee)
                    1 -> AttendanceTab(vm, classId)
                    2 -> DaysTab(vm, classId)
                    3 -> SubjectsTab(vm, classId)
                    4 -> AbsencesTab(vm, classId)
                    else -> ImportTab(vm, classId)
                }
            }
        }
    }
}

@Composable
fun TraineesTab(vm: AttendanceViewModel, classId: Int, onEdit: (Int) -> Unit) {
    val trainees by remember(classId) { vm.trainees(classId) }.collectAsState()
    var formKey by remember { mutableIntStateOf(0) }

    LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            val currentKey = formKey
            remember(currentKey) { object {} }.let {
                TraineeForm(null, classId, "Add trainee") {
                    vm.addTrainee(it)
                    formKey++
                }
            }
        }
        items(trainees, key = { it.id }) { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Avatar(t.photoPath, 40.dp)
                Spacer(Modifier.width(10.dp))
                Text("${t.lastName}, ${t.firstName} ${t.middleName}", Modifier.weight(1f))
                IconButton({ onEdit(t.id) }) { Icon(Icons.Default.Edit, "Edit") }
                IconButton({ vm.requestDelete(Pending.Trn(t)) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

@Composable
fun AttendanceTab(vm: AttendanceViewModel, classId: Int) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    val days by remember(classId) { vm.days(classId) }.collectAsState()
    val trainees by remember(classId) { vm.trainees(classId) }.collectAsState()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val hours = info?.group?.hoursPerDay ?: 8
    val today = LocalDate.now().toString()
    val current = days.firstOrNull { it.date == picked }?.date
        ?: days.lastOrNull { it.date <= today }?.date ?: days.firstOrNull()?.date

    if (current == null) {
        Text("No training days yet. Open the Days tab to add them.", Modifier.padding(12.dp))
        return
    }
    val marks by remember(classId, current) { vm.attendanceOn(classId, current) }.collectAsState(emptyList())
    val byTrainee = marks.associateBy { it.traineeId }
    val presentN = trainees.count { (byTrainee[it.id]?.hours ?: -1) > 0 }
    val absentN = trainees.count { byTrainee[it.id]?.hours == 0 }

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("Date", days, days.firstOrNull { it.date == current }, { fmtDay(it.date) }) { picked = it.date }
        Text("Present $presentN • Absent $absentN • Not marked ${trainees.size - presentN - absentN}",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ vm.markAll(trainees.map { it.id }, current, hours) }) { Text("All present") }
            TextButton({ vm.clearDay(classId, current) }) { Text("Clear day") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(trainees, key = { it.id }) { t ->
                val h = byTrainee[t.id]?.hours
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(t.photoPath, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${t.lastName}, ${t.firstName}", Modifier.weight(1f))
                    FilterChip(selected = h != null && h > 0,
                        onClick = { vm.setAttendance(t.id, current, if (h != null && h > 0) null else hours) },
                        label = { Text("$hours hrs") })
                    Spacer(Modifier.width(6.dp))
                    FilterChip(selected = h == 0,
                        onClick = { vm.setAttendance(t.id, current, if (h == 0) null else 0) },
                        label = { Text("Absent") })
                }
            }
        }
    }
}

@Composable
fun DaysTab(vm: AttendanceViewModel, classId: Int) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    val days by remember(classId) { vm.days(classId) }.collectAsState()
    var newDay by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    val hours = info?.group?.hoursPerDay ?: 8

    LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${days.size} training days • ${days.size * hours} required hours",
                    style = MaterialTheme.typography.titleMedium)
                Text("Percentage = present days ÷ total days. Remove holidays and no-class days from the list.",
                    style = MaterialTheme.typography.bodySmall)
                info?.takeIf { it.group.startDate.isNotBlank() }?.let {
                    OutlinedButton({ vm.fillDays(it.group) }) { Text("Add missing Mon–Sat days (${it.period})") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DateField("Add a day", newDay, Modifier.weight(1f)) { newDay = it }
                    Button(enabled = newDay.isNotBlank(), onClick = { vm.addDay(classId, newDay); newDay = "" }) { Text("Add") }
                }
            }
        }
        items(days, key = { it.date }) { d ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtDay(d.date), Modifier.weight(1f))
                IconButton({ removing = d.date }) { Icon(Icons.Default.Delete, "Remove day") }
            }
        }
    }
    removing?.let { d ->
        ConfirmDeleteDialog("Remove ${fmtDay(d)}?",
            "This day will no longer count, and any attendance recorded for it is deleted.",
            onDismiss = { removing = null }) { vm.removeDay(classId, d); removing = null }
    }
}

class SubjectDraft(s: Subject? = null) {
    var name by mutableStateOf(s?.name ?: "")
    var units by mutableStateOf(s?.units?.toString() ?: "3")
    var hours by mutableStateOf(s?.hoursPerSession?.toString() ?: "3")
    var pre by mutableStateOf(s?.instructorPre ?: "")
    var first by mutableStateOf(s?.instructorFirst ?: "")
    var middle by mutableStateOf(s?.instructorMiddle ?: "")
    var last by mutableStateOf(s?.instructorLast ?: "")
    var suffix by mutableStateOf(s?.instructorSuffix ?: "")
    var post by mutableStateOf(s?.instructorPost ?: "")

    val valid get() = name.isNotBlank() && (hours.toIntOrNull() ?: 0) in 1..24

    fun applyTo(base: Subject) = base.copy(
        name = name.trim(), units = units.toIntOrNull() ?: base.units,
        hoursPerSession = hours.toIntOrNull() ?: base.hoursPerSession,
        instructorPre = pre.trim(), instructorFirst = first.trim(), instructorMiddle = middle.trim(),
        instructorLast = last.trim(), instructorSuffix = suffix, instructorPost = post.trim()
    )
    val preview get() = applyTo(Subject(classId = 0, name = "")).instructorDisplay()
}

private val suffixes = listOf("None", "Jr.", "Sr.", "II", "III", "IV")

@Composable
fun SubjectFields(d: SubjectDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(d.name, { d.name = it }, label = @Composable { Text("Subject *") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(d.units, { d.units = it.filter(Char::isDigit) }, label = @Composable { Text("Units") },
                modifier = Modifier.weight(1f), keyboardOptions = digits, singleLine = true)
            OutlinedTextField(d.hours, { d.hours = it.filter(Char::isDigit) }, label = @Composable { Text("Hours / session *") },
                modifier = Modifier.weight(1f), keyboardOptions = digits, singleLine = true)
        }
        Text("Hours / session is the hours absent when a whole session is missed.", style = MaterialTheme.typography.bodySmall)

        Text("Instructor (optional)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(d.pre, { d.pre = it }, label = @Composable { Text("Pre-nominal (e.g. Engr., Dr., Mr., Ms.)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.first, { d.first = it }, label = @Composable { Text("First name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.middle, { d.middle = it }, label = @Composable { Text("Middle name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(d.last, { d.last = it }, label = @Composable { Text("Last name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Picker("Suffix (Jr., Sr., ...)", suffixes, d.suffix.ifEmpty { "None" }, { it }) { d.suffix = if (it == "None") "" else it }
        OutlinedTextField(d.post, { d.post = it }, label = @Composable { Text("Post-nominal (e.g. LPT, MAT, PhD)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        d.preview?.let { Text("Will show as: $it", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SubjectsTab(vm: AttendanceViewModel, classId: Int) {
    val subjects by remember(classId) { vm.subjects(classId) }.collectAsState()
    var formKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Subject?>(null) }
    var dating by remember { mutableStateOf<Subject?>(null) }

    LazyColumn(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            key(formKey) {
                val draft = remember { SubjectDraft() }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectFields(draft)
                    Button(enabled = draft.valid, onClick = {
                        vm.addSubject(draft.applyTo(Subject(classId = classId, name = "")))
                        formKey++
                    }) { Text("Add subject") }
                    Text("After adding, tap the calendar icon to set its weekly session dates.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        items(subjects, key = { it.id }) { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name)
                    Text("${s.units} units • ${s.hoursPerSession} hrs/session", style = MaterialTheme.typography.bodySmall)
                    Text(s.instructorDisplay() ?: "No instructor yet", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton({ dating = s }) { Icon(Icons.Default.DateRange, "Session dates") }
                IconButton({ editing = s }) { Icon(Icons.Default.Edit, "Edit subject") }
                IconButton({ vm.requestDelete(Pending.Subj(s)) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
    editing?.let { s -> EditSubjectDialog(s, onDismiss = { editing = null }) { vm.updateSubject(it); editing = null } }
    dating?.let { s -> SessionsDialog(vm, s) { dating = null } }
}

@Composable
fun EditSubjectDialog(subject: Subject, onDismiss: () -> Unit, onSave: (Subject) -> Unit) {
    val d = remember(subject.id) { SubjectDraft(subject) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit subject") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) { SubjectFields(d) } },
        confirmButton = { TextButton(enabled = d.valid, onClick = { onSave(d.applyTo(subject)) }) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun SessionsDialog(vm: AttendanceViewModel, subject: Subject, onDismiss: () -> Unit) {
    val sessions by remember(subject.id) { vm.sessions(subject.id) }.collectAsState(emptyList())
    var first by remember { mutableStateOf("") }
    var last by remember { mutableStateOf("") }
    var removing by remember { mutableStateOf<String?>(null) }
    val f = first.toDateOrNull(); val l = last.toDateOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${subject.name}: session dates") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${sessions.size} sessions", style = MaterialTheme.typography.titleSmall)
                Text("Add weekly sessions (same weekday). For a single date, use the same date twice.",
                    style = MaterialTheme.typography.bodySmall)
                DateField("First session", first, Modifier.fillMaxWidth()) { first = it; if (last.isBlank()) last = it }
                DateField("Last session on or before", last, Modifier.fillMaxWidth()) { last = it }
                Button(enabled = f != null && l != null && !l.isBefore(f), onClick = {
                    if (f != null && l != null) {
                        vm.addWeeklySessions(subject.id, f, l)
                        first = ""; last = ""
                    }
                }) { Text("Add weekly sessions") }
                sessions.forEach { s ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(fmtDay(s.date), Modifier.weight(1f))
                        IconButton({ removing = s.date }) { Icon(Icons.Default.Delete, "Remove session") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } }
    )
    removing?.let { d ->
        ConfirmDeleteDialog("Remove ${fmtDay(d)}?", "Absences recorded for this subject on this date are deleted too.",
            onDismiss = { removing = null }) { vm.removeSession(subject.id, d); removing = null }
    }
}

@Composable
fun AbsencesTab(vm: AttendanceViewModel, classId: Int) {
    val subjects by remember(classId) { vm.subjects(classId) }.collectAsState()
    val trainees by remember(classId) { vm.trainees(classId) }.collectAsState()
    var subjectId by rememberSaveable { mutableStateOf<Int?>(null) }
    val subject = subjects.firstOrNull { it.id == subjectId } ?: subjects.firstOrNull()

    if (subject == null) {
        Text("Add a subject first (Subjects tab).", Modifier.padding(12.dp))
        return
    }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("Subject", subjects, subject, { it.name }) { subjectId = it.id }
        AbsenceSheet(vm, subject, trainees)
    }
}

@Composable
private fun AbsenceSheet(vm: AttendanceViewModel, subject: Subject, trainees: List<Trainee>) {
    val sessions by remember(subject.id) { vm.sessions(subject.id) }.collectAsState(emptyList())
    var picked by rememberSaveable(subject.id) { mutableStateOf<String?>(null) }
    val today = LocalDate.now().toString()
    val current = sessions.firstOrNull { it.date == picked }?.date
        ?: sessions.lastOrNull { it.date <= today }?.date ?: sessions.firstOrNull()?.date

    if (current == null) {
        Text("No session dates yet. In the Subjects tab, tap the calendar icon on this subject.")
        return
    }
    val marks by remember(subject.id, current) { vm.absencesOn(subject.id, current) }.collectAsState(emptyList())
    val byTrainee = marks.associateBy { it.traineeId }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("Session date", sessions, sessions.firstOrNull { it.date == current }, { fmtDay(it.date) }) { picked = it.date }
        Text("Blank = present. Tap Absent for the whole session (${subject.hoursPerSession} hrs), or type the hours missed. " +
            "Absent hours on one date add up across subjects; 8 hours is a full absent day.",
            style = MaterialTheme.typography.bodySmall)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(trainees, key = { it.id }) { t ->
                val saved = byTrainee[t.id]?.hours
                var text by remember(subject.id, current, saved) { mutableStateOf(saved?.toString() ?: "") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(t.photoPath, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("${t.lastName}, ${t.firstName}", Modifier.weight(1f))
                    FilterChip(selected = saved != null,
                        onClick = { vm.setAbsence(t.id, subject.id, current, if (saved != null) null else subject.hoursPerSession) },
                        label = { Text("Absent") })
                    Spacer(Modifier.width(6.dp))
                    OutlinedTextField(text, { v ->
                        val d = v.filter(Char::isDigit); text = d
                        val n = d.toIntOrNull()
                        if (d.isEmpty()) vm.setAbsence(t.id, subject.id, current, null)
                        else if (n != null && n in 1..subject.hoursPerSession) vm.setAbsence(t.id, subject.id, current, n)
                    }, modifier = Modifier.width(72.dp), singleLine = true, keyboardOptions = digits,
                        isError = (text.toIntOrNull() ?: 0) > subject.hoursPerSession)
                }
            }
        }
    }
}
