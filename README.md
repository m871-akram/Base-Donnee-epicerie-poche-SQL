# ProjetBD — Épicerie "Le Bon Choix"

Application Java console de gestion de commandes pour une épicerie locale, connectée à Oracle via JDBC.  
Elle couvre trois fonctionnalités métier principales : passage de commande, ajustement des prix sur péremption, et clôture de commande — ainsi que la gestion des pertes et des annulations.

## Architecture

```
Menu.java  ──→  Service.java  ──→  Dao.java  ──→  Database.java
  (UI)          (Logique)          (SQL)            (JDBC)
                                                        │
                                                  Oracle DB
```

| Fichier | Rôle |
|---------|------|
| `Main.java` | Point d'entrée — instancie et relie tous les composants |
| `Database.java` | Connexion JDBC, commit/rollback, `autoCommit = false` |
| `Dao.java` | Requêtes SQL uniquement, pas de logique métier |
| `Service.java` | Logique métier et gestion transactionnelle |
| `Menu.java` | Interface console pour le client et l'épicier |
| `Ligne.java` | Objet de données immutable représentant un produit commandé |

## Prérequis

- Java 17+ (text blocks `"""` requis)
- Oracle Database 19c+ (serveur Ensimag : `oracle1.ensimag.fr:1521:oracle1`)
- Driver JDBC : `lib/ojdbc17.jar` (inclus)

## Installation et lancement

### 1. Initialiser la base de données

Ouvrir `ProjetBD.sql` dans Oracle SQL Developer et l'exécuter (**F5**) sur votre connexion. Ce script supprime et recrée toutes les tables, séquences et données de test.

### 2. Configurer la connexion

Éditer `src/Main.java` lignes 10–13 :

```java
Database db = new Database(
    "jdbc:oracle:thin:@oracle1.ensimag.fr:1521:oracle1",
    "votre_login",
    "votre_mot_de_passe"
);
```

### 3. Compiler

```bash
mkdir -p out
javac -cp lib/ojdbc17.jar -d out src/*.java
```

### 4. Lancer

```bash
java -cp out:lib/ojdbc17.jar Main
```

## Utilisation

Au démarrage, choisir son rôle :

```
BIENVENUE à l'épicerie 'LE BON CHOIX'
1. Entrer en tant que client
2. Entrer en tant qu'épicier
0. Quitter le menu
```

---

### Mode Client

**1 — Afficher le catalogue**  
Liste tous les produits avec leur catégorie et leur stock disponible (hors réservations en cours). Affiche `rupture` si le stock est à zéro.

**2 — Passer une commande (F1)**

```
Connexion (ID ou email) ou création de compte client
    ↓
Choix du mode de récupération (RETRAIT / LIVRAISON)
    ↓
Choix du mode de paiement (EN LIGNE / EN BOUTIQUE)
    ↓
Saisie des produits et quantités
  • Vérification saisonnalité (PERIODE_DISPONIBILITE)
    — Produit sans période définie → disponible toute l'année
  • Vérification stock disponible (stock réel − réservations actives)
  • Pour les produits en vrac : proposition d'un contenant compatible
    ↓
Réservation FEFO dans RESERVATION_STOCK + COMMIT
  (le stock physique n'est déduit qu'au passage en 'Prête')
```

**Connexion client :** saisir un ID numérique **ou** une adresse email (le `@` déclenche automatiquement la recherche par email).

**Frais de livraison** (partie fixe + 0,50 €/kg) :

| Zone | Frais fixes |
|------|-------------|
| Grenoble / Saint-Martin-d'Hères | 5,00 € |
| Lyon | 10,00 € |
| France métropolitaine (autre) | 15,00 € |
| DOM-TOM | 35,00 € |
| International | 50,00 € |

**Délai estimé :** 4 jours (France), 11 jours (DOM-TOM), 16 jours (international).

**3 — Annuler une commande**  
Le client peut annuler uniquement ses commandes au statut `En preparation`. Les réservations de stock associées sont libérées immédiatement.

---

### Mode Épicier

**1 — Ajuster prix péremption (F2)**  
Affiche les lots dont la date de péremption est dans les 7 prochains jours. L'épicier saisit un pourcentage de réduction appliqué sur le prix de vente de tous les produits concernés (vrac et préconditionné).

