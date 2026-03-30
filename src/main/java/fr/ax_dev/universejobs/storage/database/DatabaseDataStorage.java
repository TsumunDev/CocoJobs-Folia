package fr.ax_dev.universejobs.storage.database;

import fr.ax_dev.universejobs.UniverseJobs;
import fr.ax_dev.universejobs.job.PlayerJobData;
import fr.ax_dev.universejobs.storage.DataStorage;
import fr.ax_dev.universejobs.storage.dao.PlayerDataDao;
import fr.ax_dev.universejobs.storage.dao.RewardDao;
import fr.ax_dev.universejobs.storage.dao.JobStatsDao;
import fr.ax_dev.universejobs.storage.dao.LeaderboardDao;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class DatabaseDataStorage implements DataStorage {
    
    private final UniverseJobs plugin;
    private final DatabaseConfig config;
    private final HikariConnectionPool connectionPool;
    private final DatabaseSchema schema;
    private final PlayerDataDao playerDataDao;
    private final RewardDao rewardDao;
    private final JobStatsDao jobStatsDao;
    private final LeaderboardDao leaderboardDao;
    
    // Cache removed - now handled by UnifiedCacheManager
    // Only rewardCache remains as it's for reward tracking, not player data
    private final Map<UUID, Set<String>> rewardCache = new ConcurrentHashMap<>();
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    
    private long totalOperations = 0;

    public DatabaseDataStorage(UniverseJobs plugin) {
        this.plugin = plugin;
        this.config = new DatabaseConfig(plugin.getConfig().getConfigurationSection("database"));
        this.connectionPool = new HikariConnectionPool(plugin, config);
        this.schema = new DatabaseSchema(plugin, config, connectionPool);
        this.playerDataDao = new PlayerDataDao(connectionPool, config);
        this.rewardDao = new RewardDao(connectionPool, config);
        this.jobStatsDao = new JobStatsDao(connectionPool, config);
        this.leaderboardDao = new LeaderboardDao(plugin, connectionPool, config);
    }

    @Override
    public CompletableFuture<Void> initializeAsync() {
        return CompletableFuture.runAsync(() -> {
            if (initialized.compareAndSet(false, true)) {
                try {
                    connectionPool.initialize();
                    schema.initializeSchema();
                    plugin.getLogger().info("Database storage initialized successfully");
                } catch (Exception e) {
                    initialized.set(false);
                    plugin.getLogger().log(Level.SEVERE, "Failed to initialize database storage", e);
                    throw new RuntimeException("Failed to initialize database storage", e);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdownAsync() {
        return CompletableFuture.runAsync(() -> {
            if (shutdown.compareAndSet(false, true)) {
                try {
                    // Cache handled by UnifiedCacheManager
                    rewardCache.clear();
                    connectionPool.shutdown();
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Error during database storage shutdown", e);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Void> savePlayerDataAsync(UUID playerId, PlayerJobData data) {
        if (shutdown.get()) {
            return CompletableFuture.completedFuture(null);
        }

        totalOperations++;
        // Cache handled by UnifiedCacheManager, update it after save
        CompletableFuture<Void> saveFuture = playerDataDao.savePlayerData(playerId, data);

        saveFuture.thenRun(() -> {
            String playerName = plugin.getServer().getOfflinePlayer(playerId).getName();
            if (playerName == null) playerName = "Unknown";

            for (String jobId : data.getJobs()) {
                double xp = data.getXp(jobId);
                int level = data.getLevel(jobId);
                leaderboardDao.updatePlayerLeaderboardEntry(playerId, playerName, jobId, xp, level);
            }
            
            // Update UnifiedCacheManager
            plugin.getUnifiedCache().updatePlayerData(playerId, data);
        });
        
        return saveFuture;
    }

    @Override
    public CompletableFuture<PlayerJobData> loadPlayerDataAsync(UUID playerId) {
        if (shutdown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Storage is shutdown"));
        }
        
        totalOperations++;
        // Cache handled by UnifiedCacheManager, load directly from DB
        return playerDataDao.loadPlayerData(playerId).thenApply(data -> {
            // Cache update handled by UnifiedCacheManager via its async loader
            return data;
        });
    }

    @Override
    public CompletableFuture<Void> saveBatchPlayerData(Map<UUID, PlayerJobData> playerDataMap) {
        if (shutdown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Storage is shutdown"));
        }
        
        CompletableFuture<Void>[] futures = playerDataMap.entrySet().stream()
                .map(entry -> savePlayerDataAsync(entry.getKey(), entry.getValue()))
                .toArray(CompletableFuture[]::new);
        
        return CompletableFuture.allOf(futures);
    }

    @Override
    public CompletableFuture<Map<UUID, PlayerJobData>> loadBatchPlayerData(Set<UUID> playerIds) {
        if (shutdown.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Storage is shutdown"));
        }
        
        // Cache handled by UnifiedCacheManager, load directly from DB
        return playerDataDao.loadPlayerDataBatch(playerIds).thenApply(loadedData -> {
            // Cache update handled by UnifiedCacheManager via its async loader
            return loadedData;
        });
    }

    @Override
    public CompletableFuture<Void> preloadPlayerData(Set<UUID> playerIds) {
        return loadBatchPlayerData(playerIds).thenAccept(data -> {
            plugin.getLogger().info("Preloaded " + data.size() + " player data entries");
        });
    }

    @Override
    public void evictFromCache(UUID playerId) {
        // Delegate to UnifiedCacheManager
        plugin.getUnifiedCache().invalidatePlayer(playerId);
        rewardCache.remove(playerId);
    }

    @Override
    public void clearCache() {
        // Delegate to UnifiedCacheManager
        plugin.getUnifiedCache().invalidateAll();
        rewardCache.clear();
    }

    @Override
    public Map<String, Object> getCacheStats() {
        // Delegate to UnifiedCacheManager for cache stats
        Map<String, Object> stats = new HashMap<>();
        stats.putAll(plugin.getUnifiedCache().getStats());
        stats.put("cached_rewards", rewardCache.size());
        return stats;
    }

    @Override
    public Map<String, Object> getPerformanceMetrics() {
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("total_operations", totalOperations);
        metrics.put("pool_active", connectionPool.getDataSource() != null ? 
                connectionPool.getDataSource().getHikariPoolMXBean().getActiveConnections() : 0);
        metrics.put("pool_idle", connectionPool.getDataSource() != null ? 
                connectionPool.getDataSource().getHikariPoolMXBean().getIdleConnections() : 0);
        return metrics;
    }

    @Override
    public void resetPerformanceMetrics() {
        totalOperations = 0;
        plugin.getUnifiedCache().resetStats();
    }

    @Override
    public boolean isHealthy() {
        return initialized.get() && !shutdown.get() && connectionPool.isInitialized();
    }

    @Override
    public Map<String, Object> getHealthInfo() {
        Map<String, Object> health = new HashMap<>();
        health.put("initialized", initialized.get());
        health.put("shutdown", shutdown.get());
        health.put("pool_initialized", connectionPool.isInitialized());
        health.put("database_type", config.getType().getName());
        // Cache size from UnifiedCacheManager
        health.putAll(plugin.getUnifiedCache().getStats());
        return health;
    }

    @Override
    public void initialize() {
        initializeAsync().join();
    }

    @Override
    public void shutdown() {
        shutdownAsync().join();
    }

    @Override
    public boolean hasClaimedReward(UUID playerId, String jobId, String rewardId) {
        Set<String> playerRewards = rewardCache.get(playerId);
        if (playerRewards != null) {
            return playerRewards.contains(jobId + ":" + rewardId);
        }
        
        try {
            return rewardDao.hasClaimedReward(playerId, jobId, rewardId).get();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to check reward claim", e);
            return false;
        }
    }

    @Override
    public void claimReward(UUID playerId, String jobId, String rewardId, long claimTime) {
        rewardCache.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(jobId + ":" + rewardId);
        
        rewardDao.claimReward(playerId, jobId, rewardId, claimTime).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to save reward claim to database", throwable);
            return null;
        });
    }

    @Override
    public long getClaimTime(UUID playerId, String jobId, String rewardId) {
        try {
            return rewardDao.getClaimTime(playerId, jobId, rewardId).get();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get claim time", e);
            return -1;
        }
    }

    @Override
    public Set<String> getClaimedRewards(UUID playerId, String jobId) {
        try {
            return rewardDao.getClaimedRewards(playerId, jobId).get();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get claimed rewards", e);
            return new HashSet<>();
        }
    }

    @Override
    public Set<String> getAllClaimedRewards(UUID playerId) {
        try {
            return rewardDao.getAllClaimedRewards(playerId).get();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get all claimed rewards", e);
            return new HashSet<>();
        }
    }

    @Override
    public void resetRewardClaim(UUID playerId, String jobId, String rewardId) {
        Set<String> playerRewards = rewardCache.get(playerId);
        if (playerRewards != null) {
            playerRewards.remove(jobId + ":" + rewardId);
        }
        
        rewardDao.resetRewardClaim(playerId, jobId, rewardId).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to reset reward claim in database", throwable);
            return null;
        });
    }

    @Override
    public void resetJobRewards(UUID playerId, String jobId) {
        Set<String> playerRewards = rewardCache.get(playerId);
        if (playerRewards != null) {
            playerRewards.removeIf(reward -> reward.startsWith(jobId + ":"));
        }
        
        rewardDao.resetJobRewards(playerId, jobId).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to reset job rewards in database", throwable);
            return null;
        });
    }

    @Override
    public void resetAllRewards(UUID playerId) {
        rewardCache.remove(playerId);
        
        rewardDao.resetAllRewards(playerId).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to reset all rewards in database", throwable);
            return null;
        });
    }

    @Override
    public void save() {
        // Nothing to do for database implementation as saves are immediate
    }

    @Override
    public void loadPlayerData(UUID playerId) {
        loadPlayerDataAsync(playerId).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to load player data for " + playerId, throwable);
            return new PlayerJobData(playerId);
        });
    }

    @Override
    public void unloadPlayerData(UUID playerId) {
        evictFromCache(playerId);
    }
    
    public PlayerDataDao getPlayerDataDao() {
        return playerDataDao;
    }
    
    public RewardDao getRewardDao() {
        return rewardDao;
    }
    
    public LeaderboardDao getLeaderboardDao() {
        return leaderboardDao;
    }
    
    public JobStatsDao getJobStatsDao() {
        return jobStatsDao;
    }

    @Override
    public double getJobUserCount(String jobId) {
        try {
            return playerDataDao.getJobUserCount(jobId).get();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get job user count for " + jobId, e);
            return 0.0;
        }
    }

    @Override
    public PlayerJobData getPlayerData(UUID playerId) {
        // Delegate to UnifiedCacheManager for cache lookup
        PlayerJobData cached = plugin.getUnifiedCache().getPlayerDataSync(playerId);
        if (cached != null) {
            return cached;
        }

        // Fallback to async load
        try {
            PlayerJobData data = loadPlayerDataAsync(playerId).get();
            return data;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load player data for " + playerId, e);
            return new PlayerJobData(playerId);
        }
    }

    @Override
    public CompletableFuture<Void> deletePlayerData(UUID playerId) {
        return CompletableFuture.runAsync(() -> {
            // Invalidate from UnifiedCacheManager
            plugin.getUnifiedCache().invalidatePlayer(playerId);
            rewardCache.remove(playerId);

            try {
                playerDataDao.deletePlayerData(playerId).get();
                rewardDao.deletePlayerRewards(playerId).get();
                leaderboardDao.removeFromLeaderboard(playerId).get();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to delete player data for " + playerId, e);
                throw new RuntimeException("Failed to delete player data", e);
            }
        });
    }
}