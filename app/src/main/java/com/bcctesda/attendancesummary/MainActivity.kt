package com.bcctesda.attendancesummary

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
            val id = it.arguments!!.getInt("classId")
            SummaryScreen(vm, id, { t -> nav.navigate("trainee/$t") }, { nav.navigate("tsf/$id") }) { nav.popBackStack() }
        }
        composable("tsf/{classId}", intArg("classId")) {
            TranchesScreen(vm, it.arguments!!.getInt("classId")) { nav.popBackStack() }
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
        OutlinedTextField(selected?.let(text) ?: "", {}, readOnly = true, label = @Composable { Text(label) },
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
                            if (c.period.isNotBlank()) Text(c.period)
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
fun SummaryScreen(vm: AttendanceViewModel, classId: Int, onTrainee: (Int) -> Unit, onTsf: () -> Unit, onBack: () -> Unit) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    val list by remember(classId) { vm.summary(classId) }.collectAsState()
    Page(info?.programName ?: "Class", onBack,
        actions = { IconButton(onTsf) { Icon(Icons.Default.AttachMoney, "Training support fund") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column {
                    info?.let {
                        Text(it.label, style = MaterialTheme.typography.titleMedium)
                        if (it.period.isNotBlank()) Text(it.period)
                        if (it.group.scholarship.isNotBlank()) Text("Scholarship: ${it.group.scholarship}")
                    }
                    list.firstOrNull()?.let { Text("${it.totalDays} training days • ${it.requiredHours} required hours") }
                }
            }
            itemsIndexed(list, key = { _, s -> s.id }) { i, s ->
                Card(Modifier.fillMaxWidth().clickable { onTrainee(s.id) }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(s.photoPath, 52.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${s.lastName}, ${s.firstName}", fontWeight = FontWeight.Bold)
                            Text("Present: ${s.presentDays} / ${s.totalDays} days")
                            Text("Hours: ${s.totalHours} / ${s.requiredHours}")
                            Text("Attendance: ${"%.1f".format(s.percent)}%")
                        }
                        CategoryChip(s.category)
                    }
                }
            }
        }
    }
}