**2 — Marquer commande comme Prête**  
Passe une commande de `En preparation` à `Prete` et effectue le déstockage physique réel :
1. Verrouille la commande (`FOR UPDATE`)
2. Déduit les quantités lot par lot depuis `RESERVATION_STOCK` (ordre FEFO)
3. Supprime les entrées de `RESERVATION_STOCK`
4. Met à jour le statut → `Prete`

**3 — Clôturer une commande (F3)**  
Saisir un ID de commande au statut `En preparation`, `Prete` ou `En livraison`. Elle passe au statut :
- `Recupere` si retrait en boutique (+ `DATEREELLE` dans `RETRAIT_BOUTIQUE`)
- `Livree` si livraison à domicile (+ `DATEREELLE` dans `LIVRAISON_DOMICILE`)

Si le mode de paiement est `EN BOUTIQUE` pour un retrait, le message `Paiement EN BOUTIQUE enregistré.` est affiché — `DATEREELLE` sert de timestamp du paiement.

**4 — Annuler une commande**  
L'épicier peut annuler toute commande non encore finalisée (`Recupere`, `Livree`, `Annulee` refusés). Les réservations sont libérées si la commande est encore `En preparation`.

**5 — Enregistrer une perte**  
Saisir la nature (vol / casse / dommage), le type (Produit ou Contenant), l'ID de l'élément et la quantité. Insère dans `PERTE` et lie dans `PRODUIT_PERDU` ou `CONTENANT_PERDU`.

---

## Gestion des transactions

`autoCommit` est désactivé dès la connexion. Toutes les modifications passent par la couche `Service`, qui commit en cas de succès ou rollback en cas d'erreur :

```java
try {
    // ... opérations Dao ...
    db.commit();
} catch (Exception e) {
    db.rollback();
    throw e;
}
```

### Cycle de vie d'une commande

```
[Passage commande F1]          [Épicier]              [Épicier F3]
  En preparation  ──────────→  Prete  ──────────────→  Recupere
       │           marquer              clôture retrait
       │           Prete                                Livree
       │                                clôture livraison
       └──────────────────────────────────────────────→ Annulee
                        annulation (client ou épicier)
```

### Concurrence et verrous pessimistes

| Opération | Verrou |
|-----------|--------|
| Passer commande (F1) | `PRODUIT FOR UPDATE` par produit commandé |
| Marquer Prête | `COMMANDE FOR UPDATE` + `RESERVATION_STOCK FOR UPDATE` |
| Clôturer (F3) | `COMMANDE FOR UPDATE` |
| Annuler | `COMMANDE FOR UPDATE` |

### Stratégie FEFO

Les réservations et le déstockage consomment les lots dans l'ordre croissant de `DATEPEREMPTION` (First Expired, First Out). La table `RESERVATION_STOCK` isole les quantités réservées sans modifier le stock physique, permettant la concurrence entre plusieurs commandes en cours.

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

### Séquences Oracle

| Séquence | Usage |
|----------|-------|
| `SEQ_COMMANDE` | `IDCOMMANDE` |
| `SEQ_RETRAIT_BOUTIQUE` | `IDRETRAITBOUTIQUE` |
| `SEQ_LIVRAISON_DOMICILE` | `IDLIVRAISONDOMICILE` |

`IDADRESSE` et `IDPERTE` utilisent `MAX(ID) + 1` (pas de séquence dédiée).

---

## Fichiers SQL fournis

| Fichier | Contenu |
|---------|---------|
| `ProjetBD.sql` | Création complète du schéma + données de test |
| `Fonctionnalite1.sql` | Transactions F1 : réservation (T-A) et déstockage (T-B) |
| `Fonctionnalite2.sql` | Requêtes F2 : alertes péremption + ajustement prix |
| `Fonctionnalite3.sql` | Requêtes F3 : clôture retrait ou livraison |

---

## Données de test

`ProjetBD.sql` inclut :
- 10 producteurs, 20 produits (vrac et préconditionné)
- 7 types de contenants
- Clients avec adresses à Grenoble
- Des lots avec péremption proche (pour tester F2)
- Produits 3 et 7 (Miels) : aucune période de saisonnalité → toujours disponibles
- Produits 16 et 17 : stock à zéro → rejet stock en F1
