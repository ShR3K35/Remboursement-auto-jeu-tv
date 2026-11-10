package fr.remboursementjeux.core

import java.text.Normalizer

/** Règle d'identification d'un jeu (mots-clés de l'émission, numéros courts connus). */
data class GameRule(
    val name: String,
    val keywords: List<String> = emptyList(),
    val shortCodes: Set<String> = emptySet(),
)

/** Profil de détection d'une chaîne. Modifiable par l'utilisateur dans l'app. */
data class ChannelProfile(
    val channel: Channel,
    val keywords: List<String>,
    val shortCodes: Set<String> = emptySet(),
    val games: List<GameRule> = emptyList(),
)

/**
 * Détecte, parmi les SMS envoyés, ceux qui correspondent à une participation à un
 * jeu télévisé par SMS surtaxé (SMS+).
 *
 * En France, les SMS+ sont envoyés à des numéros courts à 5 chiffres commençant
 * par 3 à 8 ; le premier chiffre indique le palier de prix. Les jeux TV utilisent
 * le plus souvent des numéros en 7xxxx (≈ 0,99 € + prix d'un SMS).
 */
class SmsDetector(
    private val profiles: List<ChannelProfile> = DefaultProfiles.ALL,
    private val pricing: SmsPricing = SmsPricing(),
) {
    fun isPremiumShortCode(address: String): Boolean = SHORT_CODE.matches(normalizeAddress(address))

    /** Retourne la participation détectée, ou null si le SMS n'est pas un SMS+ de jeu. */
    fun detect(sms: SentSms): Participation? {
        val code = normalizeAddress(sms.address)
        if (!SHORT_CODE.matches(code)) return null
        val text = normalizeText(sms.body)

        var best: Match? = null
        for (profile in profiles) {
            val m = matchProfile(profile, code, text) ?: continue
            if (best == null || m.score > best.score) best = m
        }

        val cost = pricing.estimateCents(code)
        return if (best != null) {
            Participation(
                smsId = sms.id,
                shortCode = code,
                body = sms.body,
                timestampMillis = sms.timestampMillis,
                channel = best.channel,
                gameName = best.gameName,
                costCents = cost,
                confidence = best.confidence,
            )
        } else {
            // SMS+ sans indice de chaîne : conservé « à classer » pour que l'utilisateur décide.
            Participation(
                smsId = sms.id,
                shortCode = code,
                body = sms.body,
                timestampMillis = sms.timestampMillis,
                channel = Channel.UNKNOWN,
                gameName = "Jeu SMS $code",
                costCents = cost,
                confidence = Confidence.LOW,
            )
        }
    }

    fun detectAll(messages: List<SentSms>): List<Participation> = messages.mapNotNull(::detect)

    private data class Match(val channel: Channel, val gameName: String, val confidence: Confidence, val score: Int)

    private fun matchProfile(profile: ChannelProfile, code: String, text: String): Match? {
        // 1. Jeu reconnu par numéro court ou mot-clé d'émission.
        for (game in profile.games) {
            val byCode = code in game.shortCodes
            val byKeyword = game.keywords.any { containsWord(text, normalizeText(it)) }
            if (byCode && byKeyword) return Match(profile.channel, game.name, Confidence.HIGH, 100)
            if (byCode) return Match(profile.channel, game.name, Confidence.HIGH, 90)
            if (byKeyword) return Match(profile.channel, game.name, Confidence.MEDIUM, 70)
        }
        // 2. Numéro court connu de la chaîne.
        if (code in profile.shortCodes) {
            return Match(profile.channel, "Jeu ${profile.channel.label} ($code)", Confidence.HIGH, 80)
        }
        // 3. Mot-clé générique de la chaîne dans le SMS.
        if (profile.keywords.any { containsWord(text, normalizeText(it)) }) {
            return Match(profile.channel, "Jeu ${profile.channel.label} ($code)", Confidence.MEDIUM, 50)
        }
        return null
    }

    companion object {
        private val SHORT_CODE = Regex("^[3-8]\\d{4}$")

        fun normalizeAddress(address: String): String {
            val digits = address.filter { it.isDigit() }
            // Certains téléphones enregistrent le numéro court avec l'indicatif (+33 71414).
            return if (digits.length == 7 && digits.startsWith("33")) digits.substring(2) else digits
        }

        fun normalizeText(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .uppercase()
                .replace(Regex("[^A-Z0-9]+"), " ")
                .trim()

        private fun containsWord(text: String, needle: String): Boolean {
            if (needle.isBlank()) return false
            return Regex("(^| )${Regex.escape(needle)}( |$)").containsMatchIn(text)
        }
    }
}

/**
 * Estimation du coût d'un SMS+ selon le premier chiffre du numéro court
 * (palier tarifaire), hors prix du SMS standard (inclus dans les forfaits).
 * Ce n'est qu'une estimation : le montant réel figure sur la facture détaillée.
 */
data class SmsPricing(
    val centsByFirstDigit: Map<Char, Int> = mapOf(
        '3' to 0, '4' to 5, '5' to 35, '6' to 50, '7' to 99, '8' to 150,
    ),
) {
    fun estimateCents(shortCode: String): Int = centsByFirstDigit[shortCode.firstOrNull()] ?: 0
}

/**
 * Profils par défaut. Les mots-clés sont des indices : l'utilisateur peut
 * compléter les numéros courts relevés dans le règlement de chaque jeu.
 */
object DefaultProfiles {
    val TF1 = ChannelProfile(
        channel = Channel.TF1,
        keywords = listOf("TF1", "TFX", "TMC", "LCI"),
        games = listOf(
            GameRule("Les 12 coups de midi", listOf("12 COUPS", "DOUZE COUPS", "ETOILE MYSTERIEUSE")),
            GameRule("Koh-Lanta", listOf("KOH LANTA", "KOHLANTA")),
            GameRule("Star Academy", listOf("STAR ACADEMY", "STARAC")),
            GameRule("Danse avec les stars", listOf("DALS", "DANSE AVEC LES STARS")),
            GameRule("The Voice", listOf("THE VOICE", "VOICE")),
            GameRule("Qui veut gagner des millions", listOf("MILLIONS", "QVGDM")),
            GameRule("Journal de TF1 (jeu)", listOf("JT TF1", "JOURNAL TF1")),
        ),
    )

    val M6 = ChannelProfile(
        channel = Channel.M6,
        keywords = listOf("M6", "W9", "6TER", "6PLAY", "M6PLUS"),
        games = listOf(
            GameRule("Pékin Express", listOf("PEKIN", "PEKIN EXPRESS")),
            GameRule("Le Meilleur Pâtissier", listOf("PATISSIER", "MEILLEUR PATISSIER")),
            GameRule("Top Chef", listOf("TOP CHEF", "TOPCHEF")),
            GameRule("L'amour est dans le pré", listOf("AMOUR EST DANS LE PRE", "ADP")),
            GameRule("Mariés au premier regard", listOf("MAPR", "MARIES AU PREMIER REGARD")),
            GameRule("Le 12.45 / 19.45 (jeu)", listOf("1245", "1945", "12 45", "19 45")),
        ),
    )

    val ALL = listOf(TF1, M6)
}
