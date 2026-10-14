package de.nightbound;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedDeque;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

public class NightboundStats extends JavaPlugin implements Listener {
    private HttpServer http;
    private DiamondBank bank;
    private volatile long dayOffset;
    private double dayFactor = 1.0;
    private double slowAcc = 0.0;
    private long lastTime = -1L;
    private volatile String json = "{}";
    private volatile JsonObject boards = new JsonObject();
    private volatile JsonObject players = new JsonObject();
    private volatile double hours;
    private volatile double totalDeaths;
    private volatile double totalBlocks;
    private volatile double totalKm;
    private final Deque<JsonObject> deaths = new ConcurrentLinkedDeque<>();
    private final Map<UUID, ArmorStand> seats = new HashMap<>();
    /** Wo der Spieler beim Aufstehen landet (vor der Treppe). */
    private final Map<UUID, Location> exits = new HashMap<>();
    private static final String[] KEYS = {"playtime", "deaths", "mined", "mobs", "distance", "fish", "jumps", "crafted", "flight", "trades", "alive", "bred", "sleep", "damage", "creeper"};
    private volatile boolean warned;
    private volatile File statsDir;

    @Override
    public void onEnable() {
        this.saveDefaultConfig();
        this.dayOffset = this.getConfig().getLong("day-offset", 0L);
        this.dayFactor = Math.max(1.0, Math.min(10.0, this.getConfig().getDouble("day-length-factor", 1.5)));
        this.getLogger().info("Tageslaenge: x" + this.dayFactor + " (1 Tag = " + Math.round(20.0 * this.dayFactor) + " Minuten)");
        if (!new File(this.getDataFolder(), "stats.html").exists()) {
            this.saveResource("stats.html", false);
        }
        this.getServer().getPluginManager().registerEvents(this, this);
        this.bank = new DiamondBank(this);
        this.getServer().getPluginManager().registerEvents(this.bank, this);
        try {
            this.http = HttpServer.create(new InetSocketAddress(this.getConfig().getInt("port", 8123)), 0);
            this.http.createContext("/stats.json", ex -> {
                byte[] b = this.json.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            });
            this.http.createContext("/", ex -> {
                String path = ex.getRequestURI().getPath();
                if (!path.equals("/") && !path.equals("/index.html")) {
                    ex.sendResponseHeaders(404, -1L);
                    ex.close();
                    return;
                }
                byte[] b = Files.readAllBytes(new File(this.getDataFolder(), "stats.html").toPath());
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream o = ex.getResponseBody()) {
                    o.write(b);
                }
            });
            this.http.start();
        } catch (Exception e) {
            this.getLogger().severe("Webserver konnte nicht starten: " + e.getMessage());
        }
        Bukkit.getScheduler().runTaskTimerAsynchronously(this, this::computeBoards, 20L, 1200L);
        Bukkit.getScheduler().runTaskTimer(this, this::publish, 40L, 200L);
        Bukkit.getScheduler().runTaskTimer(this, this::checkSeats, 2L, 2L);
        Bukkit.getScheduler().runTaskTimer(this, this::slowTime, 1L, 1L);
    }

    @Override
    public void onDisable() {
        if (this.http != null) {
            this.http.stop(0);
        }
        if (this.bank != null) {
            this.bank.saveNow();
        }
        for (Map.Entry<UUID, ArmorStand> en : this.seats.entrySet()) {
            Player sitter = Bukkit.getPlayer(en.getKey());
            Location to = this.exits.get(en.getKey());
            en.getValue().remove();
            if (sitter != null && to != null) {
                sitter.teleport(to);
            }
        }
        this.seats.clear();
        this.exits.clear();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (cmd.getName().equalsIgnoreCase("bank")) {
            return this.bank.onCommand(sender);
        }
        if (!cmd.getName().equalsIgnoreCase("resetday")) {
            return false;
        }
        if (!sender.hasPermission("nightboundstats.resetday")) {
            sender.sendMessage(Component.text("Dazu hast du keine Berechtigung. Nur Operatoren d\u00fcrfen /resetday nutzen.", NamedTextColor.RED));
            return true;
        }
        Bukkit.getWorlds().get(0).setFullTime(0L);
        this.dayOffset = 0L;
        this.lastTime = -1L;
        this.getConfig().set("day-offset", 0L);
        this.saveConfig();
        sender.sendMessage(Component.text("Zeit auf 0 gesetzt wie in einer neuen Welt. Die Stats-Seite zeigt jetzt Tag 1.", NamedTextColor.GREEN));
        if (sender instanceof Player) {
            this.getLogger().info(sender.getName() + " hat /resetday ausgef\u00fchrt.");
        }
        return true;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        JsonObject d = new JsonObject();
        d.addProperty("name", e.getPlayer().getName());
        if (e.getPlayer().getKiller() != null) {
            d.addProperty("killer", e.getPlayer().getKiller().getName());
        }
        Component msg = e.deathMessage();
        d.addProperty("text", msg == null ? "" : PlainTextComponentSerializer.plainText().serialize(msg));
        d.addProperty("time", System.currentTimeMillis());
        this.deaths.addFirst(d);
        while (this.deaths.size() > 10) {
            this.deaths.removeLast();
        }
    }

    private void publish() {
        JsonObject o = new JsonObject();
        JsonArray on = new JsonArray();
        for (Player p : Bukkit.getOnlinePlayers()) {
            on.add(p.getName());
        }
        JsonArray dj = new JsonArray();
        for (JsonObject d : this.deaths) {
            dj.add(d);
        }
        // Bestenlisten aus den Stats-Dateien + Diamantenbank (immer aktuell, kommt aus dem Speicher)
        JsonObject bd = this.boards.deepCopy();
        JsonArray bankTop = new JsonArray();
        for (DiamondBank.Row r : this.bank.top(10)) {
            JsonObject row = new JsonObject();
            row.addProperty("name", r.name());
            row.addProperty("value", r.value());
            bankTop.add(row);
        }
        bd.add("bank", bankTop);
        JsonObject bankAll = new JsonObject();
        for (DiamondBank.Row r : this.bank.all()) {
            bankAll.addProperty(r.name(), r.value());
        }
        o.add("online", on);
        o.add("recentDeaths", dj);
        o.add("boards", bd);
        o.add("bankAll", bankAll);
        o.addProperty("bankTotal", this.bank.total());
        o.addProperty("hours", Math.round(this.hours));
        o.addProperty("totalDeaths", Math.round(this.totalDeaths));
        o.addProperty("totalBlocks", Math.round(this.totalBlocks));
        o.addProperty("totalKm", Math.round(this.totalKm));
        JsonObject sv = new JsonObject();
        World w = Bukkit.getWorlds().get(0);
        long t = w.getTime();
        sv.addProperty("tps", (double) Math.round(Math.min(20.0, Bukkit.getTPS()[0]) * 10.0) / 10.0);
        sv.addProperty("night", t >= 13000L && t < 23000L);
        sv.addProperty("weather", w.isThundering() ? "thunder" : (w.hasStorm() ? "rain" : "clear"));
        sv.addProperty("day", Math.max(0L, w.getFullTime() - this.dayOffset) / 24000L + 1L);
        sv.addProperty("max", Bukkit.getMaxPlayers());
        o.add("server", sv);
        o.add("players", this.players);
        this.json = o.toString();
    }

    private void computeBoards() {
        File dir = this.findStatsDir();
        if (dir == null) {
            if (!this.warned) {
                this.warned = true;
                this.getLogger().warning("Kein Stats-Ordner gefunden (erwartet: <welt>/players/stats). Hat schon jemand auf dem Server gespielt?");
            }
            return;
        }
        if (!dir.equals(this.statsDir)) {
            this.statsDir = dir;
            this.getLogger().info("Stats-Ordner: " + dir.getAbsolutePath());
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) {
            return;
        }
        Map<String, double[]> m = new HashMap<>();
        Map<String, UUID> ids = new HashMap<>();
        Map<String, JsonObject> extra = new HashMap<>();
        for (File f : files) {
            try {
                UUID id = UUID.fromString(f.getName().replace(".json", ""));
                String name = Bukkit.getOfflinePlayer(id).getName();
                if (name == null) {
                    continue;
                }
                JsonObject s = JsonParser.parseString(Files.readString(f.toPath())).getAsJsonObject().getAsJsonObject("stats");
                JsonObject c = sub(s, "minecraft:custom");
                JsonObject mined = sub(s, "minecraft:mined");
                double dist = 0.0;
                double blocks = 0.0;
                for (Map.Entry<String, JsonElement> e2 : c.entrySet()) {
                    if (!e2.getKey().endsWith("_one_cm") || e2.getKey().contains("fall")) {
                        continue;
                    }
                    dist += e2.getValue().getAsDouble();
                }
                for (Map.Entry<String, JsonElement> e2 : mined.entrySet()) {
                    blocks += e2.getValue().getAsDouble();
                }
                double ticks = num(c, "minecraft:play_time") + num(c, "minecraft:play_one_minute");
                double cr = 0.0;
                for (Map.Entry<String, JsonElement> e3 : sub(s, "minecraft:crafted").entrySet()) {
                    cr += e3.getValue().getAsDouble();
                }
                m.put(name, new double[]{
                        ticks / 72000.0,
                        num(c, "minecraft:deaths"),
                        blocks,
                        num(c, "minecraft:mob_kills"),
                        dist / 100000.0,
                        num(c, "minecraft:fish_caught"),
                        num(c, "minecraft:jump"),
                        cr,
                        num(c, "minecraft:aviate_one_cm") / 100000.0,
                        num(c, "minecraft:traded_with_villager"),
                        num(c, "minecraft:time_since_death") / 72000.0,
                        num(c, "minecraft:animals_bred"),
                        num(c, "minecraft:sleep_in_bed"),
                        num(c, "minecraft:damage_dealt") / 20.0,
                        num(sub(s, "minecraft:killed_by"), "minecraft:creeper")});
                ids.put(name, id);
                JsonObject x = new JsonObject();
                x.addProperty("joins", num(c, "minecraft:leave_game"));
                x.addProperty("taken", (double) Math.round(num(c, "minecraft:damage_taken") / 20.0 * 10.0) / 10.0);
                x.addProperty("chests", num(c, "minecraft:open_chest"));
                top(x, "block", mined);
                top(x, "mob", sub(s, "minecraft:killed"));
                extra.put(name, x);
            } catch (Exception ignored) {
                // kaputte Stats-Datei ueberspringen
            }
        }
        JsonObject b = new JsonObject();
        for (int i = 0; i < KEYS.length; i++) {
            final int k = i;
            JsonArray arr = new JsonArray();
            m.entrySet().stream()
                    .filter(en -> en.getValue()[k] > 0.0)
                    .sorted((a, c2) -> Double.compare(c2.getValue()[k], a.getValue()[k]))
                    .limit(10L)
                    .forEach(en -> {
                        JsonObject r = new JsonObject();
                        r.addProperty("name", en.getKey());
                        r.addProperty("value", (double) Math.round(en.getValue()[k] * 10.0) / 10.0);
                        arr.add(r);
                    });
            b.add(KEYS[i], arr);
        }
        this.hours = m.values().stream().mapToDouble(v -> v[0]).sum();
        this.totalDeaths = m.values().stream().mapToDouble(v -> v[1]).sum();
        this.totalBlocks = m.values().stream().mapToDouble(v -> v[2]).sum();
        this.totalKm = m.values().stream().mapToDouble(v -> v[4]).sum();
        JsonObject pl = new JsonObject();
        m.forEach((n, v) -> {
            JsonObject p = new JsonObject();
            JsonArray a = new JsonArray();
            for (double val : v) {
                a.add((double) Math.round(val * 10.0) / 10.0);
            }
            p.add("v", a);
            OfflinePlayer op = Bukkit.getOfflinePlayer(ids.get(n));
            p.addProperty("first", op.getFirstPlayed());
            p.addProperty("last", op.getLastPlayed());
            if (extra.get(n) != null) {
                p.add("x", extra.get(n));
            }
            pl.add(n, p);
        });
        this.players = pl;
        this.boards = b;
    }

    @EventHandler(ignoreCancelled = true)
    public void onSit(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player p = e.getPlayer();
        Block b = e.getClickedBlock();
        if (b == null || p.isSneaking() || p.isInsideVehicle()) {
            return;
        }
        if (!p.getInventory().getItemInMainHand().getType().isAir()) {
            return;
        }
        if (!isSeat(b) || !b.getRelative(BlockFace.UP).isPassable()) {
            return;
        }
        for (ArmorStand o : this.seats.values()) {
            if (!o.getLocation().getBlock().equals(b)) {
                continue;
            }
            return;
        }
        Location loc = b.getLocation().add(0.5, 0.5, 0.5);
        Location exit = loc.clone();
        if (b.getBlockData() instanceof Stairs stairs) {
            BlockFace out = stairs.getFacing().getOppositeFace();
            loc.setDirection(out.getDirection());
            Block front = b.getRelative(out);
            if (front.isPassable() && front.getRelative(BlockFace.UP).isPassable()) {
                exit = front.getLocation().add(0.5, 0.0, 0.5);
            }
        }
        ArmorStand st = b.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setMarker(true);
            a.setRotation(loc.getYaw(), 0.0f);
            a.setGravity(false);
            a.setSilent(true);
            a.setInvulnerable(true);
            a.setPersistent(false);
        });
        st.addPassenger(p);
        this.seats.put(p.getUniqueId(), st);
        this.exits.put(p.getUniqueId(), exit);
        e.setCancelled(true);
    }

    private void slowTime() {
        if (this.dayFactor <= 1.0) {
            return;
        }
        World w = Bukkit.getWorlds().get(0);
        long now = w.getFullTime();
        long t = now;
        if (this.lastTime >= 0L && now - this.lastTime == 1L) {
            this.slowAcc += 1.0 - 1.0 / this.dayFactor;
            while (this.slowAcc >= 1.0) {
                --t;
                this.slowAcc -= 1.0;
            }
            if (t != now) {
                w.setFullTime(t);
            }
        }
        this.lastTime = t;
    }

    private void checkSeats() {
        this.seats.entrySet().removeIf(en -> {
            ArmorStand st = en.getValue();
            Player p = Bukkit.getPlayer(en.getKey());
            boolean ok = p != null && st.isValid() && st.getPassengers().contains(p) && isSeat(st.getLocation().getBlock());
            if (ok) {
                return false;
            }
            Location exit = this.exits.remove(en.getKey());
            Location to = exit != null ? exit : st.getLocation();
            st.remove();
            if (p != null && p.isOnline() && !p.isDead()) {
                to.setYaw(p.getLocation().getYaw());
                to.setPitch(p.getLocation().getPitch());
                p.teleport(to);
            }
            return true;
        });
    }

    private static boolean isSeat(Block b) {
        BlockData d = b.getBlockData();
        return d instanceof Stairs s && s.getHalf() == Bisected.Half.BOTTOM;
    }

    private File findStatsDir() {
        World world = Bukkit.getWorlds().get(0);
        ArrayList<File> bases = new ArrayList<>();
        File wf = world.getWorldFolder();
        for (int i = 0; i < 5 && wf != null; i++, wf = wf.getParentFile()) {
            bases.add(wf);
        }
        bases.add(new File(Bukkit.getWorldContainer(), world.getName()));
        for (String sub : new String[]{"players/stats", "stats"}) {
            for (File base : bases) {
                File d = new File(base, sub);
                if (!d.isDirectory()) {
                    continue;
                }
                return d;
            }
        }
        return null;
    }

    private static void top(JsonObject x, String k, JsonObject o) {
        String best = null;
        double bv = 0.0;
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            double v = e.getValue().getAsDouble();
            if (!(v > bv)) {
                continue;
            }
            bv = v;
            best = e.getKey();
        }
        if (best != null) {
            x.addProperty(k, best.replace("minecraft:", ""));
            x.addProperty(k + "N", bv);
        }
    }

    private static JsonObject sub(JsonObject o, String k) {
        return o != null && o.has(k) ? o.getAsJsonObject(k) : new JsonObject();
    }

    private static double num(JsonObject o, String k) {
        return o.has(k) ? o.get(k).getAsDouble() : 0.0;
    }
}
