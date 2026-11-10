@file:OptIn(ExperimentalMaterial3Api::class)

package fr.remboursementjeux.app

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.stripe.android.paymentsheet.PaymentSheetResult
import fr.remboursementjeux.core.Channel
import fr.remboursementjeux.core.ClaimDraft
import fr.remboursementjeux.core.ClaimStatus
import fr.remboursementjeux.core.Confidence
import fr.remboursementjeux.core.Iban
import fr.remboursementjeux.core.Money
import fr.remboursementjeux.core.Participation
import fr.remboursementjeux.core.RequiredDocument
import fr.remboursementjeux.core.SenderProfile
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private sealed interface Screen {
    data class Tab(val index: Int) : Screen
    data class ClaimDetail(val key: String) : Screen
    data class Prixtel(val month: String, val back: String) : Screen
}

private val dateTime = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
private val dateLong = DateFormat.getDateInstance(DateFormat.LONG)

@Composable
fun AppRoot() {
    MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFF1E5AA8), secondary = Color(0xFFF4B400))) {
        var screen by remember { mutableStateOf<Screen>(Screen.Tab(0)) }
        val data by Repository.data.collectAsState()

        when (val s = screen) {
            is Screen.Prixtel -> {
                BackHandler { screen = Screen.ClaimDetail(s.back) }
                PrixtelScreen(s.month, data.settings.prixtelUrl) { screen = Screen.ClaimDetail(s.back) }
            }
            is Screen.ClaimDetail -> {
                BackHandler { screen = Screen.Tab(1) }
                ClaimDetailScreen(
                    data = data,
                    key = s.key,
                    onBack = { screen = Screen.Tab(1) },
                    onPrixtel = { month -> screen = Screen.Prixtel(month, s.key) },
                )
            }
            is Screen.Tab -> Scaffold(
                topBar = { TopAppBar(title = { Text("Remboursement Jeux TV") }) },
                bottomBar = {
                    NavigationBar {
                        listOf("Participations" to Icons.Filled.Home, "Demandes" to Icons.Filled.Email, "Réglages" to Icons.Filled.Settings)
                            .forEachIndexed { i, (label, icon) ->
                                NavigationBarItem(
                                    selected = s.index == i,
                                    onClick = { screen = Screen.Tab(i) },
                                    icon = { Icon(icon, contentDescription = label) },
                                    label = { Text(label) },
                                )
                            }
                    }
                },
            ) { padding ->
                Column(Modifier.padding(padding)) {
                    when (s.index) {
                        0 -> ParticipationsScreen(data)
                        1 -> ClaimsScreen(data) { screen = Screen.ClaimDetail(it) }
                        else -> SettingsScreen(data)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Participations

@Composable
private fun ParticipationsScreen(data: AppData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val requestPermission = LocalRequestSmsPermission.current
    var info by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Participation?>(null) }
    val list = data.participations.sortedByDescending { it.timestampMillis }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            if (!SmsScanner.hasPermission(context)) {
                InfoCard(
                    "L'app a besoin de lire vos SMS envoyés pour repérer les SMS surtaxés envoyés aux jeux TF1/M6. " +
                        "Seuls ces SMS sont conservés, chiffrés sur le téléphone.",
                )
                Button(onClick = requestPermission) { Text("Autoriser la lecture des SMS") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        scope.launch {
                            val added = SmsScanner.scan(context)
                            info = if (added.isEmpty()) "Aucune nouvelle participation." else "${added.size} nouvelle(s) participation(s)."
                        }
                    }) { Text("Analyser mes SMS") }
                    info?.let { Text(it, Modifier.padding(start = 12.dp)) }
                }
            }
            if (list.isEmpty()) Text("Aucune participation détectée pour l'instant.", Modifier.padding(top = 16.dp))
        }
        items(list, key = { "${it.smsId}-${it.timestampMillis}" }) { p ->
            ParticipationCard(p, onEdit = { editing = p })
        }
        item { Spacer(Modifier.height(16.dp)) }
    }

    editing?.let { p -> EditParticipationDialog(p, onDismiss = { editing = null }) }
}

