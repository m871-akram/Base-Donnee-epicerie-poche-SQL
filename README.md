# ProjetBD — Épicerie "Le Bon Choix"

Appli Java console pour gérer les commandes d'une épicerie locale, branchée sur Oracle via JDBC: passage de commande (F1), ajustement de prix sur péremption (F2), clôture de commande (F3), annulations et pertes.

| Fichier | Rôle |
|---------|------|
| `Main.java` | Point d'entrée |
| `Database.java` | Connexion JDBC, `autoCommit = false`, commit/rollback |
| `Dao.java` | Requêtes SQL — rien d'autre |
| `Service.java` | Logique métier + transactions |
| `Menu.java` | Interface console client / épicier |
| `Ligne.java` | Objet immuable représentant un article commandé |
| `ProjetBD.sql` | Schéma complet + données de test |
| `Fonctionnalite1.sql` | F1 : réservation (T-A) + déstockage (T-B) |
| `Fonctionnalite2.sql` | F2 : alertes péremption + ajustement prix |
| `Fonctionnalite3.sql` | F3 : clôture retrait ou livraison |

- 10 producteurs, 20 produits (vrac + préconditionné), 7 types de contenants
- Clients avec adresses à Grenoble
- Lots avec péremption proche (pour tester F2)
- Produits 3 et 7 (Miels) : aucune saison → toujours commandables
- Produits 16 et 17 : stock = 0 → rejet garanti en F1

- Accès au serveur Oracle Ensimag : `oracle1.ensimag.fr:1521:oracle1`

**1. Init la base** — ouvrir `ProjetBD.sql` dans SQL Developer, exécuter avec **F5**. Recrée tout from scratch.

**2. Configurer la connexion** dans `src/Main.java` :
```java
Database db = new Database(
    "jdbc:oracle:thin:@oracle1.ensimag.fr:1521:oracle1",
    "votre_login",
    "votre_mot_de_passe"
);
```

**3. Compiler + lancer :**
```bash
mkdir -p out && javac -cp lib/ojdbc17.jar -d out src/*.java
java -cp out:lib/ojdbc17.jar Main
```

---

## Fonctionnalités

### Mode Client

**1 — Catalogue** : liste les produits avec stock dispo (hors réservations en cours). Affiche `rupture` si stock = 0.

**2 — Passer une commande (F1)**
```
Login (ID ou email) ou création de compte
    ↓
Mode récupération : RETRAIT ou LIVRAISON
    ↓
Mode paiement : EN LIGNE ou EN BOUTIQUE
    ↓
Saisie produits + quantités
  • Vérif saisonnalité (PERIODE_DISPONIBILITE) — pas de période = dispo toute l'année
  • Vérif stock dispo (stock réel − réservations actives)
  • Produit VRAC → proposition d'un contenant compatible
    ↓
Réservation FEFO dans RESERVATION_STOCK + COMMIT
  (le stock physique est déduit uniquement au passage en 'Prête')
```

Login : saisir un ID numérique **ou** un email — la présence de `@` déclenche la recherche par email.

**Frais de livraison** (fixe + 0,50 €/kg) :

| Zone | Fixe |
|------|------|
| Grenoble / Saint-Martin-d'Hères | 5 € |
| Lyon | 10 € |
| France métropolitaine | 15 € |
| DOM-TOM | 35 € |
| International | 50 € |

Délai estimé : 4 j (France), 11 j (DOM-TOM), 16 j (international).

**3 — Annuler une commande** : uniquement si statut `En preparation`. Les réservations sont libérées immédiatement.

---

### Mode Épicier

**1 — Ajuster prix péremption (F2)** : affiche les lots qui expirent dans les 7 prochains jours, puis applique un % de réduction saisi.

**2 — Marquer Prête** : passe de `En preparation` → `Prete` et déduit le stock réel lot par lot (ordre FEFO).

**3 — Clôturer une commande (F3)** : passe au statut final :
- `Recupere` si retrait (+ `DATEREELLE` dans `RETRAIT_BOUTIQUE`)
- `Livree` si livraison (+ `DATEREELLE` dans `LIVRAISON_DOMICILE`)

