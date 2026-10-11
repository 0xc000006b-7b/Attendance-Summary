package com.bcctesda.attendancesummary.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.Period

@Entity
data class Program(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String                       // e.g. "Automotive Technology"
)

@Entity(
    foreignKeys = [ForeignKey(Program::class, ["id"], ["programId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("programId")]
)
data class ClassGroup(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val programId: Int,
    val section: String,                   // "A", "B", ...
    val yearLevel: Int,
    val semester: Int,
    val scholarship: String = ""           // e.g. "UAQTEA"
)

data class ClassInfo(@Embedded val group: ClassGroup, val programName: String) {
    val label get() = "Section ${group.section} • ${ordinal(group.yearLevel)} Year • ${ordinal(group.semester)} Sem"
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
    val birthday: String? = null,          // "yyyy-MM-dd", optional
    val email: String? = null,             // optional
    val phone: String? = null,             // optional
    val address: String? = null,           // optional
    val photoPath: String? = null          // NEW, optional
)

@Entity(
    foreignKeys = [ForeignKey(ClassGroup::class, ["id"], ["classId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("classId")]
)
data class Subject(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val classId: Int,
    val name: String,
    val sessions: Int
)

@Entity(
    primaryKeys = ["traineeId", "subjectId"],
    foreignKeys = [
        ForeignKey(Trainee::class, ["id"], ["traineeId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(Subject::class, ["id"], ["subjectId"], onDelete = ForeignKey.CASCADE)
    ],
    indices = [Index("subjectId")]
)
data class Attendance(val traineeId: Int, val subjectId: Int, val presentDays: Int)

data class SubjectAttendance(val subjectName: String, val sessions: Int, val presentDays: Int)

data class TraineeSummary(
    val id: Int, val lastName: String, val firstName: String,
    val presentDays: Int, val totalSessions: Int,
    val photoPath: String? = null           // NEW
) {
    val percent: Double get() = if (totalSessions == 0) 0.0 else presentDays * 100.0 / totalSessions
    val category: String get() = categoryOf(percent)
}

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

@Dao
interface AttendanceDao {
    @Insert suspend fun addProgram(p: Program)
    @Insert suspend fun addClass(c: ClassGroup)
    @Insert suspend fun addTrainee(t: Trainee)
    @Insert suspend fun addSubject(s: Subject)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAttendance(a: Attendance)
    @Update suspend fun updateTrainee(t: Trainee)
    @Update suspend fun updateProgram(p: Program)
    @Update suspend fun updateClass(c: ClassGroup)
    @Insert suspend fun insertTraineeId(t: Trainee): Long
    @Insert suspend fun insertSubjectId(s: Subject): Long
    @Query("SELECT * FROM Trainee WHERE classId = :classId") suspend fun traineesOnce(classId: Int): List<Trainee>
    @Query("SELECT * FROM Subject WHERE classId = :classId") suspend fun subjectsOnce(classId: Int): List<Subject>
    @Query("SELECT photoPath FROM Trainee WHERE classId = :classId AND photoPath IS NOT NULL")
    suspend fun photoPathsForClass(classId: Int): List<String>
    @Query("""SELECT t.photoPath FROM Trainee t JOIN ClassGroup c ON c.id = t.classId
              WHERE c.programId = :programId AND t.photoPath IS NOT NULL""")
    suspend fun photoPathsForProgram(programId: Int): List<String>
    @Delete suspend fun deleteProgram(p: Program)
    @Delete suspend fun deleteClass(c: ClassGroup)
    @Delete suspend fun deleteTrainee(t: Trainee)
    @Delete suspend fun deleteSubject(s: Subject)

    @Query("SELECT * FROM Program ORDER BY name") fun programs(): Flow<List<Program>>

    @Query("""SELECT c.*, p.name AS programName FROM ClassGroup c JOIN Program p ON p.id = c.programId
              ORDER BY p.name, c.yearLevel, c.semester, c.section""")
    fun classes(): Flow<List<ClassInfo>>

    @Query("""SELECT c.*, p.name AS programName FROM ClassGroup c JOIN Program p ON p.id = c.programId
              WHERE c.id = :id""")
    fun classInfo(id: Int): Flow<ClassInfo?>

    @Query("SELECT * FROM Trainee WHERE classId = :classId ORDER BY lastName")
    fun trainees(classId: Int): Flow<List<Trainee>>

    @Query("SELECT * FROM Trainee WHERE id = :id") fun trainee(id: Int): Flow<Trainee?>

    @Query("SELECT * FROM Subject WHERE classId = :classId ORDER BY id")
    fun subjects(classId: Int): Flow<List<Subject>>

    @Query("SELECT * FROM Attendance WHERE subjectId = :subjectId")
    fun attendanceFor(subjectId: Int): Flow<List<Attendance>>

    @Query("""
        SELECT t.id, t.lastName, t.firstName, t.photoPath AS photoPath,
          COALESCE((SELECT SUM(presentDays) FROM Attendance WHERE traineeId = t.id), 0) AS presentDays,
          COALESCE((SELECT SUM(sessions) FROM Subject WHERE classId = t.classId), 0) AS totalSessions
        FROM Trainee t WHERE t.classId = :classId ORDER BY t.lastName
    """)
    fun summary(classId: Int): Flow<List<TraineeSummary>>

    @Query("""
        SELECT s.name AS subjectName, s.sessions AS sessions, COALESCE(a.presentDays, 0) AS presentDays
        FROM Subject s JOIN Trainee t ON t.classId = s.classId
        LEFT JOIN Attendance a ON a.subjectId = s.id AND a.traineeId = t.id
        WHERE t.id = :traineeId ORDER BY s.id
    """)
    fun subjectAttendance(traineeId: Int): Flow<List<SubjectAttendance>>
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE Trainee ADD COLUMN photoPath TEXT")
    }
}

@Database(
    entities = [Program::class, ClassGroup::class, Trainee::class, Subject::class, Attendance::class],
    version = 3, exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): AttendanceDao
    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(c: Context) = inst ?: synchronized(this) {
            Room.databaseBuilder(c.applicationContext, AppDb::class.java, "attendance.db")
                .addMigrations(MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .build().also { inst = it }
        }
    }
}