@Composable
private fun ParticipationCard(p: Participation, onEdit: () -> Unit) {
    val scope = rememberCoroutineScope()
    fun update(transform: (Participation) -> Participation) = scope.launch {
        Repository.update { d ->
            d.copy(participations = d.participations.map {
                if (it.smsId == p.smsId && it.timestampMillis == p.timestampMillis) transform(it) else it
            })
        }
    }

    Card(Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateTime.format(Date(p.timestampMillis)), fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("${p.shortCode} · ${Money.format(p.costCents)}")
            }
            Text("${p.channel.label} – ${p.gameName}", style = MaterialTheme.typography.bodyLarge)
            Text("« ${p.body.take(80)} »", style = MaterialTheme.typography.bodySmall)
            when {
                p.claimedLetterId != null -> Text("✔ Remboursement demandé", color = Color(0xFF2E7D32))
                p.confidence == Confidence.LOW && !p.userValidated -> Text("À classer : de quelle chaîne s'agit-il ?", color = Color(0xFFB26A00))
            }
            if (p.claimedLetterId == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = p.channel == Channel.TF1 && !p.excluded,
                        onClick = { update { it.copy(channel = Channel.TF1, excluded = false, userValidated = true, gameName = if (it.channel == Channel.TF1) it.gameName else "Jeu TF1 (${it.shortCode})") } },
                        label = { Text("TF1") },
                    )
                    FilterChip(
                        selected = p.channel == Channel.M6 && !p.excluded,
                        onClick = { update { it.copy(channel = Channel.M6, excluded = false, userValidated = true, gameName = if (it.channel == Channel.M6) it.gameName else "Jeu M6 (${it.shortCode})") } },
                        label = { Text("M6") },
                    )
                    FilterChip(
                        selected = p.excluded,
                        onClick = { update { it.copy(excluded = !it.excluded, userValidated = true) } },
                        label = { Text("Pas un jeu") },
                    )
                }
            }
        }
    }
}

