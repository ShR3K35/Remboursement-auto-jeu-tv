package fr.remboursementjeux.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Contenu textuel de la lettre, indépendant du moteur de rendu PDF. */
data class Letter(
    val senderLines: List<String>,
    val recipientLines: List<String>,
    val placeAndDate: String,
    val subject: String,
    val paragraphsBefore: List<String>,
    val tableHeader: List<String>,
    val tableRows: List<List<String>>,
    val totals: List<Pair<String, String>>,
    val paragraphsAfter: List<String>,
    val signature: String,
    val attachments: List<String>,
)

object LetterContent {
    private val FR = Locale.FRENCH
    private val dateLong = DateTimeFormatter.ofPattern("d MMMM yyyy", FR)
    private val dateShort = DateTimeFormatter.ofPattern("dd/MM/yyyy", FR)
    private val time = DateTimeFormatter.ofPattern("HH:mm", FR)

    fun build(
        sender: SenderProfile,
        claim: ClaimDraft,
        today: LocalDate,
        attachmentPages: Int,
        zone: ZoneId = ZoneId.of("Europe/Paris"),
    ): Letter {
        require(claim.isSendable) { "Aucune participation à rembourser" }
        val rule = claim.rule

        val senderLines = listOfNotNull(
            sender.fullName,
            sender.addressLine,
            sender.addressComplement.takeIf { it.isNotBlank() },
            "${sender.postalCode} ${sender.city.uppercase()}",
            "Tél. : ${sender.phoneNumber}",
            sender.email.takeIf { it.isNotBlank() },
        )

        val rows = claim.participations.map {
            val dt = Instant.ofEpochMilli(it.timestampMillis).atZone(zone)
            listOf(dt.format(dateShort), dt.format(time), it.shortCode, Money.format(it.costCents))
        }

        val totals = buildList {
            add("Frais de participation (${claim.participations.size} SMS)" to Money.format(claim.smsTotalCents))
            if (claim.stampCents > 0) add("Timbre de la présente demande (tarif lent)" to Money.format(claim.stampCents))
            val copies = claim.photocopyCents(attachmentPages)
            if (copies > 0) add("Photocopies ($attachmentPages page(s))" to Money.format(copies))
            add("Total demandé" to Money.format(claim.totalCents(attachmentPages)))
        }

        val first = claim.participations.first().timestampMillis.let { Instant.ofEpochMilli(it).atZone(zone).format(dateLong) }
        val last = claim.participations.last().timestampMillis.let { Instant.ofEpochMilli(it).atZone(zone).format(dateLong) }
        val period = if (first == last) "le $first" else "entre le $first et le $last"

        val before = listOf(
            "Madame, Monsieur,",
            "J'ai participé $period au jeu « ${claim.gameName} » organisé par ${rule.organizer}, " +
                "par SMS surtaxé envoyé depuis la ligne ${sender.phoneNumber} (opérateur ${sender.operatorName}), " +
                "dont je suis titulaire.",
            "Conformément au règlement du jeu, qui prévoit le remboursement des frais de participation " +
                "sur simple demande écrite, je vous prie de bien vouloir me rembourser les frais suivants :",
        )

        val after = listOf(
            "Les SMS concernés sont identifiés sur la copie de ma facture détaillée jointe. " +
                "Je vous remercie d'effectuer le remboursement par virement sur le compte suivant : " +
                "IBAN ${Iban.format(sender.iban)} (RIB joint).",
            "Je vous prie d'agréer, Madame, Monsieur, l'expression de mes salutations distinguées.",
        )

        val attachments = rule.requiredDocuments.map { it.label } + "Relevé des participations (ci-dessus)"

        return Letter(
            senderLines = senderLines,
            recipientLines = rule.recipientLines,
            placeAndDate = "${sender.city}, le ${today.format(dateLong)}",
            subject = "Objet : demande de remboursement des frais de participation au jeu « ${claim.gameName} »",
            paragraphsBefore = before,
            tableHeader = listOf("Date", "Heure", "Numéro", "Montant"),
            tableRows = rows,
            totals = totals,
            paragraphsAfter = after,
            signature = sender.fullName,
            attachments = attachments,
        )
    }
}
