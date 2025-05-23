package me.listed.bonkpotion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class BonkPotion extends JavaPlugin {
    
    // Reflection cache for version compatibility
    private static Method getBasePotionDataMethod;
    private static Method getEffectsMethod;
    static {
        try {
            // Try to get methods for different versions
            getBasePotionDataMethod = PotionMeta.class.getMethod("getBasePotionData");
            getEffectsMethod = PotionMeta.class.getMethod("getCustomEffects");
        } catch (NoSuchMethodException e) {
            // Fallback to modern API if methods not found
            getBasePotionDataMethod = null;
            getEffectsMethod = null;
        }
    }
    
    private FileConfiguration config;
    private int maxPotionsToStack;
    private final Set<UUID> processingPlayers = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<String> POTION_MATERIALS = new HashSet<>();
    
    @Override
    public void onEnable() {
        // Initialize potion materials set
        initializePotionMaterials();
        
        // Load configuration
        loadConfig();
        
        // Register commands
        registerCommands();
        
        getLogger().info("BonkPotion has been enabled!");
    }
    
    private void initializePotionMaterials() {
        // Initialize with common potion-related materials
        String[] potionMaterials = {
            "POTION", "SPLASH_POTION", "LINGERING_POTION", "TIPPED_ARROW",
            "WATER_BOTTLE", "GLASS_BOTTLE", "DRAGON_BREATH"
        };
        
        // Add all materials that match our criteria
        for (Material mat : Material.values()) {
            String name = mat.name();
            for (String potionMat : potionMaterials) {
                if (name.contains(potionMat)) {
                    POTION_MATERIALS.add(name);
                    break;
                }
            }
        }
        
        // Log the number of potion materials found for debugging
        getLogger().info("Loaded " + POTION_MATERIALS.size() + " potion-related materials");
    }
    
    private void loadConfig() {
        saveDefaultConfig();
        reloadConfig();
        
        config = getConfig();
        config.options().copyDefaults(true);
        saveConfig();
        
        maxPotionsToStack = config.getInt("max-potions-to-stack", 64);
    }
    
    private void registerCommands() {
        Objects.requireNonNull(getCommand("potionstack")).setExecutor((sender, command, label, args) -> {
            if (!(sender instanceof Player)) {
                sender.sendMessage("This command can only be used by players!");
                return true;
            }
            
            Player player = (Player) sender;
            if (!player.hasPermission("bonkpotion.use")) {
                player.sendMessage("§cYou don't have permission to use this command!");
                return true;
            }
            
            stackPotions(player);
            return true;
        });
        
        Objects.requireNonNull(getCommand("bonkpotion")).setExecutor((sender, command, label, args) -> {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("bonkpotion.admin")) {
                    sender.sendMessage("§cYou don't have permission to reload the configuration!");
                    return true;
                }
                
                loadConfig();
                sender.sendMessage("§aBonkPotion configuration reloaded!");
                return true;
            }
            
            sender.sendMessage("§6BonkPotion v" + getDescription().getVersion() + 
                             " by " + String.join(", ", getDescription().getAuthors()));
            if (sender.hasPermission("bonkpotion.admin")) {
                sender.sendMessage("§7Use §e/bonkpotion reload §7to reload the configuration.");
            }
            return true;
        });
    }
    
    private void stackPotions(Player player) {
        UUID playerId = player.getUniqueId();
        
        if (!processingPlayers.add(playerId)) {
            player.sendMessage("§cPlease wait until the current operation is complete!");
            return;
        }
        
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    int stacked = processInventory(player);
                    
                    Bukkit.getScheduler().runTask(BonkPotion.this, () -> {
                        if (stacked > 0) {
                            player.sendMessage("§aStacked §e" + stacked + " potions§a in your inventory!");
                        } else {
                            player.sendMessage("§eNo stackable potions found in your inventory!");
                        }
                        processingPlayers.remove(playerId);
                    });
                } catch (Exception e) {
                    getLogger().warning("Error processing potion stack: " + e.getMessage());
                    processingPlayers.remove(playerId);
                }
            }
        }.runTaskAsynchronously(this);
    }
    
    private int processInventory(Player player) {
        Inventory inventory = player.getInventory();
        Map<String, List<ItemStack>> potionMap = new HashMap<>();
        int totalStacked = 0;
        int remainingPotions = maxPotionsToStack > 0 ? maxPotionsToStack : Integer.MAX_VALUE;
        
        // First pass: collect all potions
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isPotion(item)) {
                String key = getPotionKey(item);
                potionMap.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
            }
        }
        
        // Clear inventory of potions
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (isPotion(item)) {
                inventory.setItem(i, null);
            }
        }
        
        // Second pass: stack potions
        for (List<ItemStack> potionGroup : potionMap.values()) {
            if (potionGroup.size() <= 1) {
                // Put back single items
                for (ItemStack item : potionGroup) {
                    addItemSafely(inventory, item);
                }
                continue;
            }
            
            int totalAmount = potionGroup.stream().mapToInt(ItemStack::getAmount).sum();
            ItemStack template = potionGroup.get(0).clone();
            
            // Calculate how many full stacks we can make
            int fullStacks = totalAmount / 16;
            int remainder = totalAmount % 16;
            
            // Add full stacks
            int stacksToAdd = Math.min(fullStacks, remainingPotions / 16);
            for (int i = 0; i < stacksToAdd && remainingPotions >= 16; i++) {
                ItemStack stack = template.clone();
                stack.setAmount(16);
                if (addItemSafely(inventory, stack)) {
                    totalStacked += 16;
                    remainingPotions -= 16;
                } else {
                    // If we can't add more, stop trying
                    remainingPotions = 0;
                    break;
                }
            }
            
            // Add remainder if there's space and we haven't reached the limit
            if (remainder > 0 && remainingPotions > 0) {
                int toAdd = Math.min(remainder, remainingPotions);
                ItemStack stack = template.clone();
                stack.setAmount(toAdd);
                if (addItemSafely(inventory, stack)) {
                    totalStacked += toAdd;
                    remainingPotions -= toAdd;
                }
            }
            
            if (remainingPotions <= 0) break;
        }
        
        return totalStacked;
    }
    
    private String getPotionKey(ItemStack item) {
        StringBuilder key = new StringBuilder(item.getType().name());
        
        if (!item.hasItemMeta()) {
            return key.toString();
        }
        
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof PotionMeta)) {
            return key.toString();
        }
        
        PotionMeta potionMeta = (PotionMeta) meta;
        
        try {
            // Handle color if available (1.20.5+)
            try {
                if (potionMeta.hasColor()) {
                    key.append(":color:").append(potionMeta.getColor());
                }
            } catch (NoSuchMethodError ignored) {
                // Color method not available in this version
            }
            
            // Handle base potion data (pre-1.20.5)
            if (getBasePotionDataMethod != null) {
                try {
                    Object basePotionData = getBasePotionDataMethod.invoke(potionMeta);
                    if (basePotionData != null) {
                        Method getType = basePotionData.getClass().getMethod("getType");
                        Object type = getType.invoke(basePotionData);
                        key.append(":base:").append(type);
                    }
                } catch (Exception ignored) {
                    // Method not available or failed
                }
            }
            
            // Handle custom effects
            try {
                Collection<PotionEffect> effects;
                if (getEffectsMethod != null) {
                    //noinspection unchecked
                    effects = (Collection<PotionEffect>) getEffectsMethod.invoke(potionMeta);
                } else {
                    effects = potionMeta.getCustomEffects();
                }
                
                if (effects != null && !effects.isEmpty()) {
                    for (PotionEffect effect : effects) {
                        key.append(":").append(effect.getType().getName())
                           .append("-").append(effect.getAmplifier())
                           .append("-").append(effect.getDuration());
                    }
                }
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to get potion effects", e);
            }
            
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error processing potion metadata", e);
        }
        
        return key.toString();
    }
    
    private boolean isPotion(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        
        Material type = item.getType();
        String typeName = type.name();
        
        // Check if it's a potion, splash potion, lingering potion, or tipped arrow
        return type == Material.POTION || 
               type == Material.SPLASH_POTION || 
               type == Material.LINGERING_POTION ||
               type == Material.TIPPED_ARROW ||
               typeName.endsWith("_POTION") ||
               typeName.endsWith("_SPLASH_POTION") ||
               typeName.endsWith("_LINGERING_POTION") ||
               typeName.endsWith("_TIPPED_ARROW");
    }
    
    private boolean addItemSafely(Inventory inventory, ItemStack item) {
        if (item == null) return false;
        
        // Try to add to existing stacks first
        for (ItemStack content : inventory.getStorageContents()) {
            if (content != null && content.isSimilar(item) && content.getAmount() < content.getMaxStackSize()) {
                int space = content.getMaxStackSize() - content.getAmount();
                int toAdd = Math.min(space, item.getAmount());
                content.setAmount(content.getAmount() + toAdd);
                item.setAmount(item.getAmount() - toAdd);
                
                if (item.getAmount() <= 0) {
                    return true;
                }
            }
        }
        
        // Add to empty slots
        return inventory.addItem(item).isEmpty();
    }
    
    @Override
    public void onDisable() {
        getLogger().info("BonkPotion has been disabled!");
    }
}
