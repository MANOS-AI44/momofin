# Déployer MoMo Fin sur GitHub + Railway

Ce guide explique comment publier le projet sur GitHub et déployer le backend sur Railway en moins de 15 minutes, puis comment relier les APK Android au serveur.

## Vue d'ensemble

```
   ┌──────────────────┐         pousser le code         ┌──────────────────┐
   │ Votre ordinateur │ ───────────────────────────►   │      GitHub      │
   │ (dossier projet) │                                │ (dépôt git)      │
   └──────────────────┘                                └────────┬─────────┘
                                                                │  déploiement automatique
                                                                ▼
   ┌──────────────────┐    POST /api/transactions     ┌──────────────────┐
   │  APK MoMo Fin    │ ───────────────────────────► │ Railway (Node.js) │
   │  (téléphone)     │  ◄───── PDF, dashboard ────  │ + PostgreSQL      │
   └──────────────────┘                              └──────────────────┘
```

## Partie 1 — Pousser le code sur GitHub

### 1.1 Créer un dépôt GitHub

1. Aller sur https://github.com/new
2. Nom du dépôt : `momofin` (ou ce que vous voulez)
3. Cocher **Private** si vous voulez le garder privé
4. **Ne pas** cocher "Initialize with README" (le projet en a déjà un)
5. Cliquer **Create repository**

### 1.2 Initialiser git en local

Ouvrir un terminal dans le dossier qui contient `MoMoSMS/`, `MoMoFin/` et `momofin-web/` :

```bash
cd "chemin/vers/le/dossier"

git init
git add .
git commit -m "Initial commit : MoMo SMS + MoMo Fin + backend web"
git branch -M main
git remote add origin https://github.com/MANOS-AI44/momofin.git
git push -u origin main
```

> Si Git vous demande des identifiants : utilisez un **Personal Access Token** GitHub (Settings → Developer settings → Personal access tokens) en place de mot de passe.

À partir de là, chaque `git push` met à jour automatiquement le dépôt. Si vous avez gardé `.github/workflows/android-build.yml`, GitHub Actions compile les deux APK à chaque push (onglet **Actions** du dépôt).

## Partie 2 — Déployer le backend sur Railway

### 2.1 Créer un compte et un projet

1. Aller sur https://railway.app et se connecter avec GitHub
2. Cliquer **New Project → Deploy from GitHub repo**
3. Sélectionner votre dépôt `momofin`
4. Si Railway demande un dossier : choisir **`momofin-web/`** (sous-dossier du backend)

### 2.2 Ajouter une base PostgreSQL

1. Dans le projet Railway, cliquer **+ New → Database → Add PostgreSQL**
2. Railway crée la base et expose automatiquement la variable d'environnement `DATABASE_URL` au service web

### 2.3 Variables d'environnement

Dans **Project Settings → Variables**, ajouter :

| Variable | Valeur | À quoi ça sert |
|---|---|---|
| `ADMIN_PASSWORD` | mot de passe au choix | protège la page web du dashboard |
| `PORT` | (laissé vide — Railway le fournit) | port d'écoute HTTP |

`DATABASE_URL` est ajoutée automatiquement par le plugin PostgreSQL.

### 2.4 Obtenir l'URL publique

1. Dans **Settings → Networking**, cliquer **Generate Domain**
2. Railway fournit une URL du type `https://momofin-production-xxxx.up.railway.app`
3. Tester en ouvrant cette URL dans le navigateur — le tableau de bord MoMo Fin Web doit s'afficher (avec demande de mot de passe si `ADMIN_PASSWORD` est défini).

### 2.5 Créer un token pour le téléphone

1. Sur le dashboard web, aller dans **Téléphones**
2. Renseigner un nom (ex. *Boutique 1*) et cliquer **Créer un token**
3. Copier le token affiché — il faudra le coller dans l'APK MoMo Fin

## Partie 3 — Connecter l'APK MoMo Fin au backend Railway

1. Ouvrir MoMo Fin sur le téléphone
2. Appuyer sur **Paramètres** (en haut à droite)
3. Coller :
   - **URL du serveur** : `https://momofin-production-xxxx.up.railway.app`
   - **Token du téléphone** : celui obtenu à l'étape 2.5
4. Appuyer sur **Tester** → un message vert "✅ Connecté à : Boutique 1" doit apparaître
5. Appuyer sur **Enregistrer**
6. Retourner à l'écran principal et appuyer sur **Synchroniser**
7. Toutes les transactions Mobile Money parsées sont envoyées au serveur. Elles apparaissent immédiatement sur le dashboard web `https://momofin-production-xxxx.up.railway.app/`

## Partie 4 — Utilisation au quotidien

- L'APK continue à fonctionner hors ligne : tout reste stocké localement.
- À chaque appui sur **Synchroniser**, seules les nouvelles transactions sont envoyées (dédoublonnage automatique côté serveur via `UNIQUE(device_id, raw_body, ts)`).
- Sur le tableau de bord web, vous pouvez :
  - Consulter les transactions regroupées par jour avec sous-totaux Reçu / Sortie / Solde
  - Ajouter des entrées **PATRON** manuelles
  - Générer un **PDF imprimable** à tout moment via le menu *PDF*
- Vous pouvez créer plusieurs tokens (un par téléphone), tous se centralisent sur le même dashboard.

## Partie 5 — Mise à jour automatique (CI/CD)

Le fichier `.github/workflows/railway-deploy.yml` permet de redéployer automatiquement à chaque push :

1. Dans Railway → **Account Settings → Tokens**, créer un token Railway
2. Sur GitHub, aller dans **Settings → Secrets and variables → Actions**
3. Ajouter un secret `RAILWAY_TOKEN` avec le token
4. À chaque `git push` qui modifie `momofin-web/`, le déploiement Railway est lancé automatiquement.

> Astuce : Railway peut aussi se redéployer tout seul en mode "auto-deploy from GitHub" (option dans Project Settings → Service → Source), ce qui rend ce workflow optionnel.

## Partie 6 — Dépannage

| Problème | Solution |
|---|---|
| L'APK affiche "Erreur réseau" lors du test | Vérifier que l'URL commence par `https://` et **ne se termine pas par `/`**. Vérifier la connexion internet du téléphone. |
| "HTTP 401 — Token invalide" | Le token est mal recopié ou a été révoqué. Recréer un token dans le dashboard et le recopier. |
| Le dashboard web demande le mot de passe à chaque visite | Normal : `ADMIN_PASSWORD` est une protection légère. Vous pouvez l'enlever (variable vide) si Railway est déjà privé. |
| Railway ne trouve pas `package.json` | Configurer la **Root Directory** dans Service Settings sur `momofin-web` |
| Les PDF ne sont pas en français | Vérifier que la locale du serveur Node supporte le français — sur Railway/Nixpacks elle est présente par défaut. |
| Erreur PostgreSQL au démarrage | Vérifier que la base est bien démarrée (statut vert dans Railway) et que `DATABASE_URL` est exposée au service web. |

---

Bon déploiement !
