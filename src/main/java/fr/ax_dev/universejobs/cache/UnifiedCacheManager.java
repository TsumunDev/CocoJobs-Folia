package fr.ax_dev.universejobs.cache;

import com.github.benmanes.caffeine.cache.AsyncLoadingCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import com.github.benmanes.caffeine.cache.Scheduler;
import fr.ax_dev.universejobs.UniverseJobs;
import fr.ax_dev.universejobs.job.PlayerJobData;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.concurrent.atomic.AtomicLong;

/**
 * UnifiedCacheManager - Single source of truth for player data caching.
 * 
 * This unifies 4 separate cache layers:
 * 1. ActionProcessor static permission multiplier caches
 * 2. DatabaseDataStorage.cache
 * 3. JobManager.playerData
 * 4. PlayerJobCache (multiple player data caches)
 *
 * Uses Caffeine for high-performance, thread-safe L1 cache with automatic TTL.
 * Supports async loading from database with cache-aside pattern.
 */
public class UnifiedCacheManager {
    
    private final UniverseJobs plugin;
    
    // L1 Cache: Player data with automatic expiration
    private final AsyncLoadingCache<UUID, PlayerJobData> playerDataCache;
    
    // L1 Cache: Permission multipliers (from ActionProcessor)
    private final com.github.benmanes.caffeine.cache.Cache<UUID, Integer> multiplierCache;
    
    // Statistics
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    // TTL Configuration
    private static final long PLAYER_DATA_TTL_MINUTES = 5;
    private static final long MULTIPLIER_TTL_SECONDS = 30;
    private static final long MAX_CACHE_SIZE = 1000;
    
    /**
     * Create a new UnifiedCacheManager.
     * 
     * @param plugin The plugin instance
     */
    public UnifiedCacheManager(UniverseJobs plugin) {
        this.plugin = plugin;
        
        // Initialize player data cache with Caffeine
        this.playerDataCache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(PLAYER_DATA_TTL_MINUTES, TimeUnit.MINUTES)
            .removalListener((key, value, cause) -> {
                if (plugin.getConfigManager() != null && plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Player data removed from cache: " + key + " (cause: " + cause + ")");
                }
            })
            .scheduler(Scheduler.systemScheduler())
            .buildAsync(this::loadPlayerDataFromDatabase);
        
        // Initialize multiplier cache with Caffeine
        this.multiplierCache = Caffeine.newBuilder()
            .expireAfterWrite(MULTIPLIER_TTL_SECONDS, TimeUnit.SECONDS)
            .removalListener((key, value, cause) -> {
                if (plugin.getConfigManager() != null && plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Multiplier removed from cache: " + key);
                }
            })
            .scheduler(Scheduler.systemScheduler())
            .build();
    }
    
    /**
     * Get player data from cache (L1) or load from database.
     * Uses async cache-aside pattern.
     * 
     * @param playerUuid The player UUID
     * @return CompletableFuture<PlayerJobData>
     */
    public CompletableFuture<PlayerJobData> getPlayerData(UUID playerUuid) {
        return playerDataCache.get(playerUuid).thenApply(data -> {
            if (data != null) {
                cacheHits.incrementAndGet();
            } else {
                cacheMisses.incrementAndGet();
            }
            return data;
        });
    }
    
    /**
     * Get player data synchronously (for legacy code).
     * WARNING: May block if data not in cache.
     * 
     * @param playerUuid The player UUID
     * @return PlayerJobData or null if not found
     */
    public PlayerJobData getPlayerDataSync(UUID playerUuid) {
        try {
            return playerDataCache.get(playerUuid).get();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get player data synchronously for " + playerUuid, e);
            cacheMisses.incrementAndGet();
            return null;
        }
    }
    
    /**
     * Update player data in cache (cache-aside pattern).
     * 
     * @param playerUuid The player UUID
     * @param data The new player data
     */
    public void updatePlayerData(UUID playerUuid, PlayerJobData data) {
        playerDataCache.put(playerUuid, CompletableFuture.completedFuture(data));
    }
    