@Composable
private fun EditParticipationDialog(p: Participation, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var game by remember { mutableStateOf(p.gameName) }
    var cost by remember { mutableStateOf(String.format(java.util.Locale.FRANCE, "%.2f", p.costCents / 100.0)) }
    var rememberCode by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Participation du ${dateTime.format(Date(p.timestampMillis))}") },
        text = {
            Column {
                OutlinedTextField(game, { game = it }, label = { Text("Nom du jeu / de l'émission") })
                OutlinedTextField(
                    cost, { cost = it }, label = { Text("Coût du SMS (€, voir facture)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (p.channel != Channel.UNKNOWN) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberCode, { rememberCode = it })
                        Text("Toujours associer le ${p.shortCode} à ${p.channel.label}")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = cost.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 100).toInt() } ?: p.costCents
                scope.launch {
                    Repository.update { d ->
                        val settings = if (rememberCode) {
                            val codes = (d.settings.extraShortCodes[p.channel] ?: emptySet()) + p.shortCode
                            d.settings.copy(extraShortCodes = d.settings.extraShortCodes + (p.channel to codes))
                        } else d.settings
                        d.copy(
                            settings = settings,
                            participations = d.participations.map {
                                if (it.smsId == p.smsId && it.timestampMillis == p.timestampMillis) {
                                    it.copy(gameName = game.trim().ifBlank { it.gameName }, costCents = cents.coerceIn(0, 2000), userValidated = true)
                                } else it
                            },
                        )
                    }
                    onDismiss()
                }
            }) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- Demandes

@Composable
private fun ClaimsScreen(data: AppData, onOpen: (String) -> Unit) {
    val drafts = Repository.claims(data)
    val history = data.claims.filter { it.value.status == ClaimStatus.SUBMITTED }.entries.sortedByDescending { it.value.submittedAtMillis }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            Text("À envoyer", style = MaterialTheme.typography.titleMedium)
            if (drafts.isEmpty()) Text("Aucune demande en attente. Classez vos participations (TF1/M6) pour en créer.")
        }
        items(drafts, key = { it.key }) { c ->
            val state = data.claims[c.key]
            Card(Modifier.fillMaxWidth().clickable { onOpen(c.key) }) {
                Column(Modifier.padding(12.dp)) {
                    Text("${c.channel.label} – ${c.gameName}", fontWeight = FontWeight.Bold)
                    Text("${c.participations.size} SMS · ${Money.format(c.smsTotalCents + c.stampCents)} à réclamer (hors photocopies)")
                    c.deadlineMillis?.let { Text("À envoyer avant le ${dateLong.format(Date(it))}") }
                    Text("Statut : ${(state?.status ?: ClaimStatus.DRAFT).label}", style = MaterialTheme.typography.bodySmall)
                    if (c.warnings.isNotEmpty()) Text("⚠ ${c.warnings.size} point(s) d'attention", color = Color(0xFFB26A00))
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Text("Envoyées", style = MaterialTheme.typography.titleMedium)
            if (history.isEmpty()) Text("Aucun courrier envoyé.")
        }
        items(history, key = { it.key }) { (key, st) ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                Column(Modifier.padding(12.dp)) {
                    Text(key.split('|').let { "${it[0]} – ${it.getOrElse(1) { "" }}" }, fontWeight = FontWeight.Bold)
                    st.submittedAtMillis?.let { Text("Transmis à La Poste le ${dateLong.format(Date(it))}") }
                    st.amountPaidCents?.let { Text("Frais d'envoi payés : ${Money.format(it)}") }
                    st.mailevaSendingId?.let { Text("Réf. envoi : $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ClaimDetailScreen(data: AppData, key: String, onBack: () -> Unit, onPrixtel: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val payment = LocalPayment.current
    val claim = Repository.claims(data).firstOrNull { it.key == key }
    val state = data.claims[key] ?: ClaimState()

    var busy by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<CreatedLetter?>(null) }
    var askPay by remember { mutableStateOf(false) }
    var pickMonth by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val month = pickMonth ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            try {
                val doc = Documents.importUri(context, uri)
                Repository.update { d ->
                    Documents.delete(d.invoices[month])
                    d.copy(invoices = d.invoices + (month to doc))
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(claim?.gameName ?: "Demande") },
            navigationIcon = { TextButton(onClick = onBack) { Text("‹ Retour") } },
        )
    }) { padding ->
        if (claim == null) {
            Column(Modifier.padding(padding).padding(16.dp)) {
                Text("Cette demande n'existe plus (déjà envoyée ou participations reclassées).")
            }
            return@Scaffold
        }
        val rule = claim.rule
        val missing = ClaimSender.missing(data, claim)

        fun startPayment(created: CreatedLetter) {
            val api = ApiClient(data.settings.serverUrl, data.settings.apiToken)
            payment.launch(created.clientSecret, created.publishableKey) { result ->
                scope.launch {
                    when (result) {
                        is PaymentSheetResult.Completed -> {
                            busy = "Paiement accepté. Vérification et envoi à La Poste…"
                            try {
                                val s = ClaimSender.confirmAndTrack(api, claim, created.letterId)
                                pending = null
                                if (s.status == "submitted") onBack()
                                else error = s.error ?: "Statut : ${s.status}"
                            } catch (e: Exception) {
                                error = e.message
                            }
                            busy = null
                        }
                        is PaymentSheetResult.Canceled -> error = "Paiement annulé. Aucun courrier n'a été envoyé."
                        is PaymentSheetResult.Failed -> error = "Paiement refusé : ${result.error.localizedMessage}"
                        else -> error = "Paiement non abouti. Aucun courrier n'a été envoyé."
                    }
                }
            }
        }

        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("${claim.channel.label} – ${claim.participations.size} participation(s)", style = MaterialTheme.typography.titleMedium)
            claim.deadlineMillis?.let { Text("Date limite d'envoi : ${dateLong.format(Date(it))}", fontWeight = FontWeight.Bold) }
            claim.participations.forEach { Text("• ${dateTime.format(Date(it.timestampMillis))} — ${it.shortCode} — ${Money.format(it.costCents)}") }
            Text("Frais réclamés : ${Money.format(claim.smsTotalCents)} + timbre ${Money.format(claim.stampCents)}" +
                if (rule.photocopyCentsPerPage > 0) " + photocopies" else "")
            claim.warnings.forEach { Text("⚠ $it", color = Color(0xFFB26A00)) }
            InfoCard("Règlement ${rule.organizer} : ${rule.notes}")

            HorizontalDivider()
            Text("Adresse d'envoi (selon le règlement du jeu)", style = MaterialTheme.typography.titleSmall)
            RecipientEditor(data, claim)

            HorizontalDivider()
            Text("Pièces justificatives", style = MaterialTheme.typography.titleSmall)
            claim.invoiceMonths.sorted().forEach { month ->
                val doc = data.invoices[month]
                Text((if (doc != null) "✔ " else "✗ ") + "Facture détaillée $month" + (doc?.let { " (${it.displayName})" } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onPrixtel(month) }) { Text("Depuis Prixtel") }
                    OutlinedButton(onClick = { pickMonth = month; picker.launch(arrayOf("application/pdf", "image/*")) }) { Text("Choisir un fichier") }
                }
            }
            Text((if (data.rib != null) "✔ " else "✗ ") + "RIB" + (data.profile?.iban?.let { " – IBAN ${Iban.mask(it)}" } ?: ""))
            if (RequiredDocument.ID_CARD in rule.requiredDocuments) {
                Text((if (data.idCard != null) "✔ " else "✗ ") + "Pièce d'identité")
            }

            HorizontalDivider()
            if (missing.isNotEmpty()) {
                Text("Avant l'envoi :", fontWeight = FontWeight.Bold)
                missing.forEach { Text("• $it") }
            }
            Text("Statut : ${state.status.label}")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            busy?.let { Text(it) }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    enabled = busy == null && missing.none { it.startsWith("Facture") || it.startsWith("RIB") || it.startsWith("Pièce") || it.startsWith("Renseignez") || it.startsWith("Réglages") },
                    onClick = {
                        scope.launch {
                            busy = "Génération de l'aperçu…"
                            try {
                                val pdf = ClaimSender.buildPdf(context, data, claim)
                                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", pdf)
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                )
                            } catch (e: Exception) {
                                error = e.message
                            }
                            busy = null
                        }
                    },
                ) { Text("Aperçu PDF") }

                Button(
                    enabled = busy == null && missing.isEmpty(),
                    onClick = {
                        error = null
                        val already = pending
                        if (already != null) {
                            askPay = true
                            return@Button
                        }
                        scope.launch {
                            busy = "Préparation du courrier…"
                            try {
                                val api = ApiClient(data.settings.serverUrl, data.settings.apiToken)
                                val pdf = ClaimSender.buildPdf(context, data, claim)
                                val created = try {
                                    api.createLetter(
                                        pdf,
                                        ClaimSender.senderAddress(data),
                                        ApiClient.afnor(rule.recipientLines),
                                        data.settings.notificationEmail.ifBlank { data.profile?.email },
                                        claim.key,
                                    )
                                } finally {
                                    pdf.delete()
                                }
                                ClaimSender.setState(claim.key) { it.copy(status = ClaimStatus.AWAITING_PAYMENT, letterId = created.letterId, error = null) }
                                pending = created
                                askPay = true
                            } catch (e: Exception) {
                                error = e.message
                            }
                            busy = null
                        }
                    },
                ) { Text(if (pending != null) "Reprendre le paiement" else "Envoyer par La Poste") }
            }

            val failedLetterId = state.letterId
            if (state.status == ClaimStatus.FAILED && failedLetterId != null && state.error?.contains("Envoi postal") == true) {
                OutlinedButton(onClick = {
                    scope.launch {
                        busy = "Nouvelle tentative d'envoi…"
                        try {
                            val api = ApiClient(data.settings.serverUrl, data.settings.apiToken)
                            api.retry(failedLetterId)
                            ClaimSender.confirmAndTrack(api, claim, failedLetterId)
                        } catch (e: Exception) {
                            error = e.message
                        }
                        busy = null
                    }
                }) { Text("Relancer l'envoi (déjà payé)") }
            }
            Spacer(Modifier.height(24.dp))
        }

        val created = pending
        if (askPay && created != null) {
            AlertDialog(
                onDismissRequest = { askPay = false },
                title = { Text("Paiement de l'envoi") },
                text = {
                    Text(
                        "Courrier de ${created.pages} page(s) adressé à ${rule.recipientLines.first()}.\n\n" +
                            "Montant à payer : ${Money.format(created.amountCents)} (impression, mise sous pli, affranchissement La Poste).\n\n" +
                            "Le paiement se fait par carte bancaire dans le formulaire sécurisé Stripe, avec authentification " +
                            "3-D Secure auprès de votre banque. Vos données de carte ne sont ni vues ni stockées par l'app. " +
                            "Le courrier n'est envoyé qu'une fois le paiement validé.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = { askPay = false; startPayment(created) }) { Text("Payer ${Money.format(created.amountCents)}") }
                },
                dismissButton = { TextButton(onClick = { askPay = false }) { Text("Annuler") } },
            )
        }
    }
}

