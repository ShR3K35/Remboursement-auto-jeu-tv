package fr.remboursementjeux.core

/** Chaîne organisatrice du jeu. */
enum class Channel(val label: String) {
    TF1("TF1"),
    M6("M6"),
    UNKNOWN("À classer");
}

/** Niveau de confiance de la détection automatique. */
enum class Confidence { HIGH, MEDIUM, LOW }

/** SMS lu dans la boîte « envoyés » du téléphone. */
data class SentSms(
    val id: Long,
    val address: String,
    val body: String,
    val timestampMillis: Long,
)

/**
 * Participation détectée à un jeu par SMS surtaxé.
 * Seuls les SMS envoyés à un numéro court surtaxé sont conservés : le contenu
 * des SMS « normaux » n'est jamais stocké.
 */
data class Participation(
    val smsId: Long,
    val shortCode: String,
    val body: String,
    val timestampMillis: Long,
    val channel: Channel,
    val gameName: String,
    /** Coût de la participation en centimes (estimé, modifiable par l'utilisateur). */
    val costCents: Int,
    val confidence: Confidence,
    /** true si l'utilisateur a corrigé/validé la détection. */
    val userValidated: Boolean = false,
    /** true si la participation est exclue des demandes (ex. pas un jeu). */
    val excluded: Boolean = false,
    /** Identifiant de la lettre envoyée qui a réclamé cette participation. */
    val claimedLetterId: String? = null,
)

/** Pièces justificatives exigées par les règlements. */
enum class RequiredDocument(val label: String) {
    DETAILED_INVOICE("Copie de la facture détaillée de l'opérateur (lignes du jeu identifiées)"),
    RIB("Relevé d'identité bancaire (RIB)"),
    ID_CARD("Copie d'une pièce d'identité du titulaire de la ligne"),
}

/** Statut d'une demande de remboursement (une lettre = une demande). */
enum class ClaimStatus(val label: String) {
    DRAFT("Brouillon"),
    READY("Prête à envoyer"),
    AWAITING_PAYMENT("Paiement en attente"),
    PAID("Payée, envoi en cours"),
    SUBMITTED("Courrier transmis à La Poste"),
    FAILED("Échec"),
}

/** Identité de l'expéditeur (titulaire de la ligne). */
data class SenderProfile(
    val civility: String,
    val firstName: String,
    val lastName: String,
    val addressLine: String,
    val addressComplement: String,
    val postalCode: String,
    val city: String,
    val phoneNumber: String,
    val email: String,
    val iban: String,
    val operatorName: String = "Prixtel",
) {
    val fullName: String get() = listOf(civility, firstName, lastName.uppercase()).filter { it.isNotBlank() }.joinToString(" ")

    fun missingFields(): List<String> = buildList {
        if (firstName.isBlank()) add("prénom")
        if (lastName.isBlank()) add("nom")
        if (addressLine.isBlank()) add("adresse")
        if (!Regex("^\\d{5}$").matches(postalCode.trim())) add("code postal")
        if (city.isBlank()) add("ville")
        if (phoneNumber.isBlank()) add("numéro de téléphone")
        if (!Iban.isValid(iban)) add("IBAN valide")
    }
}
