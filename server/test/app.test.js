import { test, beforeEach, afterEach } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import Stripe from 'stripe';
import { PDFDocument } from 'pdf-lib';
import { createApp, computePrice, Status } from '../src/app.js';
import { createStore } from '../src/store.js';
import { createMailevaClient } from '../src/maileva.js';

const TOKEN = 'x'.repeat(40);
const WEBHOOK_SECRET = 'whsec_test_secret';
const realStripe = new Stripe('sk_test_dummy');

const config = {
  apiToken: TOKEN,
  stripe: { publishableKey: 'pk_test_123', webhookSecret: WEBHOOK_SECRET },
  pricing: { baseCents: 250, perPageCents: 25, maxPages: 40 },
};

const sender = ['', 'M. Jean DUPONT', '', '1 rue de la Paix', '', '75002 PARIS'];
const recipient = ['TF1 - Service Jeux', '', '', '1 quai du Point du Jour', '', '92656 BOULOGNE CEDEX'];

let server, base, dir, intents, sent, mailevaFails;

async function pdf(pages) {
  const doc = await PDFDocument.create();
  for (let i = 0; i < pages; i++) doc.addPage();
  return Buffer.from(await doc.save()).toString('base64');
}

beforeEach(async () => {
  dir = await fs.mkdtemp(path.join(os.tmpdir(), 'rjtv-'));
  const store = createStore(dir);
  await store.init();
  intents = new Map();
  sent = [];
  mailevaFails = false;
  const stripe = {
    webhooks: realStripe.webhooks,
    paymentIntents: {
      async create(params, opts) {
        assert.equal(params.payment_method_options.card.request_three_d_secure, 'any');
        assert.ok(opts.idempotencyKey);
        const pi = { id: `pi_${intents.size + 1}`, client_secret: 'cs_secret', status: 'requires_payment_method', ...params };
        intents.set(pi.id, pi);
        return pi;
      },
      async retrieve(id) { return intents.get(id); },
    },
  };
  const maileva = {
    async sendLetter(p) {
      if (mailevaFails) throw new Error('boom');
      sent.push(p);
      return 'sending-1';
    },
  };
  const app = createApp({ config, stripe, maileva, store, logger: { error() {} } });
  await new Promise((r) => { server = app.listen(0, r); });
  base = `http://127.0.0.1:${server.address().port}`;
});

afterEach(async () => {
  await new Promise((r) => server.close(r));
  await fs.rm(dir, { recursive: true, force: true });
});

const auth = { Authorization: `Bearer ${TOKEN}`, 'Content-Type': 'application/json' };
const post = (p, body, headers = auth) => fetch(base + p, { method: 'POST', headers, body: JSON.stringify(body) });

async function createLetter(pages = 3) {
  const res = await post('/api/letters', { pdfBase64: await pdf(pages), sender, recipient, claimKey: 'TF1|Koh-Lanta' });
  assert.equal(res.status, 201);
  return res.json();
}

function pay(letterId, amount) {
  const pi = [...intents.values()].find((x) => x.metadata.letterId === letterId);
  Object.assign(pi, { status: 'succeeded', amount_received: amount ?? pi.amount });
  return pi;
}

test('prix calculé côté serveur à partir du nombre de pages', async () => {
  assert.equal(computePrice(3, config.pricing), 325);
  const l = await createLetter(3);
  assert.equal(l.amountCents, 325);
  assert.equal(l.pages, 3);
  assert.equal(l.publishableKey, 'pk_test_123');
});

test('refuse sans jeton ou avec un mauvais jeton', async () => {
  const r1 = await post('/api/letters', {}, { 'Content-Type': 'application/json' });
  assert.equal(r1.status, 401);
  const r2 = await post('/api/letters', {}, { ...auth, Authorization: 'Bearer nope' });
  assert.equal(r2.status, 401);
});

test('refuse un fichier qui n\'est pas un PDF et une adresse invalide', async () => {
  const r1 = await post('/api/letters', { pdfBase64: Buffer.from('hello').toString('base64'), sender, recipient });
  assert.equal(r1.status, 400);
  const r2 = await post('/api/letters', { pdfBase64: await pdf(1), sender, recipient: ['a'] });
  assert.equal(r2.status, 400);
});

