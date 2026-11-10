package fr.remboursementjeux.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import fr.remboursementjeux.core.ClaimBuilder
import fr.remboursementjeux.core.Dedup
import fr.remboursementjeux.core.InvoiceParser
import fr.remboursementjeux.core.SmsDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import d'une facture détaillée : le PDF est conservé (chiffré) comme pièce
 * justificative des mois concernés, et ses lignes de SMS surtaxés deviennent des
 * participations, fusionnées sans doublon avec celles détectées dans les SMS.
 */
object InvoiceImporter {
    data class Report(val linesFound: Int, val added: Int, val duplicates: Int, val paired: Int, val months: List<String>) {
        fun message(): String = buildString {
            append("Facture enregistrée")
            if (months.isNotEmpty()) append(" (${months.joinToString()})")
            append(". $linesFound SMS surtaxé(s) trouvé(s) : $added nouveau(x)")
            if (duplicates > 0) append(", $duplicates déjà connu(s) — aucun doublon créé")
            if (paired > 0) append(", dont $paired rapproché(s) d'un SMS du téléphone (montant facturé retenu)")
            append(".")
        }
    }

    suspend fun importUri(context: Context, uri: Uri, forcedMonth: String?): Report = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Lecture du fichier impossible")
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "facture"
        when {
            mime == "application/pdf" -> importPdf(context, bytes, name, forcedMonth)
            mime.startsWith("image/") -> {
                // Photo/scan : conservée comme pièce jointe, sans lecture automatique des lignes.
                val month = forcedMonth ?: error("Pour une image, choisissez la facture depuis une demande (mois connu)")
                val doc = Documents.importBytes(context, bytes, mime, name)
                Repository.update { Repository.withInvoice(it, month, doc) }
                Report(0, 0, 0, 0, listOf(month))
            }
            else -> error("Format non pris en charge (PDF ou image)")
        }
    }

    suspend fun importPdf(context: Context, bytes: ByteArray, name: String, forcedMonth: String?): Report = withContext(Dispatchers.IO) {
        val text = extractText(context, bytes)
        val lines = InvoiceParser.parse(text)
        val data = Repository.data.value
        val incoming = InvoiceParser.toParticipations(lines, SmsDetector(Repository.profiles(data)))
        val builder = ClaimBuilder()
        val months = (lines.map { builder.monthOf(it.timestampMillis) } + listOfNotNull(forcedMonth)).distinct().sorted()
        if (months.isEmpty()) error("Aucun SMS surtaxé trouvé dans cette facture. Est-ce bien la facture détaillée ?")

        val doc = Documents.importBytes(context, bytes, "application/pdf", name)
        var report = Report(lines.size, 0, 0, 0, months)
        Repository.update { d ->
            var next = d
            months.forEach { m -> next = Repository.withInvoice(next, m, doc) }
            val merged = Dedup.merge(next.participations, incoming)
            report = report.copy(added = merged.added.size, duplicates = merged.duplicates, paired = merged.pairedWithInvoice)
            next.copy(participations = merged.participations)
        }
        report
    }

    private fun extractText(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        return try {
            PDDocument.load(bytes).use { doc ->
                PDFTextStripper().apply { sortByPosition = true }.getText(doc)
            }
        } catch (e: Exception) {
            throw IllegalStateException("Lecture du PDF impossible (protégé par mot de passe ou endommagé)", e)
        }
    }
}
