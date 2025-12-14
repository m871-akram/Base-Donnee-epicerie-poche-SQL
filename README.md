# ProjetBD - Épicerie "Le Bon Choix"

Système de gestion de commandes pour une épicerie locale avec gestion avancée des stocks, saisonnalité des produits, et livraison.



Application Java console qui permet de gérer les commandes d'une épicerie en intégrant :
- **Gestion des stocks** avec stratégie FEFO (First Expired, First Out)
- **Produits saisonniers** avec vérification automatique des périodes de disponibilité
- **Deux modes de récupération** : retrait en boutique ou livraison à domicile
- **Calcul dynamique des frais** de livraison selon la zone géographique
- **Gestion des contenants** réutilisables pour produits en vrac

##  Architecture

Architecture trois-tiers classique Java + Oracle Database :

```
Main.java
   ↓
Database.java ──→ Dao.java ──→ Service.java ──→ Menu.java
(JDBC)         (SQL)         (Logique)      (UI Console)
```

### Composants

| Fichier | Responsabilité |
|---------|---------------|
| **Main.java** | Point d'entrée, initialise les composants |
| **Database.java** | Gestion de la connexion JDBC et transactions (commit/rollback) |
| **Dao.java** | Exécution des requêtes SQL (aucune logique métier) |
| **Service.java** | Logique métier, orchestration, gestion transactionnelle |
| **Menu.java** | Interface console (client et épicier) |
| **Ligne.java** | Objet de données immutable pour ligne de commande |



### Prérequis

- **Java 17+** (utilise les text blocks `"""`)
- **Oracle Database** (testé avec Oracle 19c)
- **JDBC Driver** : `ojdbc17.jar` (déjà inclus dans `lib/`)

### Configuration de la base de données

1. **Créer le schéma Oracle** :
   ```sql
   sqlplus user/pswd@localhost:1521/oracle1
   @ProjetBD.sql
   ```

2. **Insérer les données de test** (optionnel) :
   ```sql
   -- Exécuter vos scripts d'insertion ici
   ```

3. **Vérifier les séquences** :
   ```sql
   SELECT sequence_name FROM user_sequences;
   -- Doit afficher: SEQ_COMMANDE, SEQ_LIVRAISON_DOMICILE, SEQ_RETRAIT_BOUTIQUE
   ```

### Modifier la connexion (si nécessaire)

Éditer [Main.java](src/Main.java#L10-L12) :
```java
Database db = new Database(
    "jdbc:oracle:thin:@localhost:1521:oracle1",
    "votre_username",
    "votre_password"
);
```

### Compilation et exécution

```bash
# Compiler tous les fichiers
javac -cp "lib/ojdbc17.jar:." src/*.java

# Lancer l'application
java -cp "lib/ojdbc17.jar:src" Main
```



### Interface principale

Au démarrage, vous choisissez votre rôle :

```
BIENVENUE à l'épicerie 'LE BON CHOIX'
1. Entrer en tant que client
2. Entrer en tant qu'épicier
0. Quitter le menu
```

### Mode Client

**Fonctionnalités disponibles** :
1. **Afficher le catalogue** : Liste tous les produits disponibles
2. **Passer une commande** :
   - Création/connexion compte client
   - Sélection des produits
   - Choix des contenants pour produits en vrac
   - Mode de récupération (retrait/livraison)
   - Calcul automatique du total et frais de livraison

### Mode Épicier

**Fonctionnalités disponibles** :
1. **Ajuster prix péremption** : Réduction automatique pour produits proches de la péremption
2. **Clôturer commande** : Finaliser une commande en préparation

##  Fonctionnalités principales

### 1. Passage de commande (Fonctionnalité 1)

**Workflow** :
```
Vérification client → Sélection produits → Validation stock/saison
    ↓
Création commande → Ajout lignes → Calcul frais livraison
    ↓
Déstockage FEFO → COMMIT (ou ROLLBACK si erreur)
```


-  Transaction ACID avec `setAutoCommit(false)`
-  Vérification saisonnalité via `PERIODE_DISPONIBILITE`
-  Déstockage FEFO avec `ORDER BY DATEPEREMPTION ASC FOR UPDATE`
-  Calcul des frais de livraison selon zone :
  - **Grenoble/Saint-Martin-d'Hères** : 5€ (frais de proximité)
  - **Lyon** : 10€ (frais de proximité + 5€ distance)
  - **France métropolitaine** : 15€ + 0,5€/kg
  - **DOM-TOM** : 35€ + 0,5€/kg
  - **International** : 50€ + 0,5€/kg

### 2. Ajustement prix péremption (Fonctionnalité 2)

Réduction automatique des prix pour produits avec dates de péremption proches :
- **< 7 jours** : -20%
- **< 3 jours** : -50%

### 3. Clôture de commande (Fonctionnalité 3)

Finalise les commandes "En préparation" et gère les contenants retournés.

##  Schéma de base de données

### Tables principales

| Table | Description |
|-------|-------------|
| `PRODUIT` | Catalogue des produits (lien vers producteur) |
| `VRAC` / `PRECOND` | Types de conditionnement (vrac/préconditionné) |
| `LOT_PRODUIT` | Lots de stock avec date de péremption et réception |
| `COMMANDE` | En-tête des commandes clients |
| `LIGNE_COMMANDE` | Détails des produits/contenants commandés |
| `LIVRAISON_DOMICILE` | Informations de livraison (frais, date estimée) |
| `RETRAIT_BOUTIQUE` | Informations de retrait en boutique |
| `CONTENANT` | Contenants réutilisables (bocaux, sacs, etc.) |
| `PERIODE_DISPONIBILITE` | Périodes de disponibilité saisonnière |

### Séquences Oracle

```sql
SEQ_COMMANDE              -- Génération des ID de commande
SEQ_LIVRAISON_DOMICILE    -- Génération des ID de livraison
SEQ_RETRAIT_BOUTIQUE      -- Génération des ID de retrait
```

##  Gestion des transactions

**Principe ACID strict** :

```java
// Service.passerCommande() - Pattern canonique
try {
    // 1. Validation métier
    if (stockTotal < quantite) throw new Exception("Stock insuffisant");
    
    // 2. Opérations en base
    dao.creerCommande(...);
    dao.ajouterLigneCommande(...);
    dao.updateStockLot(...);
    
    // 3. Commit si tout OK
    db.commit();
    
} catch (Exception e) {
    // 4. Rollback en cas d'erreur
    db.rollback();
    throw new Exception("Échec : " + e.getMessage());
}
```

**Règles** :
-  `setAutoCommit(false)` activé dès la connexion
-  Commit/rollback **uniquement** dans la couche Service
-  `FOR UPDATE` sur les lectures de stock pour éviter les conditions de course



## Ajout d'une  fonctionnalité

1. **Dao.java** : Ajouter la méthode SQL avec `PreparedStatement`
   ```java
   public ResultType maRequete(params) throws SQLException {
       String sql = """
           SELECT ... FROM ... WHERE ... = ?
       """;
       try (PreparedStatement st = db.prepare(sql)) {
           st.setInt(1, param);
           // ...
       }
   }
   ```

2. **Service.java** : Ajouter la logique métier avec gestion transactionnelle
   ```java
   public ResultType maFonction(params) throws Exception {
       try {
           // Logique + appels DAO
           db.commit();
           return result;
       } catch (Exception e) {
           db.rollback();
           throw new Exception("Erreur : " + e.getMessage());
       }
   }
   ```

3. **Menu.java** : Ajouter l'option dans le menu console
   ```java
   case X -> maFonction();
   ```

