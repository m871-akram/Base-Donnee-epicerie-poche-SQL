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
     * AutoCommit = false signifie qu'on doit appeler commit() manuellement pour valider les changements.
     * Cela permet de gérer des transactions (plusieurs INSERT/UPDATE atomiques).
     */
    public Database(String url, String user, String pass) throws SQLException {
        con = DriverManager.getConnection(url, user, pass);
        con.setAutoCommit(false); // Important : gestion manuelle des transactions
    }

    /**
     * Crée un PreparedStatement (requête SQL sécurisée avec paramètres).
     * RETURN_GENERATED_KEYS permet de récupérer les IDs auto-générés par Oracle.
     */
    public PreparedStatement prepare(String sql) throws SQLException {
        return con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
    }

    /**
     * Valide la transaction en cours (tous les INSERT/UPDATE sont enregistrés définitivement).
     */
    public void commit() throws SQLException {
        con.commit();
    }

    /**
     * Annule la transaction en cours (tous les INSERT/UPDATE sont annulés).
     * Utilisé en cas d'erreur pour garantir la cohérence des données.
     * Les exceptions sont ignorées (si le rollback échoue, on ne peut rien faire de plus).
     */
    public void rollback() {
        try { con.rollback(); } catch (Exception ignored) {}
    }

    /**
     * Ferme la connexion Oracle proprement.
     * Les exceptions sont ignorées (si la fermeture échoue, l'application se termine de toute façon).
     */
    public void close() {
        try { con.close(); } catch (Exception ignored) {}
    }

    /**
     * Accesseur pour récupérer la connexion brute (rarement utilisé).
     */
    public Connection getConnection() {
        return con;
    }
}