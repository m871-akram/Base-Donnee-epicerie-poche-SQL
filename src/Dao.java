import java.sql.*;
import java.util.*;

public class Dao {
    private final Database db;

    public Dao(Database db) {
        this.db = db;
    }

    public static class LotInfo {
        public final int idLot;
        public final double stockActuel;

        public LotInfo(int idLot, double stockActuel) {
            this.idLot = idLot;
            this.stockActuel = stockActuel;
        }
    }

    // --- Client ---

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

    public Integer getClientByEmail(String email) throws SQLException {
        String sql = "SELECT IDCLIENT FROM INFORMATION_CLIENT WHERE EMAILCLIENT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setString(1, email);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

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

    public int nextIdAdresse() throws SQLException {
        String sql = "SELECT NVL(MAX(IDADRESSE), 0) + 1 FROM ADRESSE";
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // --- Catalogue & Conditionnement ---

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

    public List<String> getCatalogue() throws SQLException {
        List<String> out = new ArrayList<>();
        String sql = """
            SELECT p.IDPRODUIT, p.NOMPRODUIT, p.CATEGORIEPRODUIT,
                   NVL(SUM(lp.QUANTITESTOCKLOT), 0) AS STOCK
            FROM PRODUIT p
            LEFT JOIN LOT_PRODUIT lp ON p.IDPRODUIT = lp.IDPRODUIT AND lp.DATEPEREMPTION > SYSDATE
            WHERE p.IDPRODUIT <> 999
            GROUP BY p.IDPRODUIT, p.NOMPRODUIT, p.CATEGORIEPRODUIT
            ORDER BY p.NOMPRODUIT
        """;
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            while (rs.next()) {
                double stock = rs.getDouble(4);
                String dispo = stock > 0 ? String.format("%.1f", stock) : "rupture";
                out.add(rs.getInt(1) + " - " + rs.getString(2)
                        + " (" + rs.getString(3) + ") [stock: " + dispo + "]");
            }
        }
        return out;
    }

    public boolean estProduitEnSaison(int idProduit) throws SQLException {
        // produit sans période définie = disponible toute l'année
        String sqlCount = "SELECT COUNT(*) FROM PERIODE_DISPONIBILITE WHERE IDPRODUIT = ?";
        try (PreparedStatement st = db.prepare(sqlCount)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                if (rs.getInt(1) == 0) return true;
            }
        }
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

    public double getPrixContenant(int idContenant) throws SQLException {
        String sql = "SELECT PRIXVENTECONTENANT FROM CONTENANT WHERE IDCONTENANT = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idContenant);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getDouble(1);
                throw new SQLException("Contenant " + idContenant + " introuvable.");
            }
        }
    }

    // --- Prix & Stock ---

    // cherche d'abord dans VRAC, puis dans PRECOND si le produit n'est pas en vrac
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

    // --- Commande ---

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

    public List<Ligne> getLignesCommande(int idCommande) throws SQLException {
        List<Ligne> lignes = new ArrayList<>();
        String sql = """
            SELECT IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE
            FROM LIGNE_COMMANDE
            WHERE IDCOMMANDE = ? AND IDPRODUIT <> 999
        """; // 999 = ligne technique contenant, exclue du calcul poids
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    lignes.add(new Ligne(rs.getInt(1), rs.getDouble(2), rs.getString(3)));
                }
            }
        }
        return lignes;
    }

    public void enregistrerRetrait(int idCommande) throws SQLException {
        String sql = "INSERT INTO RETRAIT_BOUTIQUE(idretraitboutique,idcommande) VALUES (SEQ_RETRAIT_BOUTIQUE.NEXTVAL,?)";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.executeUpdate();
        }
    }

    public void enregistrerLivraison(int idCommande, int idAdresse) throws SQLException {
        String sql = """
            INSERT INTO LIVRAISON_DOMICILE(idlivraisondomicile,idcommande,fraislivraison,idadresse)
            VALUES (SEQ_LIVRAISON_DOMICILE.NEXTVAL, ?, 0, ?)
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.setInt(2, idAdresse);
            st.executeUpdate();
        }
    }

    public List<String[]> getContenantsCompatibles(double quantite) throws SQLException {
        String sql = """
            SELECT IDCONTENANT, CAPACCONTENANT, TYPECONTENANT, STOCKDISPOCONTENANT
            FROM CONTENANT
            WHERE CAPACCONTENANT >= ? AND STOCKDISPOCONTENANT > 0
            ORDER BY CAPACCONTENANT
        """;
        List<String[]> out = new ArrayList<>();
        try (PreparedStatement st = db.prepare(sql)) {
            st.setDouble(1, quantite);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(new String[]{
                        String.valueOf(rs.getInt(1)),
                        String.valueOf(rs.getDouble(2)),
                        rs.getString(3)
                    });
                }
            }
        }
        return out;
    }

    public void ajouterContenantALaCommande(int idCommande, int idContenant, int numLigne, double prix) throws SQLException {
        // insère une ligne technique (produit 999) pour tracer le contenant dans la commande
        String sqlInsert = """
            INSERT INTO LIGNE_COMMANDE
            (IDCOMMANDE, IDLIGNECOMMANDE, IDPRODUIT, QUANTITECOMMANDE, UNITECOMMANDE, PRIXUNITAIRE, SOUSTOTAL)
            VALUES (?,?, 999,1,'Unite',?,?)
        """;
        try (PreparedStatement st = db.prepare(sqlInsert)) {
            st.setInt(1, idCommande);
            st.setInt(2, numLigne);
            st.setDouble(3, prix);
            st.setDouble(4, prix);
            st.executeUpdate();
        }
        String sqlStock = """
            UPDATE CONTENANT SET STOCKDISPOCONTENANT = STOCKDISPOCONTENANT - 1
            WHERE IDCONTENANT = ?
        """;
        try (PreparedStatement st = db.prepare(sqlStock)) {
            st.setInt(1, idContenant);
            st.executeUpdate();
        }
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
            if (st.executeUpdate() == 0)
                throw new SQLException("Stock insuffisant sur le lot " + idLot);
        }
    }

    // verrouille la ligne PRODUIT pour sérialiser les F1 concurrents sur ce produit
    public void verrouillerProduit(int idProduit) throws SQLException {
        String sql = "SELECT IDPRODUIT FROM PRODUIT WHERE IDPRODUIT = ? FOR UPDATE";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {}
        }
    }

    // stock disponible = stock total - quantités déjà réservées par des commandes "En preparation"
    public double getStockDisponible(int idProduit) throws SQLException {
        String sqlStock = """
            SELECT NVL(SUM(QUANTITESTOCKLOT), 0)
            FROM LOT_PRODUIT
            WHERE IDPRODUIT = ? AND DATEPEREMPTION > SYSDATE
        """;
        double totalStock = 0;
        try (PreparedStatement st = db.prepare(sqlStock)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) totalStock = rs.getDouble(1);
            }
        }
        String sqlReserved = """
            SELECT NVL(SUM(rs.QUANTITE), 0)
            FROM RESERVATION_STOCK rs
            JOIN LOT_PRODUIT lp ON rs.IDLOTPRODUIT = lp.IDLOTPRODUIT
            WHERE lp.IDPRODUIT = ? AND lp.DATEPEREMPTION > SYSDATE
        """;
        double reserved = 0;
        try (PreparedStatement st = db.prepare(sqlReserved)) {
            st.setInt(1, idProduit);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) reserved = rs.getDouble(1);
            }
        }
        return totalStock - reserved;
    }

    // lots disponibles en FEFO : stockActuel = stock du lot - ce qui est déjà réservé sur ce lot
    public List<LotInfo> getLotsDisponiblesFEFO(int idProduit) throws SQLException {
        List<LotInfo> lots = new ArrayList<>();
        String sql = """
            SELECT lp.IDLOTPRODUIT,
                   lp.QUANTITESTOCKLOT - NVL(SUM(rs.QUANTITE), 0) AS DISPONIBLE
            FROM LOT_PRODUIT lp
            LEFT JOIN RESERVATION_STOCK rs ON lp.IDLOTPRODUIT = rs.IDLOTPRODUIT
            WHERE lp.IDPRODUIT = ? AND lp.DATEPEREMPTION > SYSDATE
            GROUP BY lp.IDLOTPRODUIT, lp.QUANTITESTOCKLOT, lp.DATEPEREMPTION
            HAVING lp.QUANTITESTOCKLOT - NVL(SUM(rs.QUANTITE), 0) > 0
            ORDER BY lp.DATEPEREMPTION ASC
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

    public void reserverStockLot(int idCommande, int idLot, double quantite) throws SQLException {
        String sql = "INSERT INTO RESERVATION_STOCK (IDCOMMANDE, IDLOTPRODUIT, QUANTITE) VALUES (?, ?, ?)";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.setInt(2, idLot);
            st.setDouble(3, quantite);
            st.executeUpdate();
        }
    }

    // retourne les réservations d'une commande (idLot, quantiteReservee) en les verrouillant
    public List<LotInfo> getReservationsCommande(int idCommande) throws SQLException {
        List<LotInfo> out = new ArrayList<>();
        String sql = "SELECT IDLOTPRODUIT, QUANTITE FROM RESERVATION_STOCK WHERE IDCOMMANDE = ? FOR UPDATE";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next())
                    out.add(new LotInfo(rs.getInt(1), rs.getDouble(2)));
            }
        }
        return out;
    }

    public void effacerReservationsCommande(int idCommande) throws SQLException {
        String sql = "DELETE FROM RESERVATION_STOCK WHERE IDCOMMANDE = ?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            st.executeUpdate();
        }
    }

    // --- Pertes ---

    public int nextIdPerte() throws SQLException {
        String sql = "SELECT NVL(MAX(IDPERTE), 0) + 1 FROM PERTE";
        try (PreparedStatement st = db.prepare(sql);
             ResultSet rs = st.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    public void creerPerte(int idPerte, String nature, double quantite, String elem) throws SQLException {
        String sql = "INSERT INTO PERTE (IDPERTE, ELEMPERDU, NATUREPERTE, DATEPERTE, QUANTITEPERTE) VALUES (?,?,?,SYSDATE,?)";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idPerte);
            st.setString(2, elem);
            st.setString(3, nature);
            st.setDouble(4, quantite);
            st.executeUpdate();
        }
    }

    public void lierProduitPerte(int idPerte, int idProduit) throws SQLException {
        String sql = "INSERT INTO PRODUIT_PERDU (IDPRODUIT, IDPERTE) VALUES (?, ?)";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idProduit);
            st.setInt(2, idPerte);
            st.executeUpdate();
        }
    }

    public void lierContenantPerte(int idPerte, int idContenant) throws SQLException {
        String sql = "INSERT INTO CONTENANT_PERDU (IDCONTENANT, IDPERTE) VALUES (?, ?)";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idContenant);
            st.setInt(2, idPerte);
            st.executeUpdate();
        }
    }

    // --- Alertes & Ajustement prix (F2) ---

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

    public boolean updatePrixVrac(int idProduit, int pourcentage) throws SQLException {
        String sql = """
            UPDATE VRAC
            SET PRIXVENTEVRAC = PRIXVENTEVRAC * (1 - (? / 100.0))
            WHERE IDPRODUIT = ?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, pourcentage);
            st.setInt(2, idProduit);
            return st.executeUpdate() > 0;
        }
    }

    public boolean updatePrixPrecond(int idProduit, int pourcentage) throws SQLException {
        String sql = """
            UPDATE PRECOND
            SET PRIXVENTEP = PRIXVENTEP * (1 - (? / 100.0))
            WHERE IDPRODUIT = ?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, pourcentage);
            st.setInt(2, idProduit);
            return st.executeUpdate() > 0;
        }
    }

    // --- Clôture commande (F3) ---

    // FOR UPDATE : on verrouille la commande pour éviter une double clôture concurrente
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

    public String[] getModeRecupAndPaiement(int idCommande) throws SQLException {
        String sql = """
            SELECT c.MODEPAIEMENT,
                   CASE WHEN r.IDCOMMANDE IS NOT NULL THEN 'Retrait'
                        WHEN l.IDCOMMANDE IS NOT NULL THEN 'Livraison'
                        ELSE NULL END
            FROM COMMANDE c
            LEFT JOIN RETRAIT_BOUTIQUE r ON c.IDCOMMANDE = r.IDCOMMANDE
            LEFT JOIN LIVRAISON_DOMICILE l ON c.IDCOMMANDE = l.IDCOMMANDE
            WHERE c.IDCOMMANDE = ?
        """;
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) throw new SQLException("Commande " + idCommande + " introuvable.");
                String paiement = rs.getString(1);
                String recup = rs.getString(2);
                if (recup == null) throw new SQLException("Mode récupération introuvable.");
                return new String[]{recup, paiement};
            }
        }
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

    // --- Livraison ---

    private String getLivraisonChamp(int idCommande, String col) throws SQLException {
        String sql = "SELECT A." + col + " FROM LIVRAISON_DOMICILE L JOIN ADRESSE A ON L.IDADRESSE=A.IDADRESSE WHERE L.IDCOMMANDE=?";
        try (PreparedStatement st = db.prepare(sql)) {
            st.setInt(1, idCommande);
            try (ResultSet rs = st.executeQuery()) {
                if (rs.next()) return rs.getString(1);
            }
        }
        throw new SQLException(col + " introuvable.");
    }

    public String getPaysLivraison(int idCommande) throws SQLException { return getLivraisonChamp(idCommande, "PAYS"); }
    public String getVilleLivraison(int idCommande) throws SQLException { return getLivraisonChamp(idCommande, "VILLE"); }

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

}
