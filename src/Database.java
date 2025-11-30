import java.sql.*;

/**
 * Gestionnaire de la connexion JDBC a Oracle.
 * Encapsule la connexion et fournit des methodes pour gerer les transactions.
 */
public class Database {

    // La connexion JDBC vers Oracle 
    private final Connection con;

    // Constructeur : etablit la connexion à Oracle et desactive l'AutoCommit.
    public Database(String url, String user, String pass) throws SQLException {
        con = DriverManager.getConnection(url, user, pass);
        con.setAutoCommit(false); // gestion manuelle des transactions
    }

    // SURCHARGE EXISTANTE (Utilise l'indicateur standard)
    public PreparedStatement prepare(String sql) throws SQLException {
        return con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    // ----------------------------------------------------------------------
    /**
     * NOUVELLE SURCHARGE : Crée un PreparedStatement spécifiant les noms des colonnes à retourner.
     */
    public PreparedStatement prepare(String sql, String[] columnNames) throws SQLException {
        return con.prepareStatement(sql, columnNames);
    }
    // ----------------------------------------------------------------------

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        if (this.con != null) {
            this.con.setAutoCommit(autoCommit);
        }
    }
    public void commit() throws SQLException {
        con.commit();
    }

    public void rollback() {
        try { con.rollback(); } catch (Exception ignored) {}
    }

    public void close() {
        try { con.close(); } catch (Exception ignored) {}
    }

    /**
     * Accesseur pour recuperer la connexion brute.
     */
    public Connection getConnection() {
        return con;
    }
}