/* ---------- FRONT END 3: training support fund (tranches) ---------- */
@Composable
fun TranchesScreen(vm: AttendanceViewModel, classId: Int, onBack: () -> Unit) {
    val info by remember(classId) { vm.classInfo(classId) }.collectAsState(null)
    val days by remember(classId) { vm.days(classId) }.collectAsState()
    val trainees by remember(classId) { vm.trainees(classId) }.collectAsState()
    val presence by remember(classId) { vm.presence(classId) }.collectAsState(emptyList())
    var chosen by rememberSaveable { mutableIntStateOf(1) }

    Page("Training support fund", onBack) { pad ->
        val g = info?.group ?: return@Page
        val defs = trancheDefs(g)
        val def = defs.first { it.number == chosen }
        val byTrainee = remember(presence) { presence.groupBy({ it.traineeId }, { it.hours > 0 }) }
        val gross = trancheGross(g, def)
        val lines = trainees.map {
            TrancheLine(it, trancheOf(byTrainee[it.id].orEmpty(), def), gross, def.days, g.dailyRate)
        }
        val covered = days.drop(def.fromDay - 1).take(def.days)

        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    info?.let { Text("${it.programName} • ${it.label}", style = MaterialTheme.typography.titleMedium) }
                    if (g.scholarship.isNotBlank()) Text("Scholarship: ${g.scholarship}")
                    if (g.qualification.isNotBlank()) Text(g.qualification, style = MaterialTheme.typography.bodySmall)
                    if (g.rqmNumber.isNotBlank()) Text("RQM: ${g.rqmNumber}", style = MaterialTheme.typography.bodySmall)
                    Text("TSF per scholar: ${peso(totalTsf(g))} (${g.semesterDays} days × ${peso(g.dailyRate)})",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        defs.forEach { d ->
                            FilterChip(selected = chosen == d.number, onClick = { chosen = d.number },
                                label = { Text("${ordinal(d.number)} • ${d.sharePct}%") })
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${ordinal(def.number)} tranche • ${def.sharePct}% of the TSF (${peso(gross)})", fontWeight = FontWeight.Bold)
                        Text("Released once ${def.milestonePct}% of the training is completed (training day ${def.toDay} of ${g.semesterDays})")
                        Text("Absences counted on training days ${def.fromDay}–${def.toDay}: ${def.days} days (${def.days * g.hoursPerDay} hours)",
                            style = MaterialTheme.typography.bodySmall)
                        if (covered.isNotEmpty()) Text(fmtLongRange(covered.first().date, covered.last().date),
                            style = MaterialTheme.typography.bodySmall)
                        Text(if (covered.size < def.days) "Not yet due: ${covered.size} of ${def.days} days recorded"
                             else "Due: all ${def.days} days recorded", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                val ctx = LocalContext.current
                fun build() = runCatching {
                    PayrollPdf.create(ctx, g, info?.programName ?: "", def, covered, lines)
                }.onFailure {
                    Toast.makeText(ctx, "Couldn't create the PDF: ${it.message}", Toast.LENGTH_LONG).show()
                }.getOrNull()

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ build()?.let { PayrollPdf.share(ctx, it) } }) { Text("Share / save PDF") }
                        OutlinedButton({ build()?.let { PayrollPdf.print(ctx, it) } }) { Text("Print") }
                    }
                    if (g.qualification.isBlank() || g.rqmNumber.isBlank() || g.trainingProvider.isBlank())
                        Text("Tip: add the qualification, RQM number and training provider (Admin > edit class) so they appear on the payroll.",
                            style = MaterialTheme.typography.bodySmall)
                }
            }
            itemsIndexed(lines, key = { _, l -> l.trainee.id }) { i, l ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar(l.trainee.photoPath, 40.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${i + 1}. ${l.trainee.lastName}, ${l.trainee.firstName}", fontWeight = FontWeight.Bold)
                            Text("Present ${l.present} / ${l.trancheDays} days", style = MaterialTheme.typography.bodySmall)
                            Text("Allowance ${peso(l.allowance)} − absences ${peso(l.absences)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(peso(l.toClaim), fontWeight = FontWeight.Bold)
                            Text("to claim", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("TOTAL", fontWeight = FontWeight.Bold)
                        Text("Allowance: ${peso(lines.sumOf { it.allowance })}")
                        Text("Absences: ${peso(lines.sumOf { it.absences })}")
                        Text("To be claimed / net amount: ${peso(lines.sumOf { it.toClaim })}", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/* ---------- FRONT END 4: trainee details ---------- */
@Composable
fun TraineeDetailScreen(vm: AttendanceViewModel, id: Int, onEdit: () -> Unit, onBack: () -> Unit) {
    val t by remember(id) { vm.trainee(id) }.collectAsState(null)
    val log by remember(id) { vm.dayLog(id) }.collectAsState(emptyList())
    val info by remember(t?.classId) { t?.let { vm.classInfo(it.classId) } ?: kotlinx.coroutines.flow.flowOf(null) }
        .collectAsState(null)

    Page(t?.let { "${it.firstName} ${it.lastName}" } ?: "Trainee", onBack,
        actions = { IconButton(onEdit) { Icon(Icons.Default.Edit, "Edit") } }) { pad ->
        val trainee = t ?: return@Page
        val hoursPerDay = info?.group?.hoursPerDay ?: 8
        val presentDays = log.count { (it.hours ?: 0) > 0 }
        val totalHours = log.sumOf { it.hours ?: 0 }
        val pct = if (log.isEmpty()) 0.0 else presentDays * 100.0 / log.size

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
                    Column(Modifier.weight(1f)) {
                        Text("Present: $presentDays / ${log.size} days", fontWeight = FontWeight.Bold)
                        Text("Hours: $totalHours / ${log.size * hoursPerDay}")
                        Text("Attendance: ${"%.1f".format(pct)}%")
                    }
                    CategoryChip(categoryOf(pct))
                }
            }
            item { SectionTitle("Day by day") }
            items(log, key = { it.date }) { l ->
                Detail(fmtDay(l.date), when {
                    l.hours == null -> "—"
                    l.hours == 0 -> "Absent"
                    else -> "Present • ${l.hours} hrs"
                })
            }

            info?.group?.let { g ->
                val presentByDay = log.map { (it.hours ?: 0) > 0 }
                val rows = trancheDefs(g).map { d ->
                    val r = trancheOf(presentByDay, d)
                    val gross = trancheGross(g, d)
                    val net = (gross - (r.recorded - r.present) * g.dailyRate).coerceAtLeast(0)
                    Triple(d, r, gross to net)
                }
                item { SectionTitle("Training support fund") }
                rows.forEach { (d, r, amt) ->
                    item {
                        Detail("${ordinal(d.number)} tranche (${d.sharePct}% at day ${d.toDay})",
                            "${peso(amt.second)} of ${peso(amt.first)}" +
                                if (r.recorded < d.days) "\nnot yet due: ${r.recorded} of ${d.days} days recorded" else "")
                    }
                }
                item {
                    Text("Due so far: ${peso(rows.filter { (d, r, _) -> r.recorded >= d.days }.sumOf { it.third.second })} of ${peso(totalTsf(g))}",
                        fontWeight = FontWeight.Bold)
                }
            }
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