test('aucun envoi tant que le paiement n\'est pas confirmé par Stripe', async () => {
  const l = await createLetter();
  const res = await post(`/api/letters/${l.letterId}/confirm`, {});
  const body = await res.json();
  assert.equal(body.status, Status.PAYMENT_FAILED);
  assert.equal(sent.length, 0);
});

test('montant payé différent : pas d\'envoi', async () => {
  const l = await createLetter();
  pay(l.letterId, 1);
  const body = await (await post(`/api/letters/${l.letterId}/confirm`, {})).json();
  assert.notEqual(body.status, Status.SUBMITTED);
  assert.equal(sent.length, 0);
});

test('paiement réussi : envoi unique même avec confirmations concurrentes', async () => {
  const l = await createLetter();
  pay(l.letterId);
  const results = await Promise.all([1, 2, 3].map(() => post(`/api/letters/${l.letterId}/confirm`, {}).then((r) => r.json())));
  assert.ok(results.every((r) => r.status === Status.SUBMITTED));
  assert.equal(sent.length, 1);
  assert.deepEqual(sent[0].recipient, recipient);
  // Le PDF (facture, RIB) est supprimé après l'envoi.
  await assert.rejects(fs.access(path.join(dir, 'letters', `${l.letterId}.pdf`)));
});

test('webhook signé déclenche l\'envoi, webhook non signé refusé', async () => {
  const l = await createLetter();
  const pi = pay(l.letterId);
  const payload = JSON.stringify({ id: 'evt_1', type: 'payment_intent.succeeded', data: { object: pi } });

  const bad = await fetch(`${base}/webhooks/stripe`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'stripe-signature': 't=1,v1=abc' }, body: payload,
  });
  assert.equal(bad.status, 400);
  assert.equal(sent.length, 0);

  const header = realStripe.webhooks.generateTestHeaderString({ payload, secret: WEBHOOK_SECRET });
  const ok = await fetch(`${base}/webhooks/stripe`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'stripe-signature': header }, body: payload,
  });
  assert.equal(ok.status, 200);
  assert.equal(sent.length, 1);

  // Rejeu du même webhook : pas de second envoi.
  await fetch(`${base}/webhooks/stripe`, {
    method: 'POST', headers: { 'Content-Type': 'application/json', 'stripe-signature': header }, body: payload,
  });
  assert.equal(sent.length, 1);
});

test('échec Maileva après paiement : relance possible', async () => {
  const l = await createLetter();
  pay(l.letterId);
  mailevaFails = true;
  const failed = await (await post(`/api/letters/${l.letterId}/confirm`, {})).json();
  assert.equal(failed.status, Status.SUBMISSION_FAILED);
  mailevaFails = false;
  const retried = await (await post(`/api/letters/${l.letterId}/retry`, {})).json();
  assert.equal(retried.status, Status.SUBMITTED);
  assert.equal(sent.length, 1);
});

test('client Maileva : enchaîne auth, création, document, destinataire, soumission', async () => {
  const calls = [];
  const fakeFetch = async (url, init) => {
    calls.push(`${init.method} ${url.replace('https://api.test', '')}`);
    if (url.includes('/token')) return new Response(JSON.stringify({ access_token: 't', expires_in: 300 }), { status: 200 });
    if (url.endsWith('/sendings')) {
      const body = JSON.parse(init.body);
      assert.equal(body.postage_type, 'ECONOMIC');
      assert.equal(body.sender_address_line_2, 'M. Jean DUPONT');
      return new Response(JSON.stringify({ id: 'S1' }), { status: 201 });
    }
    if (url.endsWith('/submit')) return new Response(null, { status: 204 });
    return new Response(JSON.stringify({ id: 'X' }), { status: 201 });
  };
  const client = createMailevaClient({
    authUrl: 'https://auth.test/token', apiUrl: 'https://api.test', clientId: 'c', clientSecret: 's',
    username: 'u', password: 'p', postageType: 'ECONOMIC',
  }, fakeFetch);
  const id = await client.sendLetter({ letterId: 'L1', pdf: Buffer.from('%PDF-1.4'), sender, recipient });
  assert.equal(id, 'S1');
  assert.deepEqual(calls, [
    'POST https://auth.test/token',
    'POST /sendings',
    'POST /sendings/S1/documents',
    'POST /sendings/S1/recipients',
    'POST /sendings/S1/submit',
  ]);
});
