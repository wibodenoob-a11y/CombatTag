package dev.combattag;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CombatPlugin extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY =
            LegacyComponentSerializer.builder().character('&').hexColors().build();

    private static final class State {
        long expires;
        final BossBar bar;
        int lastSecond = -1;
        State(long expires, BossBar bar) { this.expires = expires; this.bar = bar; }
    }

    private final Map<UUID, State> states = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<String> allowedCommands = new HashSet<>();

    private long durationMs;
    private long cooldownMs;
    private boolean refreshOnHit, endOnKill, ejectOnTag, useBossBar, useActionBar, useSounds;
    private int titleLastSeconds;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        getServer().getPluginManager().registerEvents(new CombatListener(this), this);
        CombatCommand cmd = new CombatCommand(this);
        getCommand("combat").setExecutor(cmd);
        getCommand("combat").setTabCompleter(cmd);

        // one light task, 5 times a second, only touches players that are in combat
        getServer().getScheduler().runTaskTimer(this, this::tickAll, 20L, 4L);

        try {
            for (World w : getServer().getWorlds()) {
                if (Boolean.TRUE.equals(w.getGameRuleValue(GameRule.KEEP_INVENTORY))) {
                    getLogger().warning("keepInventory is ON in world '" + w.getName() + "'. CombatTag still drops "
                            + "the loot of players who die in combat, but everyone else keeps their items on death. "
                            + "Run /gamerule keepInventory false if that is not intended.");
                }
            }
        } catch (Throwable ignored) { }
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, State> e : states.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) p.hideBossBar(e.getValue().bar);
        }
        states.clear();
    }

    public void loadSettings() {
        reloadConfig();
        var c = getConfig();
        durationMs = Math.max(1, c.getInt("combat.duration-seconds", 20)) * 1000L;
        cooldownMs = Math.max(0, c.getInt("combat.retag-cooldown-seconds", 3)) * 1000L;
        refreshOnHit = c.getBoolean("combat.refresh-on-hit", true);
        endOnKill = c.getBoolean("combat.end-on-kill", true);
        ejectOnTag = c.getBoolean("combat.eject-from-vehicle-on-tag", true);
        useBossBar = c.getBoolean("countdown.bossbar", true);
        useActionBar = c.getBoolean("countdown.actionbar", true);
        useSounds = c.getBoolean("countdown.sounds", true);
        titleLastSeconds = c.getInt("countdown.title-last-seconds", 5);
        allowedCommands.clear();
        for (String s : c.getStringList("combat.allowed-commands")) {
            allowedCommands.add(s.toLowerCase(Locale.ROOT).replaceFirst("^/", ""));
        }
    }

    // ------------------------------------------------------------ public API for the listener/command

    /** OPs and anyone with combattag.bypass are never restricted. */
    public boolean isBypass(Player p) { return p.isOp() || p.hasPermission("combattag.bypass"); }
    public boolean inCombat(Player p) { return states.containsKey(p.getUniqueId()); }
    public boolean endOnKill() { return endOnKill; }
    public Set<String> allowedCommands() { return allowedCommands; }

    public int secondsLeft(Player p) {
        State s = states.get(p.getUniqueId());
        return s == null ? 0 : (int) Math.ceil((s.expires - System.currentTimeMillis()) / 1000.0);
    }

    public Component msg(String key, String... repl) {
        String raw = getConfig().getString("messages." + key, "&cMissing message: " + key);
        for (int i = 0; i + 1 < repl.length; i += 2) raw = raw.replace(repl[i], repl[i + 1]);
        return LEGACY.deserialize(raw);
    }

    public void tag(Player p) {
        if (!p.isOnline() || isBypass(p)) return;
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();

        State s = states.get(id);
        if (s != null) {
            if (refreshOnHit) s.expires = now + durationMs;
            return;
        }
        Long cd = cooldowns.get(id);
        if (cd != null && now < cd) return;

        BossBar bar = BossBar.bossBar(Component.empty(), 1.0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        states.put(id, new State(now + durationMs, bar));
        if (useBossBar) p.showBossBar(bar);

        p.sendMessage(msg("start", "%seconds%", String.valueOf(durationMs / 1000)));
        if (useSounds) p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.25f, 1.6f);
        if (ejectOnTag && p.isInsideVehicle()) p.leaveVehicle();
    }

    public void end(UUID id, boolean notify) {
        State s = states.remove(id);
        if (s == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.hideBossBar(s.bar);
            if (notify) {
                p.sendMessage(msg("end"));
                p.sendActionBar(Component.empty());
                if (useSounds) p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.4f);
            }
        }
        cooldowns.put(id, System.currentTimeMillis() + cooldownMs);
    }

    /** Wipe all state (used on quit / join so combat never persists). */
    public void forget(UUID id) {
        State s = states.remove(id);
        if (s != null) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(s.bar);
        }
        cooldowns.remove(id);
    }

    // ------------------------------------------------------------ countdown

    private void tickAll() {
        if (states.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();

        for (Map.Entry<UUID, State> e : states.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            State s = e.getValue();
            long left = s.expires - now;
            if (p == null || left <= 0) { expired.add(e.getKey()); continue; }
            update(p, s, left);
        }
        for (UUID id : expired) end(id, true);
    }

    private void update(Player p, State s, long leftMs) {
        float progress = (float) Math.max(0.0, Math.min(1.0, leftMs / (double) durationMs));
        int seconds = (int) Math.ceil(leftMs / 1000.0);

        s.bar.progress(progress);
        s.bar.color(progress > 0.66f ? BossBar.Color.GREEN : progress > 0.33f ? BossBar.Color.YELLOW : BossBar.Color.RED);

        if (seconds == s.lastSecond) return;
        s.lastSecond = seconds;
        String sec = String.valueOf(seconds);

        s.bar.name(msg("bossbar", "%seconds%", sec));
        if (useActionBar) p.sendActionBar(msg("actionbar", "%seconds%", sec));

        if (titleLastSeconds > 0 && seconds <= titleLastSeconds) {
            p.showTitle(Title.title(
                    LEGACY.deserialize("&c&l" + seconds),
                    msg("title-sub"),
                    Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(100))));
            if (useSounds) p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.0f + (titleLastSeconds - seconds) * 0.1f);
        }
    }
}
