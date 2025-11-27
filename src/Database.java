import java.sql.*;

/**
 * Gestionnaire de la connexion JDBC à Oracle.
 * Encapsule la connexion et fournit des méthodes pour gérer les transactions.
 */
public class Database {

    // La connexion JDBC vers Oracle (unique pour toute l'application)
    private final Connection con;

    /**
     * Constructeur : établit la connexion à Oracle et désactive l'AutoCommit.
     */
    public Database(String url, String user, String pass) throws SQLException {
        con = DriverManager.getConnection(url, user, pass);
        con.setAutoCommit(false); // Important : gestion manuelle des transactions
    }

    // ----------------------------------------------------------------------
    // SURCHARGE EXISTANTE (Utilise l'indicateur standard)
    public PreparedStatement prepare(String sql) throws SQLException {
        return con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    // ----------------------------------------------------------------------
    /**
     * NOUVELLE SURCHARGE : Crée un PreparedStatement spécifiant les noms des colonnes à retourner.
     * C'est la méthode recommandée pour Oracle lors de l'utilisation de séquences
     * afin de récupérer l'ID généré.
     */
    public PreparedStatement prepare(String sql, String[] columnNames) throws SQLException {
        return con.prepareStatement(sql, columnNames);
    }
    // ----------------------------------------------------------------------


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
     * Accesseur pour récupérer la connexion brute (utilisé pour les Statements simples dans Dao.java).
     */
    public Connection getConnection() {
        return con;
    }
}