    /**
     * Invalidate specific player from cache.
     * Use when player data changes externally.
     * 
     * @param playerUuid The player UUID
     */
    public void invalidatePlayer(UUID playerUuid) {
        playerDataCache.synchronous().invalidate(playerUuid);
        multiplierCache.invalidate(playerUuid);
    }
    
    /**
     * Invalidate all cached data.
     * Use during plugin reload or major data changes.
     */
    public void invalidateAll() {
        playerDataCache.synchronous().invalidateAll();
        multiplierCache.invalidateAll();
        
        cacheHits.set(0);
        cacheMisses.set(0);
        
        if (plugin.getConfigManager() != null && plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("All cache entries invalidated");
        }
    }
    
    /**
     * Get permission multiplier for player (from ActionProcessor cache).
     * 
     * @param playerUuid The player UUID
     * @return The multiplier (1.0-10.0)
     */
    public double getMultiplier(UUID playerUuid) {
        Integer multiplier = multiplierCache.getIfPresent(playerUuid);
        if (multiplier != null) {
            cacheHits.incrementAndGet();
            return multiplier;
        }
        
        // Calculate and cache multiplier
        cacheMisses.incrementAndGet();
        double calculatedMultiplier = calculateMultiplier(playerUuid);
        multiplierCache.put(playerUuid, (int) calculatedMultiplier);
        return calculatedMultiplier;
    }
    
    /**
     * Invalidate multiplier cache for specific player.
     * Use when player permissions change.
     * 
     * @param playerUuid The player UUID
     */
    public void invalidateMultiplier(UUID playerUuid) {
        multiplierCache.invalidate(playerUuid);
    }
    
    /**
     * Get player jobs set (from PlayerJobCache).
     * 
     * @param playerUuid The player UUID
     * @return Set of job IDs
     */
    public Set<String> getPlayerJobs(UUID playerUuid) {
        PlayerJobData data = getPlayerDataSync(playerUuid);
        if (data != null) {
            return data.getJobs();
        }
        return Set.of(); // Empty set
    }
    
    /**
     * Get player level for specific job (from PlayerJobCache).
     * 
     * @param playerUuid The player UUID
     * @param jobId The job ID
     * @return The player level
     */
    public int getPlayerLevel(UUID playerUuid, String jobId) {
        PlayerJobData data = getPlayerDataSync(playerUuid);
        if (data != null && data.hasJob(jobId)) {
            return data.getLevel(jobId);
        }
        return 0;
    }
    
    /**
     * Get player XP for specific job (from PlayerJobCache).
     * 
     * @param playerUuid The player UUID
     * @param jobId The job ID
     * @return The player XP
     */
    public double getPlayerXp(UUID playerUuid, String jobId) {
        PlayerJobData data = getPlayerDataSync(playerUuid);
        if (data != null && data.hasJob(jobId)) {
            return data.getXp(jobId);
        }
        return 0.0;
    }
    
    /**
     * Update player XP and level in cache (from PlayerJobCache).
     * 
     * @param playerUuid The player UUID
     * @param jobId The job ID
     * @param newXp The new XP
     * @param newLevel The new level
     */
    public void updatePlayerXp(UUID playerUuid, String jobId, double newXp, int newLevel) {
        PlayerJobData data = getPlayerDataSync(playerUuid);
        if (data != null) {
            data.setXp(jobId, newXp);
            data.setLevel(jobId, newLevel);
            // Update cache
            playerDataCache.put(playerUuid, CompletableFuture.completedFuture(data));
        }
    }
    
    /**
     * Add job to player (from PlayerJobCache).
     * 
     * @param playerUuid The player UUID
     * @param jobId The job ID
     */
    public void addPlayerJob(UUID playerUuid, String jobId) {
        PlayerJobData data = getPlayerDataSync(playerUuid);
        if (data != null) {
            data.joinJob(jobId);
            playerDataCache.put(playerUuid, CompletableFuture.completedFuture(data));
        }
    }
    
