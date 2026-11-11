package fr.remboursementjeux.app

import android.Manifest
import android.os.Build
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.lifecycleScope
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.launch

/** Pont entre l'UI Compose et la PaymentSheet Stripe (liée à l'Activity). */
fun interface PaymentLauncher {
    fun launch(clientSecret: String, publishableKey: String, onResult: (PaymentSheetResult) -> Unit)
}

val LocalPayment = staticCompositionLocalOf<PaymentLauncher> { error("PaymentLauncher non fourni") }
val LocalRequestSmsPermission = staticCompositionLocalOf<() -> Unit> { {} }

class MainActivity : ComponentActivity() {
    private lateinit var paymentSheet: PaymentSheet
    private var paymentCallback: ((PaymentSheetResult) -> Unit)? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.READ_SMS] == true) {
            (application as App).registerSmsObserver()
            lifecycleScope.launch {
                val added = SmsScanner.scan(this@MainActivity)
                Notifier.notifyNew(this@MainActivity, added)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Empêche captures d'écran et aperçu dans les applis récentes (IBAN, factures).
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        // La PaymentSheet doit être créée avant onStart (enregistrement du résultat d'activité).
        paymentSheet = PaymentSheet(this) { result ->
            paymentCallback?.invoke(result)
            paymentCallback = null
        }

        val launcher = PaymentLauncher { clientSecret, publishableKey, onResult ->
            PaymentConfiguration.init(applicationContext, publishableKey)
            paymentCallback = onResult
            paymentSheet.presentWithPaymentIntent(
                clientSecret,
                PaymentSheet.Configuration.Builder("Remboursement Jeux TV").build(),
            )
        }

        val requestPermissions = {
            val perms = buildList {
                if (SmsScanner.enabled) add(Manifest.permission.READ_SMS)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
        }

        setContent {
            CompositionLocalProvider(
                LocalPayment provides launcher,
                LocalRequestSmsPermission provides requestPermissions,
            ) {
                AppRoot()
            }
        }

        handleSharedPdf(intent)

        if (SmsScanner.enabled && !SmsScanner.hasPermission(this)) requestPermissions()
        else if (!SmsScanner.enabled && Build.VERSION.SDK_INT >= 33) requestPermissions()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedPdf(intent)
    }

    /** Facture PDF partagée vers l'app : import immédiat (sans doublon). */
    private fun handleSharedPdf(intent: Intent?) {
        if (intent == null) return
        val uri: Uri = when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return
        setIntent(Intent(this, MainActivity::class.java)) // évite un réimport à la rotation
        lifecycleScope.launch {
            val msg = try {
                InvoiceImporter.importUri(this@MainActivity, uri, null).message()
            } catch (e: Exception) {
                "Import impossible : ${e.message}"
            }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { SmsScanner.scan(this@MainActivity) }
    }
}
