# Copilot Instructions - ProjetBD (Épicerie "Le Bon Choix")

## Architecture Overview

Java console application for a grocery store ("épicerie") with Oracle database backend. Follows a **3-tier layered architecture**:

```
Main.java → Menu.java → Service.java → Dao.java → Database.java → Oracle DB
   ↓           ↓            ↓             ↓
 Bootstrap   User UI   Business Logic  SQL Queries   JDBC Connection
```

- **Menu**: Console UI with two user roles (Client / Épicier)
- **Service**: Transaction management (commit/rollback), business rules
- **Dao**: Raw SQL execution, no business logic
- **Database**: JDBC connection wrapper with manual transaction control

## Database Connection

Oracle database via JDBC (driver: `lib/ojdbc17.jar`):
```java
jdbc:oracle:thin:@oracle1.ensimag.fr:1521:oracle1
```
**AutoCommit is disabled** - all transactions require explicit `db.commit()` or `db.rollback()`.

## Key Functionalities (Fonctionnalités)

| Feature | Description | Key Methods |
|---------|-------------|-------------|
| **F1** | Order placement with stock management | `Service.passerCommande()`, FIFO destock from `LOT_PRODUIT` |
| **F2** | Expiration price adjustment | `Service.ajusterPrixPeremption()`, updates `VRAC.PRIXVENTEVRAC` |
| **F3** | Order closure with pessimistic locking | `Service.cloturerCommande()`, uses `SELECT...FOR UPDATE` |

## Database Schema Patterns

- **Products**: `PRODUIT` → specialized by `VRAC` (bulk) or `PRECOND` (pre-packaged)
- **Conditioning**: `CONDITIONNEMENT.TYPECOND` is either `'VRAC'` or `'PRE'`
- **Stock**: `LOT_PRODUIT` with expiration dates (`DATEPEREMPTION`), minus `RESERVATION_STOCK`
- **Orders**: `COMMANDE` → `LIGNE_COMMANDE` → recovery mode via `RETRAIT_BOUTIQUE` or `LIVRAISON_DOMICILE`
- **Containers**: Product ID `999` represents containers in `LIGNE_COMMANDE`

## Code Conventions

### SQL in Java
Use text blocks for multi-line SQL:
```java
String sql = """
    SELECT IDPRODUIT, NOMPRODUIT
    FROM PRODUIT
    WHERE CATEGORIEPRODUIT = ?
""";
```

### Transaction Pattern
Always wrap mutations in try-catch with rollback:
```java
try {
    dao.operation1();
    dao.operation2();
    db.commit();
} catch (Exception e) {
    db.rollback();
    throw new Exception("Context: " + e.getMessage());
}
```

### DAO Methods
- Return primitives/collections, throw `SQLException` on failure
- Use try-with-resources for `PreparedStatement` and `ResultSet`
- Generated keys: `db.prepare(sql, new String[]{"COLUMN_NAME"})`

## Delivery Logic (`Service.java`)

Shipping costs calculated in `calculerFraisLivraison()`:
- Base: France=5€, DOM-TOM=25€, International=40€
- Distance: Grenoble/SMH=0€, Lyon=5€, Other=10€
- Weight: +0.5€/kg

DOM-TOM detection via city names (CAYENNE, FORT-DE-FRANCE, PAPEETE, etc.)

## Running the Project

1. Ensure Oracle JDBC driver is in classpath: `lib/ojdbc17.jar`
2. Execute `ProjetBD.sql` to initialize schema and sequences
3. Compile: `javac -cp "lib/ojdbc17.jar" src/*.java`
4. Run: `java -cp "lib/ojdbc17.jar:src" Main`

## SQL Files Reference

| File | Purpose |
|------|---------|
| `ProjetBD.sql` | Schema creation, table definitions, sequences |
| `Fonctionnalite1.sql` | Order & client SQL templates |
| `Fonctionnalite2.sql` | Expiration alerts & price adjustment |
| `Fonctionnalite3.sql` | Order closure with locking |
