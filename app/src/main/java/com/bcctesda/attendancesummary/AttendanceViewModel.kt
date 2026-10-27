package com.bcctesda.attendancesummary

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.bcctesda.attendancesummary.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

sealed interface Pending {
    val label: String
    data class Prog(val program: Program) : Pending { override val label get() = "Deleted ${program.name}" }
    data class Cls(val info: ClassInfo) : Pending {
        override val label get() = "Deleted ${info.programName} – Section ${info.group.section}"
    }
    data class Trn(val trainee: Trainee) : Pending { override val label get() = "Deleted ${trainee.firstName} ${trainee.lastName}" }
    data class Subj(val subject: Subject) : Pending { override val label get() = "Deleted ${subject.name}" }
}

private const val UNDO_MS = 5000L

class AttendanceViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDb.get(app)
    private val dao = db.dao()

    private val _pending = MutableStateFlow<Pending?>(null)
    val pending = _pending.asStateFlow()
    private var pendingJob: Job? = null

    val programs = combine(dao.programs(), _pending) { list, p ->
        if (p is Pending.Prog) list.filterNot { it.id == p.program.id } else list
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val classes = combine(dao.classes(), _pending) { list, p ->
        when (p) {
            is Pending.Cls -> list.filterNot { it.group.id == p.info.group.id }
            is Pending.Prog -> list.filterNot { it.group.programId == p.program.id }
            else -> list
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    fun classInfo(id: Int) = dao.classInfo(id)
    fun trainee(id: Int) = dao.trainee(id)
    fun dayLog(traineeId: Int) = dao.dayLog(traineeId)
    fun attendanceOn(classId: Int, date: String) = dao.attendanceOn(classId, date)
    fun presence(classId: Int) = dao.presence(classId)

    // cached so lists are full the moment you come back (keeps scroll position)
    private val traineesCache = HashMap<Int, StateFlow<List<Trainee>>>()
    private val summaryCache = HashMap<Int, StateFlow<List<TraineeSummary>>>()
    private val daysCache = HashMap<Int, StateFlow<List<TrainingDay>>>()
    private val subjectsCache = HashMap<Int, StateFlow<List<Subject>>>()

    fun trainees(classId: Int): StateFlow<List<Trainee>> = traineesCache.getOrPut(classId) {
        combine(dao.trainees(classId), _pending) { list, p ->
            if (p is Pending.Trn) list.filterNot { it.id == p.trainee.id } else list
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    fun summary(classId: Int): StateFlow<List<TraineeSummary>> = summaryCache.getOrPut(classId) {
        combine(dao.summary(classId), _pending) { list, p ->
            if (p is Pending.Trn) list.filterNot { it.id == p.trainee.id } else list
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    fun days(classId: Int): StateFlow<List<TrainingDay>> = daysCache.getOrPut(classId) {
        dao.days(classId).stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    fun subjects(classId: Int): StateFlow<List<Subject>> = subjectsCache.getOrPut(classId) {
        combine(dao.subjects(classId), _pending) { list, p ->
            if (p is Pending.Subj) list.filterNot { it.id == p.subject.id } else list
        }.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    }

    fun sessions(subjectId: Int) = dao.sessions(subjectId)
    fun absencesOn(subjectId: Int, date: String) = dao.absencesOn(subjectId, date)
    fun absenceLog(traineeId: Int) = dao.absenceLog(traineeId)

    /* ---------- add / update ---------- */
    fun addProgram(name: String) = viewModelScope.launch { dao.addProgram(Program(name = name.trim())) }
    fun addTrainee(t: Trainee) = viewModelScope.launch { dao.addTrainee(t) }
    fun updateTrainee(t: Trainee) = viewModelScope.launch { dao.updateTrainee(t) }
    fun updateProgram(p: Program) = viewModelScope.launch { dao.updateProgram(p) }
    fun updateClass(c: ClassGroup) = viewModelScope.launch { dao.updateClass(c) }

    fun addSubject(s: Subject) = viewModelScope.launch { dao.insertSubjectId(s) }

    fun updateSubject(s: Subject) = viewModelScope.launch {
        dao.updateSubject(s)
        dao.capAbsences(s.id, s.hoursPerSession)     // absences can't exceed the session length
    }

    /** Adds a session every 7 days from first to last (same weekday). */
    fun addWeeklySessions(subjectId: Int, first: LocalDate, last: LocalDate) = viewModelScope.launch {
        dao.addSessions(generateSequence(first) { it.plusDays(7) }.takeWhile { !it.isAfter(last) }
            .map { SubjectSession(subjectId, it.toString()) }.toList())
    }

    fun removeSession(subjectId: Int, date: String) = viewModelScope.launch {
        db.withTransaction { dao.deleteAbsencesOn(subjectId, date); dao.deleteSession(subjectId, date) }
    }

    /** hours = null (or 0) clears the mark, meaning present. */
    fun setAbsence(traineeId: Int, subjectId: Int, date: String, hours: Int?) = viewModelScope.launch {
        if (hours == null || hours <= 0) dao.clearAbsence(traineeId, subjectId, date)
        else dao.saveAbsence(SubjectAbsence(traineeId, subjectId, date, hours))
    }

    private suspend fun generateDays(classId: Int, start: String, end: String) {
        val s = start.toDateOrNull() ?: return
        val e = end.toDateOrNull() ?: return
        dao.addDays(trainingDatesBetween(s, e).map { TrainingDay(classId, it.toString()) })
    }

    fun addClass(c: ClassGroup) = viewModelScope.launch {
        val id = dao.insertClassId(c).toInt()
        generateDays(id, c.startDate, c.endDate)
    }

    /** Adds any missing Mon–Sat days between the class's start and end date. */
    fun fillDays(c: ClassGroup) = viewModelScope.launch { generateDays(c.id, c.startDate, c.endDate) }

    fun addDay(classId: Int, date: String) = viewModelScope.launch { dao.addDays(listOf(TrainingDay(classId, date))) }

    fun removeDay(classId: Int, date: String) = viewModelScope.launch {
        db.withTransaction { dao.deleteAttendanceOn(classId, date); dao.deleteDay(classId, date) }
    }

    /** hours = null clears the mark; 0 = absent; >0 = present. */
    fun setAttendance(traineeId: Int, date: String, hours: Int?) = viewModelScope.launch {
        if (hours == null) dao.clearAttendance(traineeId, date)
        else dao.saveAttendance(DailyAttendance(traineeId, date, hours))
    }

    fun markAll(traineeIds: List<Int>, date: String, hours: Int) = viewModelScope.launch {
        dao.saveAttendanceBulk(traineeIds.map { DailyAttendance(it, date, hours) })
    }

    fun clearDay(classId: Int, date: String) = viewModelScope.launch { dao.deleteAttendanceOn(classId, date) }

    /* ---------- delete with undo ---------- */
    fun requestDelete(p: Pending) {
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            finishPending()
            _pending.value = p
            delay(UNDO_MS)
            finishPending()
        }
    }

    fun undoDelete() { pendingJob?.cancel(); _pending.value = null }

    private suspend fun finishPending() {
        val p = _pending.value ?: return
        withContext(NonCancellable) {
            when (p) {
                is Pending.Prog -> {
                    val photos = dao.photoPathsForProgram(p.program.id)
                    dao.deleteProgram(p.program)
                    photos.forEach { runCatching { File(it).delete() } }
                }
                is Pending.Cls -> {
                    val photos = dao.photoPathsForClass(p.info.group.id)
                    dao.deleteClass(p.info.group)
                    photos.forEach { runCatching { File(it).delete() } }
                }
                is Pending.Trn -> {
                    dao.deleteTrainee(p.trainee)
                    p.trainee.photoPath?.let { runCatching { File(it).delete() } }
                }
                is Pending.Subj -> dao.deleteSubject(p.subject)
            }
        }
        _pending.value = null
    }

    /* ---------- Excel / CSV import ---------- */
    suspend fun importAttendance(classId: Int, data: ImportData): String = db.withTransaction {
        dao.addDays(data.dates.map { TrainingDay(classId, it.toString()) })
        dao.classOnce(classId)?.let { c ->
            if (c.startDate.isBlank() || c.endDate.isBlank())
                dao.updateClass(c.copy(startDate = data.dates.min().toString(), endDate = data.dates.max().toString()))
        }
        val existing = dao.traineesOnce(classId)
        var added = 0; var updated = 0
        data.trainees.forEach { it ->
            val match = existing.firstOrNull { e ->
                e.lastName.equals(it.last, true) && e.firstName.equals(it.first, true)
            }
            val id = if (match != null) {
                if (it.middle.isNotBlank() && match.middleName.isBlank()) dao.updateTrainee(match.copy(middleName = it.middle.uppercase()))
                updated++; match.id
            } else {
                added++
                dao.insertTraineeId(Trainee(classId = classId, lastName = it.last.uppercase(),
                    firstName = it.first.uppercase(), middleName = it.middle.uppercase())).toInt()
            }
            dao.saveAttendanceBulk(it.marks.mapIndexedNotNull { i, h ->
                h?.let { hrs -> DailyAttendance(id, data.dates[i].toString(), hrs) }
            })
        }
        "Done: $added new and $updated existing trainees, ${data.dates.size} training days."
    }

    suspend fun importSubjects(classId: Int, list: List<SubjectImport>): String = db.withTransaction {
        val subjects = dao.subjectsOnce(classId).toMutableList()
        val trainees = dao.traineesOnce(classId).toMutableList()
        var newTrainees = 0; var entries = 0
        list.forEach { si ->
            val subject = subjects.firstOrNull { it.name.equals(si.name, true) } ?: run {
                val s = Subject(classId = classId, name = si.name, units = si.units, hoursPerSession = si.hoursPerSession)
                s.copy(id = dao.insertSubjectId(s).toInt()).also { subjects.add(it) }
            }
            val dates = si.dates.map { it.toString() }
            dao.addSessions(dates.map { SubjectSession(subject.id, it) })
            dao.deleteAbsencesOnDates(subject.id, dates)
            val rows = mutableListOf<SubjectAbsence>()
            si.rows.forEach { r ->
                val trainee = trainees.firstOrNull {
                    it.lastName.equals(r.last, true) && it.firstName.equals(r.first, true)
                } ?: run {
                    val t = Trainee(classId = classId, lastName = r.last.uppercase(),
                        firstName = r.first.uppercase(), middleName = r.middle.uppercase())
                    newTrainees++
                    t.copy(id = dao.insertTraineeId(t).toInt()).also { trainees.add(it) }
                }
                r.hours.forEachIndexed { i, h -> if (h > 0) rows.add(SubjectAbsence(trainee.id, subject.id, dates[i], h)) }
            }
            dao.saveAbsences(rows); entries += rows.size
        }
        "Done: ${list.size} subjects, $entries absence entries, $newTrainees new trainees."
    }
}
