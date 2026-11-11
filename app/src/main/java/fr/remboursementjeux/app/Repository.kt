package fr.remboursementjeux.app

import android.content.Context
import fr.remboursementjeux.core.Channel
import fr.remboursementjeux.core.ChannelProfile
import fr.remboursementjeux.core.ClaimBuilder
import fr.remboursementjeux.core.ClaimDraft
import fr.remboursementjeux.core.ClaimStatus
import fr.remboursementjeux.core.Confidence
import fr.remboursementjeux.core.DefaultProfiles
import fr.remboursementjeux.core.DefaultRules
import fr.remboursementjeux.core.Participation
import fr.remboursementjeux.core.RefundRule
import fr.remboursementjeux.core.RequiredDocument
import fr.remboursementjeux.core.SenderProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Page de connexion de l'espace client Prixtel. */
const val PRIXTEL_LOGIN_URL = "https://espaceclient.prixtel.com/connexion"
const val PRIXTEL_HOME_URL = "https://www.prixtel.com/"
private const val OLD_PRIXTEL_URL = "https://www.prixtel.com/espace-client/"

/** Réglages de l'app. */
data class Settings(
    val serverUrl: String = "",
    val apiToken: String = "",
    val prixtelUrl: String = PRIXTEL_LOGIN_URL,
    val stampCents: Int = DefaultRules.DEFAULT_STAMP_CENTS,
    val notificationEmail: String = "",
    /** Numéros courts relevés dans les règlements, par chaîne. */
    val extraShortCodes: Map<Channel, Set<String>> = emptyMap(),
)

/** Adaptations des règles par l'utilisateur (adresse vérifiée dans le règlement, délai…). */
data class RuleOverride(
    val recipientLines: List<String>? = null,
    val deadlineDays: Int? = null,
    val addressVerified: Boolean = false,
    val requireIdCard: Boolean = false,
)

/** Suivi d'une demande (une lettre par jeu). */
data class ClaimState(
    val status: ClaimStatus = ClaimStatus.DRAFT,
    val letterId: String? = null,
    val amountPaidCents: Int? = null,
    val mailevaSendingId: String? = null,
    val error: String? = null,
    val submittedAtMillis: Long? = null,
    val lastReminderMillis: Long = 0,
    /** Mode d'envoi utilisé (voir SendMethod). */
    val method: String? = null,
)

/** Pièce justificative stockée (fichier chiffré + type MIME). */
data class StoredDocument(val path: String, val mimeType: String, val displayName: String)

data class AppData(
    val participations: List<Participation> = emptyList(),
    val profile: SenderProfile? = null,
    val settings: Settings = Settings(),
    val ruleOverrides: Map<Channel, RuleOverride> = emptyMap(),
    val claims: Map<String, ClaimState> = emptyMap(),
    val rib: StoredDocument? = null,
    val idCard: StoredDocument? = null,
    /** Factures détaillées par mois (AAAA-MM), partagées entre les demandes. */
    val invoices: Map<String, StoredDocument> = emptyMap(),
    val lastScanMillis: Long = 0,
)

/** Dépôt unique de l'app : état en mémoire + persistance chiffrée. */
object Repository {
    private lateinit var file: File
    private val mutex = Mutex()
    private val _data = MutableStateFlow(AppData())
    val data: StateFlow<AppData> get() = _data

    fun init(context: Context) {
        if (::file.isInitialized) return
        file = File(context.filesDir, "state.enc")
        _data.value = runCatching { SecureStorage.readEncrypted(file)?.let { fromJson(JSONObject(String(it))) } }
            .getOrNull() ?: AppData()
    }

    suspend fun update(transform: (AppData) -> AppData): AppData = mutex.withLock {
        val next = transform(_data.value)
        _data.value = next
        SecureStorage.writeEncrypted(file, toJson(next).toString().toByteArray())
        next
    }

    /**
     * Associe un document de facture à un mois. L'ancien fichier n'est supprimé que
     * s'il n'est plus utilisé ailleurs (une même facture peut couvrir plusieurs mois).
     */
    fun withInvoice(d: AppData, month: String, doc: StoredDocument): AppData {
        val old = d.invoices[month]
        val next = d.copy(invoices = d.invoices + (month to doc))
        if (old != null && old.path != doc.path && next.invoices.values.none { it.path == old.path }) Documents.delete(old)
        return next
    }

    // ---- Règles et demandes effectives ----

