// Client minimal de l'API Maileva « Courrier simple » (mail/v2), service d'envoi
// de courrier postal du groupe La Poste : création d'un envoi, ajout du PDF, ajout
// du destinataire puis soumission en production (impression, mise sous pli, dépôt).

export class MailevaError extends Error {
  constructor(message, status, body) {
    super(message);
    this.status = status;
    this.body = body;
  }
}

export function createMailevaClient(cfg, fetchImpl = globalThis.fetch) {
  let token = null;
  let tokenExpiresAt = 0;

  async function getToken() {
    if (token && Date.now() < tokenExpiresAt - 30_000) return token;
    const form = new URLSearchParams({
      grant_type: 'password',
      client_id: cfg.clientId,
      client_secret: cfg.clientSecret,
      username: cfg.username,
      password: cfg.password,
    });
    const res = await fetchImpl(cfg.authUrl, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: form,
    });
    if (!res.ok) throw new MailevaError('Authentification Maileva refusée', res.status, await safeText(res));
    const json = await res.json();
    token = json.access_token;
    tokenExpiresAt = Date.now() + (json.expires_in || 300) * 1000;
    return token;
  }

  async function call(method, path, { json, body, headers = {} } = {}) {
    const t = await getToken();
    const res = await fetchImpl(cfg.apiUrl + path, {
      method,
      headers: {
        Authorization: `Bearer ${t}`,
        Accept: 'application/json',
        ...(json !== undefined ? { 'Content-Type': 'application/json' } : {}),
        ...headers,
      },
      body: json !== undefined ? JSON.stringify(json) : body,
    });
    if (!res.ok) {
      throw new MailevaError(`Maileva ${method} ${path} : HTTP ${res.status}`, res.status, await safeText(res));
    }
    if (res.status === 204) return null;
    const text = await res.text();
    return text ? JSON.parse(text) : null;
  }

  /**
   * Envoie une lettre simple.
   * @param {object} p
   * @param {string} p.letterId identifiant interne (custom_id)
   * @param {Buffer} p.pdf document complet (lettre + pièces jointes)
   * @param {string[]} p.sender 6 lignes d'adresse AFNOR de l'expéditeur
   * @param {string[]} p.recipient 6 lignes d'adresse AFNOR du destinataire
   * @param {string} [p.notificationEmail]
   * @returns {Promise<string>} identifiant de l'envoi Maileva
   */
  async function sendLetter({ letterId, pdf, sender, recipient, notificationEmail }) {
    const sending = await call('POST', '/sendings', {
      json: {
        name: `Remboursement jeu TV ${letterId}`,
        custom_id: letterId,
        color_printing: false,
        duplex_printing: false,
        // Feuille porte-adresse : garantit que l'adresse apparaît dans la fenêtre de l'enveloppe.
        optional_address_sheet: true,
        postage_type: cfg.postageType,
        print_sender_address: true,
        ...(notificationEmail ? { notification_email: notificationEmail } : {}),
        ...addressFields('sender_address_line_', sender),
        sender_country_code: 'FR',
      },
    });
    const sendingId = sending.id;
    try {
      const form = new FormData();
      form.append('document', new Blob([pdf], { type: 'application/pdf' }), 'demande-remboursement.pdf');
      form.append('metadata', JSON.stringify({ priority: 1, name: 'demande-remboursement.pdf' }));
      await call('POST', `/sendings/${sendingId}/documents`, { body: form });

      await call('POST', `/sendings/${sendingId}/recipients`, {
        json: { custom_id: letterId, ...addressFields('address_line_', recipient), country_code: 'FR' },
      });

      await call('POST', `/sendings/${sendingId}/submit`);
    } catch (err) {
      // Nettoyage du brouillon pour ne pas laisser d'envoi incomplet.
      await call('DELETE', `/sendings/${sendingId}`).catch(() => {});
      throw err;
    }
    return sendingId;
  }

  async function getSending(sendingId) {
    return call('GET', `/sendings/${sendingId}`);
  }

  return { sendLetter, getSending };
}

function addressFields(prefix, lines) {
  const out = {};
  for (let i = 0; i < 6; i++) {
    const v = (lines[i] || '').trim();
    if (v) out[`${prefix}${i + 1}`] = v.slice(0, 38); // norme AFNOR : 38 caractères max
  }
  return out;
}

async function safeText(res) {
  try { return await res.text(); } catch { return ''; }
}
