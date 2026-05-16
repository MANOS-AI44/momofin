#!/bin/bash
# publier.sh — Mise en ligne automatique sur GitHub puis suivi de la compilation des APK.
# Usage : ./publier.sh

set -e

cd "$(dirname "$0")"

cyan() { printf "\033[36m%s\033[0m\n" "$1"; }
green() { printf "\033[32m%s\033[0m\n" "$1"; }
yellow() { printf "\033[33m%s\033[0m\n" "$1"; }
red() { printf "\033[31m%s\033[0m\n" "$1"; }

cyan "▶ MoMo Fin — script de mise en ligne sur GitHub"
echo

# --- 1. Vérifs ---
if ! command -v git &> /dev/null; then
    red "❌ git n'est pas installé. Installez-le depuis https://git-scm.com/"
    exit 1
fi

# --- 2. Saisie ---
read -p "Votre identifiant GitHub : " GH_USER
read -p "Nom du dépôt à créer (par défaut : momofin) : " REPO
REPO=${REPO:-momofin}

GH_URL="https://github.com/${GH_USER}/${REPO}.git"

# --- 3. Init git si besoin ---
if [ ! -d ".git" ]; then
    cyan "▶ Initialisation du dépôt git…"
    git init -q
    git branch -M main
fi

# --- 3.5 Personnalisation : remplacer VOTRE-LOGIN par l'identifiant réel ---
cyan "▶ Personnalisation des fichiers avec votre login GitHub…"
if [[ "$OSTYPE" == "darwin"* ]]; then
    SED_INPLACE=(-i '')
else
    SED_INPLACE=(-i)
fi
find . -type f \( -name "*.md" -o -name "*.json" -o -name "*.yml" \) \
    ! -path "./.git/*" ! -path "./node_modules/*" \
    -exec sed "${SED_INPLACE[@]}" "s|VOTRE-LOGIN/momofin|${GH_USER}/${REPO}|g" {} +
find . -type f \( -name "*.md" -o -name "*.json" -o -name "*.yml" \) \
    ! -path "./.git/*" ! -path "./node_modules/*" \
    -exec sed "${SED_INPLACE[@]}" "s|REMPLACER-PAR/momofin|${GH_USER}/${REPO}|g" {} +
green "✓ Fichiers mis à jour"

# --- 4. Commit ---
cyan "▶ Ajout des fichiers et premier commit…"
git add .
if ! git diff --cached --quiet; then
    git commit -q -m "Initial commit — MoMo Fin v1.0"
    green "✓ Commit effectué"
else
    yellow "ℹ Rien de nouveau à committer"
fi

# --- 5. Création du dépôt distant via gh CLI si disponible ---
if command -v gh &> /dev/null && gh auth status &> /dev/null; then
    cyan "▶ Création du dépôt GitHub via gh CLI…"
    gh repo create "${GH_USER}/${REPO}" --public --source=. --remote=origin --push 2>/dev/null \
        || gh repo create "${GH_USER}/${REPO}" --private --source=. --remote=origin --push 2>/dev/null \
        || { red "❌ Échec création via gh. Créez le dépôt manuellement sur https://github.com/new"; exit 1; }
    green "✓ Dépôt créé et code poussé"
else
    if ! git remote get-url origin &> /dev/null; then
        git remote add origin "$GH_URL"
    fi
    echo
    yellow "▶ Étape manuelle (gh CLI absente ou non authentifiée) :"
    echo "  1. Allez sur https://github.com/new"
    echo "  2. Nom du dépôt : ${REPO}"
    echo "  3. Cliquez 'Create repository' (sans README/license/.gitignore)"
    echo "  4. Revenez ici et appuyez sur Entrée"
    read -p "Appuyez sur Entrée quand le dépôt est créé… "
    cyan "▶ Push…"
    git push -u origin main
fi

echo
green "✓✓✓ Code en ligne sur GitHub ! ✓✓✓"
echo
cyan "▶ La compilation des APK démarre automatiquement…"
echo "   Suivez-la sur : https://github.com/${GH_USER}/${REPO}/actions"
echo
cyan "▶ Une fois terminée (≈ 5 minutes), les APK sont téléchargeables ici :"
green "   📨 MoMo SMS  : https://github.com/${GH_USER}/${REPO}/releases/latest/download/MoMoSMS-debug.apk"
green "   💰 MoMo Fin  : https://github.com/${GH_USER}/${REPO}/releases/latest/download/MoMoFin-debug.apk"
echo
cyan "▶ Ou simplement la page de la dernière release :"
green "   https://github.com/${GH_USER}/${REPO}/releases/latest"
echo
yellow "ℹ Ces liens sont stables : ils continueront à pointer vers la dernière compilation à chaque push."
echo
cyan "▶ Pour héberger ensuite le backend web sur Railway : voir GUIDE_GITHUB_RAILWAY.md"
echo "  Une fois déployé, vos liens de téléchargement publics seront aussi disponibles sur :"
echo "  https://VOTRE-APP.up.railway.app/telecharger"
