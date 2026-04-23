package none.gameengineoptimizer;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.Properties;
import java.io.FileInputStream;
import java.io.IOException;

public class DatabaseManager {
    private final String url = "jdbc:mysql://localhost:3306/";
    private final String dbName = "yahtzee2";
    private String user;
    private String password;
    private HikariDataSource dataSource;

    public DatabaseManager() {
        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream("dbconfig.properties")) {
            props.load(in);
            this.user = props.getProperty("db.user", "root");
            this.password = props.getProperty("db.password", "");
        } catch (IOException e) {
            this.user = "root";
            this.password = "";
        }
    } // constructor DatabaseManager ends here

    public void initDatabase() throws SQLException {
        // Initial connection to create DB if not exists
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            try (Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE DATABASE IF NOT EXISTS " + dbName);
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("Too many connections")) {
                throw new SQLException("MySQL Server is full. Please restart MySQL or wait for connections to timeout.", e);
            }
            throw e;
        }

        // Initialize HikariCP
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url + dbName);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(20);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(60000);
        config.setLeakDetectionThreshold(5000);
        
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        
        this.dataSource = new HikariDataSource(config);

        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            // Table for games
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS games (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "strategy VARCHAR(50)," +
                    "final_score INT," +
                    "yahtzee_bonuses INT DEFAULT 0," +
                    "is_validation BOOLEAN DEFAULT FALSE," +
                    "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");

            // Table for turns
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS turns (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "game_id INT," +
                    "turn_index INT," +
                    "category VARCHAR(50)," +
                    "score INT," +
                    "dice_values VARCHAR(20)," +
                    "roll_index INT," +
                    "is_joker BOOLEAN DEFAULT FALSE," +
                    "FOREIGN KEY (game_id) REFERENCES games(id)" +
                    ")");

            // Table for roll states (to track what was available at each roll)
            stmt.executeUpdate("CREATE TABLE IF NOT EXISTS roll_states (" +
                    "id INT AUTO_INCREMENT PRIMARY KEY," +
                    "turn_id INT," +
                    "roll_index INT," +
                    "best_category VARCHAR(50)," +
                    "best_score INT," +
                    "FOREIGN KEY (turn_id) REFERENCES turns(id)" +
                    ")");
            
        }
    } // initDatabase method ends here

    private void safeCreateIndex(Connection conn, String tableName, String indexName, String columns) throws SQLException {
        try (ResultSet rs = conn.getMetaData().getIndexInfo(null, null, tableName, false, false)) {
            while (rs.next()) {
                if (indexName.equalsIgnoreCase(rs.getString("INDEX_NAME"))) {
                    return; // Index already exists
                }
            }
        }
        try (Statement stmt = conn.createStatement()) {
            // Explicitly avoiding 'IF NOT EXISTS' as it's not standard MySQL for CREATE INDEX
            stmt.executeUpdate("CREATE INDEX " + indexName + " ON " + tableName + " " + columns);
        } catch (SQLException e) {
            // Fallback for cases where getIndexInfo might fail or be incomplete
            if (e.getErrorCode() != 1061) { // 1061 = Duplicate key name
                throw e;
            }
        }
    } // safeCreateIndex method ends here

    private void checkAndAddColumn(Statement stmt, DatabaseMetaData metaData, String table, String column, String definition) throws SQLException {
        try (ResultSet rs = metaData.getColumns(null, null, table, column)) {
            if (!rs.next()) {
                stmt.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            }
        }
    } // checkAndAddColumn method ends here

    public void clearData() throws SQLException {
        try (Connection conn = getConnection()) {
            Statement stmt = conn.createStatement();
            stmt.executeUpdate("SET FOREIGN_KEY_CHECKS = 0");
            stmt.executeUpdate("TRUNCATE TABLE roll_states");
            stmt.executeUpdate("TRUNCATE TABLE turns");
            stmt.executeUpdate("TRUNCATE TABLE games");
            stmt.executeUpdate("SET FOREIGN_KEY_CHECKS = 1");
        }
    } // clearData method ends here;

    public Connection getConnection() throws SQLException {
        if (dataSource == null) {
            throw new SQLException("DataSource not initialized. Call initDatabase() first.");
        }
        return dataSource.getConnection();
    } // getConnection method ends here
    
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    } // close method ends here
} // class DatabaseManager ends here
