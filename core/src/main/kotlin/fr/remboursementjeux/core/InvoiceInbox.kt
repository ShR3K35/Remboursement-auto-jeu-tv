package fr.remboursementjeux.core

/** Mail Prixtel annonçant une facture (relevé par le script Gmail). */
data class InvoiceEmail(val id: String, val receivedMillis: Long, val subject: String, val hasPdf: Boolean = false)

/** Facture PDF présente dans le dossier Google Drive. */
data class DriveFile(val id: String, val name: String, val createdMillis: Long)

data class InboxEntry(val email: InvoiceEmail, val file: DriveFile?) {
    /** Facture annoncée par mail mais pas encore déposée dans le dossier Drive. */
    val pending: Boolean get() = file == null
}

/**
 * Rapproche les mails « facture disponible » des fichiers déposés dans Drive.
 * Chaque mail attend un fichier déposé après sa réception (avec une marge pour un
 * dépôt anticipé) ; appariement un pour un, dans l'ordre chronologique.
 */
object InvoiceInbox {
    const val EARLY_MARGIN_MS = 2 * 24 * 60 * 60 * 1000L

    fun pair(emails: List<InvoiceEmail>, files: List<DriveFile>): List<InboxEntry> {
        val remaining = files.sortedBy { it.createdMillis }.toMutableList()
        return emails.distinctBy { it.id }.sortedBy { it.receivedMillis }.map { mail ->
            val f = remaining.firstOrNull { it.createdMillis >= mail.receivedMillis - EARLY_MARGIN_MS }
            if (f != null) remaining.remove(f)
            InboxEntry(mail, f)
        }
    }

    fun pending(emails: List<InvoiceEmail>, files: List<DriveFile>): List<InvoiceEmail> =
        pair(emails, files).filter { it.pending }.map { it.email }
}
