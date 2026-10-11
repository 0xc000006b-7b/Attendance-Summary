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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One delete waiting out its undo window. */
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

    // Everything the UI sees hides the item that is waiting to be deleted.
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
    fun subjectAttendance(traineeId: Int) = dao.subjectAttendance(traineeId)
    fun attendanceFor(subjectId: Int) = dao.attendanceFor(subjectId)

    fun trainees(classId: Int) = combine(dao.trainees(classId), _pending) { list, p ->
        if (p is Pending.Trn) list.filterNot { it.id == p.trainee.id } else list
    }

    fun subjects(classId: Int) = combine(dao.subjects(classId), _pending) { list, p ->
        if (p is Pending.Subj) list.filterNot { it.id == p.subject.id } else list
    }

    fun summary(classId: Int) = combine(dao.summary(classId), _pending) { list, p ->
        if (p is Pending.Trn) list.filterNot { it.id == p.trainee.id } else list
    }

    /* ---------- add / update ---------- */
    fun addProgram(name: String) = viewModelScope.launch { dao.addProgram(Program(name = name.trim())) }
    fun addClass(c: ClassGroup) = viewModelScope.launch { dao.addClass(c) }
    fun addTrainee(t: Trainee) = viewModelScope.launch { dao.addTrainee(t) }
    fun addSubject(s: Subject) = viewModelScope.launch { dao.addSubject(s) }
    fun savePresent(traineeId: Int, subjectId: Int, days: Int) =
        viewModelScope.launch { dao.saveAttendance(Attendance(traineeId, subjectId, days)) }
    fun updateTrainee(t: Trainee) = viewModelScope.launch { dao.updateTrainee(t) }
    fun updateProgram(p: Program) = viewModelScope.launch { dao.updateProgram(p) }
    fun updateClass(c: ClassGroup) = viewModelScope.launch { dao.updateClass(c) }

    /* ---------- delete with undo ---------- */
    fun requestDelete(p: Pending) {
        pendingJob?.cancel()
        pendingJob = viewModelScope.launch {
            finishPending()            // commit an earlier delete that was still waiting
            _pending.value = p
            delay(UNDO_MS)
            finishPending()
        }
    }

    fun undoDelete() {
        pendingJob?.cancel()
        _pending.value = null
    }

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

    /* ---------- CSV / Excel import ---------- */
    suspend fun importCsv(classId: Int, data: CsvData): String = db.withTransaction {
        val known = dao.subjectsOnce(classId).toMutableList()
        val subjects = data.subjects.map { cs ->
            known.firstOrNull { it.name.equals(cs.name, ignoreCase = true) } ?: run {
                val id = dao.insertSubjectId(Subject(classId = classId, name = cs.name, sessions = cs.sessions)).toInt()
                Subject(id, classId, cs.name, cs.sessions).also { known.add(it) }
            }
        }
        val existing = dao.traineesOnce(classId)
        var added = 0; var updated = 0
        data.trainees.forEach { ct ->
            val match = existing.firstOrNull {
                it.lastName.equals(ct.last, true) && it.firstName.equals(ct.first, true)
            }
            val id = if (match != null) {
                dao.updateTrainee(match.copy(
                    middleName = ct.middle.ifBlank { match.middleName },
                    birthday = ct.birthday ?: match.birthday, email = ct.email ?: match.email,
                    phone = ct.phone ?: match.phone, address = ct.address ?: match.address))
                updated++; match.id
            } else {
                added++
                dao.insertTraineeId(Trainee(classId = classId, lastName = ct.last.uppercase(),
                    firstName = ct.first.uppercase(), middleName = ct.middle.uppercase(),
                    birthday = ct.birthday, email = ct.email, phone = ct.phone, address = ct.address)).toInt()
            }
            ct.present.forEachIndexed { i, p ->
                if (p != null) dao.saveAttendance(Attendance(id, subjects[i].id, p.coerceIn(0, subjects[i].sessions)))
            }
        }
        "Done: $added new and $updated updated trainees, ${subjects.size} subjects."
    }
}