    /**
     * Remove job from player (from PlayerJobCache).
     * 
     * @param playerUuid The player UUID
     * @param jobId The job ID
     */
    public void removePlayerJob(UUID playerUuid, String jobId) {
        PlayerJobData data = getPlayerDataSync(playerUuid);
        if (data != null) {
            data.leaveJob(jobId);
            playerDataCache.put(playerUuid, CompletableFuture.completedFuture(data));
        }
    }
    
    /**
     * Clean up expired cache entries (called periodically).
     */
    public void performCleanup() {
        // Caffeine handles automatic expiration, but we can trigger manual cleanup if needed
        playerDataCache.synchronous().cleanUp();
        multiplierCache.cleanUp();
        
        if (plugin.getConfigManager() != null && plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("Cache cleanup completed");
        }
    }
    
    /**
     * Get cache statistics.
     * 
     * @return Map containing cache stats
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("cache_hits", cacheHits.get());
        stats.put("cache_misses", cacheMisses.get());
        stats.put("cached_players", playerDataCache.synchronous().estimatedSize());
        stats.put("cached_multipliers", multiplierCache.estimatedSize());
        
        long totalRequests = cacheHits.get() + cacheMisses.get();
        if (totalRequests > 0) {
            stats.put("hit_rate", (double) cacheHits.get() / totalRequests * 100);
        }
        
        return stats;
    }
    
    /**
     * Reset cache statistics.
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
    }
    
    /**
     * Shutdown cache manager gracefully.
     */
    public void shutdown() {
        if (plugin.getConfigManager() != null && plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("Shutting down UnifiedCacheManager...");
        }
        
        // Invalidate all to ensure dirty data is saved
        invalidateAll();
        
        // Caffeine doesn't have explicit shutdown, just clean references
    }
    
    /**
     * Load player data from database (async cache loader).
     * 
     * @param key The player UUID
     * @param executor The executor for async loading
     * @return CompletableFuture<PlayerJobData>
     */
    private CompletableFuture<PlayerJobData> loadPlayerDataFromDatabase(UUID key, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            if (plugin.isDatabaseEnabled()) {
                return plugin.getDataStorage().loadPlayerDataAsync(key)
                    .thenApply(data -> {
                        data.setJobManager(plugin.getJobManager());
                        return data;
                    })
                    .join();
            }
            
            // File-based fallback
            PlayerJobData data = new PlayerJobData(key);
            data.setJobManager(plugin.getJobManager());
            plugin.getJobManager().assignDefaultJobs(data);
            return data;
        }, executor);
    }
    
    /**
     * Calculate permission multiplier for player (from ActionProcessor).
     * 
     * @param playerUuid The player UUID
     * @return The multiplier (1.0-10.0)
     */
    private double calculateMultiplier(UUID playerUuid) {
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player == null) {
            return 1.0;
        }
        
        // Skip if player is OP or has wildcard permission
        if (player.isOp() || player.hasPermission("*")) {
            return 1.0;
        }
        
        // Check multipliers 10 -> 1 for exp
        for (int i = 10; i >= 1; i--) {
            String permission = "universejobs.multiplier.exp." + i;
            if (player.hasPermission(permission) && !hasWildcardPermission(player)) {
                return i;
            }
        }
        
        return 1.0;
    }
    
    /**
     * Check if player has wildcard permissions.
     * 
     * @param player The player
     * @return true if player has wildcard permissions
     */
    private boolean hasWildcardPermission(Player player) {
        return player.hasPermission("*") || 
               player.hasPermission("universejobs.*") ||
               player.hasPermission("universejobs.multiplier.*") ||
               player.hasPermission("universejobs.multiplier.exp.*");
    }
}
