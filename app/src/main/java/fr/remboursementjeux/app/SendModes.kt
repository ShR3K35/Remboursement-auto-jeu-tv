package fr.remboursementjeux.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import fr.remboursementjeux.core.ClaimDraft
import fr.remboursementjeux.core.ClaimStatus
import fr.remboursementjeux.core.Money
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

/** Modes d'envoi du courrier de demande de remboursement. */
enum class SendMethod(val label: String, val summary: String) {
    PRINT(
        "Imprimer et poster moi-même",
        "Le moins cher : le timbre Lettre verte vous est remboursé par la chaîne. Imprimez, mettez sous " +
            "enveloppe, affranchissez (timbre classique, timbre imprimé ou code de l'appli La Poste) et déposez " +
            "dans une boîte aux lettres jaune.",
    ),
    LAPOSTE_ONLINE(
        "Lettre en ligne (laposte.fr)",
        "La Poste imprime, met sous pli et envoie. L'app enregistre le PDF, puis vous le déposez sur le site " +
            "de La Poste et payez directement chez La Poste. Le surcoût par rapport au timbre n'est pas remboursé.",
    ),
    MERCI_FACTEUR(
        "Merci Facteur (site)",
        "Service privé d'envoi de courrier en ligne. L'app enregistre le PDF, puis vous le déposez sur " +
            "merci-facteur.com et payez sur leur site. Le surcoût n'est pas remboursé.",
    ),
    MAILEVA(
        "Automatique (Maileva / La Poste)",
        "Entièrement automatique depuis l'app : paiement par carte (3-D Secure) puis envoi via l'API Maileva. " +
            "Nécessite votre serveur (Réglages). Le surcoût n'est pas remboursé.",
    ),
}

object ExternalLinks {
    const val MON_TIMBRE_EN_LIGNE = "https://www.laposte.fr/mon-timbre-en-ligne"
    const val APPLI_LA_POSTE = "https://www.laposte.fr/applications/la-poste"
    const val LAPOSTE_LETTRE_EN_LIGNE = "https://www.laposte.fr/envoyer/ecrire-lettre"
    const val MERCI_FACTEUR = "https://www.merci-facteur.com/"

    fun open(context: Context, url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
        }
    }

    /** Ouvre l'appli La Poste si installée (fiche Play Store sinon) pour obtenir un timbre en code. */
    fun openLaPosteApp(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=La%20Poste%20timbre&c=apps"))
        try {
            context.startActivity(market)
        } catch (_: ActivityNotFoundException) {
            open(context, APPLI_LA_POSTE)
        }
    }
}

/** Impression d'un PDF existant via le service d'impression Android. */
class PdfFilePrintAdapter(private val file: File, private val jobName: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

/**
 * Panneau des modes d'envoi « hors API » : impression, Lettre en ligne La Poste,
 * Merci Facteur. Le PDF est généré à la demande et n'est jamais conservé en clair
 * au-delà de la session (cache vidé au démarrage).
 */
@Composable
fun ManualSendPanel(data: AppData, claim: ClaimDraft, method: SendMethod, ready: Boolean, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var pdfToSave by remember { mutableStateOf<File?>(null) }
    var confirmSent by remember { mutableStateOf(false) }

    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pdfToSave ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            info = try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                }
                when (method) {
                    SendMethod.LAPOSTE_ONLINE -> "PDF enregistré. Sur laposte.fr, importez ce fichier comme contenu de la lettre."
                    SendMethod.MERCI_FACTEUR -> "PDF enregistré. Sur Merci Facteur, importez ce fichier comme courrier."
                    else -> "PDF enregistré."
                }
            } catch (e: Exception) {
                "Enregistrement impossible : ${e.message}"
            }
            f.delete()
            pdfToSave = null
        }
    }

    fun withPdf(label: String, action: suspend (File) -> Unit) {
        scope.launch {
            busy = label
            try {
                action(ClaimSender.buildPdf(context, data, claim))
            } catch (e: Exception) {
                info = e.message
            }
            busy = null
        }
    }

    val fileName = "remboursement-${claim.channel.name.lowercase()}-${claim.gameName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}.pdf"

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(method.summary, style = MaterialTheme.typography.bodySmall)

        if (method == SendMethod.PRINT) {
            Text("Adresse à reporter sur l'enveloppe (ou enveloppe à fenêtre) :", fontWeight = FontWeight.Bold)
            claim.rule.recipientLines.forEach { Text(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ready && busy == null, onClick = {
                    withPdf("Préparation de l'impression…") { pdf ->
                        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                        pm.print("Demande de remboursement ${claim.gameName}", PdfFilePrintAdapter(pdf, fileName), null)
                    }
                }) { Text("Imprimer") }
                OutlinedButton(enabled = ready && busy == null, onClick = {
                    withPdf("Préparation du partage…") { pdf ->
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", pdf)
                        val send = Intent(Intent.ACTION_SEND).setType("application/pdf")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Envoyer le PDF vers…"))
                    }
                }) { Text("Partager le PDF") }
            }
            Text("Affranchissement (timbre Lettre verte, remboursé) :", fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { ExternalLinks.open(context, ExternalLinks.MON_TIMBRE_EN_LIGNE) }) { Text("Timbre à imprimer") }
                OutlinedButton(onClick = { ExternalLinks.openLaPosteApp(context) }) { Text("Timbre en code (appli)") }
            }
            Text(
                "Timbre en code : recopiez le code à 8 caractères en haut à droite de l'enveloppe et déposez-la dans une " +
                    "boîte jaune de rue. Gardez une photo de l'enveloppe et du justificatif d'achat.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            val site = if (method == SendMethod.LAPOSTE_ONLINE) ExternalLinks.LAPOSTE_LETTRE_EN_LIGNE else ExternalLinks.MERCI_FACTEUR
            Text("1. Enregistrez le PDF complet (lettre + facture + RIB).")
            Text("2. Ouvrez le site, créez une lettre simple (Lettre verte si proposé), importez le PDF et saisissez l'adresse :")
            claim.rule.recipientLines.forEach { Text("    $it") }
            Text("3. Payez sur le site (paiement sécurisé par carte avec 3-D Secure de votre banque).")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = ready && busy == null, onClick = {
                    withPdf("Génération du PDF…") { pdf ->
                        pdfToSave = pdf
                        saver.launch(fileName)
                    }
                }) { Text("1. Enregistrer le PDF") }
                OutlinedButton(onClick = { ExternalLinks.open(context, site) }) { Text("2. Ouvrir le site") }
            }
        }

        busy?.let { Text(it) }
        info?.let { Text(it) }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = ready && busy == null,
            onClick = { confirmSent = true },
        ) { Text(if (method == SendMethod.PRINT) "J'ai posté le courrier" else "J'ai envoyé le courrier sur le site") }
    }

    if (confirmSent) {
        AlertDialog(
            onDismissRequest = { confirmSent = false },
            title = { Text("Confirmer l'envoi") },
            text = {
                Text(
                    "Les ${claim.participations.size} participation(s) de « ${claim.gameName} » seront marquées comme " +
                        "réclamées (${Money.format(claim.smsTotalCents + claim.stampCents)} demandés). " +
                        "Conservez la preuve d'envoi jusqu'au remboursement.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmSent = false
                    scope.launch {
                        ClaimSender.markSent(claim, UUID.randomUUID().toString(), method, ClaimStatus.POSTED)
                        onSent()
                    }
                }) { Text("Confirmer") }
            },
            dismissButton = { TextButton(onClick = { confirmSent = false }) { Text("Annuler") } },
        )
    }
}
