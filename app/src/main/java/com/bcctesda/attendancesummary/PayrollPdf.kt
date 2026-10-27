package com.bcctesda.attendancesummary

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.bcctesda.attendancesummary.data.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

object PayrollPdf {
    // Long bond paper (8.5 x 13 in), landscape, 72 points per inch.
    // For A4 landscape use PAGE_W = 842 and PAGE_H = 595 (columns scale automatically).
    private const val PAGE_W = 936
    private const val PAGE_H = 612
    private const val MARGIN = 36f
    private const val ROW_H = 20f

    // #, last, first, middle, allowance, absences, to be claimed, net amount, signature
    private val colW = floatArrayOf(28f, 120f, 130f, 110f, 90f, 80f, 100f, 90f, 116f)

    private fun money(v: Int) = String.format(Locale.US, "%,.2f", v.toDouble())

    private fun fit(s: String, p: Paint, w: Float): String {
        val n = p.breakText(s, true, w, null)
        return if (n >= s.length) s else s.take(n)
    }

    fun create(ctx: Context, g: ClassGroup, programName: String, def: TrancheDef,
               covered: List<TrainingDay>, lines: List<TrancheLine>): File {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD }
        val titleP = Paint(bold).apply { textSize = 16f; textAlign = Paint.Align.CENTER }
        val centerB = Paint(bold).apply { textAlign = Paint.Align.CENTER }
        val centerT = Paint(text).apply { textAlign = Paint.Align.CENTER }
        val headP = Paint(bold).apply { textSize = 8.5f; textAlign = Paint.Align.CENTER }
        val right = Paint(text).apply { textAlign = Paint.Align.RIGHT }
        val rightB = Paint(bold).apply { textAlign = Paint.Align.RIGHT }
        val draft = Paint(bold).apply { color = Color.rgb(180, 0, 0); textAlign = Paint.Align.RIGHT }
        val grid = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 0.7f; color = Color.BLACK }

        val scale = (PAGE_W - 2 * MARGIN) / colW.sum()
        val xs = FloatArray(colW.size + 1).also {
            it[0] = MARGIN
            for (i in colW.indices) it[i + 1] = it[i] + colW[i] * scale
        }

        val period = if (covered.isEmpty()) "" else fmtLongRange(covered.first().date, covered.last().date)
        val share = def.sharePct
        val ack = "We acknowledge receipt of cash shown opposite our name as $share% TRAINING ALLOWANCE for the period covered."

        val rows = lines.mapIndexed { i, l ->
            listOf("${i + 1}", l.trainee.lastName, l.trainee.firstName,
                l.trainee.middleName.takeUnless { it == "." } ?: "",
                money(l.allowance), money(l.absences), money(l.toClaim), money(l.toClaim), "")
        } + listOf(listOf("", "TOTAL", "", "",
            money(lines.sumOf { it.allowance }), money(lines.sumOf { it.absences }),
            money(lines.sumOf { it.toClaim }), money(lines.sumOf { it.toClaim }), ""))

        val headers = listOf("#" to "", "LAST NAME" to "", "FIRST NAME" to "", "MIDDLE NAME" to "",
            "TRAINING" to "ALLOWANCE", "ABSENCES" to "", "ALLOWANCE" to "TO BE CLAIMED", "NET" to "AMOUNT",
            "SIGNATURE" to "")

        val doc = PdfDocument()
        var index = 0
        var pageNo = 1
        while (true) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
            val c = page.canvas
            var y = MARGIN + 14f
            c.drawText("PAYROLL", PAGE_W / 2f, y, titleP); y += 16f
            if (period.isNotEmpty()) { c.drawText(period, PAGE_W / 2f, y, centerB); y += 14f }
            c.drawText(g.tesdaOffice, PAGE_W / 2f, y, centerT); y += 18f
            fun field(label: String, value: String) {
                c.drawText(label, MARGIN, y, bold); c.drawText(value, MARGIN + 120f, y, text); y += 13f
            }
            field("Scholarship:", g.scholarship)
            field("Training Provider:", g.trainingProvider)
            field("Qualification:", g.qualification)
            field("RQM Number:", g.rqmNumber)
            field("Training days/hours:", "${def.days} Days (${def.days * g.hoursPerDay} Hours)")
            y += 3f
            c.drawText(ack, MARGIN, y, text); y += 16f
            if (covered.size < def.days)
                c.drawText("DRAFT: ${covered.size} of ${def.days} days recorded", PAGE_W - MARGIN, MARGIN + 14f, draft)

            val hh = 28f
            for (i in colW.indices) {
                c.drawRect(xs[i], y, xs[i + 1], y + hh, grid)
                val cx = (xs[i] + xs[i + 1]) / 2f
                val (a, b) = headers[i]
                if (b.isEmpty()) c.drawText(a, cx, y + 17f, headP)
                else { c.drawText(a, cx, y + 12f, headP); c.drawText(b, cx, y + 23f, headP) }
            }
            y += hh

            while (index < rows.size && y + ROW_H <= PAGE_H - MARGIN) {
                val r = rows[index]
                val isTotal = r[1] == "TOTAL"
                val p = if (isTotal) bold else text
                for (i in colW.indices) {
                    c.drawRect(xs[i], y, xs[i + 1], y + ROW_H, grid)
                    when (i) {
                        0 -> c.drawText(r[i], (xs[0] + xs[1]) / 2f, y + 14f, centerT)
                        4, 5, 6, 7 -> c.drawText(r[i], xs[i + 1] - 4f, y + 14f, if (isTotal) rightB else right)
                        else -> c.drawText(fit(r[i], p, xs[i + 1] - xs[i] - 8f), xs[i] + 4f, y + 14f, p)
                    }
                }
                y += ROW_H
                index++
            }
            doc.finishPage(page)
            if (index >= rows.size) break
            pageNo++
        }

        val dir = File(ctx.cacheDir, "payroll").apply { mkdirs() }
        val name = "Payroll_${programName}_Section${g.section}_${def.number}${ordinal(def.number).drop(1)}_tranche.pdf"
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val file = File(dir, name)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file
    }

    /** Opens the share sheet: Drive, email, Messenger, "Save to Files", etc. */
    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Share payroll"))
    }

    /** Opens Android's print dialog (it also has "Save as PDF"). */
    fun print(ctx: Context, file: File) {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(file.name, object : PrintDocumentAdapter() {
            override fun onLayout(oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?,
                                  cancellationSignal: CancellationSignal?, callback: LayoutResultCallback?, extras: Bundle?) {
                if (cancellationSignal?.isCanceled == true) { callback?.onLayoutCancelled(); return }
                callback?.onLayoutFinished(PrintDocumentInfo.Builder(file.name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor?,
                                 cancellationSignal: CancellationSignal?, callback: WriteResultCallback?) {
                runCatching {
                    FileInputStream(file).use { i -> FileOutputStream(destination!!.fileDescriptor).use { o -> i.copyTo(o) } }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                }.onFailure { callback?.onWriteFailed(it.message) }
            }
        }, null)
    }
}
