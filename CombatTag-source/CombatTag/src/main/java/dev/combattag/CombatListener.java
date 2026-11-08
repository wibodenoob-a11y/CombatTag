package dev.combattag;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Locale;

public final class CombatListener implements Listener {

    private final CombatPlugin plugin;

    public CombatListener(CombatPlugin plugin) { this.plugin = plugin; }

    private Player resolvePlayer(Entity damager) {
        if (damager instanceof Player p) return p;
        if (damager instanceof Projectile pr && pr.getShooter() instanceof Player p) return p;
        return null;
    }

    /**
     * MONITOR + ignoreCancelled: only hits that actually go through count.
     * A hit cancelled by SafeZone Pro (or any other protection plugin) never starts combat.
     * Mobs/animals are ignored because the victim must be a Player.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player victim)) return;
        Player attacker = resolvePlayer(e.getDamager());
        if (attacker == null || attacker.equals(victim)) return;
        plugin.tag(attacker);
        plugin.tag(victim);
    }

    /** Non-OPs can't run commands while tagged. OPs / combattag.bypass are never touched. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!plugin.inCombat(p) || plugin.isBypass(p)) return;

        String raw = e.getMessage().startsWith("/") ? e.getMessage().substring(1) : e.getMessage();
        String label = raw.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);     // /minecraft:tp -> tp
        if (plugin.allowedCommands().contains(label)) return;

        e.setCancelled(true);
        p.sendMessage(plugin.msg("command-blocked"));
    }

    /** No boats / minecarts / mounts while in combat (anywhere). */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMount(EntityMountEvent e) {
        if (e.getEntity() instanceof Player p && plugin.inCombat(p) && !plugin.isBypass(p)) {
            e.setCancelled(true);
            p.sendActionBar(plugin.msg("vehicle-blocked"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent e) {
        if (e.getEntered() instanceof Player p && plugin.inCombat(p) && !plugin.isBypass(p)) {
            e.setCancelled(true);
            p.sendActionBar(plugin.msg("vehicle-blocked"));
        }
    }

    /** Dying in combat: everything drops, even if keepInventory is on. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent e) {
        Player p = e.getEntity();
        if (plugin.inCombat(p) && !plugin.isBypass(p) && e.getKeepInventory()) {
            e.setKeepInventory(false);
            List<ItemStack> drops = e.getDrops();
            for (ItemStack it : p.getInventory().getContents()) {   // storage + armor + offhand
                if (it != null && !it.getType().isAir()) drops.add(it.clone());
            }
            p.getInventory().clear();
        }

        Player killer = p.getKiller();
        if (killer != null && plugin.endOnKill()) plugin.end(killer.getUniqueId(), true);
        plugin.end(p.getUniqueId(), false);
    }

    /** Logging out in combat: all items drop on the ground. Nothing is lost. */
    @EventHandler(priority = EventPriority.NORMAL)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        if (plugin.inCombat(p) && !plugin.isBypass(p) && !Bukkit.isStopping()) {
            p.closeInventory();                       // returns crafting-grid items to the inventory
            Location loc = p.getLocation();
            ItemStack cursor = p.getItemOnCursor();
            if (cursor != null && !cursor.getType().isAir()) {
                loc.getWorld().dropItemNaturally(loc, cursor.clone());
                p.setItemOnCursor(null);
            }
            for (ItemStack it : p.getInventory().getContents()) {
                if (it != null && !it.getType().isAir()) loc.getWorld().dropItemNaturally(loc, it.clone());
            }
            p.getInventory().clear();
            Bukkit.broadcast(plugin.msg("logout-broadcast", "%player%", p.getName()));
        }
        plugin.forget(p.getUniqueId());
    }

    /** Combat never persists across a relog. */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) { plugin.forget(e.getPlayer().getUniqueId()); }
}
