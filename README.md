# MoMo Fin — Suite complète

**Suivi automatique des transactions Mobile Money** (MTN, Orange, Airtel, Moov, Wave, djamo, Yas)

## 📦 Deux composants

| Composant | Dossier | Technologie | Hébergement |
|---|---|---|---|
| 📱 **Application Android** | `MoMoFin/` | Kotlin / Android | Téléphones |
| 🌐 **Backend + Site web** | `momofin-web/` | Node.js + Express + PostgreSQL | Railway |

## 🔗 Liens

| Quoi | URL |
|---|---|
| 📱 APK Android | `https://github.com/<vous>/momofin/releases/latest/download/MoMoFin-debug.apk` |
| 🌐 Site web (admin) | `https://<votre-app>.up.railway.app/` |
| 📥 Page téléchargement publique | `https://<votre-app>.up.railway.app/telecharger` |

## ✨ Fonctionnalités

### Application Android (tout-en-un)
- **Lecture automatique des SMS** Mobile Money (3 sources : notifications, receiver temps réel, boîte SMS)
- **Filtrage intelligent** par opérateur et mots-clés
- **Extraction** : date/heure, type (Retrait/Dépôt), montant, numéro, référence, opérateur
- **Tableau de bord** groupé par jour avec totaux
- **Sélecteur de date** + export **PDF** imprimable
- **Mes Comptes** : carnets personnels (Gérard, Boutique 1, etc.) avec entrées/sorties
- **Mes Points** : saisie quotidienne des soldes (OM, MoMo, MOOV, WAVE, djamo, CFA) + dépenses, calcul automatique du TOTAL POINTS
- **Mes Appareils** : création de codes pour sous-téléphones
- **Synchronisation** avec le backend Railway
- **Téléphone prioritaire** : choix de qui capte les SMS vs qui consulte
- Login : email/password OU code d'appareil OU création de compte
- Réinitialisation complète possible

### Site web (Railway)
- **Tableau de bord** filtrable par période (jour, semaine, mois, année, custom)
- **Sidebar pro** avec navigation : Dashboard / Mes Points / Mes Comptes / Mes Appareils / Mon compte
- **Bouton Imprimer** et **Téléchargement PDF** avec titre dynamique « Transactions chez <Compte> du... au... »
- **Isolation par utilisateur** : admin voit tout ses appareils, sous-comptes voient leurs propres données
- **Login multi-mode** : email/password (admin) OU code d'appareil (sous-compte)

## 🚀 Déploiement

### 1) Pousser le code sur GitHub
```bash
git init && git add . && git commit -m "Initial"
git remote add origin https://github.com/<vous>/momofin.git
git push -u origin main
```

GitHub Actions compile automatiquement l'APK à chaque push et publie une release `latest`.

### 2) Déployer le backend sur Railway
- Aller sur https://railway.com → **New Project** → **Deploy from GitHub repo**
- Sélectionner votre dépôt
- **Settings → Root Directory** : `momofin-web`
- Ajouter **PostgreSQL** (+ New → Database → PostgreSQL)
- **Variables** : `GITHUB_REPO=<vous>/momofin`
- **Settings → Networking → Generate Domain**

### 3) Utiliser
- Ouvrir l'URL Railway → **Créer un compte**
- Télécharger l'APK depuis `/telecharger`
- Installer sur le téléphone → se connecter → activer l'accès aux notifications
- Synchroniser

## 📋 Workflow administrateur + boutiques

1. **Admin** crée son compte (ex. *Manos Group*)
2. Admin va dans **Mes Appareils** (web ou app) → **+ Nouveau** → reçoit un code (ex. `K7M2QX`)
3. Admin dicte le code à l'utilisateur de la boutique
4. **Sous-utilisateur** ouvre l'app MoMo Fin → bouton **« 🔢 J'ai un code d'appareil »** → entre le code
5. Le téléphone de la boutique capture les SMS et les envoie au cloud
6. Admin voit toutes les transactions de toutes ses boutiques sur le site web

## 🔧 Personnalisation rapide

| Quoi | Fichier |
|---|---|
| Opérateurs/mots-clés reconnus | `MoMoFin/.../SmsSource.kt` (`MomoFilter`) + `momofin-web/lib/parser.js` |
| Formats date/montant/référence | `MoMoFin/.../TransactionParser.kt` + `momofin-web/lib/parser.js` |
| Apparence PDF app | `MoMoFin/.../PdfGenerator.kt` |
| Apparence PDF web | `momofin-web/lib/pdf.js` |
| Style site web | `momofin-web/public/style.css` |
| Couleurs app | `MoMoFin/.../res/values/colors.xml` |
| Styles boutons app | `MoMoFin/.../res/values/styles.xml` |
