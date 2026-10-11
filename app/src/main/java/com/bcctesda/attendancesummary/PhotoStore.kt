package com.bcctesda.attendancesummary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.File
import java.io.FileOutputStream

private const val MAX_PX = 600

/** Copies a picked image into app storage (max ~600px, JPEG). Returns the file path, or null on failure. */
fun savePhoto(ctx: Context, uri: Uri): String? = runCatching {
    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val big = maxOf(info.size.width, info.size.height)
            if (big > MAX_PX) dec.setTargetSampleSize(big / MAX_PX)
        }
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, bounds) }
        var s = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (s * 2) >= MAX_PX) s *= 2
        ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
        }!!
    }
    val dir = File(ctx.filesDir, "photos").apply { mkdirs() }
    val file = File(dir, "trainee_${System.currentTimeMillis()}.jpg")
    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    file.absolutePath
}.getOrNull()

/** Loads a saved photo, downsampled so list thumbnails stay light. */
fun loadBitmap(path: String, reqPx: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    var s = 1
    while (o.outWidth / (s * 2) >= reqPx && o.outHeight / (s * 2) >= reqPx) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}
