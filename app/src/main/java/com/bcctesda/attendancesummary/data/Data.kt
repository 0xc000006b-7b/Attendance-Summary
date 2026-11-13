package com.bcctesda.attendancesummary.data

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Period
import java.time.format.DateTimeFormatter
import java.util.Locale

/* =====================================================================
   ENTITIES
   ===================================================================== */

@Entity
data class Program(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String                                   // e.g. "Automotive Technology"
)

/** One class = program + section + year + semester (+ scholarship, training period, TSF settings). */
@Entity(
    foreignKeys = [ForeignKey(Program::class, ["id"], ["programId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("programId")]
)
data class ClassGroup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val programId: Int,
    val section: String,
    val yearLevel: Int,
    val semester: Int,
    val scholarship: String = "",
    val startDate: String = "",                        // yyyy-MM-dd (training period)
    val endDate: String = "",
    val hoursPerDay: Int = 8,
    // Training support fund (TSF) -- TESDA Advisory No. 20-OE s.2026
    val semesterDays: Int = 100,                       // nominal training duration in days
    val dailyRate: Int = 100,                          // pesos per training day
    val share1Pct: Int = 20,                           // % of the TSF each tranche pays
    val share2Pct: Int = 40,
    val share3Pct: Int = 40,
    val tranche1Pct: Int = 20,                         // % of the training completed before release
    val tranche2Pct: Int = 50,
    val tranche3Pct: Int = 80,
    // payroll header details
    val qualification: String = "",
    val rqmNumber: String = "",
    val trainingProvider: String = "",
    val tesdaOffice: String = "TESDA Provincial Office"
)

data class ClassInfo(@Embedded val group: ClassGroup, val programName: String) {
    val label get() = "Section ${group.section} • ${ordinal(group.yearLevel)} Year • ${ordinal(group.semester)} Sem"
    val period get() = fmtRange(group.startDate, group.endDate)
}

@Entity(
    foreignKeys = [ForeignKey(ClassGroup::class, ["id"], ["classId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("classId")]
)
data class Trainee(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val classId: Int,
    val lastName: String,
    val firstName: String,
    val middleName: String = "",
    val birthday: String? = null,                      // yyyy-MM-dd, optional
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val photoPath: String? = null
)

/* ---------- daily (TESDA training) attendance ---------- */

/** A date this class meets (one date column in the daily sheet). */
@Entity(
    primaryKeys = ["classId", "date"],
    foreignKeys = [ForeignKey(ClassGroup::class, ["id"], ["classId"], onDelete = ForeignKey.CASCADE)]
)
data class TrainingDay(val classId: Int, val date: String)

/** hours > 0 = present (8 = 8 hours), hours = 0 = absent. No row = not marked. */
@Entity(
    primaryKeys = ["traineeId", "date"],
    foreignKeys = [ForeignKey(Trainee::class, ["id"], ["traineeId"], onDelete = ForeignKey.CASCADE)]
)
data class DailyAttendance(val traineeId: Int, val date: String, val hours: Int)

/* ---------- school subjects: absences in hours ---------- */

@Entity(
    foreignKeys = [ForeignKey(ClassGroup::class, ["id"], ["classId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("classId")]
)
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val classId: Int,
    val name: String,
    val units: Int = 3,
    val hoursPerSession: Int = 3,
    val instructorPre: String = "",                    // Engr., Dr., Mr., Ms.
    val instructorFirst: String = "",
    val instructorMiddle: String = "",
    val instructorLast: String = "",
    val instructorSuffix: String = "",                 // Jr., Sr., III
    val instructorPost: String = ""                    // LPT, MAT, PhD
)

/** A date this subject meets (one date column on its sheet). */
@Entity(
    primaryKeys = ["subjectId", "date"],
    foreignKeys = [ForeignKey(Subject::class, ["id"], ["subjectId"], onDelete = ForeignKey.CASCADE)]
)
data class SubjectSession(val subjectId: Int, val date: String)

/** Hours missed in one subject session. No row = present. */
@Entity(
    primaryKeys = ["traineeId", "subjectId", "date"],
    foreignKeys = [
        ForeignKey(Trainee::class, ["id"], ["traineeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Subject::class, ["id"], ["subjectId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("subjectId")]
)
data class SubjectAbsence(val traineeId: Int, val subjectId: Int, val date: String, val hours: Int)

/* =====================================================================
   QUERY RESULT CLASSES
   ===================================================================== */

data class DayLog(val date: String, val hours: Int?)
data class PresenceRow(val traineeId: Int, val date: String, val hours: Int)
data class AbsenceRow(val subjectName: String, val date: String, val hours: Int)

data class TraineeSummary(
    val id: Int, val lastName: String, val firstName: String, val photoPath: String?,
    val presentDays: Int, val totalHours: Int, val totalDays: Int, val hoursPerDay: Int,
    val absentHours: Int = 0
) {
    val requiredHours get() = totalDays * hoursPerDay
    val percent get() = if (totalDays == 0) 0.0 else presentDays * 100.0 / totalDays
    val category get() = categoryOf(percent)
    val absentDays get() = if (hoursPerDay == 0) 0.0 else absentHours.toDouble() / hoursPerDay
    val wholeAbsentDays get() = if (hoursPerDay == 0) 0 else absentHours / hoursPerDay
}

/* =====================================================================
   HELPERS
   ===================================================================== */

fun categoryOf(p: Double) = when {
    p >= 80 -> "80%"
    p >= 50 -> "50%"
    p >= 20 -> "20%"
    else -> "Below 20%"
}

fun ordinal(n: Int) = when (n) { 1 -> "1st"; 2 -> "2nd"; 3 -> "3rd"; else -> "${n}th" }

fun ageOf(birthday: String?): Int? = try {
    birthday?.let { Period.between(LocalDate.parse(it), LocalDate.now()).years }
} catch (e: Exception) { null }

fun String.toDateOrNull(): LocalDate? = runCatching { LocalDate.parse(this) }.getOrNull()

fun fmtDay(d: String): String = d.toDateOrNull()?.format(DateTimeFormatter.ofPattern("EEE, MMM d")) ?: d

fun fmtRange(start: String, end: String): String {
    val s = start.toDateOrNull() ?: return ""
    val e = end.toDateOrNull() ?: return ""
    return "${s.format(DateTimeFormatter.ofPattern("MMM d"))} – ${e.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))}"
}

fun fmtLongRange(a: String, b: String): String {
    val f = DateTimeFormatter.ofPattern("MMMM d, yyyy")
    val x = a.toDateOrNull() ?: return ""
    val y = b.toDateOrNull() ?: return ""
    return "${x.format(f)} TO ${y.format(f)}".uppercase()
}

/** Every date from start to end, skipping Sundays. */
fun trainingDatesBetween(start: LocalDate, end: LocalDate): List<LocalDate> =
    generateSequence(start) { it.plusDays(1) }
        .takeWhile { !it.isAfter(end) }
        .filter { it.dayOfWeek != DayOfWeek.SUNDAY }
        .toList()

fun fmtDays(d: Double): String = "%.2f".format(Locale.US, d).trimEnd('0').trimEnd('.')

fun peso(n: Int) = "₱" + String.format(Locale.US, "%,d", n)

/** "Engr. Juan D. Dela Cruz Jr., LPT" or null when no instructor was entered. */
fun Subject.instructorDisplay(): String? {
    val first = instructorFirst.trim(); val last = instructorLast.trim()
    if (first.isEmpty() && last.isEmpty()) return null
    val mid = instructorMiddle.trim().firstOrNull()?.let { "${it.uppercaseChar()}." }
    val name = listOfNotNull(
        instructorPre.trim().ifEmpty { null }, first.ifEmpty { null }, mid,
        last.ifEmpty { null }, instructorSuffix.trim().ifEmpty { null }
    ).joinToString(" ")
    val post = instructorPost.trim().trim(',').trim()
    return if (post.isEmpty()) name else "$name, $post"
}

/* =====================================================================
   TRAINING SUPPORT FUND (TSF) -- TESDA Advisory No. 20-OE, s.2026
   Programs over 2 months: 1st tranche 20% of TSF at 20% of the training,
   2nd 40% at 50%, 3rd 40% at 80%. Absences are deducted per tranche period.
   ===================================================================== */

data class TrancheDef(
    val number: Int,         // 1, 2, 3 (only tranches that pay something)
    val sharePct: Int,       // % of the total TSF this tranche pays
    val milestonePct: Int,   // released once this % of the training is completed
    val fromDay: Int,        // absences are counted from this training day...
    val toDay: Int           // ...up to the release day
) {
    val days get() = (toDay - fromDay + 1).coerceAtLeast(0)
}

fun trancheDefs(g: ClassGroup): List<TrancheDef> {
    val plan = listOf(g.share1Pct to g.tranche1Pct, g.share2Pct to g.tranche2Pct, g.share3Pct to g.tranche3Pct)
        .filter { it.first > 0 }
    var prev = 0
    return plan.mapIndexed { i, (share, pct) ->
        val cut = Math.round(g.semesterDays * pct / 100.0).toInt()
        TrancheDef(i + 1, share, pct, prev + 1, cut).also { prev = cut }
    }
}

fun totalTsf(g: ClassGroup) = g.semesterDays * g.dailyRate
fun trancheGross(g: ClassGroup, d: TrancheDef) = Math.round(totalTsf(g) * d.sharePct / 100.0).toInt()

data class TrancheResult(val present: Int, val recorded: Int)

/** presentByDay = one entry per training day, in date order. */
fun trancheOf(presentByDay: List<Boolean>, def: TrancheDef): TrancheResult {
    val slice = presentByDay.drop(def.fromDay - 1).take(def.days)
    return TrancheResult(slice.count { it }, slice.size)
}

data class TrancheLine(val trainee: Trainee, val result: TrancheResult, val gross: Int, val trancheDays: Int, val rate: Int) {
    val present get() = result.present
    val allowance get() = gross
    val absences get() = (result.recorded - result.present) * rate      // recorded days without attendance
    val toClaim get() = (gross - absences).coerceAtLeast(0)
}

/* =====================================================================
   DAO
   ===================================================================== */

@Dao
interface AttendanceDao {
    // ----- inserts / updates / deletes
    @Insert suspend fun addProgram(p: Program)
    @Insert suspend fun insertClassId(c: ClassGroup): Long
    @Insert suspend fun addTrainee(t: Trainee)
    @Insert suspend fun insertTraineeId(t: Trainee): Long
    @Insert suspend fun insertSubjectId(s: Subject): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addDays(days: List<TrainingDay>)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun addSessions(list: List<SubjectSession>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAttendance(a: DailyAttendance)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAttendanceBulk(list: List<DailyAttendance>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAbsence(a: SubjectAbsence)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAbsences(list: List<SubjectAbsence>)

    @Update suspend fun updateProgram(p: Program)
    @Update suspend fun updateClass(c: ClassGroup)
    @Update suspend fun updateTrainee(t: Trainee)
    @Update suspend fun updateSubject(s: Subject)

    @Delete suspend fun deleteProgram(p: Program)
    @Delete suspend fun deleteClass(c: ClassGroup)
    @Delete suspend fun deleteTrainee(t: Trainee)
    @Delete suspend fun deleteSubject(s: Subject)

    @Query("DELETE FROM TrainingDay WHERE classId = :classId AND date = :date")
    suspend fun deleteDay(classId: Int, date: String)

    @Query("DELETE FROM DailyAttendance WHERE traineeId = :traineeId AND date = :date")
    suspend fun clearAttendance(traineeId: Int, date: String)

    @Query("DELETE FROM DailyAttendance WHERE date = :date AND traineeId IN (SELECT id FROM Trainee WHERE classId = :classId)")
    suspend fun deleteAttendanceOn(classId: Int, date: String)

    @Query("DELETE FROM SubjectAbsence WHERE traineeId = :traineeId AND subjectId = :subjectId AND date = :date")
    suspend fun clearAbsence(traineeId: Int, subjectId: Int, date: String)

    @Query("DELETE FROM SubjectAbsence WHERE subjectId = :subjectId AND date = :date")
    suspend fun deleteAbsencesOn(subjectId: Int, date: String)

    @Query("DELETE FROM SubjectAbsence WHERE subjectId = :subjectId AND date IN (:dates)")
    suspend fun deleteAbsencesOnDates(subjectId: Int, dates: List<String>)

    @Query("DELETE FROM SubjectSession WHERE subjectId = :subjectId AND date = :date")
    suspend fun deleteSession(subjectId: Int, date: String)

    @Query("UPDATE SubjectAbsence SET hours = :max WHERE subjectId = :subjectId AND hours > :max")
    suspend fun capAbsences(subjectId: Int, max: Int)

    // ----- programs, classes
    @Query("SELECT * FROM Program ORDER BY name") fun programs(): Flow<List<Program>>

    @Query("""SELECT c.*, p.name AS programName FROM ClassGroup c JOIN Program p ON p.id = c.programId
              ORDER BY p.name, c.yearLevel, c.semester, c.section""")
    fun classes(): Flow<List<ClassInfo>>

    @Query("""SELECT c.*, p.name AS programName FROM ClassGroup c JOIN Program p ON p.id = c.programId
              WHERE c.id = :id""")
    fun classInfo(id: Int): Flow<ClassInfo?>

    @Query("SELECT * FROM ClassGroup WHERE id = :id") suspend fun classOnce(id: Int): ClassGroup?

    // ----- trainees
    @Query("SELECT * FROM Trainee WHERE classId = :classId ORDER BY lastName, firstName")
    fun trainees(classId: Int): Flow<List<Trainee>>

    @Query("SELECT * FROM Trainee WHERE classId = :classId") suspend fun traineesOnce(classId: Int): List<Trainee>
    @Query("SELECT * FROM Trainee WHERE id = :id") fun trainee(id: Int): Flow<Trainee?>

    @Query("SELECT photoPath FROM Trainee WHERE classId = :classId AND photoPath IS NOT NULL")
    suspend fun photoPathsForClass(classId: Int): List<String>

    @Query("""SELECT t.photoPath FROM Trainee t JOIN ClassGroup c ON c.id = t.classId
              WHERE c.programId = :programId AND t.photoPath IS NOT NULL""")
    suspend fun photoPathsForProgram(programId: Int): List<String>

    // ----- daily attendance
    @Query("SELECT * FROM TrainingDay WHERE classId = :classId ORDER BY date")
    fun days(classId: Int): Flow<List<TrainingDay>>

    @Query("""SELECT a.* FROM DailyAttendance a JOIN Trainee t ON t.id = a.traineeId
              WHERE t.classId = :classId AND a.date = :date""")
    fun attendanceOn(classId: Int, date: String): Flow<List<DailyAttendance>>

    @Query("""
        SELECT d.date AS date, a.hours AS hours
        FROM TrainingDay d JOIN Trainee t ON t.classId = d.classId
        LEFT JOIN DailyAttendance a ON a.traineeId = t.id AND a.date = d.date
        WHERE t.id = :traineeId ORDER BY d.date
    """)
    fun dayLog(traineeId: Int): Flow<List<DayLog>>

    @Query("""
        SELECT t.id AS traineeId, d.date AS date, COALESCE(a.hours, 0) AS hours
        FROM Trainee t JOIN TrainingDay d ON d.classId = t.classId
        LEFT JOIN DailyAttendance a ON a.traineeId = t.id AND a.date = d.date
        WHERE t.classId = :classId ORDER BY t.id, d.date
    """)
    fun presence(classId: Int): Flow<List<PresenceRow>>

    // ----- subjects
    @Query("SELECT * FROM Subject WHERE classId = :classId ORDER BY id") fun subjects(classId: Int): Flow<List<Subject>>
    @Query("SELECT * FROM Subject WHERE classId = :classId") suspend fun subjectsOnce(classId: Int): List<Subject>

    @Query("SELECT * FROM SubjectSession WHERE subjectId = :subjectId ORDER BY date")
    fun sessions(subjectId: Int): Flow<List<SubjectSession>>

    @Query("SELECT * FROM SubjectAbsence WHERE subjectId = :subjectId AND date = :date")
    fun absencesOn(subjectId: Int, date: String): Flow<List<SubjectAbsence>>

    @Query("""
        SELECT s.name AS subjectName, x.date AS date, x.hours AS hours
        FROM SubjectAbsence x JOIN Subject s ON s.id = x.subjectId
        JOIN SubjectSession ss ON ss.subjectId = x.subjectId AND ss.date = x.date
        WHERE x.traineeId = :traineeId ORDER BY x.date, s.name
    """)
    fun absenceLog(traineeId: Int): Flow<List<AbsenceRow>>

    // ----- class summary
    @Query("""
        SELECT t.id, t.lastName, t.firstName, t.photoPath AS photoPath,
          (SELECT COUNT(*) FROM DailyAttendance a JOIN TrainingDay d ON d.classId = t.classId AND d.date = a.date
             WHERE a.traineeId = t.id AND a.hours > 0) AS presentDays,
          COALESCE((SELECT SUM(a.hours) FROM DailyAttendance a JOIN TrainingDay d ON d.classId = t.classId AND d.date = a.date
             WHERE a.traineeId = t.id), 0) AS totalHours,
          (SELECT COUNT(*) FROM TrainingDay WHERE classId = t.classId) AS totalDays,
          (SELECT hoursPerDay FROM ClassGroup WHERE id = t.classId) AS hoursPerDay,
          COALESCE((SELECT SUM(x.hours) FROM SubjectAbsence x
             JOIN Subject s ON s.id = x.subjectId
             JOIN SubjectSession ss ON ss.subjectId = x.subjectId AND ss.date = x.date
             WHERE x.traineeId = t.id AND s.classId = t.classId), 0) AS absentHours
        FROM Trainee t WHERE t.classId = :classId ORDER BY t.lastName, t.firstName
    """)
    fun summary(classId: Int): Flow<List<TraineeSummary>>
}

/* =====================================================================
   DATABASE
   Version 10 is a clean start: older test databases (versions 2-9) are
   replaced instead of migrated, so the app can never crash on launch
   because of a half-matching old schema.
   ===================================================================== */

@Database(
    entities = [
        Program::class, ClassGroup::class, Trainee::class,
        TrainingDay::class, DailyAttendance::class,
        Subject::class, SubjectSession::class, SubjectAbsence::class
    ],
    version = 10, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AttendanceDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context): AppDb = inst ?: synchronized(this) {
            Room.databaseBuilder(c.applicationContext, AppDb::class.java, "attendance.db")
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
