package fr.remboursementjeux.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import fr.remboursementjeux.core.ClaimBuilder
import fr.remboursementjeux.core.Dedup
import fr.remboursementjeux.core.Participation
import fr.remboursementjeux.core.SentSms
import fr.remboursementjeux.core.SmsDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Parcourt la boîte « envoyés » et enregistre les SMS+ de jeux.
 * Seuls les SMS envoyés à un numéro court surtaxé sont conservés ; les autres
 * ne sont ni stockés ni transmis.
 */
object SmsScanner {
    /** Au premier lancement, on remonte sur 120 jours (au-delà du délai de 90 jours des règlements). */
    private const val FIRST_SCAN_DAYS = 120L

    /** false dans la version « facture » (pas de permission SMS). */
    val enabled: Boolean get() = BuildConfig.SMS_DETECTION

    fun hasPermission(context: Context) = enabled &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    /** @return les nouvelles participations détectées. */
    suspend fun scan(context: Context): List<Participation> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        val data = Repository.data.value
        val now = System.currentTimeMillis()
        val since = if (data.lastScanMillis == 0L) now - FIRST_SCAN_DAYS * ClaimBuilder.DAY_MS
        else data.lastScanMillis - 10 * 60_000 // recouvrement : SMS horodatés en retard

        val messages = mutableListOf<SentSms>()
        context.contentResolver.query(
            Telephony.Sms.Sent.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE} > ?",
            arrayOf(since.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(Telephony.Sms._ID)
            val iAddr = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val iBody = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val iDate = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (c.moveToNext()) {
                val address = c.getString(iAddr) ?: continue
                messages += SentSms(c.getLong(iId), address, c.getString(iBody) ?: "", c.getLong(iDate))
            }
        }

        val detector = SmsDetector(Repository.profiles(data))
        val detected = detector.detectAll(messages)
        var added = emptyList<Participation>()
        Repository.update { d ->
            // Fusion anti-doublon : un SMS déjà connu, ou déjà présent via la facture, n'est pas ajouté.
            val merged = Dedup.merge(d.participations, detected)
            added = merged.added
            d.copy(participations = merged.participations, lastScanMillis = now)
        }
        added
    }
}
