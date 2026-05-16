# Déployer MoMo Fin Web sur Railway — Guide détaillé clic par clic

Ce guide ne saute aucune étape. Suivez-le dans l'ordre. Durée totale : **8 à 10 minutes**.

> **Pré-requis** : votre code doit déjà être sur GitHub (étape 3 du guide précédent). Si ce n'est pas fait, lancez `./publier.sh` d'abord.

---

## Étape 1 — Créer le compte Railway

1. Allez sur **https://railway.app/login**
2. Cliquez sur **« Login with GitHub »**
3. Autorisez Railway à lire vos dépôts publics (cliquez **Authorize Railway**)
4. Railway vous propose un compte gratuit avec 5$ de crédit/mois — c'est **largement suffisant** pour MoMo Fin (≈ 1$/mois en usage normal)

---

## Étape 2 — Créer le projet et lui connecter votre dépôt GitHub

1. Une fois connecté, cliquez sur **« New Project »** (gros bouton violet en haut à droite)
2. Choisissez **« Deploy from GitHub repo »**
3. Si Railway demande l'accès à vos dépôts : cliquer **« Configure GitHub App »** puis sélectionner **« Only select repositories »** → cocher **`momofin`** → **Save**
4. Revenez à Railway, votre dépôt `momofin` apparaît dans la liste → cliquez dessus
5. Railway lance un premier déploiement **qui va échouer** (c'est normal) parce qu'il essaie de builder depuis la racine du dépôt alors qu'on veut le sous-dossier `momofin-web/`. Pas grave, on corrige à l'étape suivante.

---

## Étape 3 — Pointer Railway vers le bon sous-dossier

1. Dans le projet, cliquez sur le service créé (carte avec le nom de votre dépôt)
2. Onglet **« Settings »**
3. Section **« Source »** → trouver **« Root Directory »**
4. Cliquer **« Edit »** → taper **`momofin-web`** → **Save**
5. Railway redéploie automatiquement. Le build doit maintenant trouver le `package.json`.

---

## Étape 4 — Ajouter la base PostgreSQL

1. Dans le projet, en haut à droite cliquer **« + Create »** (ou **« New »**)
2. Choisir **« Database »** → **« Add PostgreSQL »**
3. Une nouvelle carte « Postgres » apparaît. Railway crée la variable `DATABASE_URL` automatiquement, mais il faut la connecter au service web :

### Connecter la variable au service web :
1. Cliquer sur la carte de votre service web (pas Postgres)
2. Onglet **« Variables »**
3. Cliquer **« + New Variable »** → **« Add Reference »**
4. Source : **`Postgres`** → Variable : **`DATABASE_URL`** → **Add**
5. Le service web redémarre. Cette fois la base est branchée.

---

## Étape 5 — Définir les autres variables d'environnement

Toujours dans **Variables** du service web, cliquer **« + New Variable »** (ou **Raw Editor** pour tout coller d'un coup) et ajouter :

| Variable | Valeur | Pourquoi |
|---|---|---|
| `ADMIN_PASSWORD` | un mot de passe au choix (ex. `Patron2026!`) | Protège le tableau de bord admin |
| `GITHUB_REPO` | `MANOS-AI44/momofin` | Pour les boutons de téléchargement APK |
| `GITHUB_RELEASE_TAG` | `latest` | Tag de la release à servir |

> **Note** : `PORT` et `DATABASE_URL` sont déjà fournis par Railway, **ne les ajoutez pas**.

À chaque variable ajoutée, Railway redéploie. Attendez 30 secondes entre deux modifications.

---

## Étape 6 — Générer l'URL publique

1. Service web → onglet **« Settings »**
2. Section **« Networking »**
3. Cliquer **« Generate Domain »**
4. Railway crée une URL du type `momofin-production-xxxx.up.railway.app` → cliquer **« Copy »**

---

## Étape 7 — Tester que ça marche

Ouvrez ces URL dans votre navigateur :

1. **`https://votre-url.up.railway.app/telecharger`** — page publique avec boutons APK ✅ (cette page doit afficher 2 gros boutons de téléchargement)
2. **`https://votre-url.up.railway.app/`** — tableau de bord (demande le mot de passe `ADMIN_PASSWORD`)
3. **`https://votre-url.up.railway.app/api/ping`** — doit renvoyer `{"ok":true,"ts":"..."}`

Si la page `/telecharger` affiche « APK non publié — configurez GITHUB_REPO », c'est que la variable d'environnement `GITHUB_REPO` n'est pas définie ou pas encore propagée. Attendre 1 minute et recharger.

---

## Étape 8 — Créer un token téléphone pour la synchro

1. Aller sur **`https://votre-url.up.railway.app/devices`** (mot de passe demandé)
2. Renseigner un nom (ex. *Mon téléphone*) → **Créer un token**
3. Copier le token affiché
4. Sur le téléphone Android, ouvrir l'app MoMo Fin → bouton **Paramètres**
5. Coller :
   - **URL** : `https://votre-url.up.railway.app`
   - **Token** : celui copié à l'étape 3
6. Appuyer sur **Tester** → message vert « ✅ Connecté » attendu
7. **Enregistrer** → retour à l'écran principal → bouton **Synchroniser**

---

## Étape 9 — Vérifier que tout est bon

À ce stade vous avez :

✅ Backend hébergé : `https://votre-url.up.railway.app`
✅ Tableau de bord : `https://votre-url.up.railway.app/` (protégé par mot de passe)
✅ Page de téléchargement publique : `https://votre-url.up.railway.app/telecharger`
✅ APK MoMo SMS direct : `https://github.com/MANOS-AI44/momofin/releases/latest/download/MoMoSMS-debug.apk`
✅ APK MoMo Fin direct : `https://github.com/MANOS-AI44/momofin/releases/latest/download/MoMoFin-debug.apk`
✅ Sync activée : les transactions du téléphone remontent dans le dashboard web

---

## Dépannage rapide

| Symptôme | Solution |
|---|---|
| **Build Railway échoue : « package.json not found »** | Root Directory mal défini → mettre `momofin-web` exactement |
| **Build échoue : « ECONNREFUSED »** ou DB introuvable au démarrage | Variable `DATABASE_URL` non rattachée → étape 4 sub-section « Connecter la variable » |
| **« relation tables does not exist »** | Le service a démarré avant que Postgres soit prêt. Redémarrer le service (Settings → Deployments → ⋯ → Restart) |
| **`/telecharger` montre « APK non publié »** | Variable `GITHUB_REPO` mal renseignée OU la release `latest` n'existe pas encore. Aller sur l'onglet Actions de GitHub vérifier que le workflow a bien créé la release. |
| **L'APK télécharge mais Android refuse d'installer** | Activer « Sources inconnues » dans Paramètres Android → Sécurité ou Apps spéciales |
| **Test connexion APK → « HTTP 401 »** | Le token est expiré/mal copié → recréer un token sur `/devices` |
| **Test connexion APK → « Erreur réseau »** | L'URL ne commence pas par `https://` ou se termine par `/` → corriger dans Paramètres |

---

## Coût attendu

Railway facture à l'usage (RAM × secondes + bande passante). Pour MoMo Fin :
- Web app : ~ 0,30 $/mois
- PostgreSQL : ~ 0,50 $/mois
- **Total : ≈ 1 $/mois**

Largement dans les 5 $/mois gratuits du plan Hobby.

---

## Mise à jour future

Pour livrer une nouvelle version :

```bash
# Sur votre Mac, dans le dossier du projet
git add .
git commit -m "Nouvelle version"
git push
```

- GitHub Actions recompile automatiquement les APK → **les mêmes liens** servent la nouvelle version
- Railway redéploie automatiquement le backend → **la même URL** sert la nouvelle version

Vos utilisateurs n'ont rien à changer.
