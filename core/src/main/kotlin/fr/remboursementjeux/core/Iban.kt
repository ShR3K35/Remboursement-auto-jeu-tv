package fr.remboursementjeux.core

import java.math.BigInteger

/** Validation et formatage d'IBAN (ISO 13616, contrôle modulo 97). */
object Iban {
    private val LENGTHS = mapOf(
        "FR" to 27, "MC" to 27, "BE" to 16, "DE" to 22, "ES" to 24, "IT" to 27,
        "LU" to 20, "NL" to 18, "PT" to 25, "CH" to 21, "GB" to 22,
    )

    fun normalize(raw: String): String = raw.replace(" ", "").replace("-", "").uppercase()

    fun isValid(raw: String): Boolean {
        val iban = normalize(raw)
        if (!Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{10,30}$").matches(iban)) return false
        LENGTHS[iban.substring(0, 2)]?.let { if (iban.length != it) return false }
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        val numeric = buildString {
            for (c in rearranged) {
                if (c.isDigit()) append(c) else append(c.code - 'A'.code + 10)
            }
        }
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    fun format(raw: String): String = normalize(raw).chunked(4).joinToString(" ")

    /** Masque l'IBAN pour l'affichage (FR76 **** **** ... 1234). */
    fun mask(raw: String): String {
        val n = normalize(raw)
        if (n.length < 8) return "****"
        return n.take(4) + " **** **** " + n.takeLast(4)
    }
}
