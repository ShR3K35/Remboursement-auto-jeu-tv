// API HTTP : création de la lettre + PaymentIntent Stripe, confirmation du paiement
// (webhook signé ou vérification directe auprès de Stripe), puis envoi via Maileva.
//
// Sécurité du paiement :
//  - les données de carte ne transitent jamais par ce serveur ni par l'app : elles
//    sont saisies dans la PaymentSheet Stripe (PCI-DSS) ;
//  - 3-D Secure (authentification forte DSP2) est exigé pour chaque paiement ;
//  - le montant est calculé ici, à partir du PDF reçu, jamais fourni par le client ;
//  - le courrier n'est envoyé qu'après vérification auprès de Stripe que le paiement
//    est « succeeded » pour le bon montant, la bonne devise et la bonne lettre ;
//  - webhooks vérifiés par signature ; traitement idempotent (pas de double envoi).
import crypto from 'node:crypto';
import express from 'express';
import helmet from 'helmet';
import { PDFDocument } from 'pdf-lib';

export const Status = Object.freeze({
  AWAITING_PAYMENT: 'awaiting_payment',
  PAYMENT_FAILED: 'payment_failed',
  PAID: 'paid',
  SUBMITTING: 'submitting',
  SUBMITTED: 'submitted',
  SUBMISSION_FAILED: 'submission_failed',
});

const MAX_PDF_BYTES = 15 * 1024 * 1024;

export function computePrice(pages, pricing) {
  return pricing.baseCents + pricing.perPageCents * pages;
}

