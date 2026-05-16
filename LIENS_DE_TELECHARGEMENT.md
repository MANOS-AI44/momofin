# Vos liens de téléchargement publics — MoMo SMS et MoMo Fin

Une fois la mise en ligne effectuée (5 minutes la première fois), vous disposez de **liens stables et permanents** pour télécharger les APK, sans Play Store.

## 🎯 Les liens à partager

Remplacez `MANOS-AI44` et `momofin` par vos vraies valeurs (login GitHub + nom du dépôt) :

| Quoi | Lien |
|---|---|
| 📨 APK MoMo SMS | `https://github.com/MANOS-AI44/momofin/releases/latest/download/MoMoSMS-debug.apk` |
| 💰 APK MoMo Fin | `https://github.com/MANOS-AI44/momofin/releases/latest/download/MoMoFin-debug.apk` |
| 🌐 Page de téléchargement (boutons visuels) | `https://VOTRE-APP.up.railway.app/telecharger` |
| 🌐 Tableau de bord web | `https://VOTRE-APP.up.railway.app/` |

Les liens GitHub **ne changent jamais** : à chaque push, le dernier APK compilé est rattaché au tag `latest`. Vos utilisateurs peuvent cliquer le même lien à vie pour récupérer la version la plus récente.

## 🚀 Pour publier ces liens en 5 minutes

### Étape 1 — Décompresser le ZIP

Décompressez `MoMoFin-source-v1.0.zip` quelque part sur votre Mac.

### Étape 2 — Lancer le script de mise en ligne

Ouvrez le Terminal, allez dans le dossier décompressé, puis :

```bash
./publier.sh
```

Le script vous demande :
- Votre identifiant GitHub
- Le nom du dépôt (par défaut : `momofin`)

Il s'occupe de tout : `git init`, commit, création du dépôt (si vous avez `gh` CLI) ou guidage pour créer le dépôt en ligne, push, et il vous affiche directement vos liens stables.

### Étape 3 — Attendre la compilation (≈ 5 minutes)

Allez sur `https://github.com/MANOS-AI44/momofin/actions` pour suivre la progression. À la fin :
- Une **Release "latest"** est créée automatiquement
- Vos liens APK fonctionnent immédiatement

### Étape 4 — Distribuer

Partagez les liens APK directement (WhatsApp, SMS, email, lien sur une page) ou pointez vos utilisateurs vers la jolie page `https://VOTRE-APP.up.railway.app/telecharger` (après déploiement Railway).

## 📲 Installation côté téléphone

L'utilisateur :
1. Ouvre le lien dans son navigateur Android
2. Le fichier `.apk` se télécharge
3. Il appuie dessus → Android demande l'autorisation d'installer une app extérieure → il l'accorde
4. L'app est installée comme n'importe quelle autre

## 🔄 Mise à jour de l'app

À chaque fois que vous voulez mettre à jour les APK :

```bash
# Modifier le code…
git add .
git commit -m "Description du changement"
git push
```

GitHub recompile automatiquement et **le même lien** sert maintenant la nouvelle version. Vos utilisateurs n'ont rien à changer.

## ⚙️ Configurer Railway pour la page /telecharger

Pour que `https://VOTRE-APP.up.railway.app/telecharger` affiche bien les boutons de téléchargement :

Dans Railway → **Variables**, ajouter :
- `GITHUB_REPO` = `MANOS-AI44/momofin`
- `GITHUB_RELEASE_TAG` = `latest`

C'est tout.

## ❓ Et si je ne veux pas du tout passer par GitHub ?

Vous pouvez aussi héberger les APK directement sur Railway. Dans ce cas :
1. Compilez localement les APK avec Android Studio
2. Copiez-les dans `momofin-web/public/apks/MoMoSMS-debug.apk` et `MoMoFin-debug.apk`
3. La page `/telecharger` les servira automatiquement (Express sert `public/` statiquement)

Ou n'importe quel hébergeur statique (Netlify, Vercel, votre propre serveur…). L'important est juste de pouvoir partager une URL `.apk`.
