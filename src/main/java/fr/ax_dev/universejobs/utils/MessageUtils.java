package fr.ax_dev.universejobs.utils;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ultra-optimized utility class for handling message formatting with MiniMessage and legacy color codes support.
 * Uses Caffeine-style cache eviction and lock-free operations.
 */
public class MessageUtils {

    private static final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final LegacyComponentSerializer legacySerializer = LegacyComponentSerializer.legacySection();

    // High-performance caches with size limits instead of time-based eviction
    private static final int MAX_CACHE_SIZE = 512;
    private static final Map<String, Component> COMPONENT_CACHE = new ConcurrentHashMap<>(256);
    private static final Map<String, String> COLORIZE_CACHE = new ConcurrentHashMap<>(256);
    private static final Map<String, String> LEGACY_CONVERTED_CACHE = new ConcurrentHashMap<>(256);

    // Atomic counter for cheap cache size checks
    private static final AtomicLong cacheAccessCount = new AtomicLong(0);
    private static final long CLEANUP_INTERVAL = 1000; // Check every 1000 accesses
    
    /**
     * Parse a message string with MiniMessage and legacy color code support.
     * Ultra-optimized with lazy cache cleanup.
     *
     * @param message The message to parse
     * @return The parsed Component
     */
    public static Component parseMessage(String message) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }

        // Fast path: check cache first
        Component cached = COMPONENT_CACHE.get(message);
        if (cached != null) {
            return cached;
        }

        // Lazy cleanup: only check periodically
        if (cacheAccessCount.incrementAndGet() % CLEANUP_INTERVAL == 0) {
            cleanupCacheIfNeeded();
        }

        // Convert legacy codes if needed (converter handles early exit internally)
        String processedMessage = LEGACY_CONVERTED_CACHE.get(message);
        if (processedMessage == null) {
            processedMessage = LegacyToMiniMessageConverter.convert(message);
            // Only cache if different from input (saves memory)
            if (!processedMessage.equals(message)) {
                LEGACY_CONVERTED_CACHE.put(message, processedMessage);
            }
        }

        // Parse as MiniMessage
        Component result;
        try {
            result = miniMessage.deserialize(processedMessage);
        } catch (Exception e) {
            result = Component.text(message);
        }

        COMPONENT_CACHE.put(message, result);
        return result;
    }
    
    /**
     * Parse a message with placeholders.
     *
     * @param message The message to parse
     * @param placeholders Map of placeholder keys to values
     * @return The parsed Component with placeholders replaced
     */
    public static Component parseMessage(String message, Map<String, String> placeholders) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }

        if (placeholders == null || placeholders.isEmpty()) {
            return parseMessage(message);
        }

        String processedMessage = replacePlaceholders(message, placeholders);
        return parseMessage(processedMessage);
    }

    /**
     * Parse a message with a single placeholder.
     * Optimized to avoid Map allocation.
     *
     * @param message The message to parse
     * @param placeholder The placeholder key
     * @param value The placeholder value
     * @return The parsed Component with placeholder replaced
     */
    public static Component parseMessage(String message, String placeholder, String value) {
        if (message == null || message.isEmpty()) {
            return Component.empty();
        }

        // Direct replacement without Map allocation
        String replaced = message.replace("{" + placeholder + "}", value);
        return parseMessage(replaced);
    }
    
    /**
     * Send a formatted message to a player.
     * 
     * @param player The player to send the message to
     * @param message The message to send
     */
    public static void sendMessage(Player player, String message) {
        if (player != null && message != null && !message.isEmpty()) {
            player.sendMessage(parseMessage(message));
        }
    }
    
    /**
     * Send a formatted message to a command sender.
     * 
     * @param sender The command sender to send the message to
     * @param message The message to send
     */
    public static void sendMessage(CommandSender sender, String message) {
        if (sender != null && message != null && !message.isEmpty()) {
            sender.sendMessage(parseMessage(message));
        }
    }
    
    /**
     * Send a formatted message with placeholders to a player.
     * 
     * @param player The player to send the message to
     * @param message The message to send
     * @param placeholders Map of placeholder keys to values
     */
    public static void sendMessage(Player player, String message, Map<String, String> placeholders) {
        if (player != null && message != null && !message.isEmpty()) {
            player.sendMessage(parseMessage(message, placeholders));
        }
    }
    
    /**
     * Send a formatted message with a single placeholder to a player.
     * 
     * @param player The player to send the message to
     * @param message The message to send
     * @param placeholder The placeholder key
     * @param value The placeholder value
     */
    public static void sendMessage(Player player, String message, String placeholder, String value) {
        sendMessage(player, message, Map.of(placeholder, value));
    }
    
    /**
     * Convert legacy color codes to MiniMessage format.
     *
     * @param message The message to convert
     * @return The converted message in MiniMessage format
     */
    public static String translateLegacyColorCodes(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        // Check cache first
        String cached = LEGACY_CONVERTED_CACHE.get(message);
        if (cached != null) {
            return cached;
        }

        // Delegate to optimized converter
        String result = LegacyToMiniMessageConverter.convert(message);

        // Only cache if actually converted
        if (!result.equals(message)) {
            LEGACY_CONVERTED_CACHE.put(message, result);
        }

        return result;
    }

    /**
     * Size-based cache cleanup - much cheaper than time-based.
     */
    private static void cleanupCacheIfNeeded() {
        // Simple size-based eviction: clear half when too large
        if (COMPONENT_CACHE.size() > MAX_CACHE_SIZE) {
            COMPONENT_CACHE.clear();
        }
        if (COLORIZE_CACHE.size() > MAX_CACHE_SIZE) {
            COLORIZE_CACHE.clear();
        }
        if (LEGACY_CONVERTED_CACHE.size() > MAX_CACHE_SIZE) {
            LEGACY_CONVERTED_CACHE.clear();
        }
    }
    
    /**
     * Replace placeholders in a message.
     * Optimized: uses direct String.replace for small placeholder counts,
     * avoiding regex overhead.
     */
    private static String replacePlaceholders(String message, Map<String, String> placeholders) {
        if (placeholders == null || placeholders.isEmpty()) {
            return message;
        }

        // For small placeholder counts, direct replace is faster than regex
        String result = message;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            if (result.contains(placeholder)) {
                result = result.replace(placeholder, entry.getValue());
            }
        }
        return result;
    }
    
    /**
     * Strip all formatting from a message.
     * 
     * @param message The message to strip
     * @return The message without formatting
     */
    public static String stripFormatting(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        
        Component component = parseMessage(message);
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
    
    /**
     * Send an action bar message to a player.
     * 
     * @param player The player to send the message to
     * @param message The message to send
     */
    public static void sendActionBar(Player player, String message) {
        if (player != null && message != null) {
            player.sendActionBar(parseMessage(message));
        }
    }
    
    /**
     * Send an action bar message with placeholders to a player.
     * 
     * @param player The player to send the message to
     * @param message The message to send
     * @param placeholders Map of placeholder keys to values
     */
    public static void sendActionBar(Player player, String message, Map<String, String> placeholders) {
        if (player != null && message != null) {
            player.sendActionBar(parseMessage(message, placeholders));
        }
    }
    
    /**
     * Colorize a message (convert color codes).
     *
     * @param message The message to colorize
     * @return The colorized message
     */
    public static String colorize(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        // Check cache first
        String cached = COLORIZE_CACHE.get(message);
        if (cached != null) {
            return cached;
        }

        // Convert and serialize
        Component component = parseMessage(message);
        String result = legacySerializer.serialize(component);

        COLORIZE_CACHE.put(message, result);
        return result;
    }

    /**
     * Clears all caches. Useful for reload commands.
     */
    public static void clearCaches() {
        COMPONENT_CACHE.clear();
        COLORIZE_CACHE.clear();
        LEGACY_CONVERTED_CACHE.clear();
        cacheAccessCount.set(0);
    }
}