# Script Google : factures Prixtel depuis Gmail et Google Drive

Ce script tourne **dans votre compte Google** (aucun serveur, aucun projet Google Cloud).

- Chaque jour, il cherche dans Gmail les mails Prixtel annonçant une facture. Ces mails ne
  contiennent pas la facture : l'app la met « en attente » jusqu'à ce que vous l'ayez
  téléchargée dans l'espace client Prixtel et déposée dans le dossier Drive (ou importée
  directement dans l'app). Si un jour un mail contient un PDF, il est rangé automatiquement.
- L'app Android l'interroge une fois par jour : elle importe les nouveaux PDF du dossier
  (sans doublon) et affiche « en attente de dépôt » chaque facture annoncée par mail mais pas
  encore déposée dans le dossier, avec une notification.

## Installation (10 minutes, une seule fois)

1. Dans Google Drive, créez un dossier, par exemple `Factures Prixtel`. Ouvrez-le et copiez
   son identifiant : la fin de l'URL `https://drive.google.com/drive/folders/<ID>`.
2. Ouvrez https://script.google.com > **Nouveau projet**. Remplacez le contenu de `Code.gs`
   par celui de [Code.gs](Code.gs), puis collez l'identifiant du dossier dans `FOLDER_ID`.
3. Sélectionnez la fonction `setup` et cliquez **Exécuter**. Acceptez les autorisations
   (Gmail en lecture, Drive). Google affiche « application non validée » car c'est votre propre
   script : *Paramètres avancés > Accéder au projet*.
4. Dans le **Journal d'exécution**, copiez le **jeton** affiché.
5. **Déployer > Nouveau déploiement** > type **Application Web** :
   - *Exécuter en tant que* : **Moi**
   - *Qui a accès* : **Tout le monde** (l'accès est protégé par le jeton)
   Copiez l'**URL de l'application Web** (`https://script.google.com/macros/s/…/exec`).
6. Dans l'app : **Réglages > Google Drive** : collez l'URL et le jeton, puis
   **Participations > Synchroniser Drive**.

## Sécurité

- Le script n'expose que les PDF du dossier configuré, et uniquement avec le jeton (64 caractères).
- Il ne lit dans Gmail que l'expéditeur, la date et l'objet des mails Prixtel (et leurs PDF joints).
- Le jeton est stocké chiffré dans l'app. Pour le révoquer : supprimez la propriété `APP_TOKEN`
  (Paramètres du projet > Propriétés du script) puis relancez `setup`.
