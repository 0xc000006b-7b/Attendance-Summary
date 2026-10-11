package com.bcctesda.attendancesummary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bcctesda.attendancesummary.data.*

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
                label = { Text("Program name (e.g. Automotive Technology)") },
                isError = programDup,
                supportingText = { if (programDup) Text("This program already exists.") },
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
            OutlinedTextField(section, { section = it.uppercase() }, label = { Text("Section (A, B, ...)") },
                modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(year, { year = it.filter(Char::isDigit) }, label = { Text("Year") },
                    modifier = Modifier.weight(1f), keyboardOptions = digits)
                OutlinedTextField(sem, { sem = it.filter(Char::isDigit) }, label = { Text("Semester") },
                    modifier = Modifier.weight(1f), keyboardOptions = digits)
            }
            OutlinedTextField(scholarship, { scholarship = it }, label = { Text("Scholarship (e.g. UAQTEA)") },
                modifier = Modifier.fillMaxWidth())
            if (classDup) Text("This class already exists (same program, section, year and semester).",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(enabled = prog != null && section.isNotBlank() && y != null && s != null && !classDup, onClick = {
                vm.addClass(ClassGroup(programId = prog!!.id, section = section.trim(),
                    yearLevel = y!!, semester = s!!, scholarship = scholarship.trim()))
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
            OutlinedTextField(name, { name = it }, label = { Text("Program name") }, singleLine = true,
                isError = dup, supportingText = { if (dup) Text("This program already exists.") },
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
                OutlinedTextField(section, { section = it.uppercase() }, label = { Text("Section") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(year, { year = it.filter(Char::isDigit) }, label = { Text("Year") },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = digits)
                    OutlinedTextField(sem, { sem = it.filter(Char::isDigit) }, label = { Text("Semester") },
                        singleLine = true, modifier = Modifier.weight(1f), keyboardOptions = digits)
                }
                OutlinedTextField(scholarship, { scholarship = it }, label = { Text("Scholarship") },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                if (dup) Text("Another class already has this program, section, year and semester.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = program != null && section.isNotBlank() && y != null && s != null && !dup, onClick = {
                onSave(info.group.copy(programId = program!!.id, section = section.trim(),
                    yearLevel = y!!, semester = s!!, scholarship = scholarship.trim()))
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

/* ---- One class: trainees / subjects / present days / import csv ---- */
@Composable
fun AdminClassScreen(vm: AttendanceViewModel, classId: Int, onEditTrainee: (Int) -> Unit, onBack: () -> Unit) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    var tab by remember { mutableIntStateOf(0) }
    val snack = rememberUndoSnackbar(vm)

    Page(info?.let { "${it.programName} – ${it.group.section}" } ?: "Class", onBack,
        snackbarHost = { SnackbarHost(snack) }) { pad ->
        Column(Modifier.padding(pad)) {
            ScrollableTabRow(tab, edgePadding = 0.dp) {
                listOf("Trainees", "Subjects", "Present Days", "Import CSV").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> TraineesTab(vm, classId, onEditTrainee)
                1 -> SubjectsTab(vm, classId)
                2 -> PresentDaysTab(vm, classId)
                else -> ImportTab(vm, classId)
            }
        }
    }
}

@Composable
fun TraineesTab(vm: AttendanceViewModel, classId: Int, onEdit: (Int) -> Unit) {
    val trainees by remember(classId) { vm.trainees(classId) }.collectAsState(emptyList())
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
fun SubjectsTab(vm: AttendanceViewModel, classId: Int) {
    val subjects by remember(classId) { vm.subjects(classId) }.collectAsState(emptyList())
    var name by remember { mutableStateOf("") }
    var sessions by remember { mutableStateOf("") }
    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Subject") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(sessions, { sessions = it.filter(Char::isDigit) }, label = { Text("Number of sessions") },
            modifier = Modifier.fillMaxWidth(), keyboardOptions = digits)
        Button({
            val n = sessions.toIntOrNull()
            if (name.isNotBlank() && n != null && n > 0) {
                vm.addSubject(Subject(classId = classId, name = name.trim(), sessions = n)); name = ""; sessions = ""
            }
        }) { Text("Add subject") }
        LazyColumn {
            items(subjects, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.name} (${s.sessions} sessions)", Modifier.weight(1f))
                    IconButton({ vm.requestDelete(Pending.Subj(s)) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
}

@Composable
fun PresentDaysTab(vm: AttendanceViewModel, classId: Int) {
    val subjects by remember(classId) { vm.subjects(classId) }.collectAsState(emptyList())
    val trainees by remember(classId) { vm.trainees(classId) }.collectAsState(emptyList())
    var selectedId by remember { mutableStateOf<Int?>(null) }
    val selected = subjects.firstOrNull { it.id == selectedId }
    val records by remember(selected?.id) {
        selected?.let { vm.attendanceFor(it.id) } ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(emptyList())

    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Picker("Subject", subjects, selected, { "${it.name} (${it.sessions}d)" }) { selectedId = it.id }
        if (selected != null)LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(trainees, key = { "${selected.id}-${it.id}" }) { t ->
                val saved = records.firstOrNull { it.traineeId == t.id }?.presentDays ?: 0
                var text by remember(saved) { mutableStateOf(saved.toString()) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${t.lastName}, ${t.firstName}", Modifier.weight(1f))
                    OutlinedTextField(text, {
                        val v = it.filter(Char::isDigit); text = v
                        v.toIntOrNull()?.let { n -> if (n <= selected.sessions) vm.savePresent(t.id, selected.id, n) }
                    }, modifier = Modifier.width(90.dp), singleLine = true,
                        isError = (text.toIntOrNull() ?: 0) > selected.sessions, keyboardOptions = digits)
                }
            }
        }
    }
}
