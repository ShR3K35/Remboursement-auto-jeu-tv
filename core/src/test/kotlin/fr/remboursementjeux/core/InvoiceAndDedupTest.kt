package fr.remboursementjeux.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InvoiceParserTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun ts(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, zone).toInstant().toEpochMilli()

    private val invoice = """
        PRIXTEL - Facture du 05/11/2026
        Client : Jean DUPONT 1 rue de la Paix 75002 PARIS
        Ligne 06 12 34 56 78
        Détail des communications
        Date       Heure     Numéro      Type        Durée/Vol.  Montant TTC
        12/10/2026 20:45:12  0698765432  SMS         1           0,00
        12/10/2026 20:46:03  71414       SMS+        1           0,99 €
        13/10 21h02          71414       SMS surtaxé              0,99
        14/10/26 09:15:00    36179       Appel 0,80€/min  00:02:10    1,60
        15/10/2026 18:00:00  0800123456  Appel       00:01:00    0,00
        Total hors forfait 3,58 €
    """.trimIndent()

    @Test
    fun `extrait les SMS surtaxes avec date heure numero et montant`() {
        val lines = InvoiceParser.parse(invoice, zone, LocalDate.of(2026, 11, 10))
        assertEquals(listOf("71414", "71414", "36179"), lines.map { it.shortCode })
        assertEquals(ts(2026, 10, 12, 20, 46, 3), lines[0].timestampMillis)
        assertEquals(99, lines[0].amountCents)
        // Date sans année : complétée par la date de la facture
        assertEquals(ts(2026, 10, 13, 21, 2), lines[1].timestampMillis)
        assertEquals(160, lines[2].amountCents)
    }

    @Test
    fun `annee precedente pour un mois posterieur a la facture`() {
        val lines = InvoiceParser.parse("Facture du 03/01/2027\n28/12 22:10 71414 0,99", zone, LocalDate.of(2027, 1, 10))
        assertEquals(ts(2026, 12, 28, 22, 10), lines.single().timestampMillis)
    }

    @Test
    fun `conversion en participations avec montant facture`() {
        val lines = InvoiceParser.parse(invoice, zone, LocalDate.of(2026, 11, 10))
        val detector = SmsDetector(listOf(DefaultProfiles.TF1.copy(shortCodes = setOf("71414"))))
        val parts = InvoiceParser.toParticipations(lines, detector)
        assertTrue(parts.all { it.fromInvoice && !it.fromSms && it.smsId < 0 })
        assertEquals(Channel.TF1, parts[0].channel)
        assertEquals(Channel.UNKNOWN, parts[2].channel)
        assertEquals(160, parts[2].costCents)
    }
}

class DedupTest {
    private fun sms(id: Long, t: Long, code: String = "71414") =
        Participation(id, code, "12 coups A", t, Channel.TF1, "Les 12 coups de midi", 99, Confidence.MEDIUM)

    private fun line(t: Long, code: String = "71414", cost: Int = 109) =
        Participation(-t, code, "ligne", t, Channel.UNKNOWN, "Jeu SMS $code", cost, Confidence.LOW, invoiceRef = "$code|$t")

    private val t0 = 1_790_000_000_000L

    @Test
    fun `un SMS et sa ligne de facture ne font qu'une participation`() {
        val r = Dedup.merge(listOf(sms(10, t0)), listOf(line(t0 + 40_000)))
        assertEquals(1, r.participations.size)
        assertEquals(1, r.pairedWithInvoice)
        val p = r.participations.single()
        assertTrue(p.fromSms && p.fromInvoice)
        assertEquals(109, p.costCents) // montant facturé
        assertEquals(Channel.TF1, p.channel) // classification issue du SMS
    }

    @Test
    fun `facture importee avant les SMS puis SMS scannes`() {
        val afterInvoice = Dedup.merge(emptyList(), listOf(line(t0))).participations
        val r = Dedup.merge(afterInvoice, listOf(sms(10, t0 - 30_000)))
        assertEquals(1, r.participations.size)
        assertEquals(10, r.participations.single().smsId)
        assertEquals(Channel.TF1, r.participations.single().channel)
    }

    @Test
    fun `reimport de la meme facture et rescan des memes SMS sans doublon`() {
        var list = Dedup.merge(emptyList(), listOf(sms(10, t0), sms(11, t0 + 60_000))).participations
        list = Dedup.merge(list, listOf(line(t0), line(t0 + 60_000))).participations
        val again = Dedup.merge(list, listOf(line(t0), line(t0 + 60_000), sms(10, t0), sms(11, t0 + 60_000)))
        assertEquals(2, again.participations.size)
        assertTrue(again.added.isEmpty())
        assertEquals(4, again.duplicates)
    }

    @Test
    fun `deux vrais SMS rapproches restent deux participations`() {
        val r = Dedup.merge(listOf(sms(10, t0)), listOf(sms(11, t0 + 30_000)))
        assertEquals(2, r.participations.size)
    }

    @Test
    fun `une participation deja reclamee le reste apres fusion`() {
        val claimed = sms(10, t0).copy(claimedLetterId = "L1")
        val r = Dedup.merge(listOf(claimed), listOf(line(t0 + 10_000)))
        assertEquals("L1", r.participations.single().claimedLetterId)
        assertTrue(ClaimBuilder().build(r.participations, t0 + 1000).isEmpty())
    }

    @Test
    fun `numero different ou trop eloigne non fusionne`() {
        val r = Dedup.merge(listOf(sms(10, t0)), listOf(line(t0, "73333"), line(t0 + 20 * 60_000)))
        assertEquals(3, r.participations.size)
    }

    @Test
    fun `une demande ne contient jamais deux fois le meme SMS`() {
        val p = sms(10, t0)
        val claims = ClaimBuilder().build(listOf(p, p.copy(), line(t0 + 5_000).copy(channel = Channel.TF1, gameName = p.gameName, invoiceRef = "x"), line(t0 + 5_000).copy(channel = Channel.TF1, gameName = p.gameName, invoiceRef = "x")), t0 + 1000)
        assertEquals(2, claims.single().participations.size)
        assertNull(claims.single().participations.firstOrNull { it.excluded })
    }
}