    fun effectiveRule(data: AppData, channel: Channel): RefundRule? {
        val base = DefaultRules.forChannel(channel) ?: return null
        val o = data.ruleOverrides[channel] ?: return base
        return base.copy(
            recipientLines = o.recipientLines?.filter { it.isNotBlank() }?.takeIf { it.size >= 2 } ?: base.recipientLines,
            deadlineDays = o.deadlineDays ?: base.deadlineDays,
            addressVerified = o.addressVerified,
            requiredDocuments = if (o.requireIdCard && RequiredDocument.ID_CARD !in base.requiredDocuments) {
                base.requiredDocuments + RequiredDocument.ID_CARD
            } else {
                base.requiredDocuments
            },
        )
    }

    fun claims(data: AppData, now: Long = System.currentTimeMillis()): List<ClaimDraft> =
        ClaimBuilder(rules = { effectiveRule(data, it) }, stampCents = data.settings.stampCents)
            .build(data.participations, now)

    fun profiles(data: AppData): List<ChannelProfile> = DefaultProfiles.ALL.map {
        it.copy(shortCodes = it.shortCodes + (data.settings.extraShortCodes[it.channel] ?: emptySet()))
    }

    // ---- Sérialisation JSON (org.json, sans dépendance supplémentaire) ----

    private fun toJson(d: AppData): JSONObject = JSONObject().apply {
        put("participations", JSONArray(d.participations.map { p ->
            JSONObject().apply {
                put("smsId", p.smsId); put("shortCode", p.shortCode); put("body", p.body)
                put("ts", p.timestampMillis); put("channel", p.channel.name); put("game", p.gameName)
                put("cost", p.costCents); put("confidence", p.confidence.name)
                put("validated", p.userValidated); put("excluded", p.excluded)
                put("claimedLetterId", p.claimedLetterId ?: JSONObject.NULL)
                put("invoiceRef", p.invoiceRef ?: JSONObject.NULL)
            }
        }))
        d.profile?.let { p ->
            put("profile", JSONObject().apply {
                put("civility", p.civility); put("firstName", p.firstName); put("lastName", p.lastName)
                put("addressLine", p.addressLine); put("addressComplement", p.addressComplement)
                put("postalCode", p.postalCode); put("city", p.city); put("phone", p.phoneNumber)
                put("email", p.email); put("iban", p.iban); put("operator", p.operatorName)
            })
        }
        put("settings", JSONObject().apply {
            val s = d.settings
            put("serverUrl", s.serverUrl); put("apiToken", s.apiToken); put("prixtelUrl", s.prixtelUrl)
            put("stampCents", s.stampCents); put("notificationEmail", s.notificationEmail)
            put("extraShortCodes", JSONObject().apply {
                s.extraShortCodes.forEach { (c, codes) -> put(c.name, JSONArray(codes.toList())) }
            })
        })
        put("ruleOverrides", JSONObject().apply {
            d.ruleOverrides.forEach { (c, o) ->
                put(c.name, JSONObject().apply {
                    o.recipientLines?.let { put("recipientLines", JSONArray(it)) }
                    o.deadlineDays?.let { put("deadlineDays", it) }
                    put("addressVerified", o.addressVerified); put("requireIdCard", o.requireIdCard)
                })
            }
        })
        put("claims", JSONObject().apply {
            d.claims.forEach { (k, c) ->
                put(k, JSONObject().apply {
                    put("status", c.status.name); put("letterId", c.letterId ?: JSONObject.NULL)
                    put("amountPaidCents", c.amountPaidCents ?: JSONObject.NULL)
                    put("mailevaSendingId", c.mailevaSendingId ?: JSONObject.NULL)
                    put("error", c.error ?: JSONObject.NULL)
                    put("submittedAt", c.submittedAtMillis ?: JSONObject.NULL)
                    put("lastReminder", c.lastReminderMillis)
                    put("method", c.method ?: JSONObject.NULL)
                })
            }
        })
        d.rib?.let { put("rib", docJson(it)) }
        d.idCard?.let { put("idCard", docJson(it)) }
        put("invoices", JSONObject().apply { d.invoices.forEach { (k, v) -> put(k, docJson(v)) } })
        put("lastScan", d.lastScanMillis)
    }

    private fun docJson(doc: StoredDocument) = JSONObject().apply {
        put("path", doc.path); put("mime", doc.mimeType); put("name", doc.displayName)
    }

