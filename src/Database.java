import java.sql.*;

public class Database {

    private final Connection con;

    public Database(String url, String user, String pass) throws SQLException {
        con = DriverManager.getConnection(url, user, pass);
        con.setAutoCommit(false); // transactions gérées manuellement dans Service
    }

    public PreparedStatement prepare(String sql) throws SQLException {
        return con.prepareStatement(sql);
    }

    // à utiliser quand on veut récupérer des colonnes générées (ex: IDCOMMANDE après INSERT)
    public PreparedStatement prepare(String sql, String[] columnNames) throws SQLException {
        return con.prepareStatement(sql, columnNames);
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
}
