package com.soprso.dhurandharai.document.vision

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.graphics.pdf.PdfRenderer
import com.soprso.dhurandharai.document.DocumentStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

/** Renders PDF pages to bitmaps using Android's built-in PdfRenderer (API 21+). */
class PageRenderer(private val context: Context) {

    /**
     * Renders a single PDF page to a bitmap at the given sample size.
     * Returns null if rendering fails.
     */
    suspend fun renderPage(
        pdfFile: File,
        pageIndex: Int,
        maxDimension: Int = 1024,
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return@withContext null
                    renderer.openPage(pageIndex).use { page ->
                        val width = page.width
                        val height = page.height
                        val scale = maxDimension.toFloat() / max(width, height)
                        val targetWidth = (width * scale).toInt().coerceAtLeast(1)
                        val targetHeight = (height * scale).toInt().coerceAtLeast(1)
                        val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Returns the number of pages in a PDF file. */
    suspend fun pageCount(pdfFile: File): Int = withContext(Dispatchers.Default) {
        try {
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer -> renderer.pageCount }
            }
        } catch (_: Exception) {
            0
        }
    }

    /** Renders all pages of a PDF to the output directory, returning paths. */
    suspend fun renderAllPages(
        pdfFile: File,
        outputDir: File,
        maxDimension: Int = 1024,
    ): List<Pair<Int, String>> = withContext(Dispatchers.Default) {
        val results = mutableListOf<Pair<Int, String>>()
        val fd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
        PdfRenderer(fd).use { renderer ->
            val count = renderer.pageCount
            for (i in 0 until count) {
                val bitmap = renderPage(pdfFile, i, maxDimension)
                if (bitmap != null) {
                    val outFile = File(outputDir, "page_${i}.png")
                    try {
                        java.io.FileOutputStream(outFile).use { out ->
                            bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
                        }
                        results += Pair(i, outFile.absolutePath)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }
        results
    }
}
