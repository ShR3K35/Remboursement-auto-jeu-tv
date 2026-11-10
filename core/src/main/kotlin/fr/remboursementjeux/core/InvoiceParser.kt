package fr.remboursementjeux.core

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Ligne de facture correspondant à un SMS surtaxé. */
data class InvoiceLine(
    val timestampMillis: Long,
    val shortCode: String,
    val amountCents: Int?,
    val raw: String,
) {
    val ref: String get() = "$shortCode|$timestampMillis"
}

/**
 * Extraction des SMS surtaxés d'une facture détaillée (texte extrait du PDF).
 *
 * Une ligne est retenue si elle contient une date (JJ/MM ou JJ/MM/AAAA), une heure
 * (HH:MM[:SS] ou HHhMM) et un numéro court SMS+ à 5 chiffres (3xxxx à 8xxxx).
 * Le montant retenu est le dernier montant de la ligne (TTC).
 */
object InvoiceParser {
    private val DATE = Regex("""(?<!\d)(\d{2})[/.-](\d{2})(?:[/.-](\d{4}|\d{2}))?(?!\d)""")
    private val TIME = Regex("""(?<![\d,.])(\d{1,2})[:hH](\d{2})(?::(\d{2}))?(?![\d,])""")
    private val CODE = Regex("""(?<![\d,.:/€-])([3-8]\d{4})(?![\d,.:/])""")
    private val AMOUNT = Regex("""(?<![\d:/])(\d{1,3})[,.](\d{2})(?![\d/])""")

    fun parse(text: String, zone: ZoneId = ZoneId.of("Europe/Paris"), today: LocalDate = LocalDate.now(zone)): List<InvoiceLine> {
        val reference = referenceDate(text, today)
        val out = mutableListOf<InvoiceLine>()
        for (raw in text.lines()) {
            val line = raw.trim()
            val date = DATE.find(line) ?: continue
            val afterDate = line.substring(date.range.last + 1)
            val time = TIME.find(afterDate) ?: TIME.find(line) ?: continue
            // Le numéro court est cherché hors date et heure.
            val masked = line.replaceRange(date.range, " ".repeat(date.value.length))
                .replace(time.value, " ".repeat(time.value.length))
            val code = CODE.find(masked)?.groupValues?.get(1) ?: continue

            val day = date.groupValues[1].toInt()
            val month = date.groupValues[2].toInt()
            if (day !in 1..31 || month !in 1..12) continue
            val year = date.groupValues[3].takeIf { it.isNotEmpty() }?.let { if (it.length == 2) 2000 + it.toInt() else it.toInt() }
                ?: inferYear(month, reference)
            val h = time.groupValues[1].toInt()
            val m = time.groupValues[2].toInt()
            val s = time.groupValues[3].takeIf { it.isNotEmpty() }?.toInt() ?: 0
            if (h > 23 || m > 59 || s > 59) continue
            val ts = runCatching { LocalDateTime.of(year, month, day, h, m, s).atZone(zone).toInstant().toEpochMilli() }
                .getOrNull() ?: continue

            val afterCode = masked.substring(masked.indexOf(code) + code.length)
            val amount = AMOUNT.findAll(afterCode).lastOrNull()?.let { it.groupValues[1].toInt() * 100 + it.groupValues[2].toInt() }
            out += InvoiceLine(ts, code, amount, line)
        }
        return out
    }

    /** Date de référence pour compléter les dates sans année : la plus récente date complète de la facture. */
    private fun referenceDate(text: String, today: LocalDate): LocalDate =
        DATE.findAll(text).mapNotNull { m ->
            val y = m.groupValues[3]
            if (y.length != 4) return@mapNotNull null
            runCatching { LocalDate.of(y.toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull()
        }.filter { !it.isAfter(today.plusDays(31)) }.maxOrNull() ?: today

    private fun inferYear(month: Int, reference: LocalDate): Int =
        if (month > reference.monthValue) reference.year - 1 else reference.year

    /** Convertit les lignes de facture en participations (chaîne via les numéros courts connus). */
    fun toParticipations(lines: List<InvoiceLine>, detector: SmsDetector): List<Participation> = lines.map { l ->
        val base = detector.detect(SentSms(syntheticId(l), l.shortCode, "", l.timestampMillis))!!
        base.copy(
            body = "Ligne de facture : ${l.raw.take(120)}",
            costCents = l.amountCents ?: base.costCents,
            invoiceRef = l.ref,
        )
    }

    /** Identifiant négatif (jamais confondu avec un identifiant SMS Android). */
    fun syntheticId(l: InvoiceLine): Long = -((l.timestampMillis / 1000) * 100_000 + l.shortCode.toLong())
}
