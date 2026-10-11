package com.bcctesda.attendancesummary

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bcctesda.attendancesummary.data.*

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot(vm: AttendanceViewModel = viewModel()) {
    val nav = rememberNavController()
    val intArg = { name: String -> listOf(navArgument(name) { type = NavType.IntType }) }
    NavHost(nav, "home") {
        composable("home") {
            HomeScreen(vm, { nav.navigate("summary/$it") }, { nav.navigate("admin") })
        }
        composable("summary/{classId}", intArg("classId")) {
            SummaryScreen(vm, it.arguments!!.getInt("classId"), { id -> nav.navigate("trainee/$id") }, { nav.popBackStack() })
        }
        composable("trainee/{id}", intArg("id")) {
            val id = it.arguments!!.getInt("id")
            TraineeDetailScreen(vm, id, { nav.navigate("edit/$id") }) { nav.popBackStack() }
        }
        composable("edit/{id}", intArg("id")) {
            EditTraineeScreen(vm, it.arguments!!.getInt("id")) { nav.popBackStack() }
        }
        composable("admin") {
            AdminHome(vm, { id -> nav.navigate("admin/$id") }) { nav.popBackStack() }
        }
        composable("admin/{classId}", intArg("classId")) {
            AdminClassScreen(vm, it.arguments!!.getInt("classId"),
                onEditTrainee = { id -> nav.navigate("edit/$id") }) { nav.popBackStack() }
        }
    }
}

/* ---------- shared helpers ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(title: String, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {},
         snackbarHost: @Composable () -> Unit = {},
         content: @Composable (PaddingValues) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = { onBack?.let { IconButton(it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } } },
                actions = actions
            )
        },
        snackbarHost = snackbarHost,
        content = content
    )
}

@Composable
fun CategoryChip(category: String) {
    val color = when (category) {
        "80%" -> Color(0xFF2E7D32); "50%" -> Color(0xFFF9A825)
        "20%" -> Color(0xFFEF6C00); else -> Color(0xFFC62828)
    }
    Surface(color = color, shape = MaterialTheme.shapes.small) {
        Text(category, Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Picker(label: String, items: List<T>, selected: T?, text: (T) -> String, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }) {
        OutlinedTextField(selected?.let(text) ?: "", {}, readOnly = true, label = { Text(label) },
            modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(open, { open = false }) {
            items.forEach { DropdownMenuItem({ Text(text(it)) }, { onSelect(it); open = false }) }
        }
    }
}

/* ---------- FRONT END 1: classes grouped by program ---------- */
@Composable
fun HomeScreen(vm: AttendanceViewModel, onClass: (Int) -> Unit, onAdmin: () -> Unit) {
    val classes by vm.classes.collectAsState()
    Page("Attendance Summary", actions = {
        IconButton(onAdmin) { Icon(Icons.Default.Settings, "Admin") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (classes.isEmpty()) item { Text("No classes yet. Tap the gear icon to add a program and class.") }
            classes.groupBy { it.programName }.forEach { (program, list) ->
                item { Text(program, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
                items(list, key = { it.group.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onClass(c.group.id) }) {
                        Column(Modifier.padding(12.dp)) {
                            Text(c.label, fontWeight = FontWeight.SemiBold)
                            if (c.group.scholarship.isNotBlank()) Text("Scholarship: ${c.group.scholarship}")
                        }
                    }
                }
            }
        }
    }
}

/* ---------- FRONT END 2: student list for one class ---------- */
@Composable
fun SummaryScreen(vm: AttendanceViewModel, classId: Int, onTrainee: (Int) -> Unit, onBack: () -> Unit) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    val list by remember(classId) { vm.summary(classId) }.collectAsState(emptyList())
    Page(info?.programName ?: "Class", onBack) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                info?.let {
                    Text(it.label, style = MaterialTheme.typography.titleMedium)
                    if (it.group.scholarship.isNotBlank()) Text("Scholarship: ${it.group.scholarship}")
                }
            }
            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                Card(Modifier.fillMaxWidth().clickable { onTrainee(s.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(s.photoPath, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${s.lastName}, ${s.firstName}", fontWeight = FontWeight.Bold)
                            Text("Present: ${s.presentDays} / ${s.totalSessions} sessions")
                            Text("Attendance: ${"%.1f".format(s.percent)}%")
                        }
                        CategoryChip(s.category)
                    }
                }
            }
        }
    }
}

/* ---------- FRONT END 3: trainee details ---------- */
@Composable
fun TraineeDetailScreen(vm: AttendanceViewModel, id: Int, onEdit: () -> Unit, onBack: () -> Unit) {
    val t by remember(id) { vm.trainee(id) }.collectAsState(null)
    val rows by remember(id) { vm.subjectAttendance(id) }.collectAsState(emptyList())
    val info by remember(t?.classId) { t?.let { vm.classInfo(it.classId) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(null)

    Page(t?.let { "${it.firstName} ${it.lastName}" } ?: "Trainee", onBack,
        actions = { IconButton(onEdit) { Icon(Icons.Default.Edit, "Edit") } }) { pad ->
        val trainee = t ?: return@Page
        val present = rows.sumOf { it.presentDays }
        val total = rows.sumOf { it.sessions }
        val pct = if (total == 0) 0.0 else present * 100.0 / total

        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Avatar(trainee.photoPath, 120.dp) } }
            item { SectionTitle("Personal details") }
            item { Detail("Name", "${trainee.lastName}, ${trainee.firstName} ${trainee.middleName}") }
            item { Detail("Birthday", trainee.birthday) }
            item { Detail("Age", ageOf(trainee.birthday)?.toString()) }
            item { Detail("Email", trainee.email) }
            item { Detail("Phone", trainee.phone) }
            item { Detail("Address", trainee.address) }

            item { SectionTitle("Program") }
            item { Detail("Program", info?.programName) }
            item { Detail("Section", info?.group?.section) }
            item { Detail("Year", info?.group?.yearLevel?.let { "${ordinal(it)} Year" }) }
            item { Detail("Semester", info?.group?.semester?.let { "${ordinal(it)} Semester" }) }
            item { Detail("Scholarship", info?.group?.scholarship) }

            item { SectionTitle("Attendance") }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Overall: $present / $total  (${"%.1f".format(pct)}%)", Modifier.weight(1f),
                        fontWeight = FontWeight.Bold)
                    CategoryChip(categoryOf(pct))
                }
            }
            items(rows) { r -> Detail(r.subjectName, "${r.presentDays} / ${r.sessions}") }
        }
    }
}

@Composable fun SectionTitle(s: String) =
    Text(s, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

@Composable
fun Detail(label: String, value: String?) = Row(Modifier.fillMaxWidth()) {
    Text(label, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value?.takeIf { it.isNotBlank() } ?: "—", Modifier.weight(0.6f))
}
