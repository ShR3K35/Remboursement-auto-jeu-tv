package fr.remboursementjeux.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Demande de remboursement calculée pour un jeu donné. */
data class ClaimDraft(
    val key: String,
    val channel: Channel,
    val gameName: String,
    val rule: RefundRule,
    /** Participations incluses dans la lettre (dans le délai et sous le plafond). */
    val participations: List<Participation>,
    /** Participations hors délai (non réclamables). */
    val expired: List<Participation>,
    /** Participations au-delà du plafond du règlement. */
    val overLimit: List<Participation>,
    /** Date limite d'envoi (calculée sur la participation la plus ancienne incluse). */
    val deadlineMillis: Long?,
    /** Mois de facture (AAAA-MM) à joindre. */
    val invoiceMonths: List<String>,
    val smsTotalCents: Int,
    val stampCents: Int,
    val warnings: List<String>,
) {
    fun photocopyCents(attachmentPages: Int): Int = rule.photocopyCentsPerPage * attachmentPages
    fun totalCents(attachmentPages: Int): Int = smsTotalCents + stampCents + photocopyCents(attachmentPages)
    val isSendable: Boolean get() = participations.isNotEmpty()
}

class ClaimBuilder(
    private val rules: (Channel) -> RefundRule? = DefaultRules::forChannel,
    private val stampCents: Int = DefaultRules.DEFAULT_STAMP_CENTS,
    private val zone: ZoneId = ZoneId.of("Europe/Paris"),
) {
    private val monthFormat = DateTimeFormatter.ofPattern("yyyy-MM")

    /** Regroupe les participations par chaîne + jeu (une lettre par jeu). */
    fun build(participations: List<Participation>, nowMillis: Long): List<ClaimDraft> =
        Dedup.distinct(participations)
            .filter { !it.excluded && it.claimedLetterId == null && it.channel != Channel.UNKNOWN }
            .groupBy { it.channel to it.gameName }
            .mapNotNull { (k, list) -> buildOne(k.first, k.second, list, nowMillis) }
            .sortedWith(compareBy(nullsLast()) { it.deadlineMillis })

    private fun buildOne(channel: Channel, game: String, list: List<Participation>, now: Long): ClaimDraft? {
        val rule = rules(channel) ?: return null
        val sorted = list.sortedBy { it.timestampMillis }
        val deadlineMs = rule.deadlineDays * DAY_MS

        val (inTime, expired) = sorted.partition { it.timestampMillis + deadlineMs >= now }
        val max = rule.maxParticipationsPerClaim
        val included = if (max != null) inTime.take(max) else inTime
        val overLimit = if (max != null) inTime.drop(max) else emptyList()

        val deadline = included.firstOrNull()?.let { it.timestampMillis + deadlineMs }
        val warnings = buildList {
            if (!rule.addressVerified) add("Adresse par défaut : vérifiez-la dans le règlement du jeu « $game ».")
            if (expired.isNotEmpty()) add("${expired.size} participation(s) hors délai de ${rule.deadlineDays} jours, non incluse(s).")
            if (overLimit.isNotEmpty()) add("${overLimit.size} participation(s) au-delà du plafond de $max par demande.")
            if (deadline != null && deadline - now < 7 * DAY_MS) add("Moins de 7 jours avant la date limite d'envoi !")
            if (included.any { it.confidence == Confidence.LOW && !it.userValidated }) {
                add("Certaines participations sont à confirmer.")
            }
        }

        return ClaimDraft(
            key = "${channel.name}|$game",
            channel = channel,
            gameName = game,
            rule = rule,
            participations = included,
            expired = expired,
            overLimit = overLimit,
            deadlineMillis = deadline,
            invoiceMonths = included.map { monthOf(it.timestampMillis) }.distinct(),
            smsTotalCents = included.sumOf { it.costCents },
            stampCents = if (rule.refundsStamp && included.isNotEmpty()) stampCents else 0,
            warnings = warnings,
        )
    }

    fun monthOf(ts: Long): String = Instant.ofEpochMilli(ts).atZone(zone).format(monthFormat)

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

object Money {
    fun format(cents: Int): String {
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')} €"
    }
}
