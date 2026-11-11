package fr.remboursementjeux.app

import android.content.Context
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import fr.remboursementjeux.core.DriveFile
import fr.remboursementjeux.core.InvoiceEmail
import fr.remboursementjeux.core.InvoiceInbox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Synchronisation avec le script Google (voir google-script/) :
 *  - récupère les mails Prixtel « facture disponible » relevés chaque jour dans Gmail ;
 *  - importe les nouveaux PDF déposés dans le dossier Drive (chaque fichier une seule fois) ;
 *  - signale les factures annoncées par mail mais pas encore déposées.
 */
object DriveSync {
    data class Result(val imported: List<String>, val pending: List<InvoiceEmail>, val messages: List<String>)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // un script Apps Script peut être lent au réveil
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isConfigured(data: AppData) =
        data.settings.driveUrl.startsWith("https://script.google.com/") && data.settings.driveToken.length >= 32

    /** Mails de facture des 150 derniers jours restant à déposer dans Drive. */
    fun pending(data: AppData): List<InvoiceEmail> {
        val since = System.currentTimeMillis() - 150L * 24 * 60 * 60 * 1000
        return InvoiceInbox.pending(
            data.inbox.emails.filter { it.receivedMillis >= since },
            data.inbox.files + data.inbox.localImports,
        )
    }

    private suspend fun get(data: AppData, vararg params: Pair<String, String>): JSONObject = withContext(Dispatchers.IO) {
        val url = data.settings.driveUrl.toHttpUrl().newBuilder().apply {
            addQueryParameter("token", data.settings.driveToken)
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        http.newCall(Request.Builder().url(url).build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) error("Script Google : HTTP ${res.code}")
            val json = runCatching { JSONObject(text) }.getOrElse {
                error("Réponse inattendue du script (vérifiez le déploiement « Application Web », accès « Tout le monde »)")
            }
            if (json.has("error")) error("Script Google : ${json.getString("error")}")
            json
        }
    }

    suspend fun sync(context: Context): Result {
        val data = Repository.data.value
        if (!isConfigured(data)) error("Configurez l'URL et le jeton du script Google dans Réglages")
        val messages = mutableListOf<String>()
        try {
            val list = get(data, "action" to "list")
            val emailsJson = list.getJSONArray("emails")
            val emails = (0 until emailsJson.length()).map { emailsJson.getJSONObject(it) }.map {
                InvoiceEmail(it.getString("id"), it.getLong("received"), it.optString("subject"), it.optBoolean("hasPdf"))
            }
            val filesJson = list.getJSONArray("files")
            val files = (0 until filesJson.length()).map { filesJson.getJSONObject(it) }.map {
                DriveFile(it.getString("id"), it.optString("name"), it.getLong("created"))
            }

            val imported = mutableListOf<String>()
            for (f in files.filter { it.id !in data.inbox.importedFileIds }) {
                try {
                    val content = get(data, "action" to "file", "id" to f.id)
                    val bytes = Base64.decode(content.getString("base64"), Base64.DEFAULT)
                    messages += "${f.name} : " + InvoiceImporter.importPdf(context, bytes, f.name, null, driveFileId = f.id).message()
                } catch (e: Exception) {
                    // Fichier illisible ou sans SMS surtaxé : on ne le retente pas à chaque synchro.
                    messages += "${f.name} : ${e.message}"
                }
                imported += f.id
                Repository.update { d -> d.copy(inbox = d.inbox.copy(importedFileIds = d.inbox.importedFileIds + f.id)) }
            }

            Repository.update { d ->
                d.copy(inbox = d.inbox.copy(emails = emails, files = files, lastSyncMillis = System.currentTimeMillis(), lastError = null))
            }
            return Result(imported, pending(Repository.data.value), messages)
        } catch (e: Exception) {
            Repository.update { d -> d.copy(inbox = d.inbox.copy(lastError = e.message, lastSyncMillis = System.currentTimeMillis())) }
            throw e
        }
    }
}

/** Synchronisation quotidienne Gmail/Drive + notification des factures à déposer. */
class DriveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repository.init(applicationContext)
        if (!DriveSync.isConfigured(Repository.data.value)) return Result.success()
        return try {
            val r = DriveSync.sync(applicationContext)
            Notifier.notifyInvoices(applicationContext, r)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
