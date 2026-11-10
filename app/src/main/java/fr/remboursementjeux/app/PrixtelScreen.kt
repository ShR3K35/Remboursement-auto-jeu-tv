package fr.remboursementjeux.app

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Connexion à l'espace client Prixtel dans une WebView : l'utilisateur saisit
 * lui-même ses identifiants sur le site officiel (l'app ne les voit ni ne les
 * stocke, et la double authentification éventuelle fonctionne). Lorsqu'une facture
 * PDF est téléchargée, l'app l'intercepte et l'associe au mois demandé.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PrixtelScreen(month: String?, startUrl: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val which = month?.let { "de $it" } ?: "du mois voulu"
    var message by remember { mutableStateOf("Connectez-vous, ouvrez « Mes factures » puis téléchargez la facture détaillée $which.") }

    Column(Modifier.fillMaxSize()) {
        Text(message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
            OutlinedButton(onClick = {
                CookieManager.getInstance().removeAllCookies(null)
                message = "Session Prixtel effacée."
            }) { Text("Se déconnecter") }
            Button(onClick = onDone, modifier = Modifier.padding(start = 8.dp)) { Text("Terminer") }
        }
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val host = request.url.host ?: return true
                            if (request.url.scheme != "https") return true
                            // Navigation limitée aux domaines Prixtel ; le reste s'ouvre dans le navigateur.
                            if (host == "prixtel.com" || host.endsWith(".prixtel.com")) return false
                            ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            return true
                        }
                    }
                    setDownloadListener { url, userAgent, _, mimeType, _ ->
                        scope.launch {
                            message = "Téléchargement de la facture…"
                            message = try {
                                val bytes = download(url, userAgent)
                                // Enregistre la facture et en extrait les SMS surtaxés (sans doublon).
                                InvoiceImporter.importPdf(context, bytes, "Facture Prixtel.pdf", month).message() +
                                    " Vous pouvez terminer ou télécharger une autre facture."
                            } catch (e: Exception) {
                                "Échec : ${e.message ?: mimeType}"
                            }
                        }
                    }
                    loadUrl(startUrl)
                }
            },
        )
    }
}

private val http = OkHttpClient()

private suspend fun download(url: String, userAgent: String): ByteArray = withContext(Dispatchers.IO) {
    val uri = Uri.parse(url)
    val host = uri.host.orEmpty()
    require(uri.scheme == "https" && (host == "prixtel.com" || host.endsWith(".prixtel.com"))) { "Téléchargement refusé hors du site Prixtel" }
    val cookies = CookieManager.getInstance().getCookie(url)
    val req = Request.Builder().url(url).header("User-Agent", userAgent).apply {
        if (cookies != null) header("Cookie", cookies)
    }.build()
    http.newCall(req).execute().use { res ->
        if (!res.isSuccessful) error("HTTP ${res.code}")
        res.body?.bytes() ?: error("Réponse vide")
    }
}
