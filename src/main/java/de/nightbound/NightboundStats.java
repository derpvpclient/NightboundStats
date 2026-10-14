package de.nightbound;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;

public class NightboundStats extends JavaPlugin implements Listener {
    private HttpServer http;
    private volatile long dayOffset;
    private double dayFactor = 1.0, slowAcc = 0;
    private long lastTime = -1;
    private volatile String json = "{}";
    private volatile JsonObject boards = new JsonObject(), players = new JsonObject();
    private volatile double hours, totalDeaths, totalBlocks, totalKm;
    private final Deque<JsonObject> deaths = new ConcurrentLinkedDeque<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    private static final String[] KEYS = {"playtime", "deaths", "mined", "mobs", "distance", "fish", "jumps", "crafted", "flight", "trades", "alive", "bred", "sleep", "damage", "creeper"};

    @Override public void onEnable() {
        saveDefaultConfig();
        dayOffset = getConfig().getLong("day-offset", 0L);
        dayFactor = Math.max(1.0, Math.min(10.0, getConfig().getDouble("day-length-factor", 1.5)));
        getLogger().info("Tageslaenge: x" + dayFactor + " (1 Tag = " + Math.round(20 * dayFactor) + " Minuten)");
        if (!new File(getDataFolder(), "stats.html").exists()) saveResource("stats.html", false);
        getServer().getPluginManager().registerEvents(this, this);
        try {
            http = HttpServer.create(new InetSocketAddress(getConfig().getInt("port", 8123)), 0);
            http.createContext("/stats.json", ex -> {
                byte[] b = json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                ex.sendResponseHeaders(200, b.length);
                try (var o = ex.getResponseBody()) { o.write(b); }
            });
            http.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                if (!path.equals("/") && !path.equals("/index.html")) { ex.sendResponseHeaders(404, -1); ex.close(); return; }
                byte[] b = Files.readAllBytes(new File(getDataFolder(), "stats.html").toPath());
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                try (var o = ex.getResponseBody()) { o.write(b); }
            });
            http.start();
        } catch (Exception e) {
            getLogger().severe("Webserver konnte nicht starten: " + e.getMessage());
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::computeBoards, 20L, 20L * 60);
        Bukkit.getScheduler().runTaskTimer(this, this::publish, 40L, 20L * 10);
        Bukkit.getScheduler().runTaskTimer(this, this::checkSeats, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, this::slowTime, 1L, 1L);
    }

    @Override public void onDisable() {
        if (http != null) http.stop(0);
        seats.values().forEach(ArmorStand::remove);
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!cmd.getName().equalsIgnoreCase("resetday")) return false;
        if (!sender.hasPermission("nightboundstats.resetday")) {
            sender.sendMessage(Component.text("Dazu hast du keine Berechtigung. Nur Operatoren dürfen /resetday nutzen.", NamedTextColor.RED));
            return true;
        }
        Bukkit.getWorlds().get(0).setFullTime(0L);
        dayOffset = 0L;
        lastTime = -1;
        getConfig().set("day-offset", 0L);
        saveConfig();
        sender.sendMessage(Component.text("Zeit auf 0 gesetzt wie in einer neuen Welt. Die Stats-Seite zeigt jetzt Tag 1.", NamedTextColor.GREEN));
        if (sender instanceof Player) getLogger().info(sender.getName() + " hat /resetday ausgeführt.");
        return true;
    }

    @EventHandler public void onDeath(PlayerDeathEvent e) {
        JsonObject d = new JsonObject();
        d.addProperty("name", e.getPlayer().getName());
        if (e.getPlayer().getKiller() != null) d.addProperty("killer", e.getPlayer().getKiller().getName());
        var msg = e.deathMessage();
        d.addProperty("text", msg == null ? "" : PlainTextComponentSerializer.plainText().serialize(msg));
        d.addProperty("time", System.currentTimeMillis());
        deaths.addFirst(d);
        while (deaths.size() > 10) deaths.removeLast();
    }

    private void publish() {
        JsonObject o = new JsonObject();
        JsonArray on = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) on.add(p.getName());
        JsonArray dj = new JsonArray();
        deaths.forEach(dj::add);
        o.add("online", on);
        o.add("recentDeaths", dj);
        o.add("boards", boards);
        o.addProperty("hours", Math.round(hours));
        o.addProperty("totalDeaths", Math.round(totalDeaths));
        o.addProperty("totalBlocks", Math.round(totalBlocks));
        o.addProperty("totalKm", Math.round(totalKm));
        JsonObject sv = new JsonObject();
        var w = Bukkit.getWorlds().get(0);
        long t = w.getTime();
        sv.addProperty("tps", Math.round(Math.min(20.0, Bukkit.getTPS()[0]) * 10) / 10.0);
        sv.addProperty("night", t >= 13000 && t < 23000);
        sv.addProperty("weather", w.isThundering() ? "thunder" : w.hasStorm() ? "rain" : "clear");
        sv.addProperty("day", Math.max(0L, w.getFullTime() - dayOffset) / 24000 + 1);
        sv.addProperty("max", Bukkit.getMaxPlayers());
        o.add("server", sv);
        o.add("players", players);
        json = o.toString();
    }

    private void computeBoards() {
        File dir = findStatsDir();
        if (dir == null) {
            if (!warned) {
                warned = true;
                getLogger().warning("Kein Stats-Ordner gefunden (erwartet: <welt>/players/stats). Hat schon jemand auf dem Server gespielt?");
            }
            return;
        }
        if (!dir.equals(statsDir)) {
            statsDir = dir;
            getLogger().info("Stats-Ordner: " + dir.getAbsolutePath());
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return;
        Map<String, double[]> m = new HashMap<>();
        Map<String, UUID> ids = new HashMap<>();
        Map<String, JsonObject> extra = new HashMap<>();
        for (File f : files) {
            try {
                UUID id = UUID.fromString(f.getName().replace(".json", ""));
                String name = Bukkit.getOfflinePlayer(id).getName();
                if (name == null) continue;
                JsonObject s = JsonParser.parseString(Files.readString(f.toPath()))
                        .getAsJsonObject().getAsJsonObject("stats");
                JsonObject c = sub(s, "minecraft:custom"), mined = sub(s, "minecraft:mined");
                double dist = 0, blocks = 0;
                for (var e : c.entrySet())
                    if (e.getKey().endsWith("_one_cm") && !e.getKey().contains("fall")) dist += e.getValue().getAsDouble();
                for (var e : mined.entrySet()) blocks += e.getValue().getAsDouble();
                double ticks = num(c, "minecraft:play_time") + num(c, "minecraft:play_one_minute");
                double cr = 0;
                for (var e : sub(s, "minecraft:crafted").entrySet()) cr += e.getValue().getAsDouble();
                m.put(name, new double[]{ticks / 72000.0, num(c, "minecraft:deaths"), blocks,
                        num(c, "minecraft:mob_kills"), dist / 100000.0, num(c, "minecraft:fish_caught"),
                        num(c, "minecraft:jump"), cr, num(c, "minecraft:aviate_one_cm") / 100000.0,
                        num(c, "minecraft:traded_with_villager"), num(c, "minecraft:time_since_death") / 72000.0,
                        num(c, "minecraft:animals_bred"), num(c, "minecraft:sleep_in_bed"),
                        num(c, "minecraft:damage_dealt") / 20.0, num(sub(s, "minecraft:killed_by"), "minecraft:creeper")});
                ids.put(name, id);
                JsonObject x = new JsonObject();
                x.addProperty("joins", num(c, "minecraft:leave_game"));
                x.addProperty("taken", Math.round(num(c, "minecraft:damage_taken") / 20.0 * 10) / 10.0);
                x.addProperty("chests", num(c, "minecraft:open_chest"));
                top(x, "block", mined);
                top(x, "mob", sub(s, "minecraft:killed"));
                extra.put(name, x);
            } catch (Exception ignored) {}
        }
        JsonObject b = new JsonObject();
        for (int i = 0; i < KEYS.length; i++) {
            final int k = i;
            JsonArray arr = new JsonArray();
            m.entrySet().stream()
                    .filter(e -> e.getValue()[k] > 0)
                    .sorted((a, c2) -> Double.compare(c2.getValue()[k], a.getValue()[k]))
                    .limit(10).forEach(e -> {
                        JsonObject r = new JsonObject();
                        r.addProperty("name", e.getKey());
                        r.addProperty("value", Math.round(e.getValue()[k] * 10) / 10.0);
                        arr.add(r);
                    });
            b.add(KEYS[i], arr);
        }
        hours = m.values().stream().mapToDouble(v -> v[0]).sum();
        totalDeaths = m.values().stream().mapToDouble(v -> v[1]).sum();
        totalBlocks = m.values().stream().mapToDouble(v -> v[2]).sum();
        totalKm = m.values().stream().mapToDouble(v -> v[4]).sum();
        JsonObject pl = new JsonObject();
        m.forEach((n, v) -> {
            JsonObject p = new JsonObject();
            JsonArray a = new JsonArray();
            for (double x : v) a.add(Math.round(x * 10) / 10.0);
            p.add("v", a);
            var op = Bukkit.getOfflinePlayer(ids.get(n));
            p.addProperty("first", op.getFirstPlayed());
            p.addProperty("last", op.getLastPlayed());
            if (extra.get(n) != null) p.add("x", extra.get(n));
            pl.add(n, p);
        });
        players = pl;
        boards = b;
    }

    // ---- Sitzen auf Treppen und Slabs (Rechtsklick mit leerer Hand, Shift zum Aufstehen) ----
    @EventHandler(ignoreCancelled = true)
    public void onSit(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b == null || p.isSneaking() || p.isInsideVehicle()) return;
        if (!p.getInventory().getItemInMainHand().getType().isAir()) return;
        if (!isSeat(b) || !b.getRelative(BlockFace.UP).isPassable()) return;
        for (ArmorStand o : seats.values()) if (o.getLocation().getBlock().equals(b)) return;
        Location loc = b.getLocation().add(0.5, 0.5, 0.5); // 0.5 = Oberkante; bei Bedarf Höhe anpassen
        ArmorStand st = b.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setInvisible(true); a.setMarker(true); a.setGravity(false);
            a.setSilent(true); a.setInvulnerable(true); a.setPersistent(false);
        });
        st.addPassenger(p);
        seats.put(p.getUniqueId(), st);
        e.setCancelled(true);
    }

    // Macht den Tag laenger: bremst die Weltzeit, ohne Gamerules anzufassen.
    // Greift nur, wenn die Zeit pro Tick genau um 1 weiterlaeuft (nicht beim Schlafen oder bei /time).
    private void slowTime() {
        if (dayFactor <= 1.0) return;
        var w = Bukkit.getWorlds().get(0);
        long now = w.getFullTime(), t = now;
        if (lastTime >= 0 && now - lastTime == 1) {
            slowAcc += 1.0 - 1.0 / dayFactor;
            while (slowAcc >= 1.0) { t -= 1; slowAcc -= 1.0; }
            if (t != now) w.setFullTime(t);
        }
        lastTime = t;
    }

    private void checkSeats() {
        seats.entrySet().removeIf(en -> {
            ArmorStand st = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            boolean ok = p != null && st.isValid() && st.getPassengers().contains(p) && isSeat(st.getLocation().getBlock());
            if (ok) return false;
            Location loc = st.getLocation();
            st.remove();
            if (p != null && p.isOnline() && !p.isDead()) {
                loc.setYaw(p.getLocation().getYaw());
                loc.setPitch(p.getLocation().getPitch());
                p.teleport(loc);
            }
            return true;
        });
    }

    private static boolean isSeat(Block b) {
        var d = b.getBlockData();
        if (d instanceof Stairs s) return s.getHalf() == Bisected.Half.BOTTOM;
        if (d instanceof Slab s) return s.getType() == Slab.Type.BOTTOM;
        return false;
    }

    private volatile boolean warned;
    private volatile File statsDir;

    private File findStatsDir() {
        var world = Bukkit.getWorlds().get(0);
        List<File> bases = new ArrayList<>();
        File wf = world.getWorldFolder();
        for (int i = 0; i < 5 && wf != null; i++, wf = wf.getParentFile()) bases.add(wf);
        bases.add(new File(Bukkit.getWorldContainer(), world.getName()));
        for (String sub : new String[]{"players/stats", "stats"})
            for (File base : bases) {
                File d = new File(base, sub);
                if (d.isDirectory()) return d;
            }
        return null;
    }

    private static void top(JsonObject x, String k, JsonObject o) {
        String best = null;
        double bv = 0;
        for (var e : o.entrySet()) {
            double v = e.getValue().getAsDouble();
            if (v > bv) { bv = v; best = e.getKey(); }
        }
        if (best != null) {
            x.addProperty(k, best.replace("minecraft:", ""));
            x.addProperty(k + "N", bv);
        }
    }

    private static JsonObject sub(JsonObject o, String k) {
        return o != null && o.has(k) ? o.getAsJsonObject(k) : new JsonObject();
    }
    private static double num(JsonObject o, String k) { return o.has(k) ? o.get(k).getAsDouble() : 0; }
}