    private fun parseDoc(o: JSONObject?) = o?.let { StoredDocument(it.getString("path"), it.getString("mime"), it.optString("name")) }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name)
    private fun JSONObject.optLongOrNull(name: String): Long? = if (isNull(name)) null else optLong(name)
    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else optInt(name)

    private fun <T> JSONObject.mapEntries(transform: (String, Any) -> T): Map<String, T> =
        keys().asSequence().associateWith { transform(it, get(it)) }

    private fun fromJson(o: JSONObject): AppData {
        val parts = o.optJSONArray("participations") ?: JSONArray()
        val participations = (0 until parts.length()).map { i ->
            val p = parts.getJSONObject(i)
            Participation(
                smsId = p.getLong("smsId"), shortCode = p.getString("shortCode"), body = p.optString("body"),
                timestampMillis = p.getLong("ts"), channel = Channel.valueOf(p.getString("channel")),
                gameName = p.getString("game"), costCents = p.getInt("cost"),
                confidence = Confidence.valueOf(p.getString("confidence")),
                userValidated = p.optBoolean("validated"), excluded = p.optBoolean("excluded"),
                claimedLetterId = p.optStringOrNull("claimedLetterId"),
                invoiceRef = p.optStringOrNull("invoiceRef"),
            )
        }
        val profile = o.optJSONObject("profile")?.let { p ->
            SenderProfile(
                civility = p.optString("civility"), firstName = p.optString("firstName"), lastName = p.optString("lastName"),
                addressLine = p.optString("addressLine"), addressComplement = p.optString("addressComplement"),
                postalCode = p.optString("postalCode"), city = p.optString("city"), phoneNumber = p.optString("phone"),
                email = p.optString("email"), iban = p.optString("iban"), operatorName = p.optString("operator", "Prixtel"),
            )
        }
        val s = o.optJSONObject("settings") ?: JSONObject()
        val extra = s.optJSONObject("extraShortCodes") ?: JSONObject()
        val settings = Settings(
            serverUrl = s.optString("serverUrl"), apiToken = s.optString("apiToken"),
            // Migration : l'ancienne adresse par défaut renvoyait une erreur 404.
            prixtelUrl = s.optString("prixtelUrl", PRIXTEL_LOGIN_URL)
                .takeUnless { it.isBlank() || it == OLD_PRIXTEL_URL } ?: PRIXTEL_LOGIN_URL,
            stampCents = s.optInt("stampCents", DefaultRules.DEFAULT_STAMP_CENTS),
            notificationEmail = s.optString("notificationEmail"),
            extraShortCodes = extra.mapEntries { _, v ->
                val arr = v as JSONArray
                (0 until arr.length()).map { arr.getString(it) }.toSet()
            }.mapKeys { Channel.valueOf(it.key) },
        )
        val overrides = (o.optJSONObject("ruleOverrides") ?: JSONObject()).mapEntries { _, v ->
            val r = v as JSONObject
            RuleOverride(
                recipientLines = r.optJSONArray("recipientLines")?.let { a -> (0 until a.length()).map { a.getString(it) } },
                deadlineDays = if (r.has("deadlineDays")) r.getInt("deadlineDays") else null,
                addressVerified = r.optBoolean("addressVerified"),
                requireIdCard = r.optBoolean("requireIdCard"),
            )
        }.mapKeys { Channel.valueOf(it.key) }
        val claims = (o.optJSONObject("claims") ?: JSONObject()).mapEntries { _, v ->
            val c = v as JSONObject
            ClaimState(
                status = ClaimStatus.valueOf(c.getString("status")),
                letterId = c.optStringOrNull("letterId"),
                amountPaidCents = c.optIntOrNull("amountPaidCents"),
                mailevaSendingId = c.optStringOrNull("mailevaSendingId"),
                error = c.optStringOrNull("error"),
                submittedAtMillis = c.optLongOrNull("submittedAt"),
                lastReminderMillis = c.optLong("lastReminder"),
                method = c.optStringOrNull("method"),
            )
        }
        return AppData(
            participations = participations,
            profile = profile,
            settings = settings,
            ruleOverrides = overrides,
            claims = claims,
            rib = parseDoc(o.optJSONObject("rib")),
            idCard = parseDoc(o.optJSONObject("idCard")),
            invoices = (o.optJSONObject("invoices") ?: JSONObject()).mapEntries { _, v -> parseDoc(v as JSONObject)!! },
            lastScanMillis = o.optLong("lastScan"),
        )
    }
}
