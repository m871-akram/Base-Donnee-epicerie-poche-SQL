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
     */
    public int creerCommande(int idClient, String modePaiement) throws SQLException { 
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
     * CORRECTION : Ajout de l'IDADRESSE = 1 dans LIVRAISON_DOMICILE pour les tests.
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
            // Livraison : Ajout de IDADRESSE = 1 pour permettre la jointure avec la table ADRESSE.
            String sql = "INSERT INTO livraison_domicile(idlivraisondomicile, idcommande, fraislivraison, idadresse) VALUES (?, ?, 5, 1)";
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
     * Récupère le mode de récupération ('Retrait' ou 'Livraison') et le mode de paiement.
     * @return Un tableau de String {modeRecuperation, modePaiement}.
     */
    public String[] getModeRecupAndPaiement(int idCommande) throws SQLException {
    
        // 1. Récupérer le mode de paiement depuis COMMANDE
        String modePaiement = null;
        String sqlPaiement = "SELECT MODEPAIEMENT FROM COMMANDE WHERE IDCOMMANDE = ?";
        try (PreparedStatement st = db.prepare(sqlPaiement)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    modePaiement = rs.getString(1);
                }
            }
        }
    
        if (modePaiement == null) {
            throw new SQLException("Commande ID " + idCommande + " non trouvée ou sans mode de paiement.");
        }

        // 2. Déterminer le mode de récupération (Retrait ou Livraison)
        String modeRecuperation = null;
    
        // Vérifier Retrait
        String sqlRetrait = "SELECT IDCOMMANDE FROM RETRAIT_BOUTIQUE WHERE IDCOMMANDE = ?";
        try (PreparedStatement st = db.prepare(sqlRetrait)) {
            st.setInt(1, idCommande);
            if (st.executeQuery().next()) {
                modeRecuperation = "Retrait";
            }
        }
    
        // Si ce n'est pas Retrait, vérifier Livraison
        if (modeRecuperation == null) {
            String sqlLivraison = "SELECT IDCOMMANDE FROM LIVRAISON_DOMICILE WHERE IDCOMMANDE = ?";
            try (PreparedStatement st = db.prepare(sqlLivraison)) {
                st.setInt(1, idCommande);
                if (st.executeQuery().next()) {
                    modeRecuperation = "Livraison";
                }
            }
        }

        if (modeRecuperation == null) {
             throw new SQLException("Mode de récupération inconnu pour la commande " + idCommande);
        }

        return new String[]{modeRecuperation, modePaiement};
    }
    
    /**
     * Enregistre la date effective de récupération/livraison dans la table de détail correspondante.
     */
    public void enregistrerDateRecup(int idCommande, String modeRecuperation) throws SQLException {
        String sqlDetail;

        if ("Retrait".equals(modeRecuperation)) {
            sqlDetail = "UPDATE RETRAIT_BOUTIQUE SET DATEREELLE = SYSDATE WHERE IDCOMMANDE = ?";
        } else if ("Livraison".equals(modeRecuperation)) {
            sqlDetail = "UPDATE LIVRAISON_DOMICILE SET DATEREELLE = SYSDATE WHERE IDCOMMANDE = ?";
        } else {
            throw new SQLException("Mode de récupération invalide: " + modeRecuperation);
        } 

        try (PreparedStatement st = db.prepare(sqlDetail)) {
            st.setInt(1, idCommande);
            int rows = st.executeUpdate();
            
            if (rows == 0) {
                throw new SQLException("Aucune ligne de détail mise à jour pour la commande ID " + idCommande);
            }
        }
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

    // =============================================================
    // MÉTHODES DAO POUR CALCUL LIVRAISON (F1)
    // =============================================================

    /**
     * Calcule le poids total (en Kg) d'une commande en sommant les quantités des lignes.
     */
    public double calculerPoidsTotalCommande(int idCommande) throws SQLException {
        String sql = """
            SELECT SUM(QUANTITECOMMANDE) 
            FROM LIGNE_COMMANDE 
            WHERE IDCOMMANDE = ? 
            AND UNITECOMMANDE = 'Kg'
            """; 

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                // Retourne 0 si la somme est NULL (pas de Kg)
                return rs.next() ? rs.getDouble(1) : 0.0; 
            }
        }
    }

    /**
     * Récupère le PAYS de livraison pour la commande spécifiée.
     */
    public String getPaysLivraison(int idCommande) throws SQLException {
        String sql = """
            SELECT A.PAYS 
            FROM LIVRAISON_DOMICILE LD
            JOIN ADRESSE A ON LD.IDADRESSE = A.IDADRESSE
            WHERE LD.IDCOMMANDE = ?
            """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("PAYS");
                }
            }
        }
        throw new SQLException("Impossible de trouver le pays : la commande " + idCommande + " n'est pas une livraison à domicile.");
    }

    /**
     * Récupère la ville associée à l'adresse de livraison d'une commande.
     */
    public String getVilleLivraison(int idCommande) throws SQLException {
        String sql = """
            SELECT A.VILLE 
            FROM LIVRAISON_DOMICILE LD
            JOIN ADRESSE A ON LD.IDADRESSE = A.IDADRESSE
            WHERE LD.IDCOMMANDE = ?
            """; 

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("VILLE").toUpperCase(); 
                } 
                return null;
            }
        }
    }

    /**
     * Met à jour les frais et la date de livraison estimée dans LIVRAISON_DOMICILE.
     */
    public void updateFraisEtDateLivraison(int idCommande, double fraisLivraison, java.util.Date dateEstimee) throws SQLException {
        String sql = """
            UPDATE LIVRAISON_DOMICILE 
            SET FRAISLIVRAISON = ?, DATELIVRAISONESTIMEE = ? 
            WHERE IDCOMMANDE = ?
            """;
        try (PreparedStatement st = db.prepare(sql)) {
            // 1. Les frais
            st.setDouble(1, fraisLivraison);
            // 2. La date estimée (conversion de java.util.Date à java.sql.Date)
            st.setDate(2, new java.sql.Date(dateEstimee.getTime())); 
            // 3. L'ID de la commande
            st.setInt(3, idCommande);
            
            st.executeUpdate();
        }
    }
}