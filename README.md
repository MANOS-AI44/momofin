# MoMo Fin — Suite complète

[![Deploy on Railway](https://railway.app/button.svg)](https://railway.app/new/template?template=https://github.com/MANOS-AI44/momofin&envs=ADMIN_PASSWORD,GITHUB_REPO,GITHUB_RELEASE_TAG&optionalEnvs=GITHUB_RELEASE_TAG&ADMIN_PASSWORDDesc=Mot+de+passe+du+dashboard&GITHUB_REPODesc=Votre+depot+(login%2Fnom)&GITHUB_RELEASE_TAGDesc=Tag+release+APK&GITHUB_RELEASE_TAGDefault=latest)

> ⚠️ **Modifiez `MANOS-AI44` dans le bouton ci-dessus** par votre vrai login GitHub avant de cliquer (ou modifiez le README après avoir poussé sur GitHub).

Solution Mobile Money complète, en trois composants :

| Composant | Dossier | Technologie | Hébergement |
|---|---|---|---|
| **MoMo SMS** (APK) | `MoMoSMS/` | Kotlin / Android | Téléphone |
| **MoMo Fin** (APK) | `MoMoFin/` | Kotlin / Android | Téléphone |
| **MoMo Fin Web** (backend) | `momofin-web/` | Node.js / Express / PostgreSQL | Railway |

## Architecture

```
┌─────────────┐  ContentProvider  ┌─────────────┐   HTTPS    ┌──────────────────┐
│  MoMo SMS   │ ────────────────► │  MoMo Fin   │ ─────────► │ Railway backend  │
│  (lit SMS)  │                   │  (parse,    │            │ Express + Postgres│
│             │                   │   PDF,      │ ◄────────  │ Dashboard web    │
│             │                   │   PATRON)   │   PDF web  │ (GitHub Actions) │
└─────────────┘                   └─────────────┘            └──────────────────┘
```

## Fichiers à lire en premier

- `GUIDE_COMPILATION.md` → compiler les APK Android
- `GUIDE_GITHUB_RAILWAY.md` → publier sur GitHub et déployer sur Railway
- `momofin-web/.env.example` → variables d'environnement du backend

## Fonctionnalités

### APK MoMo SMS
- Capte les SMS Mobile Money en temps réel (MTN, Orange, Airtel)
- Stocke localement et les expose à MoMo Fin via ContentProvider

### APK MoMo Fin
- Extrait date, heure, montant, devise, référence/ID de chaque SMS
- Liste regroupée **par jour** avec sous-totaux Reçu / Sortie / Solde
- Export PDF A4 imprimable (rapport quotidien)
- Section **PATRON** : boutons Reçu / Sortie / Total
- Bouton **Synchroniser** vers Railway

### Backend MoMo Fin Web (Railway)
- API REST (token par téléphone)
- Tableau de bord web (transactions groupées par jour, totaux)
- Section PATRON éditable depuis le web
- Génération PDF côté serveur
- Multi-téléphones : un token par appareil, tout se centralise
- Mot de passe admin optionnel pour le dashboard

## Démarrage rapide

```bash
# 1. Pousser le code sur GitHub
git init && git add . && git commit -m "Initial commit"
git remote add origin https://github.com/VOUS/momofin.git && git push -u origin main

# 2. Déployer momofin-web/ sur Railway
#    (voir GUIDE_GITHUB_RAILWAY.md)

# 3. Compiler les APK dans Android Studio
#    (voir GUIDE_COMPILATION.md)

# 4. Dans l'APK, ouvrir Paramètres → coller URL Railway + token
# 5. Appuyer sur Synchroniser
```

## Confidentialité

- Tout reste sur **votre** infrastructure (votre Railway, votre PostgreSQL)
- Aucune donnée ne quitte la chaîne téléphone → Railway
- Pas d'API tierce, pas de tracking, pas de pub
- Authentification par token simple révocable depuis le dashboard

## Personnalisation

| Pour modifier… | Fichier |
|---|---|
| Opérateurs / mots-clés détectés | `MomoFilter.kt` (APK) + `lib/parser.js` (web) |
| Formats de date supportés | `TransactionParser.kt` + `lib/parser.js` |
| Apparence du PDF | `PdfGenerator.kt` (APK) + `lib/pdf.js` (web) |
| Style du dashboard | `momofin-web/public/style.css` |
| Couleurs / textes APK | `app/src/main/res/values/colors.xml`, `strings.xml` |
