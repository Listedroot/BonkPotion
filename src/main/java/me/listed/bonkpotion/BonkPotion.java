package me.listed.bonkpotion;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class BonkPotion extends JavaPlugin {
    
    private File configFile;
    private FileConfiguration config;
    private int maxPotionsToStack;
    private final Set<UUID> processingPlayers = Collections.synchronizedSet(new HashSet<>());
    
    @Override
    public void onEnable() {
        // Create config if it doesn't exist
        saveDefaultConfig();
        loadConfig();
        
        // Register commands
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
                
                reloadConfig();
                loadConfig();
                sender.sendMessage("§aBonkPotion configuration reloaded!");
                return true;
            }
            
            sender.sendMessage("§6BonkPotion v" + getDescription().getVersion() + " by " + String.join(", ", getDescription().getAuthors()));
            if (sender.hasPermission("bonkpotion.admin")) {
                sender.sendMessage("§7Use §e/bonkpotion reload §7to reload the configuration.");
            }
            return true;
        });
        
        getLogger().info("BonkPotion has been enabled!");
    }
    
    private void loadConfig() {
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }
        
        config = YamlConfiguration.loadConfiguration(configFile);
        maxPotionsToStack = config.getInt("max-potions-to-stack", 64);
    }
    
    private void stackPotions(Player player) {
        UUID playerId = player.getUniqueId();
        
        // Prevent multiple simultaneous executions for the same player
        if (processingPlayers.contains(playerId)) {
            player.sendMessage("§cPlease wait until the current operation is complete!");
            return;
        }
        
        processingPlayers.add(playerId);
        
        // Run the stacking asynchronously
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    int stacked = stackPlayerPotions(player);
                    
                    // Schedule the message to be sent on the main thread
                    Bukkit.getScheduler().runTask(BonkPotion.this, () -> {
                        if (stacked > 0) {
                            player.sendMessage("§aStacked §e" + stacked + " potions§a in your inventory!");
                        } else {
                            player.sendMessage("§eNo potions to stack found in your inventory!");
                        }
                        processingPlayers.remove(playerId);
                    });
                } catch (Exception e) {
                    getLogger().warning("Error while stacking potions for " + player.getName() + ": " + e.getMessage());
                    processingPlayers.remove(playerId);
                }
            }
        }.runTaskAsynchronously(this);
    }
    
    private int stackPlayerPotions(Player player) {
        Inventory inventory = player.getInventory();
        Map<ItemStack, List<Integer>> potionMap = new HashMap<>();
        int totalStacked = 0;
        int remainingPotions = maxPotionsToStack;
        
        // First pass: collect all potions and their positions
        for (int i = 0; i < inventory.getSize(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && isPotion(item.getType())) {
                ItemStack key = new ItemStack(item);
                key.setAmount(1);
                
                potionMap.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
            }
        }
        
        // Second pass: stack potions
        for (Map.Entry<ItemStack, List<Integer>> entry : potionMap.entrySet()) {
            ItemStack potionType = entry.getKey();
            List<Integer> slots = entry.getValue();
            
            if (slots.size() <= 1) continue;
            
            int totalAmount = 0;
            for (int slot : slots) {
                totalAmount += inventory.getItem(slot).getAmount();
            }
            
            // Calculate how many full stacks we can make
            int fullStacks = totalAmount / 16;
            int remainder = totalAmount % 16;
            
            // Clear all slots first
            for (int slot : slots) {
                inventory.setItem(slot, null);
            }
            
            // Add full stacks
            int stacksToAdd = Math.min(fullStacks, remainingPotions / 16);
            if (stacksToAdd > 0) {
                for (int i = 0; i < stacksToAdd; i++) {
                    ItemStack stack = potionType.clone();
                    stack.setAmount(16);
                    HashMap<Integer, ItemStack> leftover = inventory.addItem(stack);
                    if (!leftover.isEmpty()) {
                        break; // No more space
                    }
                    totalStacked += 16;
                    remainingPotions -= 16;
                }
            }
            
            // Add remainder if there's space and we haven't reached the limit
            if (remainder > 0 && remainingPotions > 0) {
                int toAdd = Math.min(remainder, remainingPotions);
                ItemStack stack = potionType.clone();
                stack.setAmount(toAdd);
                HashMap<Integer, ItemStack> leftover = inventory.addItem(stack);
                if (leftover.isEmpty()) {
                    totalStacked += toAdd;
                    remainingPotions -= toAdd;
                }
            }
            
            if (remainingPotions <= 0) break;
        }
        
        return totalStacked;
    }
    
    private boolean isPotion(Material material) {
        String name = material.name().toLowerCase();
        return name.contains("potion") || name.contains("splash_potion") || name.contains("lingering_potion") || 
               name.contains("water_bottle") || name.contains("harming") || name.contains("healing") ||
               name.contains("poison") || name.contains("regeneration") || name.contains("strength") ||
               name.contains("weakness") || name.contains("slowness") || name.contains("swiftness") ||
               name.contains("fire_resistance") || name.contains("invisibility") || name.contains("leaping") ||
               name.contains("night_vision") || name.contains("slow_falling") || name.contains("turtle_master") ||
               name.contains("water_breathing") || name.contains("luck") || name.contains("unluck") ||
               name.contains("strong") || name.contains("long") || name.contains("thick") || name.contains("mundane") ||
               name.contains("awkward") || name.contains("turtle_master") || name.contains("slow_falling");
    }
    
    @Override
    public void onDisable() {
        getLogger().info("BonkPotion has been disabled!");
    }
}
