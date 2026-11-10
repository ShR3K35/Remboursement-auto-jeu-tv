package fr.remboursementjeux.app

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class CreatedLetter(
    val letterId: String,
    val pages: Int,
    val amountCents: Int,
    val clientSecret: String,
    val publishableKey: String,
)

data class LetterStatus(
    val letterId: String,
    val status: String,
    val amountCents: Int,
    val mailevaSendingId: String?,
    val error: String?,
)

class ApiException(message: String) : Exception(message)

/** Client du serveur de paiement / envoi postal (HTTPS uniquement). */
class ApiClient(baseUrl: String, private val token: String) {
    private val base = baseUrl.trimEnd('/')

    init {
        require(base.startsWith("https://")) { "L'adresse du serveur doit commencer par https://" }
        require(token.length >= 32) { "Jeton d'API manquant ou trop court" }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build()

    private suspend fun call(method: String, path: String, body: JSONObject? = null): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(base + path)
            .header("Authorization", "Bearer $token")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType()) ?: if (method == "POST") "{}".toRequestBody("application/json".toMediaType()) else null)
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!res.isSuccessful) throw ApiException(json.optString("error", "Erreur serveur (${res.code})"))
            json
        }
    }

    suspend fun createLetter(pdf: File, sender: List<String>, recipient: List<String>, email: String?, claimKey: String): CreatedLetter {
        val body = JSONObject().apply {
            put("pdfBase64", Base64.encodeToString(pdf.readBytes(), Base64.NO_WRAP))
            put("sender", JSONArray(sender))
            put("recipient", JSONArray(recipient))
            if (!email.isNullOrBlank()) put("notificationEmail", email)
            put("claimKey", claimKey)
        }
        val j = call("POST", "/api/letters", body)
        return CreatedLetter(
            letterId = j.getString("letterId"),
            pages = j.getInt("pages"),
            amountCents = j.getInt("amountCents"),
            clientSecret = j.getString("paymentIntentClientSecret"),
            publishableKey = j.getString("publishableKey"),
        )
    }

    suspend fun confirm(letterId: String) = parseStatus(call("POST", "/api/letters/$letterId/confirm"))
    suspend fun status(letterId: String) = parseStatus(call("GET", "/api/letters/$letterId"))
    suspend fun retry(letterId: String) = parseStatus(call("POST", "/api/letters/$letterId/retry"))

    private fun parseStatus(j: JSONObject) = LetterStatus(
        letterId = j.getString("letterId"),
        status = j.getString("status"),
        amountCents = j.optInt("amountCents"),
        mailevaSendingId = if (j.isNull("mailevaSendingId")) null else j.optString("mailevaSendingId"),
        error = if (j.isNull("error")) null else j.optString("error"),
    )

    companion object {
        /** Lignes d'adresse AFNOR (6 lignes) pour l'API Maileva. */
        fun afnor(lines: List<String>): List<String> {
            val l = lines.filter { it.isNotBlank() }
            require(l.size >= 2) { "Adresse incomplète" }
            val out = MutableList(6) { "" }
            out[0] = l.first()
            out[5] = l.last()
            val middle = l.subList(1, l.size - 1)
            // Ligne 4 = voie ; lignes 2-3 et 5 pour les compléments.
            when (middle.size) {
                0 -> {}
                1 -> out[3] = middle[0]
                2 -> { out[2] = middle[0]; out[3] = middle[1] }
                else -> { out[1] = middle[0]; out[2] = middle[1]; out[3] = middle[2]; out[4] = middle.drop(3).joinToString(" ") }
            }
            return out.map { it.take(38) }
        }
    }
}
