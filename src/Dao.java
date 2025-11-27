import java.sql.*;
import java.util.*;

/**
 * Data Access Object (DAO) : couche d'accès aux données.
 * Contient toutes les requêtes SQL pour dialoguer avec Oracle.
 * Cette classe N'A PAS la responsabilité de gérer les transactions (commit/rollback).
 */
public class Dao {

    // Référence vers Database pour exécuter les requêtes SQL
    private final Database db;

    /**
     * Constructeur : reçoit la Database par injection de dépendance.
     */
    public Dao(Database db) {
        this.db = db;
    }

    /**
     * Classe interne pour encapsuler les informations des lots nécessaires au déstockage FIFO/FEFO.
     */
    public static class LotInfo {
        public final int idLot;
        public final double stockActuel;
        
        public LotInfo(int idLot, double stockActuel) {
            this.idLot = idLot;
            this.stockActuel = stockActuel;
        }
    }

    // =============================================================
    // FONCTIONNALITÉ F1 : CATALOGUE & CRÉATION COMMANDE
    // =============================================================

    /**
     * Récupère tous les produits de la base, triés par nom.
     * Retourne une liste de chaînes formatées (ex : "1 - Pomme de terre (Légume) - VRAC").
     */
    public List<String> getCatalogue() throws SQLException {
        List<String> out = new ArrayList<>();

        String sql = """
            SELECT idproduit, nomproduit, categorieproduit, modecond
            FROM produit
            ORDER BY nomproduit
        """;

        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(
                    rs.getInt(1) + " - " +
                    rs.getString(2) + " (" + rs.getString(3) + ") - " + rs.getString(4)
                );
            }
        }
        return out;
    }

    // --- VÉRIFICATIONS F1 ---

    /**
     * Vérifie si le produit est en saison (sa période de disponibilité couvre la date actuelle).
     */
    public boolean estProduitEnSaison(int idProduit) throws SQLException {
        String sql = """
            SELECT COUNT(*) FROM PERIODE p
            JOIN PERIODE_DISPONIBILITE pd ON p.IDPERIODE = pd.IDPERIODE
            WHERE pd.IDPRODUIT = ? 
            AND SYSDATE BETWEEN p.DEBUTPERIODE AND p.FINPERIODE
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1) > 0;
                }
                return false;
            }
        }
    }

    /**
     * Récupère le stock total cumulé pour un produit (somme de tous les lots).
     */
    public double getStockTotalProduit(int idProduit) throws SQLException {
        String sql = "SELECT SUM(QUANTITESTOCKLOT) FROM LOT_PRODUIT WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    // Si le SUM est NULL (pas de lot), retourne 0.0
                    return rs.getDouble(1);
                }
                return 0.0;
            }
        }
    }

    /**
     * Récupère le prix de vente d'un produit en VRAC.
     * NOTE: On utilise le prix VRAC car le déstockage se fait sur les lots (VRAC).
     */
    public double getPrixVente(int idProduit) throws SQLException {
        String sql = "SELECT PRIXVENTEVRAC FROM VRAC WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getDouble(1);
                }
                throw new SQLException("Prix de vente VRAC non trouvé pour le produit ID " + idProduit);
            }
        }
    }

    // --- CRÉATION DE COMMANDE F1 ---
    
    /**
     * Crée une nouvelle commande dans la base de données.
     * ATTENTION : Utilise la séquence 'SEQ_COMMANDE' qui n'existe pas dans le script SQL initial.
     */
    public int creerCommande(int idClient, String modePaiement) throws SQLException { 
        // L'appel à SEQ_COMMANDE.NEXTVAL est la source de l'erreur ORA-02289 si la séquence n'est pas créée.
        String sql = """
            INSERT INTO COMMANDE (IDCOMMANDE, DATECOMMANDE, HEURECOMMANDE, STATUT, MODEPAIEMENT, IDCLIENT) 
            VALUES (SEQ_COMMANDE.NEXTVAL, SYSDATE, TO_CHAR(SYSDATE, 'HH24:MI:SS'), 'En preparation', ?, ?)
        """;
        
        try (PreparedStatement st = db.prepare(sql, new String[]{"IDCOMMANDE"})) { 
            st.setString(1, modePaiement); 
            st.setInt(2, idClient);
            st.executeUpdate();
            
            // Récupérer l'ID auto-généré
            try (ResultSet rs = st.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getInt(1); // Retourne le premier ID généré (IDCOMMANDE)
                }
                throw new SQLException("Échec de la récupération de l'ID de commande.");
            }
        }
    }

    /**
     * Ajoute une ligne à la commande.
     */
    public void ajouterLigneCommande(int idCommande, Ligne l, int idLigne, double prixUnitaire, double sousTotal) throws SQLException {
        String sql = """
            INSERT INTO LIGNE_COMMANDE (IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.setInt(2, idLigne);
            st.setInt(3, l.idProduit);
            st.setDouble(4, l.quantite);
            st.setString(5, l.unite);
            st.setDouble(6, prixUnitaire); // Prix unitaire calculé par le Service
            st.setDouble(7, sousTotal); // Sous-total calculé par le Service
            st.executeUpdate();
        }
    }

    /**
     * Enregistre le mode de récupération (Retrait ou Livraison).
     */
    public void enregistrerModeRecuperation(int idCommande, String mode) throws SQLException {

        if (mode.equals("Retrait")) {
            // IDRETRAITBOUTIQUE = IDCOMMANDE est une convention courante si l'ID est unique
            String sql = "INSERT INTO retrait_boutique(idretraitboutique, idcommande) VALUES (?, ?)";
            try (PreparedStatement st = db.prepare(sql)) {
                st.setInt(1, idCommande); 
                st.setInt(2, idCommande);
                st.executeUpdate();
            }
        } else {
            // Livraison : frais de 5€ hardcodés, IDLIVRAISONDOMICILE = IDCOMMANDE
            // NOTE: IDADRESSE (non-NULL dans le schéma) est omis ici pour la simplicité de l'exercice F1.
            String sql = "INSERT INTO livraison_domicile(idlivraisondomicile, idcommande, fraislivraison) VALUES (?, ?, 5)";
            try (PreparedStatement st = db.prepare(sql)) {
                st.setInt(1, idCommande);
                st.setInt(2, idCommande);
                st.executeUpdate();
            }
        }
    }

    // --- DÉSTOCKAGE F1/F3 ---

    /**
     * Récupère la liste des lots disponibles pour un produit, triés selon la règle FIFO/FEFO.
     * Priorité : 1. Date de Péremption (FEFO) ASC, 2. Date de Réception (FIFO).
     */
    public List<LotInfo> getStockLotsProduit(int idProduit) throws SQLException {
        List<LotInfo> lots = new ArrayList<>();
        // Tri FEFO (DATEPEREMPTION ASC) puis FIFO (DATERECEPTION ASC)
        String sql = """
            SELECT IDLOTPRODUIT, QUANTITESTOCKLOT
            FROM LOT_PRODUIT
            WHERE IDPRODUIT = ? AND QUANTITESTOCKLOT > 0
            ORDER BY DATEPEREMPTION ASC, DATERECEPTION ASC
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    lots.add(new LotInfo(rs.getInt(1), rs.getDouble(2)));
                }
            }
        }
        return lots;
    }

    /**
     * Déduit la quantité spécifiée du stock d'un lot donné.
     */
    public void updateStockLot(int idLot, double quantitePrise) throws SQLException {
        String sql = """
            UPDATE LOT_PRODUIT 
            SET QUANTITESTOCKLOT = QUANTITESTOCKLOT - ? 
            WHERE IDLOTPRODUIT = ?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setDouble(1, quantitePrise);
            st.setInt(2, idLot);
            st.executeUpdate();
        }
    }

    // =============================================================
    // FONCTIONNALITÉ F2 : ALERTES & AJUSTEMENT PRIX
    // =============================================================

    /**
     * Récupère les lots qui périment ou atteignent la DLUO dans les 7 jours.
     */
    public List<String> getAlertes() throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = """
            SELECT lp.IDLOTPRODUIT, p.NOMPRODUIT, lp.QUANTITESTOCKLOT, lp.UNITESTOCKLOT, lp.DATEPEREMPTION
            FROM LOT_PRODUIT lp
            JOIN PRODUIT p ON lp.IDPRODUIT = p.IDPRODUIT
            WHERE lp.DATEPEREMPTION BETWEEN SYSDATE AND SYSDATE + 7
            ORDER BY lp.DATEPEREMPTION ASC
        """;
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(
                    "Lot ID " + rs.getInt(1) + 
                    " - " + rs.getString(2) + 
                    " : " + rs.getDouble(3) + " " + rs.getString(4) +
                    " - Expire le " + rs.getDate(5)
                );
            }
        }
        return out;
    }

    /**
     * Récupère les ID des produits Vrac ayant des lots expirant dans les 7 jours.
     */
    public List<Integer> getProduitsAjuster() throws SQLException {
        List<Integer> out = new ArrayList<>();
        String sql = """
            SELECT DISTINCT IDPRODUIT
            FROM LOT_PRODUIT 
            WHERE DATEPEREMPTION BETWEEN SYSDATE AND SYSDATE + 7
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
     * Applique une réduction en pourcentage au PRIXVENTEVRAC du produit.
     */
    public void updatePrixVrac(int idProduit, int pourcentage) throws SQLException {
        String sql = """
            UPDATE VRAC 
            SET PRIXVENTEVRAC = PRIXVENTEVRAC * (1 - (? / 100.0)) 
            WHERE IDPRODUIT = ?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, pourcentage);
            st.setInt(2, idProduit);
            st.executeUpdate();
        }
    }

    // =============================================================
    // FONCTIONNALITÉ F3 : CLÔTURE & DÉSTOCKAGE (simplifié)
    // =============================================================

    /**
     * Verrouille la commande (SELECT FOR UPDATE) et récupère son statut actuel.
     * Utilisé pour la vérification du statut et la protection contre la concurrence.
     */
    public String getStatutEtVerrouillerCommande(int idCommande) throws SQLException {
        String statut = null;
        String sql = "SELECT STATUT FROM COMMANDE WHERE IDCOMMANDE = ? FOR UPDATE";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    statut = rs.getString(1);
                } else {
                    throw new SQLException("Commande ID " + idCommande + " non trouvée.");
                }
            }
        }
        return statut;
    }

    /**
     * Met à jour le statut d'une commande.
     */
    public void updateStatutCommande(int idCommande, String statut) throws SQLException {
        String sql = "UPDATE COMMANDE SET STATUT = ? WHERE IDCOMMANDE = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setString(1, statut);
            st.setInt(2, idCommande);
            st.executeUpdate();
        }
    }
}