package fr.ax_dev.universejobs.cache;

import fr.ax_dev.universejobs.UniverseJobs;
import fr.ax_dev.universejobs.job.PlayerJobData;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cache ultra-rapide des données joueur.
 * Lookup instantané sans attente de base de données.
 * 
 * REFACTORED: Now delegates to UnifiedCacheManager for all player data.
 * ConfigurationCache remains for configuration-only data (no player data mixing).
 */
public class PlayerJobCache {
    
    private final UniverseJobs plugin;
    private final UnifiedCacheManager unifiedCache;
    
    // Cache des permissions par joueur (configuration-only, not player data)
    private final Map<UUID, Map<String, Boolean>> playerPermissionsCache = new ConcurrentHashMap<>();
    
    // Statistiques de performance
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong cacheMisses = new AtomicLong(0);
    
    public PlayerJobCache(UniverseJobs plugin) {
        this.plugin = plugin;
        this.unifiedCache = plugin.getUnifiedCache();
    }
    
    /**
     * Précharge les données des joueurs connectés.
     * Now delegates to UnifiedCacheManager.
     */
    public void preloadOnlinePlayers() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            preloadPlayer(player.getUniqueId());
        }
    }
    
    /**
     * Précharge les données d'un joueur de manière asynchrone.
     * Now delegates to UnifiedCacheManager.
     */
    public CompletableFuture<Void> preloadPlayer(UUID playerUuid) {
        // Delegate to unified cache
        return unifiedCache.getPlayerData(playerUuid).thenAccept(data -> {
            // Data is now cached in UnifiedCacheManager
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("Player data preloaded for " + playerUuid);
            }
        }).exceptionally(ex -> {
            plugin.getLogger().warning("Failed to preload player data for " + playerUuid + ": " + ex.getMessage());
            return null;
        });
    }
    
    /**
     * Lookup instantané des jobs d'un joueur (0ms).
     * Now delegates to UnifiedCacheManager.
     */
    public Set<String> getPlayerJobs(UUID playerUuid) {
        // Delegate to unified cache
        Set<String> jobs = unifiedCache.getPlayerJobs(playerUuid);
        if (!jobs.isEmpty()) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        return jobs;
    }
    
    
    /**
     * Cache des permissions avec TTL (configuration-only).
     */
    public boolean hasPermissionCached(UUID playerUuid, String permission) {
        Map<String, Boolean> perms = playerPermissionsCache.get(playerUuid);
        if (perms != null && perms.containsKey(permission)) {
            cacheHits.incrementAndGet();
            return perms.get(permission);
        }
        
        cacheMisses.incrementAndGet();
        // Fallback - check réel et cache le résultat
        Player player = plugin.getServer().getPlayer(playerUuid);
        if (player != null) {
            boolean result = player.hasPermission(permission);
            playerPermissionsCache.computeIfAbsent(playerUuid, k -> new ConcurrentHashMap<>())
                .put(permission, result);
            return result;
        }
        
        return false;
    }
    
    /**
     * Cache du multiplier d'un joueur.
     * Now delegates to UnifiedCacheManager.
     */
    public double getPlayerMultiplier(UUID playerUuid) {
        // Delegate to unified cache
        double multiplier = unifiedCache.getMultiplier(playerUuid);
        cacheHits.incrementAndGet();
        return multiplier;
    }
    
    /**
     * Get niveau avec cache instantané.
     * Now delegates to UnifiedCacheManager.
     */
    public int getPlayerLevel(UUID playerUuid, String jobId) {
        // Delegate to unified cache
        int level = unifiedCache.getPlayerLevel(playerUuid, jobId);
        if (level > 0) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        return level;
    }
    
    /**
     * Get XP avec cache instantané.
     * Now delegates to UnifiedCacheManager.
     */
    public double getPlayerXp(UUID playerUuid, String jobId) {
        // Delegate to unified cache
        double xp = unifiedCache.getPlayerXp(playerUuid, jobId);
        if (xp > 0.0) {
            cacheHits.incrementAndGet();
        } else {
            cacheMisses.incrementAndGet();
        }
        return xp;
    }
    
    /**
     * Mise à jour du cache après gain d'XP.
     * Now delegates to UnifiedCacheManager.
     */
    public void updatePlayerXp(UUID playerUuid, String jobId, double newXp, int newLevel) {
        // Delegate to unified cache
        unifiedCache.updatePlayerXp(playerUuid, jobId, newXp, newLevel);
    }
    
    /**
     * Ajout d'un job dans le cache.
     * Now delegates to UnifiedCacheManager.
     */
    public void addPlayerJob(UUID playerUuid, String jobId) {
        // Delegate to unified cache
        unifiedCache.addPlayerJob(playerUuid, jobId);
    }
    
    /**
     * Retrait d'un job du cache.
     * Now delegates to UnifiedCacheManager.
     */
    public void removePlayerJob(UUID playerUuid, String jobId) {
        // Delegate to unified cache
        unifiedCache.removePlayerJob(playerUuid, jobId);
    }
    
    /**
     * Nettoyage complet d'un joueur (déconnexion).
     * Now delegates to UnifiedCacheManager.
     */
    public void cleanupPlayer(UUID playerUuid) {
        // Delegate to unified cache
        unifiedCache.invalidatePlayer(playerUuid);
        
        // Also cleanup local permission cache
        playerPermissionsCache.remove(playerUuid);
    }
    
    /**
     * Nettoyage des caches expirés.
     * Now delegates to UnifiedCacheManager for player data.
     */
    public void performCleanup() {
        // Cleanup local permission cache
        playerPermissionsCache.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            return player == null || !player.isOnline();
        });
        
        // Delegate unified cache cleanup
        unifiedCache.performCleanup();
    }
    
    /**
     * Statistiques de performance du cache.
     * Now includes UnifiedCacheManager stats.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cache_hits", cacheHits.get());
        stats.put("cache_misses", cacheMisses.get());
        stats.put("cached_permissions", playerPermissionsCache.size());
        
        // Include unified cache stats
        stats.putAll(unifiedCache.getStats());
        
        long totalRequests = cacheHits.get() + cacheMisses.get();
        if (totalRequests > 0) {
            stats.put("hit_rate", (double) cacheHits.get() / totalRequests * 100);
        }
        
        return stats;
    }
    
    
    /**
     * Reset des statistiques.
     * Now resets both local and unified cache stats.
     */
    public void resetStats() {
        cacheHits.set(0);
        cacheMisses.set(0);
        unifiedCache.resetStats();
    }
}