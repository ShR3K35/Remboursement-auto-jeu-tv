package fr.remboursementjeux.app

import android.content.Context
import fr.remboursementjeux.core.ClaimDraft
import fr.remboursementjeux.core.ClaimStatus
import fr.remboursementjeux.core.LetterContent
import fr.remboursementjeux.core.RequiredDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/** Préparation du courrier et suivi de l'envoi après paiement. */
object ClaimSender {

    /** Liste des éléments manquants avant de pouvoir générer la lettre. */
    fun missing(data: AppData, claim: ClaimDraft): List<String> = buildList {
        val profile = data.profile
        if (profile == null) add("Renseignez votre identité dans Réglages")
        else profile.missingFields().takeIf { it.isNotEmpty() }?.let { add("Réglages incomplets : " + it.joinToString()) }
        claim.invoiceMonths.filter { it !in data.invoices }.forEach { add("Facture détaillée de $it") }
        if (RequiredDocument.RIB in claim.rule.requiredDocuments && data.rib == null) add("RIB (Réglages)")
        if (RequiredDocument.ID_CARD in claim.rule.requiredDocuments && data.idCard == null) add("Pièce d'identité (Réglages)")
        if (!claim.rule.addressVerified) add("Confirmez l'adresse du règlement du jeu")
        if (!claim.isSendable) add("Aucune participation dans le délai")
    }

    /** Génère le PDF complet (lettre + pièces). Le fichier est temporaire : à supprimer après usage. */
    suspend fun buildPdf(context: Context, data: AppData, claim: ClaimDraft): File = withContext(Dispatchers.IO) {
        val docs = buildList {
            claim.invoiceMonths.sorted().forEach { add(data.invoices.getValue(it)) }
            if (RequiredDocument.RIB in claim.rule.requiredDocuments) add(data.rib!!)
            if (RequiredDocument.ID_CARD in claim.rule.requiredDocuments) add(data.idCard!!)
        }
        val temps = docs.map { Documents.decryptToTemp(context, it) to it.mimeType }
        try {
            val pages = PdfComposer.attachmentPageCount(temps)
            val letter = LetterContent.build(data.profile!!, claim, LocalDate.now(), pages)
            val out = File(File(context.cacheDir, "previews"), "demande-remboursement-${claim.channel.name.lowercase()}.pdf")
            PdfComposer.compose(context, letter, temps, out)
            out
        } finally {
            temps.forEach { it.first.delete() }
        }
    }

    fun senderAddress(data: AppData): List<String> {
        val p = data.profile!!
        return listOf("", p.fullName, p.addressComplement, p.addressLine, "", "${p.postalCode} ${p.city.uppercase()}")
            .map { it.take(38) }
    }

    suspend fun setState(key: String, transform: (ClaimState) -> ClaimState) {
        Repository.update { d -> d.copy(claims = d.claims + (key to transform(d.claims[key] ?: ClaimState()))) }
    }

    /**
     * Après le paiement : le serveur vérifie lui-même le paiement auprès de Stripe
     * puis transmet le courrier à Maileva. On interroge jusqu'au résultat.
     */
    suspend fun confirmAndTrack(api: ApiClient, claim: ClaimDraft, letterId: String): LetterStatus {
        var last: LetterStatus? = null
        repeat(15) {
            val s = api.confirm(letterId)
            last = s
            when (s.status) {
                "submitted" -> {
                    markSent(claim, s.letterId, SendMethod.MAILEVA, ClaimStatus.SUBMITTED, s.amountCents, s.mailevaSendingId)
                    return s
                }
                "submission_failed", "payment_failed" -> {
                    setState(claim.key) { it.copy(status = ClaimStatus.FAILED, letterId = letterId, error = s.error ?: s.status) }
                    return s
                }
            }
            delay(2_000)
        }
        setState(claim.key) { it.copy(status = ClaimStatus.PAID, letterId = letterId) }
        return last!!
    }

    /** Archive la demande envoyée et marque ses participations comme réclamées. */
    suspend fun markSent(
        claim: ClaimDraft,
        letterId: String,
        method: SendMethod,
        status: ClaimStatus,
        amountPaidCents: Int? = null,
        sendingId: String? = null,
    ) {
        val ids = claim.participations.map { it.smsId to it.timestampMillis }.toSet()
        Repository.update { d ->
            val history = (d.claims[claim.key] ?: ClaimState()).copy(
                status = status,
                letterId = letterId,
                amountPaidCents = amountPaidCents,
                mailevaSendingId = sendingId,
                submittedAtMillis = System.currentTimeMillis(),
                error = null,
                method = method.name,
            )
            d.copy(
                participations = d.participations.map {
                    if ((it.smsId to it.timestampMillis) in ids) it.copy(claimedLetterId = letterId) else it
                },
                claims = (d.claims - claim.key) + ("${claim.key}|$letterId" to history),
            )
        }
    }
}
