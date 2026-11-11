/**
 * Remboursement Jeux TV — script Google Apps Script (s'exécute dans VOTRE compte Google).
 *
 * 1. Une fois par jour : cherche dans Gmail les mails Prixtel annonçant une facture.
 *    Si un mail contient la facture en PDF, il est enregistré dans le dossier Drive.
 * 2. Sert à l'app Android (application Web protégée par un jeton) :
 *      ?action=list&token=…      -> mails « facture disponible » + PDF du dossier
 *      ?action=file&id=…&token=… -> contenu d'un PDF du dossier (base64)
 *
 * Installation : voir google-script/README.md
 */

// ---- Configuration -------------------------------------------------------
/** Identifiant du dossier Drive où vous déposez les factures (fin de l'URL du dossier). */
const FOLDER_ID = 'COLLEZ_ICI_L_ID_DU_DOSSIER';
/** Recherche Gmail des mails de facture Prixtel (modifiable). */
const GMAIL_QUERY = 'from:prixtel.com (facture OR "votre facture") newer_than:150d';
/** Heure de la vérification quotidienne (0-23, heure du compte Google). */
const DAILY_HOUR = 8;
// ------------------------------------------------------------------------

const PROP_TOKEN = 'APP_TOKEN';
const PROP_EMAILS = 'INVOICE_EMAILS';
const PROP_LAST_CHECK = 'LAST_CHECK';

/** À exécuter une fois depuis l'éditeur : autorisations, déclencheur quotidien, jeton. */
function setup() {
  const props = PropertiesService.getScriptProperties();
  if (!props.getProperty(PROP_TOKEN)) {
    props.setProperty(PROP_TOKEN, Utilities.getUuid().replace(/-/g, '') + Utilities.getUuid().replace(/-/g, ''));
  }
  ScriptApp.getProjectTriggers()
    .filter(t => t.getHandlerFunction() === 'checkGmail')
    .forEach(t => ScriptApp.deleteTrigger(t));
  ScriptApp.newTrigger('checkGmail').timeBased().everyDays(1).atHour(DAILY_HOUR).create();
  DriveApp.getFolderById(FOLDER_ID); // vérifie l'accès au dossier
  checkGmail();
  Logger.log('Jeton à copier dans l\'app (Réglages > Google Drive) : ' + props.getProperty(PROP_TOKEN));
}

/** Vérification quotidienne de Gmail. */
function checkGmail() {
  const props = PropertiesService.getScriptProperties();
  const folder = DriveApp.getFolderById(FOLDER_ID);
  const known = JSON.parse(props.getProperty(PROP_EMAILS) || '[]');
  const byId = {};
  known.forEach(e => { byId[e.id] = e; });

  GmailApp.search(GMAIL_QUERY, 0, 50).forEach(thread => {
    thread.getMessages().forEach(msg => {
      const id = msg.getId();
      if (byId[id]) return;
      const from = msg.getFrom().toLowerCase();
      if (from.indexOf('prixtel') < 0) return;
      const entry = { id: id, received: msg.getDate().getTime(), subject: msg.getSubject().slice(0, 150), hasPdf: false };
      // Facture jointe au mail : rangée automatiquement dans le dossier.
      msg.getAttachments().forEach(att => {
        const isPdf = att.getContentType() === 'application/pdf' || /\.pdf$/i.test(att.getName());
        if (!isPdf) return;
        const name = 'Prixtel ' + Utilities.formatDate(msg.getDate(), 'Europe/Paris', 'yyyy-MM-dd') + ' ' + att.getName();
        if (!folder.getFilesByName(name).hasNext()) folder.createFile(att.copyBlob().setName(name));
        entry.hasPdf = true;
      });
      byId[id] = entry;
    });
  });

  // On garde les 60 mails les plus récents.
  const all = Object.keys(byId).map(k => byId[k]).sort((a, b) => b.received - a.received).slice(0, 60);
  props.setProperty(PROP_EMAILS, JSON.stringify(all));
  props.setProperty(PROP_LAST_CHECK, String(Date.now()));
}

/** Point d'entrée de l'application Web (appelé par l'app Android). */
function doGet(e) {
  const props = PropertiesService.getScriptProperties();
  const expected = props.getProperty(PROP_TOKEN);
  const token = (e && e.parameter && e.parameter.token) || '';
  if (!expected || !safeEquals(token, expected)) return json({ error: 'Non autorisé' });

  const action = e.parameter.action || 'list';
  const folder = DriveApp.getFolderById(FOLDER_ID);

  if (action === 'list') {
    // Si le déclencheur n'a pas tourné depuis 20 h, on vérifie Gmail maintenant.
    const last = Number(props.getProperty(PROP_LAST_CHECK) || 0);
    if (Date.now() - last > 20 * 3600 * 1000) checkGmail();
    const files = [];
    const it = folder.getFilesByType(MimeType.PDF);
    while (it.hasNext()) {
      const f = it.next();
      files.push({ id: f.getId(), name: f.getName(), created: f.getDateCreated().getTime(), size: f.getSize() });
    }
    return json({
      emails: JSON.parse(props.getProperty(PROP_EMAILS) || '[]'),
      files: files,
      lastCheck: Number(props.getProperty(PROP_LAST_CHECK) || 0),
    });
  }

  if (action === 'file') {
    const id = e.parameter.id || '';
    const file = DriveApp.getFileById(id);
    // Seuls les fichiers du dossier configuré sont accessibles.
    let inFolder = false;
    const parents = file.getParents();
    while (parents.hasNext()) if (parents.next().getId() === FOLDER_ID) inFolder = true;
    if (!inFolder || file.getMimeType() !== MimeType.PDF) return json({ error: 'Fichier non autorisé' });
    if (file.getSize() > 10 * 1024 * 1024) return json({ error: 'Fichier trop volumineux' });
    return json({ id: id, name: file.getName(), base64: Utilities.base64Encode(file.getBlob().getBytes()) });
  }

  return json({ error: 'Action inconnue' });
}

function safeEquals(a, b) {
  const da = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(a));
  const db = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, String(b));
  let diff = 0;
  for (let i = 0; i < da.length; i++) diff |= da[i] ^ db[i];
  return diff === 0 && String(a).length > 0;
}

function json(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
