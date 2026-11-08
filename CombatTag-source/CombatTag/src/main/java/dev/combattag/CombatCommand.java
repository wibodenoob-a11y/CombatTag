package dev.combattag;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class CombatCommand implements TabExecutor {

    private final CombatPlugin plugin;

    public CombatCommand(CombatPlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (a.length == 0) { s.sendMessage(plugin.msg("usage")); return true; }
        switch (a[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> { plugin.loadSettings(); s.sendMessage(plugin.msg("reloaded")); }
            case "status" -> {
                Player t = target(s, a);
                if (t == null) { s.sendMessage(plugin.msg("not-found")); return true; }
                if (plugin.inCombat(t)) {
                    s.sendMessage(plugin.msg("status-in", "%player%", t.getName(), "%seconds%", String.valueOf(plugin.secondsLeft(t))));
                } else {
                    s.sendMessage(plugin.msg("status-out", "%player%", t.getName()));
                }
            }
            case "end" -> {
                Player t = a.length > 1 ? Bukkit.getPlayerExact(a[1]) : null;
                if (t == null) { s.sendMessage(plugin.msg("not-found")); return true; }
                plugin.end(t.getUniqueId(), true);
                s.sendMessage(plugin.msg("ended", "%player%", t.getName()));
            }
            default -> s.sendMessage(plugin.msg("usage"));
        }
        return true;
    }

    private Player target(CommandSender s, String[] a) {
        if (a.length > 1) return Bukkit.getPlayerExact(a[1]);
        return s instanceof Player p ? p : null;
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String label, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String x : List.of("reload", "status", "end")) if (x.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(x);
        } else if (a.length == 2 && !a[0].equalsIgnoreCase("reload")) {
            for (Player p : Bukkit.getOnlinePlayers()) if (p.getName().toLowerCase(Locale.ROOT).startsWith(a[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
        }
        return out;
    }
}
