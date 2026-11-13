package com.bcctesda.attendancesummary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.bcctesda.attendancesummary.data.*
import java.io.File
import java.io.FileOutputStream
import java.time.format.TextStyle
import java.util.Locale

/**
 * One-page "Student Profile" form (portrait). The header is res/drawable-nodpi/college_header.jpg
 * (your Picture1.jpg). Fields the app doesn't store yet are left empty so they can be written by hand.
 */
object StudentProfilePdf {
    // Long bond paper (8.5 x 13 in), portrait, 72 points per inch. For A4 use 595 x 842.
    private const val PAGE_W = 612
    private const val PAGE_H = 936
    private const val M = 36f

    private val BLUE = Color.rgb(30, 77, 183)
    private val LABEL_FILL = Color.rgb(232, 238, 249)
    private val CAP_FILL = Color.rgb(217, 217, 217)

    fun create(ctx: Context, t: Trainee, info: ClassInfo?): File {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f; color = Color.BLACK }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val centerT = Paint(text).apply { textAlign = Paint.Align.CENTER }
        val centerB = Paint(bold).apply { textAlign = Paint.Align.CENTER }
        val caption = Paint(text).apply { textSize = 7.5f; textAlign = Paint.Align.CENTER; color = Color.rgb(55, 55, 55) }
        val onBlue = Paint(bold).apply { textSize = 9f; color = Color.WHITE; textAlign = Paint.Align.CENTER }
        val grid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = Color.BLACK }
        val thick = Paint(grid).apply { strokeWidth = 1.6f }
        val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        fun fillPaint(color: Int) = Paint().apply { style = Paint.Style.FILL; this.color = color }
        val blueFill = fillPaint(BLUE); val labelFill = fillPaint(LABEL_FILL); val capFill = fillPaint(CAP_FILL)

        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
        val c = page.canvas

        /* ---------- header image (full width) ---------- */
        var bannerH = 0f
        val header: Bitmap? = runCatching {
            BitmapFactory.decodeResource(ctx.resources, R.drawable.college_header)
        }.getOrNull()
        if (header != null) {
            bannerH = PAGE_W.toFloat() * header.height / header.width
            c.drawBitmap(header, null, RectF(0f, 0f, PAGE_W.toFloat(), bannerH), bmpPaint)
        }

        /* ---------- title, ID line, 1x1 picture ---------- */
        val boxSide = 72f                                   // 1 inch
        val boxX = PAGE_W - M - boxSide
        val top = bannerH + 12f
        centerB.textSize = 16f
        c.drawText("STUDENT PROFILE", (M + boxX) / 2f, top + 30f, centerB)
        centerB.textSize = 10f
        c.drawText("Student ID No.:", M, top + 64f, bold)
        c.drawLine(M + 80f, top + 66f, M + 250f, top + 66f, grid)

        val photo = t.photoPath?.takeIf { File(it).exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it) }.getOrNull() }
        if (photo != null) {
            val side = minOf(photo.width, photo.height)
            val src = Rect((photo.width - side) / 2, (photo.height - side) / 2,
                (photo.width + side) / 2, (photo.height + side) / 2)
            c.drawBitmap(photo, src, RectF(boxX, top, boxX + boxSide, top + boxSide), bmpPaint)
        } else {
            c.drawText("1X1", boxX + boxSide / 2, top + 34f, centerB)
            c.drawText("Picture", boxX + boxSide / 2, top + 48f, centerT)
        }
        c.drawRect(boxX, top, boxX + boxSide, top + boxSide, thick)

        /* ---------- table helpers ---------- */
        val x0 = M
        val tw = PAGE_W - 2 * M
        val labelW = 86f
        val colW = (tw - labelW) / 3f
        val valueH = 20f
        val capH = 13f
        var y = top + boxSide + 10f

        fun drawFit(s: String, cx: Float, cy: Float, maxW: Float, base: Paint) {
            val p = Paint(base)
            while (p.measureText(s) > maxW && p.textSize > 5f) p.textSize -= 0.5f
            c.drawText(s, cx, cy + p.textSize * 0.35f, p)
        }

        fun cell(x: Float, yy: Float, w: Float, h: Float, s: String, p: Paint, fill: Paint? = null) {
            fill?.let { c.drawRect(x, yy, x + w, yy + h, it) }
            c.drawRect(x, yy, x + w, yy + h, grid)
            if (s.isNotEmpty()) drawFit(s, x + w / 2, yy + h / 2, w - 6f, p)
        }

        fun bar(s: String) {
            c.drawRect(x0, y, x0 + tw, y + 14f, blueFill)
            c.drawRect(x0, y, x0 + tw, y + 14f, grid)
            c.drawText(s, x0 + tw / 2, y + 10.5f, onBlue)
            y += 14f
        }

        /** rows: each row is 1 cell (spans all three columns) or 3 cells (value to caption). */
        fun block(label: String, rows: List<List<Pair<String, String>>>) {
            val pairH = valueH + capH
            val total = pairH * rows.size
            cell(x0, y, labelW, total, label, centerB, labelFill)
            rows.forEachIndexed { r, cells ->
                val yy = y + pairH * r
                val span = 3 / cells.size
                cells.forEachIndexed { i, (value, cap) ->
                    val x = x0 + labelW + colW * i * span
                    val w = colW * span
                    cell(x, yy, w, valueH, value, centerT)
                    cell(x, yy + valueH, w, capH, cap, caption, capFill)
                }
            }
            y += total
        }

        /** One row of cells with custom widths: Triple(text, width, isLabel). */
        fun strip(h: Float, cells: List<Triple<String, Float, Boolean>>) {
            var x = x0
            cells.forEach { (s, w, isLabel) ->
                cell(x, y, w, h, s, if (isLabel) centerB else centerT, if (isLabel) labelFill else null)
                x += w
            }
            y += h
        }

        /* ---------- data ---------- */
        val g = info?.group
        val dob = t.birthday?.toDateOrNull()
        val month = dob?.month?.getDisplayName(TextStyle.FULL, Locale.ENGLISH)?.uppercase() ?: ""
        val age = ageOf(t.birthday)?.toString() ?: ""
        val yearSem = if (g == null) "" else "${ordinal(g.yearLevel)} Year • ${ordinal(g.semester)} Sem"

        /* ---------- PART I ---------- */
        bar("PART I")
        block("Program", listOf(
            listOf((info?.programName ?: "") to "Program", (g?.section ?: "") to "Section", yearSem to "Year & Semester"),
            listOf((g?.scholarship ?: "") to "Scholarship", (info?.period ?: "") to "Training Period",
                (g?.rqmNumber ?: "") to "RQM Number")
        ))
        block("Name", listOf(
            listOf(t.lastName to "Surname", t.firstName to "First Name", t.middleName to "Middle Name")
        ))
        block("Date of Birth", listOf(
            listOf(month to "Month", (dob?.dayOfMonth?.toString() ?: "") to "Day", (dob?.year?.toString() ?: "") to "Year")
        ))
        block("Address", listOf(
            listOf((t.address ?: "") to "Complete Address (House/Block/Lot No., Street, Subdivision/Village, Barangay, City/Municipality, Province, Zip Code)"),
            listOf((t.phone ?: "") to "Contact Number", (t.email ?: "") to "Email Address", age to "Age"),
            listOf("" to "Birthplace", "" to "Religion", "" to "Zip Code")
        ))
        block("Father", listOf(
            listOf("" to "Surname", "" to "First Name", "" to "Middle Name"),
            listOf("" to "Age & Date of Birth", "" to "Occupation & Status", "" to "Contact Number")
        ))
        block("Mother", listOf(
            listOf("" to "Maiden Surname", "" to "First Name", "" to "Maiden Middle Name"),
            listOf("" to "Age & Date of Birth", "" to "Occupation & Status", "" to "Contact Number")
        ))
        block("Guardian", listOf(
            listOf("" to "Surname", "" to "First Name", "" to "Middle Name"),
            listOf("" to "Age & Date of Birth", "" to "Occupation & Relation to the child", "" to "Contact Number")
        ))

        /* ---------- PART II ---------- */
        bar("PART II")
        strip(22f, listOf(
            Triple("Height", 60f, true), Triple("", 90f, false),
            Triple("Weight", 60f, true), Triple("", 90f, false),
            Triple("Body Mass Index (BMI)", 130f, true), Triple("", tw - 430f, false)
        ))
        strip(26f, listOf(
            Triple("Do you have ailments? (Attach medical record/certificate)", 230f, true),
            Triple("[   ] Yes     [   ] No", 110f, false),
            Triple("Specify:", 60f, true), Triple("", tw - 400f, false)
        ))

        /* ---------- signature ---------- */
        y += 46f
        c.drawLine(x0, y, x0 + 260f, y, grid)
        c.drawLine(x0 + tw - 150f, y, x0 + tw, y, grid)
        caption.textAlign = Paint.Align.LEFT
        c.drawText("Student's signature over printed name", x0, y + 11f, caption)
        c.drawText("Date", x0 + tw - 150f, y + 11f, caption)

        doc.finishPage(page)

        val dir = File(ctx.cacheDir, "profiles").apply { mkdirs() }
        val name = "Student_Profile_${t.lastName}_${t.firstName}.pdf".replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(dir, name)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }
}
