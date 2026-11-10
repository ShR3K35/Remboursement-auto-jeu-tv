// Stockage minimal sur disque (JSON + PDF). Écritures atomiques (fichier temporaire + rename).
import fs from 'node:fs/promises';
import path from 'node:path';

export function createStore(dataDir) {
  const lettersDir = path.join(dataDir, 'letters');

  async function init() {
    await fs.mkdir(lettersDir, { recursive: true, mode: 0o700 });
  }

  const metaPath = (id) => path.join(lettersDir, `${safeId(id)}.json`);
  const pdfPath = (id) => path.join(lettersDir, `${safeId(id)}.pdf`);

  async function writeAtomic(file, data) {
    const tmp = `${file}.${process.pid}.${Date.now()}.tmp`;
    await fs.writeFile(tmp, data, { mode: 0o600 });
    await fs.rename(tmp, file);
  }

  return {
    init,
    async saveLetter(letter) {
      await writeAtomic(metaPath(letter.id), JSON.stringify(letter, null, 2));
      return letter;
    },
    async getLetter(id) {
      try {
        return JSON.parse(await fs.readFile(metaPath(id), 'utf8'));
      } catch (e) {
        if (e.code === 'ENOENT') return null;
        throw e;
      }
    },
    async savePdf(id, buf) { await writeAtomic(pdfPath(id), buf); },
    async readPdf(id) { return fs.readFile(pdfPath(id)); },
    // Minimisation des données : le PDF (facture, RIB) est supprimé dès l'envoi effectué.
    async deletePdf(id) { await fs.rm(pdfPath(id), { force: true }); },
  };
}

function safeId(id) {
  if (!/^[a-f0-9-]{36}$/.test(id)) throw new Error('Identifiant de lettre invalide');
  return id;
}
