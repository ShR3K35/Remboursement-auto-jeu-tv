package fr.remboursementjeux.core

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SmsDetectorTest {
    private val detector = SmsDetector()

    @Test
    fun `ignore les SMS vers des numeros classiques`() {
        assertNull(detector.detect(SentSms(1, "+33612345678", "Coucou TF1", 0)))
        assertNull(detector.detect(SentSms(2, "0612345678", "M6", 0)))
        assertNull(detector.detect(SentSms(3, "112", "x", 0)))
        assertNull(detector.detect(SentSms(4, "21234", "TF1", 0))) // pas un SMS+
    }

    @Test
    fun `detecte un jeu TF1 par mot-cle d'emission`() {
        val p = assertNotNull(detector.detect(SentSms(1, "71414", "12 coups A", 1000)))
        assertEquals(Channel.TF1, p.channel)
        assertEquals("Les 12 coups de midi", p.gameName)
        assertEquals(Confidence.MEDIUM, p.confidence)
        assertEquals(99, p.costCents)
    }

    @Test
    fun `detecte M6 avec accents et casse`() {
        val p = assertNotNull(detector.detect(SentSms(1, "7 3 3 3 3", "pékin express B", 0)))
        assertEquals(Channel.M6, p.channel)
        assertEquals("Pékin Express", p.gameName)
        assertEquals("73333", p.shortCode)
    }

    @Test
    fun `mot-cle de chaine generique`() {
        val p = assertNotNull(detector.detect(SentSms(1, "74444", "JEU M6 REPONSE A", 0)))
        assertEquals(Channel.M6, p.channel)
        assertEquals(Confidence.MEDIUM, p.confidence)
    }

    @Test
    fun `M6 n'est pas detecte dans un mot plus long`() {
        val p = assertNotNull(detector.detect(SentSms(1, "74444", "CODEM67", 0)))
        assertEquals(Channel.UNKNOWN, p.channel)
        assertEquals(Confidence.LOW, p.confidence)
    }

    @Test
    fun `numero court connu donne une confiance haute`() {
        val d = SmsDetector(listOf(DefaultProfiles.TF1.copy(shortCodes = setOf("71030"))))
        val p = assertNotNull(d.detect(SentSms(1, "71030", "A", 0)))
        assertEquals(Channel.TF1, p.channel)
        assertEquals(Confidence.HIGH, p.confidence)
    }

    @Test
    fun `indicatif 33 retire`() {
        assertEquals("71414", SmsDetector.normalizeAddress("+33 71414"))
        assertTrue(detector.isPremiumShortCode("+3371414"))
    }
}

class IbanTest {
    @Test
    fun validation() {
        assertTrue(Iban.isValid("FR76 3000 6000 0112 3456 7890 189"))
        assertFalse(Iban.isValid("FR76 3000 6000 0112 3456 7890 188"))
        assertFalse(Iban.isValid("FR76"))
        assertEquals("FR76 **** **** 0189", Iban.mask("FR7630006000011234567890189"))
    }
}

class ClaimBuilderTest {
    private val zone = ZoneId.of("Europe/Paris")
    private fun ts(y: Int, m: Int, d: Int, h: Int = 12) = ZonedDateTime.of(y, m, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun p(id: Long, t: Long, ch: Channel = Channel.TF1, game: String = "Les 12 coups de midi") =
        Participation(id, "71414", "x", t, ch, game, 99, Confidence.MEDIUM)

    @Test
    fun `regroupe par jeu et calcule montants et echeance`() {
        val now = ts(2026, 11, 10)
        val list = listOf(
            p(1, ts(2026, 10, 1)), p(2, ts(2026, 10, 15)), p(3, ts(2026, 11, 2)),
            p(4, ts(2026, 11, 3), Channel.M6, "Top Chef"),
            p(5, ts(2026, 11, 3), Channel.UNKNOWN, "?"),
        )
        val claims = ClaimBuilder(stampCents = 152).build(list, now)
        assertEquals(2, claims.size)
        val tf1 = claims.first { it.channel == Channel.TF1 }
        assertEquals(3, tf1.participations.size)
        assertEquals(297, tf1.smsTotalCents)
        assertEquals(152, tf1.stampCents)
        assertEquals(listOf("2026-10", "2026-11"), tf1.invoiceMonths)
        assertEquals(ts(2026, 10, 1) + 90 * ClaimBuilder.DAY_MS, tf1.deadlineMillis)
        // 4 pages de pièces jointes x 5 centimes pour TF1
        assertEquals(297 + 152 + 20, tf1.totalCents(4))
    }

    @Test
    fun `exclut les participations hors delai et applique le plafond`() {
        val now = ts(2026, 11, 10)
        val old = p(1, ts(2026, 6, 1), Channel.M6, "Top Chef")
        val recent = (2L..13L).map { p(it, ts(2026, 11, 1) + it * 60_000, Channel.M6, "Top Chef") }
        val claim = ClaimBuilder().build(listOf(old) + recent, now).single()
        assertEquals(listOf(old), claim.expired)
        assertEquals(10, claim.participations.size)
        assertEquals(2, claim.overLimit.size)
        assertTrue(claim.warnings.any { it.contains("hors délai") })
    }

    @Test
    fun `participations exclues ignorees`() {
        val claims = ClaimBuilder().build(
            listOf(p(1, ts(2026, 11, 1)).copy(excluded = true), p(2, ts(2026, 11, 1)).copy(claimedLetterId = "L1")),
            ts(2026, 11, 10),
        )
        assertTrue(claims.isEmpty())
    }
}

class LetterContentTest {
    @Test
    fun `genere une lettre complete`() {
        val zone = ZoneId.of("Europe/Paris")
        val t = ZonedDateTime.of(2026, 11, 2, 20, 45, 0, 0, zone).toInstant().toEpochMilli()
        val claim = ClaimBuilder().build(
            listOf(Participation(1, "71414", "A", t, Channel.TF1, "Koh-Lanta", 99, Confidence.HIGH)),
            t + 1000,
        ).single()
        val sender = SenderProfile("M.", "Jean", "Dupont", "1 rue de la Paix", "", "75002", "Paris",
            "06 12 34 56 78", "", "FR7630006000011234567890189")
        assertTrue(sender.missingFields().isEmpty())
        val letter = LetterContent.build(sender, claim, LocalDate.of(2026, 11, 10), attachmentPages = 3)
        assertEquals("Paris, le 10 novembre 2026", letter.placeAndDate)
        assertEquals(listOf("02/11/2026", "20:45", "71414", "0,99 €"), letter.tableRows.single())
        assertEquals("Total demandé" to "2,66 €", letter.totals.last()) // 0,99 + 1,52 + 3 x 0,05
        assertTrue(letter.paragraphsAfter.first().contains("FR76 3000 6000 0112 3456 7890 189"))
        assertTrue(letter.recipientLines.first().startsWith("TF1"))
    }
}
