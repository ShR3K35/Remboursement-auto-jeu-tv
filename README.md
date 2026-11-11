# Remboursement Jeux TV

Application Android qui **détecte vos participations par SMS aux jeux TF1 et M6** et
**envoie automatiquement les demandes de remboursement par courrier postal** (La Poste,
via l'API Maileva), avec la facture détaillée Prixtel et le RIB, après **paiement par carte
sécurisé** (Stripe, 3-D Secure).

Les règles de remboursement prises en compte sont décrites dans [docs/REGLES.md](docs/REGLES.md).

## Architecture

```
core/    Logique métier Kotlin pur (détection SMS+, règles, calcul des demandes, contenu de la lettre) + tests
app/     Application Android (Jetpack Compose)
server/  Petit serveur Node.js : paiement Stripe + envoi du courrier via l'API Maileva
```

Pourquoi un serveur ? La clé secrète Stripe et les identifiants Maileva ne doivent jamais
être embarqués dans une application mobile (ils seraient extractibles de l'APK). Le serveur
calcule le prix, crée le paiement, vérifie qu'il a abouti puis transmet le courrier.

## Fonctionnement

1. **Participations** : l'app lit la boîte « SMS envoyés » (permission `READ_SMS`), en temps
   réel tant qu'elle tourne et toutes les 15 minutes en arrière-plan. Seuls les SMS envoyés à
   un numéro court surtaxé sont conservés. Vous classez les SMS incertains (TF1 / M6 / pas un jeu).
2. **Demandes** : une demande par jeu, avec date limite, montant, alertes (délai, plafond,
   adresse à vérifier) et notification 14 jours avant l'échéance.
3. **Pièces** : bouton « Depuis Prixtel » : l'espace client Prixtel s'ouvre dans l'app, vous
   vous connectez vous-même (l'app ne voit pas votre mot de passe), et la facture PDF téléchargée
   est rattachée au mois voulu. Ou « Choisir un fichier ». Le RIB s'ajoute dans Réglages.
4. **Envoi** : « Aperçu PDF » pour relire, puis choix du mode d'envoi :

   | Mode | Comment | Coût |
   |---|---|---|
   | **Imprimer et poster moi-même** (par défaut) | Impression directe (service d'impression Android) ou partage du PDF ; liens vers *Mon Timbre en Ligne* (timbre à imprimer) et l'appli La Poste (timbre en code à recopier) ; dépôt en boîte jaune ; bouton « J'ai posté » | Timbre Lettre verte, **remboursé par la chaîne** |
   | **Lettre en ligne (laposte.fr)** | L'app enregistre le PDF complet et ouvre le site ; vous l'importez et payez chez La Poste | Surcoût non remboursé |
   | **Merci Facteur (site)** | Idem sur merci-facteur.com | Surcoût non remboursé |
   | **Automatique (Maileva)** | Paiement par carte dans l'app (Stripe, 3-D Secure) puis envoi via l'API Maileva, sans autre action | Surcoût non remboursé, serveur requis |

   Les sites La Poste et Merci Facteur n'offrent pas d'API publique pour les particuliers :
   l'app prépare tout et vous terminez sur leur site (paiement sécurisé chez eux).
   Seul le mode Maileva nécessite le serveur décrit ci-dessous.

## Récupération des factures Prixtel

Prixtel ne propose pas d'API publique de facturation. L'app offre plusieurs chemins, tous
sans doublon (un fichier déjà importé ou un SMS déjà connu n'est jamais ajouté deux fois) :

- **Espace client dans l'app** (bouton « Depuis Prixtel ») : vous vous connectez, la facture
  téléchargée est importée.
- **Partager vers l'app** : depuis l'appli Prixtel, Gmail ou Fichiers, « Partager » ou « Ouvrir
  avec » *Remboursement Jeux TV* sur le PDF.
- **Dossier Google Drive + Gmail** : un script Google ([google-script/](google-script/README.md))
  relève chaque jour les mails Prixtel « votre facture est disponible ». L'app se synchronise une
  fois par jour : elle importe les PDF déposés dans le dossier et affiche « en attente de dépôt »
  (avec notification) chaque facture annoncée et pas encore reçue.

## Sécurité

- **Paiement** : saisie de la carte uniquement dans le formulaire Stripe (PCI-DSS), jamais
  dans l'app ni sur le serveur ; 3-D Secure exigé (`request_three_d_secure: any`) ; montant
  calculé côté serveur à partir du PDF ; envoi conditionné à un PaymentIntent `succeeded`
  avec montant, devise et identifiant de lettre vérifiés ; webhooks Stripe vérifiés par
  signature ; traitement idempotent (pas de double envoi ni de double débit).
- **Données** : tout est chiffré sur le téléphone (AES-256-GCM, clé dans l'Android Keystore) :
  SMS de jeux, identité, IBAN, factures, RIB. Pas de sauvegarde cloud. Captures d'écran
  bloquées. HTTPS obligatoire. Le serveur supprime le PDF dès l'envoi effectué.
- **Prixtel** : connexion sur le site officiel dans une WebView limitée aux domaines
  prixtel.com ; aucun identifiant stocké.
- **API** : jeton d'au moins 32 caractères, comparé à temps constant ; limitation de débit.

## Installation

### 1. Serveur (uniquement pour le mode automatique Maileva)

Prérequis : un compte Stripe et un compte API Maileva (sandbox gratuite pour tester).

```bash
cd server
cp .env.example .env    # puis remplissez les valeurs
npm ci
set -a && . ./.env && set +a && npm start
```

Hébergez-le derrière HTTPS (Render, Fly.io, Railway, un VPS avec Caddy…), puis déclarez le
webhook Stripe `https://<votre-serveur>/webhooks/stripe` (événements
`payment_intent.succeeded` et `payment_intent.payment_failed`).

### 2. Application Android

Deux versions (installables côte à côte) :

| Version | Détection | Installation | Lien |
|---|---|---|---|
| **SMS** | SMS envoyés en temps réel + facture | Par ADB (`adb install remboursement-jeux-tv.apk`) : Play Protect bloque les APK téléchargés qui lisent les SMS | [remboursement-jeux-tv.apk](https://github.com/ShR3K35/Remboursement-auto-jeu-tv/releases/latest/download/remboursement-jeux-tv.apk) |
| **Facture** | Facture détaillée Prixtel (SMS surtaxés, montants réels) | Directe depuis le téléphone | [remboursement-jeux-tv-facture.apk](https://github.com/ShR3K35/Remboursement-auto-jeu-tv/releases/latest/download/remboursement-jeux-tv-facture.apk) |

Ou compilez : `./gradlew :app:assembleSmsRelease` / `:app:assembleFactureRelease`.

**Anti-doublon** : un même SMS vu dans le téléphone et sur la facture (même numéro court, moins
de 5 minutes d'écart) ne fait qu'une participation ; réimporter une facture ou rescanner les SMS
n'ajoute rien ; une participation déjà réclamée ne peut plus entrer dans une demande, et l'app
revérifie juste avant de générer ou d'envoyer chaque courrier.
- Installez-le (autorisez l'installation depuis cette source).
- Android 13+ : si la permission SMS est grisée, ouvrez *Paramètres > Applications >
  Remboursement Jeux TV > ⋮ > Autoriser les paramètres restreints*.
- Dans **Réglages** : identité, numéro de ligne, IBAN, RIB, adresse du serveur et jeton.

> L'app utilise `READ_SMS` : elle est faite pour une installation personnelle (hors Play
> Store, qui réserve cette permission aux applications SMS par défaut).

## Tests

```bash
SKIP_ANDROID=true ./gradlew :core:test   # logique métier, sans SDK Android
cd server && npm test                    # paiement, webhooks, envoi Maileva (simulés)
```

## Limites connues

- Les adresses, délais et plafonds varient selon les jeux : vérifiez le règlement (l'app l'exige pour l'adresse).
- Le coût d'un SMS+ est estimé d'après le numéro court ; corrigez-le d'après la facture si besoin.
- Si Prixtel génère ses factures autrement que par un téléchargement classique, utilisez « Choisir un fichier ».
- Les appels surtaxés (Audiotel) ne sont pas détectés, seulement les SMS.
- L'APK est signé avec une clé de debug générée par la CI : pour les mises à jour, signez avec votre propre keystore.