Si paiement `EN BOUTIQUE` : `DATEREELLE` fait office de timestamp de paiement.

**4 — Annuler une commande** : possible tant que la commande n'est pas finalisée (`Recupere`/`Livree`/`Annulee` bloqués).

**5 — Enregistrer une perte** : nature (vol/casse/dommage), type (Produit ou Contenant), ID et quantité. Insère dans `PERTE` + `PRODUIT_PERDU` ou `CONTENANT_PERDU`.

---

## Transactions & concurrence

`autoCommit = false` dès la connexion. Chaque méthode de `Service` commit si tout va bien, rollback sinon :

```java
try {
    // opérations Dao...
    db.commit();
} catch (Exception e) {
    db.rollback();
    throw e;
}
```

### Cycle de vie d'une commande

```
[F1]                    [Épicier]           [Épicier F3]
En preparation  ──────→  Prete  ──────────→  Recupere
     │           marquer         clôture           Livree
     └─────────────────────────────────────→  Annulee
                    annulation (client ou épicier)
```

### Verrous pessimistes

| Opération | Verrou |
|-----------|--------|
| Passer commande (F1) | `PRODUIT FOR UPDATE` par produit |
| Marquer Prête | `COMMANDE FOR UPDATE` + `RESERVATION_STOCK FOR UPDATE` |
| Clôturer / Annuler | `COMMANDE FOR UPDATE` |

### FEFO

Les lots sont consommés dans l'ordre croissant de `DATEPEREMPTION`. `RESERVATION_STOCK` isole les quantités réservées sans toucher au stock physique — plusieurs commandes peuvent coexister sans conflit.

---

## Modèle de données

```
PRODUCTEUR ──< PRODUIT >── CONDITIONNEMENT
                                ├── VRAC  (PRIXVENTEVRAC, PRIXKGVRAC)
                                └── PRECOND (PRIXVENTEP, POIDSFIXE)
                 │
                 ├──< LOT_PRODUIT ──< RESERVATION_STOCK
                 └──< PERIODE_DISPONIBILITE >── PERIODE

CLIENT ──< ADRESSE
  │
  └──< COMMANDE >──< LIGNE_COMMANDE >── PRODUIT (sentinel 999 = contenant)
          │
          ├── RETRAIT_BOUTIQUE
          └── LIVRAISON_DOMICILE ──> ADRESSE

CONTENANT ──< LOT_CONTENANT
           ──< LIGNE_COMMANDE (via produit 999)

PERTE ──< PRODUIT_PERDU  >── PRODUIT
      └─< CONTENANT_PERDU >── CONTENANT
```

**Séquences Oracle :** `SEQ_COMMANDE`, `SEQ_RETRAIT_BOUTIQUE`, `SEQ_LIVRAISON_DOMICILE`.  
`IDADRESSE` et `IDPERTE` utilisent `MAX(ID) + 1`.

---

## Tests

Compiler d'abord :
```bash
mkdir -p out && javac -cp lib/ojdbc17.jar -d out src/*.java
```

> Les tests 7–12 et 15 utilisent des IDs créés dans les tests précédents.  
> Remplacer `<ID_RETRAIT>`, `<ID_LIVRAISON>`, `<ID_NOUVEAU>`, `<ID_NOUVEAU2>` par les IDs affichés.

---

### TEST 1 — Catalogue
```bash
printf "1\n1\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : 20 produits avec stock numérique ou `rupture`.

---

### TEST 2 — Nouveau client + commande retrait EN BOUTIQUE
```bash
printf "1\n2\nO\n204\nO\nLefebvre\nAnne\nanne204@test.fr\n0611223344\n3 avenue Gambetta\nGrenoble\nFrance\nRETRAIT\nEN BOUTIQUE\n9\n2\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : commande créée, ID + montant. **Noter l'ID → `<ID_RETRAIT>`.**

---

