package fr.remboursementjeux.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import fr.remboursementjeux.core.Letter
import java.io.File

/**
 * Compose le courrier complet en un seul PDF A4 : la lettre puis les pièces
 * jointes (factures, RIB, pièce d'identité). Les PDF joints sont rendus page à page
 * (PdfRenderer) et les images sont mises à l'échelle de la page.
 */
object PdfComposer {
    private const val W = 595 // A4 en points
    private const val H = 842
    private const val M = 56f // marges

    /** Nombre de pages qu'occuperont les pièces jointes (pour calculer les photocopies). */
    fun attachmentPageCount(files: List<Pair<File, String>>): Int = files.sumOf { (f, mime) ->
        if (mime == "application/pdf") {
            ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
        } else {
            1
        }
    }

    /** @return le nombre total de pages du PDF généré. */
    fun compose(context: Context, letter: Letter, attachments: List<Pair<File, String>>, out: File): Int {
        val doc = PdfDocument()
        var pageNo = 0
        try {
            pageNo = drawLetter(doc, letter, pageNo)
            for ((file, mime) in attachments) {
                pageNo = if (mime == "application/pdf") drawPdf(doc, file, pageNo) else drawImage(doc, file, pageNo)
            }
            out.parentFile?.mkdirs()
            out.outputStream().use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return pageNo
    }

    private fun drawLetter(doc: PdfDocument, letter: Letter, startPage: Int): Int {
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 10.5f; color = Color.BLACK }
        val bold = Paint(text).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val line = Paint().apply { color = Color.GRAY; strokeWidth = 0.6f }
        val lh = 14.5f

        var pageNo = startPage
        var page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
        var c = page.canvas
        var y = M + 10

        fun newPageIfNeeded(needed: Float) {
            if (y + needed > H - M) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, ++pageNo).create())
                c = page.canvas
                y = M + 10
            }
        }

        fun paragraph(s: String, p: Paint = text, x: Float = M, width: Float = W - 2 * M) {
            for (l in wrap(s, p, width)) {
                newPageIfNeeded(lh)
                c.drawText(l, x, y, p)
                y += lh
            }
        }

        // Expéditeur (haut gauche)
        letter.senderLines.forEachIndexed { i, s -> c.drawText(s, M, y + i * lh, if (i == 0) bold else text) }
        // Destinataire (zone de fenêtre à droite)
        var ry = M + 10 + 6 * lh
        letter.recipientLines.forEachIndexed { i, s -> c.drawText(s, 310f, ry, if (i == 0) bold else text); ry += lh }
        y = maxOf(y + letter.senderLines.size * lh, ry) + lh
        c.drawText(letter.placeAndDate, 310f, y, text)
        y += 2 * lh

        paragraph(letter.subject, bold)
        y += lh
        for (p in letter.paragraphsBefore) { paragraph(p); y += lh / 2 }

        // Tableau des participations
        val cols = floatArrayOf(M, M + 110, M + 190, M + 300)
        newPageIfNeeded(2 * lh)
        letter.tableHeader.forEachIndexed { i, h -> c.drawText(h, cols[i], y, bold) }
        y += 4; c.drawLine(M, y, W - M, y, line); y += lh
        for (row in letter.tableRows) {
            newPageIfNeeded(lh)
            row.forEachIndexed { i, cell -> c.drawText(cell, cols[i], y, text) }
            y += lh
        }
        y -= lh - 4; c.drawLine(M, y, W - M, y, line); y += lh
        for ((label, amount) in letter.totals) {
            newPageIfNeeded(lh)
            val p = if (label.startsWith("Total")) bold else text
            c.drawText(label, M, y, p)
            c.drawText(amount, W - M - p.measureText(amount), y, p)
            y += lh
        }
        y += lh
        for (p in letter.paragraphsAfter) { paragraph(p); y += lh / 2 }
        y += lh
        newPageIfNeeded(4 * lh)
        c.drawText(letter.signature, 310f, y, bold)
        y += 3 * lh

        paragraph("Pièces jointes :", bold)
        for (a in letter.attachments) paragraph("– $a")

        doc.finishPage(page)
        return pageNo
    }

    private fun drawPdf(doc: PdfDocument, file: File, startPage: Int): Int {
        var pageNo = startPage
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { src ->
                        // Rendu à ~150 dpi pour une copie lisible.
                        val scale = 150f / 72f
                        val bmp = Bitmap.createBitmap((src.width * scale).toInt(), (src.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        src.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        pageNo = drawBitmapPage(doc, bmp, pageNo)
                        bmp.recycle()
                    }
                }
            }
        }
        return pageNo
    }

    private fun drawImage(doc: PdfDocument, file: File, startPage: Int): Int {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Image illisible")
        val n = drawBitmapPage(doc, bmp, startPage)
        bmp.recycle()
        return n
    }

    private fun drawBitmapPage(doc: PdfDocument, bmp: Bitmap, startPage: Int): Int {
        val pageNo = startPage + 1
        val page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
        val margin = 20f
        val availW = W - 2 * margin
        val availH = H - 2 * margin
        val ratio = minOf(availW / bmp.width, availH / bmp.height)
        val w = bmp.width * ratio
        val h = bmp.height * ratio
        val left = (W - w) / 2
        val top = (H - h) / 2
        page.canvas.drawBitmap(bmp, Rect(0, 0, bmp.width, bmp.height), RectF(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
        doc.finishPage(page)
        return pageNo
    }

    private fun wrap(s: String, p: Paint, width: Float): List<String> {
        val out = mutableListOf<String>()
        var current = StringBuilder()
        for (word in s.split(' ')) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (p.measureText(candidate) <= width) {
                current = StringBuilder(candidate)
            } else {
                if (current.isNotEmpty()) out += current.toString()
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }
}
