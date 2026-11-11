package fr.remboursementjeux.app

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fr.remboursementjeux.core.ClaimBuilder
import fr.remboursementjeux.core.ClaimStatus
import fr.remboursementjeux.core.Participation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingScan: Job? = null
    private var observerRegistered = false

    override fun onCreate() {
        super.onCreate()
        Repository.init(this)
        Notifier.createChannel(this)
        Documents.clearTemp(this)
        registerSmsObserver()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "scan-sms",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ScanWorker>(15, TimeUnit.MINUTES).build(),
        )
        // Une fois par jour : mails Prixtel (via le script Google) et factures déposées dans Drive.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "drive-sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<DriveWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** Écoute en temps réel la base SMS tant que l'app tourne ; le worker prend le relais sinon. */
    fun registerSmsObserver() {
        if (observerRegistered || !SmsScanner.hasPermission(this)) return
        observerRegistered = true
        contentResolver.registerContentObserver(
            Telephony.Sms.CONTENT_URI,
            true,
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    pendingScan?.cancel()
                    pendingScan = scope.launch {
                        delay(3_000) // regroupe les notifications successives
                        val added = SmsScanner.scan(this@App)
                        Notifier.notifyNew(this@App, added)
                    }
                }
            },
        )
    }
}

/** Tâche périodique : scan des SMS envoyés + rappels d'échéance. */
class ScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repository.init(applicationContext)
        val added = SmsScanner.scan(applicationContext)
        Notifier.notifyNew(applicationContext, added)
        Notifier.remindDeadlines(applicationContext)
        return Result.success()
    }
}

object Notifier {
    private const val CHANNEL = "participations"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL, "Participations et échéances", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun post(context: Context, id: Int, title: String, text: String) {
        if (!canNotify(context)) return
        val intent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    suspend fun notifyInvoices(context: Context, r: DriveSync.Result) {
        if (r.imported.isNotEmpty()) {
            post(context, 3, "${r.imported.size} facture(s) importée(s) depuis Google Drive", r.messages.joinToString("\n"))
        }
        val notified = Repository.data.value.inbox.notifiedEmailIds
        val fresh = r.pending.filter { it.id !in notified }
        if (fresh.isNotEmpty()) {
            val fmt = DateFormat.getDateInstance(DateFormat.LONG)
            post(
                context, 4, "Facture Prixtel à déposer dans Google Drive",
                fresh.joinToString("\n") { "Reçue le ${fmt.format(Date(it.receivedMillis))} : ${it.subject}" } +
                    "\nElle n'est pas jointe au mail : récupérez-la dans l'espace client Prixtel (bouton dans l'app, " +
                    "onglet Participations) puis déposez-la dans votre dossier Drive, ou importez-la directement.",
            )
            Repository.update { d -> d.copy(inbox = d.inbox.copy(notifiedEmailIds = d.inbox.notifiedEmailIds + fresh.map { it.id })) }
        }
    }

    fun notifyNew(context: Context, added: List<Participation>) {
        if (added.isEmpty()) return
        val games = added.joinToString { "${it.channel.label} – ${it.gameName}" }
        post(context, 1, "${added.size} participation(s) à un jeu détectée(s)", "$games. Ouvrez l'app pour préparer la demande de remboursement.")
    }

    suspend fun remindDeadlines(context: Context) {
        val data = Repository.data.value
        val now = System.currentTimeMillis()
        val due = Repository.claims(data, now).filter { c ->
            val state = data.claims[c.key]
            val deadline = c.deadlineMillis ?: return@filter false
            (state == null || state.status == ClaimStatus.DRAFT || state.status == ClaimStatus.READY) &&
                deadline - now in 0..(14 * ClaimBuilder.DAY_MS) &&
                now - (state?.lastReminderMillis ?: 0) > ClaimBuilder.DAY_MS
        }
        if (due.isEmpty()) return
        val fmt = DateFormat.getDateInstance(DateFormat.LONG)
        val text = due.joinToString("\n") { "${it.channel.label} – ${it.gameName} : avant le ${fmt.format(Date(it.deadlineMillis!!))}" }
        post(context, 2, "Demandes de remboursement à envoyer bientôt", text)
        Repository.update { d ->
            d.copy(claims = d.claims + due.associate { it.key to (d.claims[it.key] ?: ClaimState()).copy(lastReminderMillis = now) })
        }
    }
}