export function createApp({ config, stripe, maileva, store, logger = console }) {
  const app = express();
  app.disable('x-powered-by');
  app.use(helmet());
  app.set('trust proxy', 1);

  const inFlight = new Map(); // letterId -> Promise (évite les doubles envois concurrents)

  // --- Webhook Stripe : corps brut requis pour la vérification de signature ---
  app.post('/webhooks/stripe', express.raw({ type: 'application/json', limit: '1mb' }), async (req, res) => {
    let event;
    try {
      event = stripe.webhooks.constructEvent(req.body, req.get('stripe-signature'), config.stripe.webhookSecret);
    } catch {
      return res.status(400).send('Signature invalide');
    }
    try {
      const pi = event.data.object;
      const letterId = pi?.metadata?.letterId;
      if (letterId && event.type === 'payment_intent.succeeded') {
        await processPayment(letterId);
      } else if (letterId && event.type === 'payment_intent.payment_failed') {
        const letter = await store.getLetter(letterId);
        if (letter && letter.status === Status.AWAITING_PAYMENT) {
          await store.saveLetter({ ...letter, status: Status.PAYMENT_FAILED, updatedAt: now() });
        }
      }
      res.json({ received: true });
    } catch (err) {
      logger.error('Erreur webhook', err.message);
      res.status(500).json({ error: 'Traitement impossible' });
    }
  });

  app.use(express.json({ limit: '25mb' }));
  app.get('/health', (_req, res) => res.json({ ok: true }));

  // --- Authentification de l'app (jeton d'API partagé, comparaison à temps constant) ---
  const api = express.Router();
  api.use(rateLimit({ windowMs: 60_000, max: 30 }));
  api.use((req, res, next) => {
    const header = req.get('authorization') || '';
    const token = header.startsWith('Bearer ') ? header.slice(7) : '';
    if (!timingSafeEqual(token, config.apiToken)) return res.status(401).json({ error: 'Non autorisé' });
    next();
  });

  // Crée la lettre et le paiement associé.
  api.post('/letters', async (req, res) => {
    try {
      const { pdfBase64, sender, recipient, notificationEmail, claimKey } = req.body || {};
      if (typeof pdfBase64 !== 'string') return res.status(400).json({ error: 'PDF manquant' });
      const pdf = Buffer.from(pdfBase64, 'base64');
      if (pdf.length === 0 || pdf.length > MAX_PDF_BYTES) return res.status(400).json({ error: 'Taille de PDF invalide' });
      if (pdf.subarray(0, 5).toString('latin1') !== '%PDF-') return res.status(400).json({ error: 'Le fichier n\'est pas un PDF' });

      const senderLines = validateAddress(sender);
      const recipientLines = validateAddress(recipient);
      if (!senderLines || !recipientLines) return res.status(400).json({ error: 'Adresse invalide (6 lignes, ligne 6 = code postal + ville)' });
      if (notificationEmail && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(notificationEmail)) {
        return res.status(400).json({ error: 'E-mail invalide' });
      }

      let pages;
      try {
        pages = (await PDFDocument.load(pdf, { updateMetadata: false })).getPageCount();
      } catch {
        return res.status(400).json({ error: 'PDF illisible' });
      }
      if (pages < 1 || pages > config.pricing.maxPages) {
        return res.status(400).json({ error: `Le courrier doit faire entre 1 et ${config.pricing.maxPages} pages` });
      }

      const id = crypto.randomUUID();
      const amountCents = computePrice(pages, config.pricing);
      const paymentIntent = await stripe.paymentIntents.create({
        amount: amountCents,
        currency: 'eur',
        payment_method_types: ['card'],
        // Authentification forte 3-D Secure exigée pour chaque paiement.
        payment_method_options: { card: { request_three_d_secure: 'any' } },
        description: `Envoi courrier remboursement jeu TV (${pages} p.)`,
        metadata: { letterId: id, claimKey: String(claimKey || '').slice(0, 200) },
      }, { idempotencyKey: `letter-${id}` });

      await store.savePdf(id, pdf);
      await store.saveLetter({
        id,
        claimKey: String(claimKey || '').slice(0, 200),
        status: Status.AWAITING_PAYMENT,
        pages,
        amountCents,
        currency: 'eur',
        paymentIntentId: paymentIntent.id,
        sender: senderLines,
        recipient: recipientLines,
        notificationEmail: notificationEmail || null,
        createdAt: now(),
        updatedAt: now(),
      });

      res.status(201).json({
        letterId: id,
        pages,
        amountCents,
        currency: 'eur',
        paymentIntentClientSecret: paymentIntent.client_secret,
        publishableKey: config.stripe.publishableKey,
      });
    } catch (err) {
      logger.error('Création de lettre impossible', err.message);
      res.status(502).json({ error: 'Création du paiement impossible' });
    }
  });

  api.get('/letters/:id', async (req, res) => {
    const letter = await getLetterOr404(req, res);
    if (letter) res.json(publicView(letter));
  });

  // Appelé par l'app après la PaymentSheet : le serveur vérifie lui-même auprès de Stripe.
  api.post('/letters/:id/confirm', async (req, res) => {
    const letter = await getLetterOr404(req, res);
    if (!letter) return;
    try {
      const updated = await processPayment(letter.id);
      res.json(publicView(updated));
    } catch (err) {
      logger.error('Confirmation impossible', err.message);
      res.status(502).json({ error: 'Vérification du paiement impossible' });
    }
  });

  // Nouvelle tentative d'envoi si Maileva a échoué après un paiement réussi.
  api.post('/letters/:id/retry', async (req, res) => {
    const letter = await getLetterOr404(req, res);
    if (!letter) return;
    if (letter.status !== Status.SUBMISSION_FAILED) return res.status(409).json({ error: 'Rien à relancer' });
    await store.saveLetter({ ...letter, status: Status.PAID, updatedAt: now() });
    const updated = await processPayment(letter.id);
    res.json(publicView(updated));
  });

  app.use('/api', api);

  async function getLetterOr404(req, res) {
    let letter = null;
    try { letter = await store.getLetter(req.params.id); } catch { /* id invalide */ }
    if (!letter) res.status(404).json({ error: 'Lettre introuvable' });
    return letter;
  }

  /** Vérifie le paiement auprès de Stripe puis envoie le courrier (idempotent). */
  function processPayment(letterId) {
    if (inFlight.has(letterId)) return inFlight.get(letterId);
    const p = (async () => {
      let letter = await store.getLetter(letterId);
      if (!letter) throw new Error('Lettre introuvable');
      if ([Status.SUBMITTING, Status.SUBMITTED, Status.SUBMISSION_FAILED].includes(letter.status)) return letter;

      if (letter.status !== Status.PAID) {
        const pi = await stripe.paymentIntents.retrieve(letter.paymentIntentId);
        const valid = pi.status === 'succeeded'
          && pi.amount === letter.amountCents
          && pi.amount_received === letter.amountCents
          && pi.currency === letter.currency
          && pi.metadata?.letterId === letter.id;
        if (!valid) {
          const status = pi.status === 'requires_payment_method' ? Status.PAYMENT_FAILED : letter.status;
          if (status !== letter.status) {
            letter = await store.saveLetter({ ...letter, status, updatedAt: now() });
          }
          return letter;
        }
        letter = await store.saveLetter({ ...letter, status: Status.PAID, paidAt: now(), updatedAt: now() });
      }

      letter = await store.saveLetter({ ...letter, status: Status.SUBMITTING, updatedAt: now() });
      try {
        const pdf = await store.readPdf(letter.id);
        const sendingId = await maileva.sendLetter({
          letterId: letter.id,
          pdf,
          sender: letter.sender,
          recipient: letter.recipient,
          notificationEmail: letter.notificationEmail,
        });
        letter = await store.saveLetter({
          ...letter, status: Status.SUBMITTED, mailevaSendingId: sendingId, submittedAt: now(), updatedAt: now(), error: null,
        });
        await store.deletePdf(letter.id);
      } catch (err) {
        logger.error('Envoi Maileva en échec', err.message);
        letter = await store.saveLetter({
          ...letter, status: Status.SUBMISSION_FAILED, error: 'Envoi postal en échec, nouvelle tentative possible', updatedAt: now(),
        });
      }
      return letter;
    })().finally(() => inFlight.delete(letterId));
    inFlight.set(letterId, p);
    return p;
  }

  return app;
}

function publicView(l) {
  return {
    letterId: l.id,
    status: l.status,
    pages: l.pages,
    amountCents: l.amountCents,
    currency: l.currency,
    mailevaSendingId: l.mailevaSendingId || null,
    error: l.error || null,
    createdAt: l.createdAt,
    submittedAt: l.submittedAt || null,
  };
}

function validateAddress(a) {
  if (!Array.isArray(a) || a.length !== 6) return null;
  const lines = a.map((x) => (typeof x === 'string' ? x.trim() : ''));
  if (lines.some((l) => l.length > 38 || /[\r\n<>]/.test(l))) return null;
  if (!/^\d{5} \S/.test(lines[5])) return null;
  if (!lines[0] && !lines[1]) return null;
  return lines;
}

function timingSafeEqual(a, b) {
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb) && a.length > 0;
}

function rateLimit({ windowMs, max }) {
  const hits = new Map();
  return (req, res, next) => {
    const key = req.ip;
    const t = Date.now();
    const entry = hits.get(key);
    if (!entry || t - entry.start > windowMs) {
      hits.set(key, { start: t, count: 1 });
      return next();
    }
    if (++entry.count > max) return res.status(429).json({ error: 'Trop de requêtes' });
    next();
  };
}

const now = () => new Date().toISOString();
