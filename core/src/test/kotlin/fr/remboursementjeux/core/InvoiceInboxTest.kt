package fr.remboursementjeux.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InvoiceInboxTest {
    private val day = 24 * 60 * 60 * 1000L
    private fun mail(id: String, d: Int) = InvoiceEmail(id, d * day, "Votre facture Prixtel")
    private fun file(id: String, d: Int) = DriveFile(id, "$id.pdf", d * day)

    @Test
    fun `facture en attente tant qu'aucun fichier n'est depose`() {
        val r = InvoiceInbox.pair(listOf(mail("m1", 10)), emptyList())
        assertTrue(r.single().pending)
    }

    @Test
    fun `un fichier depose apres le mail le solde`() {
        val r = InvoiceInbox.pair(listOf(mail("m1", 10), mail("m2", 40)), listOf(file("f1", 12)))
        assertEquals("f1", r[0].file?.id)
        assertTrue(r[1].pending)
    }

    @Test
    fun `un ancien fichier ne solde pas une nouvelle facture`() {
        val r = InvoiceInbox.pair(listOf(mail("m2", 40)), listOf(file("f0", 5)))
        assertTrue(r.single().pending)
    }

    @Test
    fun `appariement un pour un et mails en double ignores`() {
        val r = InvoiceInbox.pair(listOf(mail("m1", 10), mail("m1", 10), mail("m2", 40)), listOf(file("f1", 41), file("f2", 42)))
        assertEquals(2, r.size)
        assertEquals(listOf("f1", "f2"), r.map { it.file?.id })
        assertTrue(InvoiceInbox.pending(listOf(mail("m3", 70)), listOf(file("f1", 41))).isNotEmpty())
    }
}
