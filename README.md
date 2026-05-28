# ProjetBD — Épicerie "Le Bon Choix"

Application Java console de gestion de commandes pour une épicerie locale, connectée à Oracle via JDBC. Elle couvre trois fonctionnalités métier : passage de commande, ajustement des prix sur péremption, et clôture de commande.

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
| `Database.java` | Connexion JDBC, commit/rollback, autoCommit désactivé par défaut |
| `Dao.java` | Requêtes SQL uniquement, pas de logique métier |
| `Service.java` | Logique métier et gestion transactionnelle |
| `Menu.java` | Interface console pour le client et l'épicier |
| `Ligne.java` | Objet de données immutable représentant un produit commandé |

## Prérequis

- Java 17+ (text blocks `"""` requis)
- Oracle Database 19c (serveur Ensimag : `oracle1.ensimag.fr`)
- Driver JDBC : `lib/ojdbc17.jar` (inclus)

## Installation et lancement

### 1. Initialiser la base de données

Ouvrir `ProjetBD.sql` dans Oracle SQL Developer et l'exécuter (**F5**) sur votre connexion. Ce script supprime et recrée toutes les tables, séquences et données de test.

> Si la base a déjà été initialisée mais que les périodes de saison sont expirées, exécuter uniquement `patch_saison.sql` à la place.

### 2. Compiler

```bash
javac -cp lib/ojdbc17.jar -d out src/*.java
```

### 3. Configurer la connexion

Éditer `src/Main.java` lignes 10–13 :

```java
Database db = new Database(
    "jdbc:oracle:thin:@oracle1.ensimag.fr:1521:oracle1",
    "votre_login",
    "votre_mot_de_passe"
);
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

### Mode Client

**1 — Afficher le catalogue**
Liste tous les produits disponibles avec leur catégorie.

**2 — Passer une commande**

```
Connexion ou création de compte client
    ↓
Choix du mode de récupération (Retrait / Livraison)
    ↓
Choix du mode de paiement (EN LIGNE / EN BOUTIQUE)
    ↓
Saisie des produits et quantités
    ↓
Validation (stock, saisonnalité) + calcul du total
    ↓
Déstockage FEFO + COMMIT
```

Pour chaque produit en vrac, l'application propose un contenant compatible (capacité ≥ quantité commandée, stock disponible).

**Frais de livraison** (base + distance + 0,50 €/kg) :

| Zone | Total fixe |
|------|-----------|
| Grenoble / Saint-Martin-d'Hères | 5,00 € |
| Lyon | 10,00 € |
| France métropolitaine (autre) | 15,00 € |
| DOM-TOM | 35,00 € |
| International | 50,00 € |

**Délai de livraison estimé** : 4 jours (France), 11 jours (DOM-TOM), 16 jours (international).

### Mode Épicier

**1 — Ajuster prix péremption**

Affiche les lots dont la date de péremption est dans les 7 prochains jours. L'épicier saisit un pourcentage de réduction qui est appliqué sur le prix de vente de tous les produits concernés (vrac et préconditionné).

**2 — Clôturer une commande**

Saisir un ID de commande. La commande doit être au statut `En preparation`, `Prete` ou `En livraison`. Elle passe au statut :
- `Recupere` si retrait en boutique
- `Livree` si livraison à domicile

La date réelle de récupération est enregistrée. L'opération est protégée par un verrou pessimiste (`FOR UPDATE`).

## Modèle de données

```
PRODUCTEUR ──< PRODUIT >── CONDITIONNEMENT (VRAC | PRE)
                 │
                 ├──< LOT_PRODUIT (stock, péremption FEFO)
                 └──< PERIODE_DISPONIBILITE >── PERIODE (saisonnalité)

CLIENT ──< ADRESSE
  │
  └──< COMMANDE >──< LIGNE_COMMANDE
          │
          ├── RETRAIT_BOUTIQUE
          └── LIVRAISON_DOMICILE ──> ADRESSE

CONTENANT ──< LIGNE_COMMANDE (contenants choisis)
```

### Séquences

| Séquence | Usage |
|----------|-------|
| `SEQ_COMMANDE` | ID des commandes |
| `SEQ_RETRAIT_BOUTIQUE` | ID des retraits |
| `SEQ_LIVRAISON_DOMICILE` | ID des livraisons |

## Gestion des transactions

`autoCommit` est désactivé dès la connexion (`Database` constructeur). Toutes les modifications passent par la couche `Service` qui commit en cas de succès ou rollback en cas d'erreur :

```java
try {
    dao.creerCommande(...);
    dao.ajouterLigneCommande(...);
    dao.updateStockLot(...);       // FOR UPDATE sur LOT_PRODUIT
    db.commit();
} catch (Exception e) {
    db.rollback();
    throw new Exception("Échec : " + e.getMessage());
}
```

Le déstockage suit la stratégie **FEFO** (First Expired, First Out) : les lots sont consommés dans l'ordre croissant de `DATEPEREMPTION`.

## Données de test

`ProjetBD.sql` inclut :
- 10 producteurs, 20 produits (vrac et préconditionné)
- 7 types de contenants
- 3 clients avec adresses à Grenoble
- 5 commandes dans différents états (pour tester F3)
- Des lots avec péremption proche (pour tester F2)

Le produit ID 16 (Asperges Vertes) est volontairement hors saison pour tester le rejet en F1.
Le produit ID 17 (Courgettes) est volontairement sans stock pour tester le rejet en F1.
