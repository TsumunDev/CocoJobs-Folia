package fr.ax_dev.universejobs.condition.impl;

import fr.ax_dev.universejobs.condition.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;

import java.util.List;
import java.util.ArrayList;

/**
 * Condition that checks the item in the player's hand.
 * Supports material, NBT, custom model data, and MMOItems integration.
 */
public class ItemCondition extends AbstractCondition {
    
    private final List<String> materials;
    private final String nbtKey;
    private final String nbtValue;
    private final Integer customModelData;
    private final String mmoItemsType;
    private final String mmoItemsId;
    
    /**
     * Create an item condition from configuration.
     * 
     * @param config The configuration section
     */
    public ItemCondition(ConfigurationSection config) {
        super(config);
        
        // Handle both single material and material list
        this.materials = new ArrayList<>();
        if (config.isList("material")) {
            this.materials.addAll(config.getStringList("material"));
        } else if (config.getString("material") != null) {
            this.materials.add(config.getString("material"));
        }
        
        this.nbtKey = config.getString("nbt.key");
        this.nbtValue = config.getString("nbt.value");
        this.customModelData = config.contains("custom-model-data") ? 
                              config.getInt("custom-model-data") : null;
        this.mmoItemsType = config.getString("mmoitems.type");
        this.mmoItemsId = config.getString("mmoitems.id");
    }
    
    @Override
    public boolean isMet(Player player, Event event, ConditionContext context) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType() == Material.AIR) {
            return false;
        }
        
        // Check materials (OR logic - item must match at least one material)
        if (!materials.isEmpty()) {
            boolean materialMatched = false;
            for (String materialName : materials) {
                Material requiredMaterial = fr.ax_dev.universejobs.utils.EnumUtils.parseMaterial(materialName, null);
                if (requiredMaterial != null && item.getType() == requiredMaterial) {
                    materialMatched = true;
                    break;
                }
            }
            if (!materialMatched) {
                return false;
            }
        }
        
        // Check custom model data
        if (customModelData != null) {
            if (!item.hasItemMeta() || !item.getItemMeta().hasCustomModelData() ||
                item.getItemMeta().getCustomModelData() != customModelData) {
                return false;
            }
        }
        
        // Check NBT data using Paper PDC API
        if (nbtKey != null && nbtValue != null) {
            try {
                if (!item.hasItemMeta()) {
                    return false;
                }

                ItemMeta meta = item.getItemMeta();
                PersistentDataContainer container = meta.getPersistentDataContainer();
                NamespacedKey namespacedKey = new NamespacedKey("universejobs", nbtKey);

                if (!container.has(namespacedKey, PersistentDataType.STRING)) {
                    return false;
                }

                String itemNbtValue = container.get(namespacedKey, PersistentDataType.STRING);
                if (!nbtValue.equals(itemNbtValue)) {
                    return false;
                }
            } catch (Exception e) {
                // Error reading PDC data
                return false;
            }
        }
        
        // Check MMOItems data (requires MMOItems API - NBTAPI removed per Stack 2026)
        // TODO: Integrate with official MMOItems API if needed
        if (mmoItemsType != null && mmoItemsId != null) {
            // MMOItems checks disabled - would require NBTAPI or official MMOItems API
            // To enable, implement using: net.Indyuce.mmoitems.api.Type and net.Indyuce.mmoitems.api.item.mmoitem.MMOItem
        }

        return true;
    }

    @Override
    public ConditionType getType() {
        return ConditionType.ITEM;
    }
    
    @Override
    public String toString() {
        return "ItemCondition{materials=" + materials + ", hasNBT=" + (nbtKey != null) + 
               ", hasMMOItems=" + (mmoItemsType != null) + 
               "}";
    }
}