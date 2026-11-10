# Règles de remboursement des jeux TV par SMS (TF1, M6)

## Principe

Les jeux télévisés auxquels on participe par SMS surtaxé (SMS+) ou par appel surtaxé
doivent rester « sans obligation d'achat ». Les règlements de TF1 et M6 prévoient donc le
**remboursement des frais de participation sur simple demande écrite par courrier**.

## Ce que les règlements demandent

| | TF1 | M6 |
|---|---|---|
| Forme | Courrier postal à l'adresse indiquée dans le règlement du jeu | Courrier postal à l'adresse indiquée dans le règlement du jeu |
| Délai | 90 jours après la participation | En général 3 mois (selon le jeu) |
| Pièces | Copie de la facture détaillée (SMS du jeu identifiés) + RIB | Facture détaillée faisant apparaître les SMS + RIB ; parfois une pièce d'identité |
| Frais remboursés | SMS de participation, timbre au tarif Lettre verte, photocopies | SMS de participation, timbre au tarif lent |
| Limites | Une demande par foyer et par jeu dans certains règlements | Plafond fréquent (ex. 10 participations par émission) |
| Délai de traitement | Jusqu'à plusieurs mois | Constaté jusqu'à environ 6 mois |

Les adresses intégrées par défaut sont celles des sièges (TF1 : 1 quai du Point du Jour,
Boulogne-Billancourt ; M6 : 89 avenue Charles de Gaulle, Neuilly-sur-Seine). **Chaque
règlement précise l'adresse exacte du service jeux** : l'app bloque l'envoi tant que vous
n'avez pas confirmé l'adresse (case « J'ai vérifié cette adresse dans le règlement »).

## Process mis en œuvre par l'app

1. **Détection** : lecture des SMS envoyés à un numéro court surtaxé (5 chiffres, 3xxxx à
   8xxxx). L'émission est reconnue par mots-clés (« 12 coups », « Pékin », « M6 », etc.)
   ou par les numéros courts que vous avez enregistrés. Les SMS sans indice sont « à classer ».
2. **Regroupement** : une demande (une lettre) par jeu ; calcul de la date limite à partir de
   la participation la plus ancienne ; exclusion des participations hors délai ; plafond appliqué.
3. **Pièces** : facture détaillée Prixtel de chaque mois concerné (téléchargée dans l'espace
   client depuis l'app, ou fichier choisi), RIB, et pièce d'identité si exigée.
4. **Lettre** : nom, adresse, numéro de la ligne, jeu, tableau date/heure/numéro/montant de
   chaque SMS, total demandé (SMS + timbre + photocopies), IBAN, liste des pièces jointes.
5. **Envoi** : PDF unique (lettre + pièces) transmis à l'API Maileva (groupe La Poste), qui
   imprime, met sous pli et dépose le courrier en affranchissement économique, après paiement
   par carte de l'envoi.

Sources consultées : règlements de jeux SMS/Audiotel publiés par les chaînes, articles du
Journal du Net (procédure TF1) et de Sud Radio (délais M6). Vérifiez toujours le règlement
du jeu concerné : il fait foi.
