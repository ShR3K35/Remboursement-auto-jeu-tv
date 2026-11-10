package fr.remboursementjeux.core

/**
 * Règles de remboursement d'une chaîne, issues des règlements de jeux.
 *
 * Principe légal : les jeux par SMS/appel surtaxé doivent rester « sans
 * obligation d'achat », ce qui oblige l'organisateur à rembourser, sur simple
 * demande écrite, les frais de participation (SMS+), ainsi que le timbre de la
 * demande (tarif lent/Lettre verte) et parfois les photocopies.
 *
 * Chaque règlement de jeu précise l'adresse, le délai et les pièces. Les valeurs
 * par défaut ci-dessous reprennent les règlements consultés ; l'app affiche un
 * avertissement tant que l'adresse n'a pas été vérifiée par l'utilisateur.
 */
data class RefundRule(
    val channel: Channel,
    val organizer: String,
    val recipientLines: List<String>,
    /** Délai pour envoyer la demande, en jours à compter de la participation. */
    val deadlineDays: Int,
    val requiredDocuments: List<RequiredDocument>,
    /** Nombre max. de participations remboursées par demande (null = non limité). */
    val maxParticipationsPerClaim: Int? = null,
    /** Remboursement du timbre de la demande (tarif lent / Lettre verte). */
    val refundsStamp: Boolean = true,
    /** Remboursement des photocopies, en centimes par page (0 = non remboursé). */
    val photocopyCentsPerPage: Int = 0,
    /** true quand l'utilisateur a confirmé l'adresse dans le règlement du jeu. */
    val addressVerified: Boolean = false,
    val notes: String = "",
)

object DefaultRules {
    /** Tarif Lettre verte 20 g en centimes : à mettre à jour dans les réglages. */
    const val DEFAULT_STAMP_CENTS = 152

    val TF1 = RefundRule(
        channel = Channel.TF1,
        organizer = "TF1",
        recipientLines = listOf(
            "TF1 - Service Jeux - Remboursement",
            "1 quai du Point du Jour",
            "92656 BOULOGNE-BILLANCOURT CEDEX",
        ),
        deadlineDays = 90,
        requiredDocuments = listOf(RequiredDocument.DETAILED_INVOICE, RequiredDocument.RIB),
        refundsStamp = true,
        photocopyCentsPerPage = 5,
        notes = "Demande écrite sous 90 jours après la participation, avec RIB et " +
            "copie de la facture détaillée où les SMS du jeu sont identifiés. " +
            "Timbre remboursé au tarif Lettre verte. Traitement pouvant prendre plusieurs mois.",
    )

    val M6 = RefundRule(
        channel = Channel.M6,
        organizer = "M6 (Métropole Télévision)",
        recipientLines = listOf(
            "M6 - Service Jeux - Remboursement",
            "89 avenue Charles de Gaulle",
            "92575 NEUILLY-SUR-SEINE CEDEX",
        ),
        deadlineDays = 90,
        requiredDocuments = listOf(RequiredDocument.DETAILED_INVOICE, RequiredDocument.RIB),
        maxParticipationsPerClaim = 10,
        refundsStamp = true,
        photocopyCentsPerPage = 0,
        notes = "Demande écrite à l'adresse du règlement (en général sous 3 mois), avec " +
            "facture détaillée faisant apparaître les SMS envoyés et RIB ; certains " +
            "règlements demandent aussi une pièce d'identité et plafonnent le nombre " +
            "de participations remboursées (ex. 10 par émission).",
    )

    fun forChannel(channel: Channel): RefundRule? = when (channel) {
        Channel.TF1 -> TF1
        Channel.M6 -> M6
        Channel.UNKNOWN -> null
    }
}