### TEST 3 — Login par email
```bash
printf "1\n2\nN\nanne204@test.fr\nRETRAIT\nEN LIGNE\n9\n1\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : commande créée — valide que le `@` déclenche bien la recherche par email.

---

### TEST 4 — Produit sans saison (toujours dispo)
```bash
printf "1\n2\nN\n204\nRETRAIT\nEN LIGNE\n3\n2\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : commande créée. Produit 3 (Miel, 0 entrée dans `PERIODE_DISPONIBILITE`) passe sans rejet.

---

### TEST 5 — Rejet stock insuffisant
```bash
printf "1\n2\nN\n204\nRETRAIT\nEN LIGNE\n2\n999\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Erreur commande : Stock insuffisant pour produit 2`

---

### TEST 6 — Commande livraison domicile
```bash
printf "1\n2\nN\n204\nLIVRAISON\nEN LIGNE\n9\n1\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : commande créée, montant inclut les frais de livraison. **Noter l'ID → `<ID_LIVRAISON>`.**

---

### TEST 7 — Marquer Prête (déstockage réel)
```bash
printf "2\n2\n<ID_RETRAIT>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Commande passée en 'Prête'. Stock déduit.`

---

### TEST 8 — Clôturer retrait EN BOUTIQUE
```bash
printf "2\n3\n<ID_RETRAIT>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu :
```
Commande clôturée.
Paiement EN BOUTIQUE enregistré.
```

---

### TEST 9 — Clôturer livraison domicile
```bash
# Marquer Prête d'abord
printf "2\n2\n<ID_LIVRAISON>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
# Puis clôturer
printf "2\n3\n<ID_LIVRAISON>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Commande clôturée.` (sans message paiement boutique).

---

### TEST 10 — Annulation client (En preparation)
```bash
# Créer une commande
printf "1\n2\nN\n204\nRETRAIT\nEN LIGNE\n9\n1\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
# Annuler → <ID_NOUVEAU>
printf "1\n3\n<ID_NOUVEAU>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Commande annulée.`

---

### TEST 11 — Annulation client refusée (déjà clôturée)
```bash
printf "1\n3\n<ID_RETRAIT>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Erreur annulation : Annulation client autorisée uniquement en statut 'En preparation'.`

---

### TEST 12 — Double marquer Prête refusée
```bash
printf "2\n2\n<ID_RETRAIT>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Erreur : La commande n'est pas en préparation (statut : Recupere).`

---

### TEST 13 — F2 alertes péremption + ajustement prix
```bash
printf "2\n1\n10\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : liste des lots qui expirent dans 7 jours, puis `N produits ajustés.`

---

### TEST 14 — Enregistrer une perte
```bash
printf "2\n5\nvol\nP\n9\n0.5\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Perte enregistrée.`

---

### TEST 15 — Annulation épicier (En preparation)
```bash
# Créer une commande
printf "1\n2\nN\n204\nRETRAIT\nEN LIGNE\n1\n5\n0\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
# Annuler côté épicier → <ID_NOUVEAU2>
printf "2\n4\n<ID_NOUVEAU2>\n0\n0\n" | java -cp out:lib/ojdbc17.jar Main
```
Attendu : `Commande annulée.`

---

### TEST 16 — Concurrence (verrous pessimistes)

Oracle tourne en **Read Committed** (niveau 1) par défaut. Le code ajoute des `SELECT ... FOR UPDATE` explicites sur `PRODUIT` pour sérialiser les F1 concurrents sur le même produit.

Ouvrir **deux sessions SQLPlus** (ou deux feuilles SQL Developer avec `AUTOCOMMIT OFF`) :

**Session 1** — prend le verrou et attend :
```sql
SELECT IDPRODUIT, NOMCOMMERCIAL FROM PRODUIT WHERE IDPRODUIT = 1 FOR UPDATE;
-- ne pas committer
```

**Session 2** — immédiatement après :
```sql
SELECT IDPRODUIT, NOMCOMMERCIAL FROM PRODUIT WHERE IDPRODUIT = 1 FOR UPDATE;
-- BLOQUE jusqu'au COMMIT de S1
```

Dans Session 1 :
```sql
COMMIT;
```
Attendu : Session 2 se débloque instantanément.

---
