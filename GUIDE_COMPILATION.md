# MoMo SMS + MoMo Fin — Guide de compilation des APK

Ce dossier contient **deux projets Android Studio en Kotlin** prêts à compiler :

- `MoMoSMS/` : lit les SMS Mobile Money et les expose à MoMo Fin via un ContentProvider
- `MoMoFin/` : extrait date, heure, montant, référence, produit le PDF imprimable et contient la section **PATRON** (Reçu / Sortie / Total)

> Je ne peux pas générer directement de fichiers `.apk` ici. Vous compilez les APK depuis votre ordinateur en suivant les étapes ci-dessous. Une fois compilés, les deux APK s'installent sur n'importe quel téléphone Android 6.0+ (API 23+).

---

## 1. Pré-requis

1. Installer **Android Studio Hedgehog (2023.1.1)** ou plus récent : https://developer.android.com/studio
2. Lors du premier lancement, Android Studio installera automatiquement :
   - JDK 17 intégré
   - Android SDK 34
   - Outils de build

Aucune autre installation n'est nécessaire — Gradle 8.5 est téléchargé automatiquement par le wrapper la première fois.

---

## 2. Compiler MoMo SMS

1. Ouvrir Android Studio → **File → Open…** → sélectionner le dossier `MoMoSMS/`
2. Attendre la fin de l'indexation et du téléchargement Gradle (5–10 min la première fois)
3. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**
4. À la fin, une notification « *APK(s) generated successfully* » apparaît. Cliquer sur **locate** pour ouvrir le fichier :
   ```
   MoMoSMS/app/build/outputs/apk/debug/app-debug.apk
   ```
5. Copier cet APK sur le téléphone (USB, Bluetooth, Google Drive…) et l'installer.

---

## 3. Compiler MoMo Fin

Mêmes étapes : ouvrir le dossier `MoMoFin/`, puis **Build → Build APK(s)**.

L'APK généré se trouve dans :
```
MoMoFin/app/build/outputs/apk/debug/app-debug.apk
```

---

## 4. APK de release signé (optionnel, mais recommandé pour distribution)

Pour un APK signé et plus léger :

1. Menu **Build → Generate Signed Bundle / APK → APK**
2. Créer une keystore (`.jks`) — Android Studio guide la procédure
3. Choisir variante **release** → l'APK signé se trouve dans
   `app/build/outputs/apk/release/app-release.apk`

---

## 5. Installation sur le téléphone

Ordre **recommandé** :

1. Installer d'abord **MoMo SMS**, l'ouvrir, accepter les permissions `READ_SMS` / `RECEIVE_SMS`, puis appuyer sur **Importer SMS**.
2. Installer ensuite **MoMo Fin**. Au premier lancement, accepter la permission `READ_MOMO` demandée par MoMo SMS (et `READ_SMS` en fallback).
3. MoMo Fin lit alors automatiquement les SMS Mobile Money depuis MoMo SMS, les regroupe par jour et calcule les totaux.

> ⚠️ Sur Android, l'autorisation **« Installer des applications inconnues »** doit être activée dans Paramètres → Sécurité avant d'installer un APK qui ne vient pas du Play Store.

---

## 6. Utilisation rapide

### MoMo SMS
- **Importer SMS** : balaye la boîte SMS et stocke uniquement les SMS Mobile Money détectés (MTN, Orange, Airtel)
- **Rafraîchir** : recharge la liste
- Les nouveaux SMS reçus sont captés automatiquement en temps réel par le BroadcastReceiver.

### MoMo Fin — page principale
- Affiche les transactions **groupées par jour** avec :
  - Heure, Type (Reçu/Sortie), Montant, Référence, Opérateur
  - Sous-totaux jour : Reçu / Sortie / Solde
- **Exporter PDF** : génère un PDF A4 imprimable (`Téléchargements/MoMoFin/`) puis l'ouvre dans un lecteur PDF de votre choix → vous pouvez imprimer directement.
- **PATRON** : ouvre la section dédiée.

### MoMo Fin — section PATRON
- Bouton **Reçu** : ajoute une entrée d'argent reçu (montant + note)
- Bouton **Sortie** : ajoute une dépense
- Bouton **Total** : affiche le solde = Reçu − Sortie
- L'historique est listé sous les boutons (appui long pour supprimer une ligne)
- Les saisies PATRON sont **incluses dans le PDF exporté** depuis l'écran principal.

---

## 7. Personnalisation rapide

| Élément | Fichier | Quoi changer |
|---|---|---|
| Nom de l'app | `app/src/main/res/values/strings.xml` | `app_name` |
| Couleurs | `app/src/main/res/values/colors.xml` | `primary`, `accent` |
| Icône | `app/src/main/res/drawable/ic_launcher_foreground.xml` | Vecteur SVG |
| Expéditeurs reconnus | `MomoFilter.kt` (champ `SENDERS`) | Ajouter votre opérateur |
| Formats de date supportés | `TransactionParser.kt` (`DATE_PATTERNS`) | Ajouter d'autres formats |
| Devises | `TransactionParser.kt` (`CURRENCY`) | Ajouter votre devise |

---

## 8. Dépannage

| Problème | Solution |
|---|---|
| « SDK location not found » | **File → Project Structure → SDK Location** puis pointer vers le SDK Android |
| Téléchargement Gradle bloqué | Vérifier la connexion ; relancer **File → Sync Project with Gradle Files** |
| « Permission denied » sur Téléchargements | Android 10+ : MoMo Fin utilise MediaStore, aucune permission nécessaire. Vérifier que vous êtes sur Android 10+ ou ajouter `WRITE_EXTERNAL_STORAGE` |
| MoMo Fin n'affiche rien | Lancer d'abord MoMo SMS, appuyer sur **Importer SMS**, puis revenir dans MoMo Fin et **Rafraîchir** |
| SMS non détectés | Ajouter les noms d'expéditeur de votre opérateur dans `MomoFilter.kt` (`SENDERS`) |

---

Bonne compilation !
