package com.dillyysmp.randomspawn;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public final class RandomSpawnPlugin extends JavaPlugin implements Listener {

    private Set<Material> unsafeBlocks;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadUnsafeBlocks();
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("DillyyRandomSpawn enabled.");
    }

    private void loadUnsafeBlocks() {
        unsafeBlocks = EnumSet.noneOf(Material.class);
        for (String name : getConfig().getStringList("unsafe-blocks")) {
            try {
                unsafeBlocks.add(Material.valueOf(name.trim().toUpperCase()));
            } catch (IllegalArgumentException ex) {
                getLogger().warning("Unknown material in unsafe-blocks: " + name);
            }
        }
    }

    // ---------- Commands ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadUnsafeBlocks();
            sender.sendMessage(ChatColor.GREEN + "DillyyRandomSpawn config reloaded.");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Only players can use /rspawn without arguments.");
            return true;
        }

        Location loc = findSafeLocation(player.getWorld());
        if (loc == null) {
            player.sendMessage(ChatColor.RED + "Couldn't find a safe spot, try again.");
            return true;
        }
        player.teleport(loc);
        player.sendMessage(ChatColor.GREEN + "Teleported to a random location.");
        return true;
    }

    // ---------- Events ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("spawn-on-first-join", true)) return;

        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        if (player.hasPermission("randomspawn.bypass")) return;

        World world = getTargetWorld();
        if (world == null || !player.getWorld().equals(world)) return;

        teleportPlayerSafely(player, world);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        if (!getConfig().getBoolean("spawn-on-death-without-bed", true)) return;

        Player player = event.getPlayer();
        if (player.hasPermission("randomspawn.bypass")) return;

        // If the player has a valid bed/anchor respawn location, don't touch it - let vanilla handle it.
        if (event.isBedSpawn() || event.isAnchorSpawn()) return;

        World world = getTargetWorld();
        if (world == null) return;

        Location safe = findSafeLocation(world);
        if (safe != null) {
            event.setRespawnLocation(safe);
        }
        // If no safe spot was found, falls back to whatever vanilla had set (usually world spawn).
    }

    // ---------- Helpers ----------

    private World getTargetWorld() {
        String name = getConfig().getString("world", "world");
        return getServer().getWorld(name);
    }

    private void teleportPlayerSafely(Player player, World world) {
        Location safe = findSafeLocation(world);
        if (safe == null) {
            getLogger().warning("Could not find a safe random location for " + player.getName() + ", leaving at default spawn.");
            return;
        }
        player.teleport(safe);
        String msg = getConfig().getString("message", "");
        if (msg != null && !msg.isEmpty()) {
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    /**
     * Picks a random safe surface location within the world border
     * (and optional respawn-radius), using a spiral-ish retry approach.
     */
    private Location findSafeLocation(World world) {
        WorldBorder border = world.getWorldBorder();
        double borderRadius = border.getSize() / 2.0;
        Location center = border.getCenter();

        double configuredRadius = getConfig().getDouble("respawn-radius", 0);
        double radius = (configuredRadius > 0) ? Math.min(configuredRadius, borderRadius) : borderRadius;

        int maxAttempts = getConfig().getInt("max-attempts", 50);
        ThreadLocalRandom rand = ThreadLocalRandom.current();

        for (int i = 0; i < maxAttempts; i++) {
            double angle = rand.nextDouble() * 2 * Math.PI;
            double dist = rand.nextDouble() * radius;
            int x = (int) (center.getX() + Math.cos(angle) * dist);
            int z = (int) (center.getZ() + Math.sin(angle) * dist);

            int y = world.getHighestBlockYAt(x, z);
            Material ground = world.getBlockAt(x, y, z).getType();
            Material above = world.getBlockAt(x, y + 1, z).getType();

            if (unsafeBlocks.contains(ground) || unsafeBlocks.contains(above)) {
                continue;
            }
            if (ground == Material.AIR || ground == Material.VOID_AIR) {
                continue; // likely void or an unloaded/ungenerated area
            }
            if (!ground.isSolid()) {
                continue;
            }

            return new Location(world, x + 0.5, y + 1, z + 0.5);
        }

        return null; // caller decides the fallback
    }
}