@Composable
private fun RecipientEditor(data: AppData, claim: ClaimDraft) {
    val scope = rememberCoroutineScope()
    val override = data.ruleOverrides[claim.channel] ?: RuleOverride()
    var text by remember(claim.key) { mutableStateOf(claim.rule.recipientLines.joinToString("\n")) }
    OutlinedTextField(
        text, { text = it }, Modifier.fillMaxWidth(), minLines = 3,
        label = { Text("Destinataire (une ligne par ligne d'adresse)") },
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = override.addressVerified,
            onCheckedChange = { checked ->
                scope.launch {
                    val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    Repository.update { d ->
                        val o = (d.ruleOverrides[claim.channel] ?: RuleOverride()).copy(
                            recipientLines = lines.takeIf { it.size >= 2 },
                            addressVerified = checked,
                        )
                        d.copy(ruleOverrides = d.ruleOverrides + (claim.channel to o))
                    }
                }
            },
        )
        Text("J'ai vérifié cette adresse dans le règlement du jeu")
    }
}

// ---------------------------------------------------------------- Réglages

@Composable
private fun SettingsScreen(data: AppData) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = data.profile ?: SenderProfile("", "", "", "", "", "", "", "", "", "")
    var civility by remember { mutableStateOf(p.civility) }
    var first by remember { mutableStateOf(p.firstName) }
    var last by remember { mutableStateOf(p.lastName) }
    var address by remember { mutableStateOf(p.addressLine) }
    var complement by remember { mutableStateOf(p.addressComplement) }
    var postal by remember { mutableStateOf(p.postalCode) }
    var city by remember { mutableStateOf(p.city) }
    var phone by remember { mutableStateOf(p.phoneNumber) }
    var email by remember { mutableStateOf(p.email) }
    var iban by remember { mutableStateOf(p.iban) }
    var server by remember { mutableStateOf(data.settings.serverUrl) }
    var token by remember { mutableStateOf(data.settings.apiToken) }
    var prixtel by remember { mutableStateOf(data.settings.prixtelUrl) }
    var stamp by remember { mutableStateOf(String.format(java.util.Locale.FRANCE, "%.2f", data.settings.stampCents / 100.0)) }
    var tf1Codes by remember { mutableStateOf(data.settings.extraShortCodes[Channel.TF1].orEmpty().joinToString(", ")) }
    var m6Codes by remember { mutableStateOf(data.settings.extraShortCodes[Channel.M6].orEmpty().joinToString(", ")) }
    var m6Id by remember { mutableStateOf(data.ruleOverrides[Channel.M6]?.requireIdCard ?: false) }
    var message by remember { mutableStateOf<String?>(null) }
    var docTarget by remember { mutableStateOf("rib") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val doc = Documents.importUri(context, uri)
                Repository.update { d ->
                    if (docTarget == "rib") { Documents.delete(d.rib); d.copy(rib = doc) }
                    else { Documents.delete(d.idCard); d.copy(idCard = doc) }
                }
                message = "Document enregistré (chiffré)."
            } catch (e: Exception) {
                message = e.message
            }
        }
    }

    fun parseCodes(s: String) = s.split(',', ' ', ';').map { it.trim() }.filter { Regex("^[3-8]\\d{4}$").matches(it) }.toSet()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Titulaire de la ligne", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(civility, { civility = it }, Modifier.fillMaxWidth(), label = { Text("Civilité (M., Mme)") })
        OutlinedTextField(first, { first = it }, Modifier.fillMaxWidth(), label = { Text("Prénom") })
        OutlinedTextField(last, { last = it }, Modifier.fillMaxWidth(), label = { Text("Nom") })
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("N° et rue") })
        OutlinedTextField(complement, { complement = it }, Modifier.fillMaxWidth(), label = { Text("Complément (bât., appt…)") })
        OutlinedTextField(postal, { postal = it }, Modifier.fillMaxWidth(), label = { Text("Code postal") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        OutlinedTextField(city, { city = it }, Modifier.fillMaxWidth(), label = { Text("Ville") })
        OutlinedTextField(phone, { phone = it }, Modifier.fillMaxWidth(), label = { Text("Numéro de la ligne Prixtel") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone))
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("E-mail (suivi de l'envoi)") })
        OutlinedTextField(iban, { iban = it }, Modifier.fillMaxWidth(), label = { Text("IBAN (pour le remboursement)") },
            isError = iban.isNotBlank() && !Iban.isValid(iban))

        Text("Pièces", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { docTarget = "rib"; picker.launch(arrayOf("application/pdf", "image/*")) }) {
                Text(if (data.rib != null) "RIB ✔ (remplacer)" else "Ajouter le RIB")
            }
            OutlinedButton(onClick = { docTarget = "id"; picker.launch(arrayOf("application/pdf", "image/*")) }) {
                Text(if (data.idCard != null) "Pièce d'identité ✔" else "Pièce d'identité")
            }
        }

        Text("Jeux et règlements", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(tf1Codes, { tf1Codes = it }, Modifier.fillMaxWidth(), label = { Text("Numéros courts des jeux TF1 (ex. 71414)") })
        OutlinedTextField(m6Codes, { m6Codes = it }, Modifier.fillMaxWidth(), label = { Text("Numéros courts des jeux M6") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(m6Id, { m6Id = it })
            Text("Le règlement M6 exige une pièce d'identité")
        }
        OutlinedTextField(stamp, { stamp = it }, Modifier.fillMaxWidth(), label = { Text("Tarif du timbre Lettre verte (€)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

        Text("Envoi postal et paiement", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Adresse du serveur (https://…)") })
        OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Jeton d'API du serveur") },
            visualTransformation = PasswordVisualTransformation())
        OutlinedTextField(prixtel, { prixtel = it }, Modifier.fillMaxWidth(), label = { Text("Page de l'espace client Prixtel") })

        message?.let { Text(it) }
        Button(onClick = {
            val stampCents = stamp.replace(',', '.').toDoubleOrNull()?.let { Math.round(it * 100).toInt() }
            when {
                server.isNotBlank() && !server.trim().startsWith("https://") -> message = "Le serveur doit être en https://"
                !prixtel.trim().startsWith("https://") -> message = "L'adresse Prixtel doit être en https://"
                iban.isNotBlank() && !Iban.isValid(iban) -> message = "IBAN invalide"
                else -> scope.launch {
                    Repository.update { d ->
                        d.copy(
                            profile = SenderProfile(civility.trim(), first.trim(), last.trim(), address.trim(), complement.trim(),
                                postal.trim(), city.trim(), phone.trim(), email.trim(), Iban.normalize(iban)),
                            settings = d.settings.copy(
                                serverUrl = server.trim(), apiToken = token.trim(), prixtelUrl = prixtel.trim(),
                                stampCents = stampCents ?: d.settings.stampCents,
                                notificationEmail = email.trim(),
                                extraShortCodes = mapOf(Channel.TF1 to parseCodes(tf1Codes), Channel.M6 to parseCodes(m6Codes)),
                            ),
                            ruleOverrides = d.ruleOverrides + (Channel.M6 to (d.ruleOverrides[Channel.M6] ?: RuleOverride()).copy(requireIdCard = m6Id)),
                        )
                    }
                    message = "Réglages enregistrés."
                }
            }
        }) { Text("Enregistrer") }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
