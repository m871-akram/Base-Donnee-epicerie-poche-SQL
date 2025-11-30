import java.sql.*;
import java.util.*;

/**
 * DAO C'est pour l'acces aux donnees.
 * Il execute les requetes SQL, pas de logique metier.
 */
public class Dao {
    private final Database db;
    public Dao(Database db) {
        this.db = db;
    }
    
    //Structure lot
    public static class LotInfo {
        public final int idLot;
        public final double stockActuel;

        public LotInfo(int idLot, double stockActuel) {
            this.idLot = idLot;
            this.stockActuel = stockActuel;
        }
    }

    // Client 
    public boolean clientExiste(int idClient) throws SQLException {
        String sql = "SELECT COUNT(*) FROM CLIENT WHERE IDCLIENT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idClient);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }

    //On cree le client si il veut 
    public void creerClient(int idClient, boolean anonyme) throws SQLException {
        String sql = "INSERT INTO CLIENT (IDCLIENT, ANONYME) VALUES (?, ?)";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idClient);
            st.setInt(2, anonyme ? 1 : 0);
            st.executeUpdate();
        }
    }

    public void creerInfosClient(int idClient, String nom, String prenom,
                                 String email, String tel) throws SQLException {
        String sql = """
            INSERT INTO INFORMATION_CLIENT
            (EMAILCLIENT, NOMCLIENT, PRENOMCLIENT, NUMTELCLIENT, IDCLIENT)
            VALUES (?, ?, ?, ?, ?)
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setString(1, email);
            st.setString(2, nom);
            st.setString(3, prenom);
            st.setString(4, tel);
            st.setInt(5, idClient);
            st.executeUpdate();
        }
    }

    // Insertion des coordonnees 
    public void creerAdresse(int idAdresse, int idClient,
                             String rue, String ville, String pays) throws SQLException {
        String sql = """
            INSERT INTO ADRESSE (IDADRESSE, ADRESSEPOSTALE, VILLE, RUE, PAYS, IDCLIENT)
            VALUES (?, ?, ?, ?, ?, ?)
        """;
        String adressePostale = rue + ", " + ville + ", " + pays;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idAdresse);
            st.setString(2, adressePostale);
            st.setString(3, ville);
            st.setString(4, rue);
            st.setString(5, pays);
            st.setInt(6, idClient);
            st.executeUpdate();
        }
    }

    public List<Integer> getAdressesClient(int idClient) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        String sql = "SELECT IDADRESSE FROM ADRESSE WHERE IDCLIENT=?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idClient);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) ids.add(rs.getInt(1));
            }
        }
        return ids;
    }


    // Conditionnement
    public String getTypeCond(int idProduit) throws SQLException {
        String sql = "SELECT TYPECOND FROM CONDITIONNEMENT WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString(1).toUpperCase();
                throw new SQLException("Aucun conditionnement pour produit " + idProduit);
            }
        }
    }


    // Cataalogue
    public List<String> getCatalogue() throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = """
            SELECT IDPRODUIT, NOMPRODUIT, CATEGORIEPRODUIT
            FROM PRODUIT
            ORDER BY NOMPRODUIT
        """;
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getInt(1) + " - " + rs.getString(2)
                        + " (" + rs.getString(3) + ")");
            }
        }
        return out;
    }

    //Saisonalite
    public boolean estProduitEnSaison(int idProduit) throws SQLException {
        String sql = """
            SELECT COUNT(*)
            FROM PERIODE p
            JOIN PERIODE_DISPONIBILITE pd ON p.IDPERIODE = pd.IDPERIODE
            WHERE pd.IDPRODUIT = ?
            AND SYSDATE BETWEEN p.DEBUTPERIODE AND p.FINPERIODE
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1) > 0;
            }
        }
    }
    // PRIX / POIDS
    public double getPrixVente(int idProduit) throws SQLException {
        String sqlV = "SELECT PRIXVENTEVRAC FROM VRAC WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sqlV)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        }
        String sqlP = "SELECT PRIXVENTEP FROM PRECOND WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sqlP)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
            }
        }
        throw new SQLException("Pas de prix pour le produit " + idProduit);
    }
    
    /**
     * Recupere le poids fixe d'un produit preconditionne pour le calcul du poids total.
     */
    public Double getPoidsFixe(int idProduit) throws SQLException {
        String sql = "SELECT POIDSFIXE FROM PRECOND WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) {
                    double poids = rs.getDouble(1);
                    return rs.wasNull() ? null : poids;
                }
            }
        }
        return null;
    }


    // Les commandes
    public int creerCommande(int idClient, String modePaiement) throws SQLException {
        String sql = """
            INSERT INTO COMMANDE
            (IDCOMMANDE, DATECOMMANDE, HEURECOMMANDE, STATUT, MODEPAIEMENT, IDCLIENT)
            VALUES (SEQ_COMMANDE.NEXTVAL, SYSDATE, TO_CHAR(SYSDATE,'HH24:MI:SS'),
                    'En preparation', ?, ?)
        """;
        try (PreparedStatement st = db.prepare(sql, new String[]{"IDCOMMANDE"})) {
            st.setString(1, modePaiement);
            st.setInt(2, idClient);
            st.executeUpdate();
            try (ResultSet rs = st.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
                throw new SQLException("Impossible de recuperer le IDCOMMANDE.");
            }
        }
    }

    public void ajouterLigneCommande(int idCommande, Ligne l, int num,
                                     double prixUnitaire, double sousTotal) throws SQLException {
        String sql = """
            INSERT INTO LIGNE_COMMANDE
            (IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT,
             QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.setInt(2, num);
            st.setInt(3, l.idProduit);
            st.setDouble(4, l.quantite);
            st.setString(5, l.unite);
            st.setDouble(6, prixUnitaire);
            st.setDouble(7, sousTotal);
            st.executeUpdate();
        }
    }
    
    /**
     * n recupere les lignes de commande pour un ID de commande donne.
     */
    public List<Ligne> getLignesCommande(int idCommande) throws SQLException {
        List<Ligne> lignes = new ArrayList<>();
        String sql = """
            SELECT IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE
            FROM LIGNE_COMMANDE
            WHERE IDCOMMANDE = ? AND IDPRODUIT IS NOT NULL
        """;
        String sqlFinal = """
            SELECT IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE
            FROM LIGNE_COMMANDE
            WHERE IDCOMMANDE = ? AND IDPRODUIT <> 999
        """;
        try (PreparedStatement st = db.prepare(sqlFinal)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    lignes.add(new Ligne(rs.getInt(1), rs.getDouble(2), rs.getString(3)));
                }
            }
        }
        return lignes;
    }

    // Mode de recuperation
    public void enregistrerModeRecuperation(int idCommande, String mode) throws SQLException {
        if (mode.equalsIgnoreCase("Retrait")) {
            String sql = "INSERT INTO RETRAIT_BOUTIQUE(idretraitboutique,idcommande) VALUES (?,?)";
            try (PreparedStatement st = db.prepare(sql)) {
                st.setInt(1, idCommande);
                st.setInt(2, idCommande);
                st.executeUpdate();
            }
        } else {
            String sql = """
                INSERT INTO LIVRAISON_DOMICILE(idlivraisondomicile,idcommande,fraislivraison,idadresse)
                VALUES (?, ?, 5, 1)
            """;
            try (PreparedStatement st = db.prepare(sql)) {
                st.setInt(1, idCommande);
                st.setInt(2, idCommande);
                st.executeUpdate();
            }
        }
    }

    // Les Contenants
    public List<String[]> getContenantsCompatibles(double quantite) throws SQLException {
        String sql = """
            SELECT IDCONTENANT, CAPACCONTENANT, TYPECONTENANT, STOCKDISPOCONTENANT
            FROM CONTENANT
            WHERE CAPACCONTENANT >= ? AND STOCKDISPOCONTENANT > 0
            ORDER BY CAPACCONTENANT
        """;
        PreparedStatement st = db.prepare(sql);
        st.setDouble(1, quantite);

        ResultSet rs = st.executeQuery();

        List<String[]> out = new ArrayList<>();
        while (rs.next()) {
            out.add(new String[]{
                String.valueOf(rs.getInt(1)),                 
                String.valueOf(rs.getDouble(2)),              
                rs.getString(3)                      
            });
        }
        return out;
    }
    
    /**
     * Ajoute un contenant à la commande.
     */
    public void ajouterContenantALaCommande(int idCommande, int idContenant, int numLigne) throws SQLException {
        String sql1 = """
            INSERT INTO LIGNE_COMMANDE
            (IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE)
            VALUES (?,?, 999,1,'Unite')
        """;
        try (PreparedStatement st = db.prepare(sql1)) {
            st.setInt(1, idCommande);
            st.setInt(2, numLigne); 
            st.executeUpdate();
        }
        // Getion du stock
        String sql2 = """
            UPDATE CONTENANT SET STOCKDISPOCONTENANT = STOCKDISPOCONTENANT - 1
            WHERE IDCONTENANT = ?
        """;
        try (PreparedStatement st2 = db.prepare(sql2)) {
            st2.setInt(1, idContenant);
            st2.executeUpdate();
        }
    }

    // STOCK 

    /**
     * On Recupere les lots disponibles pour un produit.
     * On Filtre les lots perimes et soustrait les quantites reservees (RESERVATION_STOCK).
     */
    public List<LotInfo> getStockLotsProduit(int idProduit) throws SQLException {
        List<LotInfo> lots = new ArrayList<>();
        String sql = """
                SELECT 
                    IDLOTPRODUIT,
                    QUANTITESTOCKLOT
                FROM LOT_PRODUIT
                WHERE IDPRODUIT = ?
                  AND DATEPEREMPTION > SYSDATE
                  AND QUANTITESTOCKLOT > 0
                ORDER BY DATEPEREMPTION ASC, DATERECEPTION ASC
                FOR UPDATE
            """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next())
                    lots.add(new LotInfo(rs.getInt(1), rs.getDouble(2)));
            }
        }
        return lots;
    }
    public void updateStockLot(int idLot, double quantitePrise) throws SQLException {
        String sql = """
              UPDATE LOT_PRODUIT
                SET QUANTITESTOCKLOT = QUANTITESTOCKLOT - ?
                WHERE IDLOTPRODUIT = ?
                  AND QUANTITESTOCKLOT >= ?
            """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setDouble(1, quantitePrise);
            st.setInt(2, idLot);
            st.setDouble(3, quantitePrise);
            int rows = st.executeUpdate();
            if (rows == 0) {
                throw new SQLException("Stock insuffisant sur le lot " + idLot);
            }
        }
    }

    // Les Alertes (fonctionnalite 2)
    public List<String> getAlertes() throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = """
            SELECT lp.IDLOTPRODUIT, p.NOMPRODUIT, lp.QUANTITESTOCKLOT, lp.DATEPEREMPTION
            FROM LOT_PRODUIT lp
            JOIN PRODUIT p ON lp.IDPRODUIT = p.IDPRODUIT
            WHERE lp.DATEPEREMPTION BETWEEN SYSDATE AND SYSDATE + 7
            ORDER BY lp.DATEPEREMPTION ASC
        """;
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                out.add("Lot " + rs.getInt(1) +
                        " - " + rs.getString(2) +
                        " : " + rs.getDouble(3) + " unites" +
                        " - expire le " + rs.getDate(4));
            }
        }
        return out;
    }

    public List<Integer> getProduitsAjuster() throws SQLException {
        List<Integer> out = new ArrayList<>();
        String sql = """
            SELECT DISTINCT IDPRODUIT
            FROM LOT_PRODUIT
            WHERE DATEPEREMPTION BETWEEN SYSDATE AND SYSDATE + 7
        """;
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next())
                out.add(rs.getInt(1));
        }
        return out;
    }
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

    public void updatePrixPrecond(int idProduit, int pourcentage) throws SQLException {
     String sql = """
         UPDATE PRECOND
         SET PRIXVENTEP = PRIXVENTEP * (1 - (? / 100.0))
         WHERE IDPRODUIT = ?
     """;
     try (PreparedStatement st = db.prepare(sql)) {
         st.setInt(1, pourcentage);
         st.setInt(2, idProduit);
         st.executeUpdate();
     }
    }


    // La cloture ( fonctionnalite3)
    public String getStatutEtVerrouillerCommande(int idCommande) throws SQLException {
        String sql = "SELECT STATUT FROM COMMANDE WHERE IDCOMMANDE=? FOR UPDATE";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                throw new SQLException("Commande " + idCommande + " introuvable.");
            }
        }
    }


    // Paiement
    public String[] getModeRecupAndPaiement(int idCommande) throws SQLException {
        String paiement = null;
        String sql1 = "SELECT MODEPAIEMENT FROM COMMANDE WHERE IDCOMMANDE=?";
        try (PreparedStatement st = db.prepare(sql1)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) paiement = rs.getString(1);
            }
        }
        if (paiement == null)
            throw new SQLException("Choisissez soit Retrait soit Livraison pour commande " + idCommande);
        String recup = null;
        String sqlR = "SELECT 1 FROM RETRAIT_BOUTIQUE WHERE IDCOMMANDE=?";
        try (PreparedStatement st = db.prepare(sqlR)) {
            st.setInt(1, idCommande);
            if (st.executeQuery().next()) recup = "Retrait";
        }
        if (recup == null) {
            String sqlL = "SELECT 1 FROM LIVRAISON_DOMICILE WHERE IDCOMMANDE=?";
            try (PreparedStatement st = db.prepare(sqlL)) {
                st.setInt(1, idCommande);
                if (st.executeQuery().next()) recup = "Livraison";
            }
        }
        if (recup == null)
            throw new SQLException("Mode récupération introuvable.");

        return new String[]{recup, paiement};
    }

    public void enregistrerDateRecup(int idCommande, String mode) throws SQLException {
        String sql = mode.equalsIgnoreCase("Retrait")
                ? "UPDATE RETRAIT_BOUTIQUE SET DATEREELLE=SYSDATE WHERE IDCOMMANDE=?"
                : "UPDATE LIVRAISON_DOMICILE SET DATEREELLE=SYSDATE WHERE IDCOMMANDE=?";

        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            if (st.executeUpdate() == 0)
                throw new SQLException("Aucune date mise à jour.");
        }
    }

    public void updateStatutCommande(int idCommande, String statut) throws SQLException {
        String sql = "UPDATE COMMANDE SET STATUT=? WHERE IDCOMMANDE=?";

        try (PreparedStatement st = db.prepare(sql)) {
            st.setString(1, statut);
            st.setInt(2, idCommande);
            st.executeUpdate();
        }
    }

    // ADRESSES / LIVRAISON
    public String getPaysLivraison(int idCommande) throws SQLException {
        String sql = """
            SELECT A.PAYS
            FROM LIVRAISON_DOMICILE L
            JOIN ADRESSE A ON L.IDADRESSE = A.IDADRESSE
            WHERE L.IDCOMMANDE=?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        throw new SQLException("Pays introuvable.");
    }

    public String getVilleLivraison(int idCommande) throws SQLException {
        String sql = """
            SELECT A.VILLE
            FROM LIVRAISON_DOMICILE L
            JOIN ADRESSE A ON L.IDADRESSE = A.IDADRESSE
            WHERE L.IDCOMMANDE=?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        throw new SQLException("Ville introuvable.");
    }

    public void updateFraisEtDateLivraison(int idCommande,
                                           double frais,
                                           java.util.Date dateEstimee) throws SQLException {
        String sql = """
            UPDATE LIVRAISON_DOMICILE
            SET FRAISLIVRAISON=?, DATELIVRAISONESTIMEE=?
            WHERE IDCOMMANDE=?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setDouble(1, frais);
            st.setDate(2, new java.sql.Date(dateEstimee.getTime()));
            st.setInt(3, idCommande);
            st.executeUpdate();
        }
    }

    // STOCK TOTAL
    /**
     * Recupere le stock total disponible d'un produit.
     * Le stock physique est soustrait des reservations et les lots perimes sont exclus.
     */
    public double getStockTotalProduit(int idProduit) throws SQLException {
        String sql = """
            SELECT
                COALESCE(SUM(QUANTITESTOCKLOT), 0)
            FROM
                LOT_PRODUIT
            WHERE
                IDPRODUIT = ?
                AND DATEPEREMPTION > SYSDATE
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getDouble(1) : 0.0;
            }
        }
    }
}