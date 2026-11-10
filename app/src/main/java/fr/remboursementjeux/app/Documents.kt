package fr.remboursementjeux.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.util.UUID

/** Import et lecture des pièces justificatives (facture, RIB, pièce d'identité), chiffrées au repos. */
object Documents {
    private const val MAX_BYTES = 10 * 1024 * 1024

    private fun docsDir(context: Context) = File(context.filesDir, "docs").apply { mkdirs() }

    /** Copie le fichier choisi par l'utilisateur dans l'espace privé de l'app, chiffré. */
    fun importUri(context: Context, uri: Uri): StoredDocument {
        val cr = context.contentResolver
        val mime = cr.getType(uri) ?: "application/octet-stream"
        require(mime == "application/pdf" || mime.startsWith("image/")) { "Format non pris en charge (PDF ou image uniquement)" }
        val bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("Lecture du fichier impossible")
        require(bytes.size <= MAX_BYTES) { "Fichier trop volumineux (10 Mo max.)" }
        val name = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "document"
        return store(context, bytes, mime, name)
    }

    fun importBytes(context: Context, bytes: ByteArray, mime: String, name: String): StoredDocument {
        require(bytes.size <= MAX_BYTES) { "Fichier trop volumineux (10 Mo max.)" }
        if (mime == "application/pdf") {
            require(bytes.size > 5 && String(bytes, 0, 5, Charsets.ISO_8859_1) == "%PDF-") { "Le fichier téléchargé n'est pas un PDF" }
        }
        return store(context, bytes, mime, name)
    }

    private fun store(context: Context, bytes: ByteArray, mime: String, name: String): StoredDocument {
        val f = File(docsDir(context), UUID.randomUUID().toString() + ".enc")
        SecureStorage.writeEncrypted(f, bytes)
        return StoredDocument(f.absolutePath, mime, name)
    }

    /** Déchiffre le document dans un fichier temporaire du cache (à supprimer après usage). */
    fun decryptToTemp(context: Context, doc: StoredDocument): File {
        val bytes = SecureStorage.readEncrypted(File(doc.path)) ?: error("Document introuvable : ${doc.displayName}")
        val ext = if (doc.mimeType == "application/pdf") ".pdf" else ".img"
        val dir = File(context.cacheDir, "tmp").apply { mkdirs() }
        return File.createTempFile("doc", ext, dir).apply { writeBytes(bytes) }
    }

    fun delete(doc: StoredDocument?) {
        doc?.let { File(it.path).delete() }
    }

    fun clearTemp(context: Context) {
        File(context.cacheDir, "tmp").deleteRecursively()
        File(context.cacheDir, "previews").deleteRecursively()
    }
}
