package fr.ax_dev.universejobs.storage.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import fr.ax_dev.universejobs.UniverseJobs;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public class HikariConnectionPool {
    
    private final UniverseJobs plugin;
    private final DatabaseConfig config;
    private HikariDataSource dataSource;
    private boolean initialized = false;

    public HikariConnectionPool(UniverseJobs plugin, DatabaseConfig config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() throws SQLException {
        if (initialized) {
            return;
        }

        HikariConfig hikariConfig = new HikariConfig();
        
        String jdbcUrl = config.buildJdbcUrl(plugin.getDataFolder().getAbsolutePath());
        hikariConfig.setJdbcUrl(jdbcUrl);
        
        if (config.getType() == DatabaseType.MYSQL) {
            hikariConfig.setUsername(config.getUsername());
            hikariConfig.setPassword(config.getPassword());
            hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else {
            hikariConfig.setDriverClassName("org.sqlite.JDBC");
        }

        hikariConfig.setConnectionTimeout(config.getConnectionTimeoutMs());
        hikariConfig.setValidationTimeout(TimeUnit.SECONDS.toMillis(5));
        hikariConfig.setLeakDetectionThreshold(TimeUnit.MINUTES.toMillis(1));

        if (config.getType() == DatabaseType.MYSQL) {
            // MySQL-specific optimizations
            hikariConfig.setMinimumIdle(config.getMinConnections());
            hikariConfig.setMaximumPoolSize(config.getMaxConnections());

            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            hikariConfig.addDataSourceProperty("useServerPrepStmts", "true");
            hikariConfig.addDataSourceProperty("rewriteBatchedStatements", "true");
            hikariConfig.addDataSourceProperty("maintainTimeStats", "false");
            hikariConfig.addDataSourceProperty("useLocalSessionState", "true");
            hikariConfig.addDataSourceProperty("cacheResultSetMetadata", "true");
            hikariConfig.addDataSourceProperty("cacheServerConfiguration", "true");
            hikariConfig.addDataSourceProperty("elideSetAutoCommits", "true");
        } else {
            // SQLite-specific optimizations
            // SQLite works best with a single connection due to file locking
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setMaximumPoolSize(1);
            hikariConfig.setIdleTimeout(0); // Never timeout for single connection
            hikariConfig.setMaxLifetime(0); // Never expire the connection

            // SQLite performance pragmas via connection init
            hikariConfig.setConnectionInitSql(
                "PRAGMA journal_mode=WAL; " +
                "PRAGMA synchronous=NORMAL; " +
                "PRAGMA cache_size=10000; " +
                "PRAGMA temp_store=MEMORY; " +
                "PRAGMA mmap_size=268435456;"
            );
        }

        try {
            this.dataSource = new HikariDataSource(hikariConfig);
            
            try (Connection connection = dataSource.getConnection()) {
                plugin.getLogger().info("Database connection established successfully (" + config.getType().getName() + ")");
            }
            
            this.initialized = true;
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database connection pool", e);
            throw e;
        }
    }

    public Connection getConnection() throws SQLException {
        if (!initialized) {
            throw new SQLException("Connection pool not initialized");
        }
        return dataSource.getConnection();
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool shut down");
        }
        initialized = false;
    }

    public HikariDataSource getDataSource() {
        return dataSource;
    }
}