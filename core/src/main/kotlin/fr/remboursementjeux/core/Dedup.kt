package fr.remboursementjeux.core

import kotlin.math.abs

/**
 * Évite qu'un même SMS soit compté (et réclamé) deux fois lorsqu'il est détecté
 * à la fois dans les SMS du téléphone et sur la facture détaillée, ou lors
 * d'imports répétés de la même facture.
 *
 * Règles :
 *  - un SMS (identifiant Android) n'est jamais ajouté deux fois ;
 *  - une ligne de facture (référence) n'est jamais ajoutée deux fois ;
 *  - un SMS et une ligne de facture vers le même numéro court, à moins de
 *    [TOLERANCE_MS] d'écart, sont la même participation : ils sont fusionnés
 *    (appariement un pour un : deux SMS réels restent deux participations) ;
 *  - une participation déjà réclamée le reste après fusion.
 */
object Dedup {
    /** Écart toléré entre l'heure du SMS sur le téléphone et l'heure facturée. */
    const val TOLERANCE_MS = 5 * 60_000L

    data class MergeResult(
        val participations: List<Participation>,
        val added: List<Participation>,
        /** Participations entrantes reconnues comme déjà connues (doublons évités). */
        val duplicates: Int,
        /** Dont : appariements SMS ↔ facture. */
        val pairedWithInvoice: Int,
    )

    fun merge(existing: List<Participation>, incoming: List<Participation>, tolerance: Long = TOLERANCE_MS): MergeResult {
        val result = existing.toMutableList()
        val added = mutableListOf<Participation>()
        var duplicates = 0
        var paired = 0

        for (inc in incoming.sortedBy { it.timestampMillis }) {
            // 1. Déjà connue à l'identique.
            val sameSms = inc.fromSms && result.any { it.fromSms && it.smsId == inc.smsId && it.timestampMillis == inc.timestampMillis }
            val sameLine = inc.invoiceRef != null && result.any { it.invoiceRef == inc.invoiceRef }
            if (sameSms || sameLine) {
                duplicates++
                continue
            }
            // 2. Même SMS vu par l'autre source : on fusionne avec la plus proche dans le temps.
            val idx = result.indices
                .filter { i ->
                    val e = result[i]
                    e.shortCode == inc.shortCode &&
                        abs(e.timestampMillis - inc.timestampMillis) <= tolerance &&
                        ((inc.fromSms && !e.fromSms && e.fromInvoice) || (inc.fromInvoice && !e.fromInvoice && e.fromSms))
                }
                .minByOrNull { abs(result[it].timestampMillis - inc.timestampMillis) }
            if (idx != null) {
                result[idx] = combine(result[idx], inc)
                duplicates++
                paired++
            } else {
                result += inc
                added += inc
            }
        }
        return MergeResult(result, added, duplicates, paired)
    }

    /** Fusionne la version SMS et la version facture d'une même participation. */
    private fun combine(existing: Participation, incoming: Participation): Participation {
        val sms = if (existing.fromSms) existing else incoming
        val line = if (existing.fromInvoice) existing else incoming
        // Les choix de l'utilisateur et l'état de réclamation de la participation existante priment.
        val classification = when {
            existing.userValidated || existing.claimedLetterId != null -> existing
            existing.channel == Channel.UNKNOWN && incoming.channel != Channel.UNKNOWN -> incoming
            else -> existing
        }
        return sms.copy(
            channel = classification.channel,
            gameName = classification.gameName,
            confidence = classification.confidence,
            userValidated = existing.userValidated,
            excluded = existing.excluded,
            claimedLetterId = existing.claimedLetterId ?: incoming.claimedLetterId,
            invoiceRef = line.invoiceRef,
            costCents = line.costCents, // montant réellement facturé
        )
    }

    /** Filet de sécurité : retire les doublons éventuels d'une liste (même SMS ou même ligne de facture). */
    fun distinct(list: List<Participation>): List<Participation> {
        val seenSms = HashSet<Pair<Long, Long>>()
        val seenLines = HashSet<String>()
        return list.filter { p ->
            val smsKey = p.smsId to p.timestampMillis
            val newSms = !p.fromSms || seenSms.add(smsKey)
            val newLine = p.invoiceRef == null || seenLines.add(p.invoiceRef)
            newSms && newLine
        }
    }
}
