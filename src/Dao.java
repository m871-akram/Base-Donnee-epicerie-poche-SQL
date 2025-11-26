import java.sql.*;
import java.util.*;

/**
 * Data Access Object (DAO) : couche d'accès aux données.
 * Contient toutes les requêtes SQL pour dialoguer avec Oracle.
 * ⚠️ Cette classe N'A PAS la responsabilité de gérer les transactions (commit/rollback).
 */
public class Dao {

    // Référence vers Database pour exécuter les requêtes SQL
    private final Database db;

    /**
     * Classe interne pour représenter les informations d'un lot de stock.
     * Utilisée par le Service pour gérer le déstockage (F3).
     */
    public static class LotInfo {
        public final int idLot;
        public final double stockActuel;
        public final String unite;

        public LotInfo(int idLot, double stockActuel, String unite) {
            this.idLot = idLot;
            this.stockActuel = stockActuel;
            this.unite = unite;
        }
    }

    /**
     * Constructeur : reçoit la Database par injection de dépendance.
     */
    public Dao(Database db) {
        this.db = db;
    }

    // =============================================================
    // FONCTIONNALITÉ F1 : CATALOGUE
    // =============================================================

    /**
     * Récupère tous les produits de la base, triés par nom.
     * Retourne une liste de chaînes formatées (ex : "1 - Pomme (Fruits)").
     */
    public List<String> getCatalogue() throws SQLException {
        List<String> out = new ArrayList<>();

        String sql = """
            SELECT idproduit, nomproduit, categorieproduit
            FROM produit
            ORDER BY nomproduit
        """;

        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(
                        rs.getInt(1) + " - " +
                                rs.getString(2) + " (" + rs.getString(3) + ")"
                );
            }
        }
        return out;
    }

    // =============================================================
    // FONCTIONNALITÉ F2 : ALERTES & AJUSTEMENT PRIX
    // =============================================================

    /**
     * Récupère les lots de produits qui expirent dans les 7 prochains jours.
     */
    public List<String> getAlertes() throws SQLException {
        List<String> out = new ArrayList<>();

        String sql = """
            SELECT idproduit, idlotproduit, dateperemption
            FROM lot_produit
            WHERE dateperemption <= SYSDATE + 7
        """;

        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add("Produit " + rs.getInt(1)
                        + " (lot " + rs.getInt(2)
                        + ") expire le " + rs.getDate(3));
            }
        }
        return out;
    }

    /**
     * Récupère la liste des ID de produits qui ont au moins un lot
     * dont la date de péremption est dans les 7 prochains jours ou moins.
     * Utilisé par Service.ajusterPrixPeremption().
     */
    public List<Integer> getProduitsAjuster() throws SQLException {
        List<Integer> out = new ArrayList<>();

        String sql = """
            SELECT DISTINCT idproduit
            FROM lot_produit
            WHERE dateperemption <= SYSDATE + 7
        """;

        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getInt(1));
            }
        }
        return out;
    }

    /**
     * Applique une réduction en pourcentage sur le prix de vente Vrac d'un produit.
     */
    public void updatePrixVrac(int idProduit, double pourcentageReduction) throws SQLException {
        String sql = """
            UPDATE vrac
            SET prixventevrac = prixventevrac * (1 - ?)
            WHERE idproduit = ?
        """;

        try (PreparedStatement st = db.prepare(sql)) {
            // On convertit le pourcentage en fraction (ex: 30 -> 0.3)
            st.setDouble(1, pourcentageReduction / 100.0);
            st.setInt(2, idProduit);
            st.executeUpdate();
        }
    }


    // =============================================================
    // FONCTIONNALITÉ F1 : PASSER COMMANDE
    // =============================================================

    /**
     * Crée une nouvelle commande dans la table commande.
     * Retourne l'ID de la commande créée.
     */
    public int creerCommande(int idClient) throws SQLException {

        String sql = """
            INSERT INTO commande(idcommande, datecommande, heurecommande, statut, idclient)
            VALUES ( (SELECT NVL(MAX(idcommande),0)+1 FROM commande), SYSDATE, TO_CHAR(SYSDATE,'HH24:MI'), 'En préparation', ?)
        """;

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idClient);
            st.executeUpdate();
        }

        // Récupérer l'id après l'INSERT
        try (PreparedStatement st = db.prepare("SELECT MAX(idcommande) FROM commande");
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /**
     * Ajoute une ligne de commande (un produit) à une commande existante.
     */
    public void ajouterLigneCommande(int idCommande, Ligne l, int numLigne) throws SQLException {

        String sql = """
            INSERT INTO ligne_commande(idcommande, idlignecommande, idproduit, quantitecommande, unitecommande, modepaiement)
            VALUES (?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.setInt(2, numLigne);
            st.setInt(3, l.idProduit);
            st.setDouble(4, l.quantite);
            st.setString(5, l.unite);
            st.setString(6, l.modePaiement);
            st.executeUpdate();
        }
    }

    /**
     * Enregistre le mode de récupération de la commande.
     */
    public void enregistrerModeRecuperation(int idCommande, String mode) throws SQLException {

        if (mode.equals("Retrait")) {
            String sql = "INSERT INTO retrait_boutique(idretraitboutique, idcommande) VALUES (?, ?)";
            try (PreparedStatement st = db.prepare(sql)) {
                st.setInt(1, idCommande);
                st.setInt(2, idCommande);
                st.executeUpdate();
            }
        } else {
            // Si c'est "Livraison"
            String sql = "INSERT INTO livraison_domicile(idlivraisondomicile, idcommande, fraislivraison) VALUES (?, ?, 5)";
            try (PreparedStatement st = db.prepare(sql)) {
                st.setInt(1, idCommande);
                st.setInt(2, idCommande);
                st.executeUpdate();
            }
        }
    }

    // =============================================================
    // FONCTIONNALITÉ F3 : CLÔTURE & DÉSTOCKAGE
    // =============================================================

    /**
     * Récupère le statut de la commande et verrouille la ligne (SELECT... FOR UPDATE).
     * ESSENTIEL pour la transaction de clôture.
     */
    public String getStatutEtVerrouillerCommande(int idCommande) throws SQLException {
        String sql = """
            SELECT statut
            FROM commande
            WHERE idcommande = ?
            FOR UPDATE
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("statut");
                } else {
                    throw new SQLException("La commande ID " + idCommande + " n'existe pas.");
                }
            }
        }
    }

    /**
     * Récupère toutes les lignes (produits) d'une commande.
     * Attention : La classe Ligne doit être accessible au Dao.
     */
    public List<Ligne> getLignesCommande(int idCommande) throws SQLException {
        List<Ligne> out = new ArrayList<>();

        String sql = """
            SELECT idproduit, quantitecommande, unitecommande, modepaiement
            FROM ligne_commande
            WHERE idcommande = ?
        """;

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    // Création de l'objet Ligne
                    out.add(new Ligne(
                            rs.getInt("idproduit"),
                            rs.getDouble("quantitecommande"),
                            rs.getString("unitecommande"),
                            rs.getString("modepaiement")
                    ));
                }
            }
        }
        return out;
    }

    /**
     * Récupère tous les lots d'un produit spécifique, triés par date de péremption (FIFO/FEFO).
     */
    public List<LotInfo> getStockLotsProduit(int idProduit) throws SQLException {
        List<LotInfo> out = new ArrayList<>();

        // Trié par date de péremption la plus proche (ASC)
        String sql = """
            SELECT idlotproduit, quantitestocklot, unitestocklot
            FROM lot_produit
            WHERE idproduit = ? AND quantitestocklot > 0
            ORDER BY dateperemption ASC, datereception ASC
        """;

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new LotInfo(
                            rs.getInt("idlotproduit"),
                            rs.getDouble("quantitestocklot"),
                            rs.getString("unitestocklot")
                    ));
                }
            }
        }
        return out;
    }

    /**
     * Déduit la quantité spécifiée d'un lot de stock.
     */
    public void updateStockLot(int idLot, double quantiteADeduire) throws SQLException {
        String sql = """
            UPDATE lot_produit
            SET quantitestocklot = quantitestocklot - ?
            WHERE idlotproduit = ?
        """;

        try (PreparedStatement st = db.prepare(sql)) {
            st.setDouble(1, quantiteADeduire);
            st.setInt(2, idLot);
            st.executeUpdate();
        }
    }

    /**
     * Met à jour le statut de la commande.
     * Remplace la méthode cloturerCommande initiale.
     */
    public void updateStatutCommande(int idCommande, String statut) throws SQLException {
        try (PreparedStatement st =
                     db.prepare("UPDATE commande SET statut=? WHERE idcommande=?")) {
            st.setString(1, statut);
            st.setInt(2, idCommande);
            st.executeUpdate();
        }
    }
}