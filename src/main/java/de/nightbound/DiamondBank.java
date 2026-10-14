package de.nightbound;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Diamantenbank: Spieler lagern Diamanten ein, die Rangliste landet auf der Stats-Seite
 * und auf Schildern ([BankTop] 1-10). Ein Rechtsklick auf ein Bank-Schild oeffnet die Bank.
 */
public final class DiamondBank implements Listener {

    /** Eine Zeile der Rangliste. */
    public record Row(String name, long value) {}

    private static final class Account {
        String name;
        long diamonds;

        Account(String name, long diamonds) {
            this.name = name;
            this.diamonds = diamonds;
        }
    }

    private static final class BankHolder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    static final int TOP = 10;
    static final String ADMIN_PERM = "nightboundstats.bank.admin";

    private final JavaPlugin plugin;
    private final File file;
    private final Map<UUID, Account> accounts = new HashMap<>();
    /** Schluessel "welt;x;y;z" -> 0 = normales Bank-Schild, 1..10 = Rang-Schild. */
    private final Map<String, Integer> signs = new HashMap<>();
    /** Zuletzt auf das Schild geschriebener Text, damit nur bei Aenderung aktualisiert wird. */
    private final Map<String, String> rendered = new HashMap<>();
    private final NumberFormat nf = NumberFormat.getIntegerInstance(Locale.GERMANY);
    private final Object ioLock = new Object();
    private long saveSeq = 0L;
    private long lastWritten = 0L;

    public DiamondBank(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "bank.yml");
        load();
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshSigns, 60L, 400L);
    }

    // ------------------------------------------------------------------ Daten

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    long d = ps.getLong(key + ".diamonds");
                    if (d > 0) {
                        accounts.put(id, new Account(ps.getString(key + ".name", "?"), d));
                    }
                } catch (IllegalArgumentException ignored) {
                    // kaputter Eintrag, ueberspringen
                }
            }
        }
        for (String s : y.getStringList("signs")) {
            int i = s.lastIndexOf(';');
            if (i < 0) {
                continue;
            }
            try {
                signs.put(s.substring(0, i), Integer.parseInt(s.substring(i + 1)));
            } catch (NumberFormatException ignored) {
                // kaputter Eintrag, ueberspringen
            }
        }
        plugin.getLogger().info("Diamantenbank: " + accounts.size() + " Konten, " + signs.size() + " Schilder geladen.");
    }

    private String serialize() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Account> en : accounts.entrySet()) {
            String base = "players." + en.getKey();
            y.set(base + ".name", en.getValue().name);
            y.set(base + ".diamonds", en.getValue().diamonds);
        }
        List<String> sl = new ArrayList<>();
        for (Map.Entry<String, Integer> en : signs.entrySet()) {
            sl.add(en.getKey() + ";" + en.getValue());
        }
        y.set("signs", sl);
        return y.saveToString();
    }

    /** Speichert im Hintergrund (Momentaufnahme wird im Haupt-Thread erstellt). */
    private void saveAsync() {
        String text = serialize();
        long seq = ++saveSeq;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(text, seq));
    }

    /** Speichert sofort, z. B. beim Abschalten des Servers. */
    public void saveNow() {
        write(serialize(), ++saveSeq);
    }

    private void write(String text, long seq) {
        synchronized (ioLock) {
            if (seq < lastWritten) {
                return; // ein neuerer Stand wurde schon geschrieben
            }
            lastWritten = seq;
            try {
                File dir = file.getParentFile();
                if (dir != null) {
                    dir.mkdirs();
                }
                Path tmp = file.toPath().resolveSibling("bank.yml.tmp");
                Files.writeString(tmp, text, StandardCharsets.UTF_8);
                Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                plugin.getLogger().severe("bank.yml konnte nicht gespeichert werden: " + ex.getMessage());
            }
        }
    }

    private long balance(UUID id) {
        Account a = accounts.get(id);
        return a == null ? 0L : a.diamonds;
    }

    // ------------------------------------------------------- Abfragen (Stats)

    /** Die besten n Konten, absteigend sortiert. */
    public List<Row> top(int n) {
        List<Row> rows = all();
        rows.sort(Comparator.comparingLong(Row::value).reversed().thenComparing(Row::name, String.CASE_INSENSITIVE_ORDER));
        return rows.size() > n ? new ArrayList<>(rows.subList(0, n)) : rows;
    }

    /** Alle Konten mit mehr als 0 Diamanten (unsortiert). */
    public List<Row> all() {
        List<Row> rows = new ArrayList<>();
        for (Account a : accounts.values()) {
            if (a.diamonds > 0) {
                rows.add(new Row(a.name, a.diamonds));
            }
        }
        return rows;
    }

    public long total() {
        long sum = 0L;
        for (Account a : accounts.values()) {
            sum += a.diamonds;
        }
        return sum;
    }

    private int rankOf(UUID id) {
        long b = balance(id);
        if (b <= 0) {
            return 0;
        }
        int better = 0;
        for (Account a : accounts.values()) {
            if (a.diamonds > b) {
                better++;
            }
        }
        return better + 1;
    }

    // ---------------------------------------------------------------- Befehl

    public boolean onCommand(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Die Bank kann nur ingame geoeffnet werden.");
            return true;
        }
        if (!plugin.getConfig().getBoolean("bank-command", true) && !p.hasPermission(ADMIN_PERM)) {
            p.sendMessage(Component.text("Die Bank erreichst du nur ueber die Bank-Schilder.", NamedTextColor.RED));
            return true;
        }
        open(p);
        return true;
    }

    // ------------------------------------------------------------------- GUI

    public void open(Player p) {
        BankHolder holder = new BankHolder();
        Inventory inv = Bukkit.createInventory(holder, 27, Component.text("Diamantenbank", NamedTextColor.DARK_AQUA));
        holder.inventory = inv;
        render(inv, p);
        p.openInventory(inv);
    }

    private void render(Inventory inv, Player p) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, 1, text(" ", NamedTextColor.GRAY), null);
        for (int i = 0; i < inv.getSize(); i++) {
            inv.setItem(i, filler);
        }
        long bal = balance(p.getUniqueId());
        int rank = rankOf(p.getUniqueId());

        inv.setItem(9, item(Material.LIME_CONCRETE, 1, text("Alle einzahlen", NamedTextColor.GREEN),
                List.of(text("Alle Diamanten aus deinem Inventar", NamedTextColor.GRAY))));
        inv.setItem(10, item(Material.LIME_CONCRETE, 64, text("64 einzahlen", NamedTextColor.GREEN), null));
        inv.setItem(11, item(Material.LIME_CONCRETE, 10, text("10 einzahlen", NamedTextColor.GREEN), null));
        inv.setItem(12, item(Material.LIME_CONCRETE, 1, text("1 einzahlen", NamedTextColor.GREEN), null));

        List<Component> lore = new ArrayList<>();
        lore.add(text("Dein Kontostand", NamedTextColor.GRAY));
        lore.add(text(rank > 0 ? "Rang #" + rank : "Noch nicht in der Rangliste", NamedTextColor.YELLOW));
        lore.add(text("Im Inventar: " + nf.format(countInventory(p)), NamedTextColor.DARK_GRAY));
        inv.setItem(13, item(Material.DIAMOND, 1, text(nf.format(bal) + " Diamanten", NamedTextColor.AQUA), lore));

        inv.setItem(14, item(Material.RED_CONCRETE, 1, text("1 abheben", NamedTextColor.RED), null));
        inv.setItem(15, item(Material.RED_CONCRETE, 10, text("10 abheben", NamedTextColor.RED), null));
        inv.setItem(16, item(Material.RED_CONCRETE, 64, text("64 abheben", NamedTextColor.RED), null));
        inv.setItem(17, item(Material.RED_CONCRETE, 1, text("Alle abheben", NamedTextColor.RED),
                List.of(text("Soviel wie in dein Inventar passt", NamedTextColor.GRAY))));

        List<Component> board = new ArrayList<>();
        List<Row> rows = top(TOP);
        if (rows.isEmpty()) {
            board.add(text("Noch niemand in der Bank", NamedTextColor.GRAY));
        }
        for (int i = 0; i < rows.size(); i++) {
            board.add(text((i + 1) + ". " + rows.get(i).name() + " - " + nf.format(rows.get(i).value()), NamedTextColor.GRAY));
        }
        inv.setItem(22, item(Material.BOOK, 1, text("Top 10", NamedTextColor.GOLD), board));
    }

    private ItemStack item(Material m, int amount, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(m, amount);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null) {
            meta.lore(lore);
        }
        it.setItemMeta(meta);
        return it;
    }

    /** Text ohne das standardmaessige Kursiv von Item-Namen. */
    private static Component text(String s, NamedTextColor color) {
        return Component.text(s, color).decoration(TextDecoration.ITALIC, false);
    }

    private static boolean isPlainDiamond(ItemStack it) {
        return it != null && it.getType() == Material.DIAMOND && !it.hasItemMeta();
    }

    private long countInventory(Player p) {
        long n = 0L;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            if (isPlainDiamond(it)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (!(top.getHolder() instanceof BankHolder)) {
            return;
        }
        e.setCancelled(true);
        if (e.getClickedInventory() != top || !(e.getWhoClicked() instanceof Player p)) {
            return;
        }
        boolean changed = switch (e.getRawSlot()) {
            case 9 -> deposit(p, Long.MAX_VALUE);
            case 10 -> deposit(p, 64L);
            case 11 -> deposit(p, 10L);
            case 12 -> deposit(p, 1L);
            case 14 -> withdraw(p, 1L);
            case 15 -> withdraw(p, 10L);
            case 16 -> withdraw(p, 64L);
            case 17 -> withdraw(p, Long.MAX_VALUE);
            default -> false;
        };
        if (changed) {
            render(top, p);
            refreshSigns();
            saveAsync();
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof BankHolder) {
            e.setCancelled(true);
        }
    }

    private boolean deposit(Player p, long want) {
        ItemStack[] c = p.getInventory().getStorageContents();
        long left = want;
        long moved = 0L;
        for (int i = 0; i < c.length && left > 0; i++) {
            ItemStack it = c[i];
            if (!isPlainDiamond(it)) {
                continue;
            }
            int take = (int) Math.min(it.getAmount(), left);
            moved += take;
            left -= take;
            if (take >= it.getAmount()) {
                c[i] = null;
            } else {
                it.setAmount(it.getAmount() - take);
            }
        }
        if (moved == 0L) {
            p.sendMessage(Component.text("Du hast keine Diamanten dabei.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return false;
        }
        p.getInventory().setStorageContents(c);
        Account a = accounts.computeIfAbsent(p.getUniqueId(), k -> new Account(p.getName(), 0L));
        a.name = p.getName();
        a.diamonds += moved;
        p.sendActionBar(Component.text("+" + nf.format(moved) + " eingezahlt - Kontostand: " + nf.format(a.diamonds), NamedTextColor.GREEN));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        return true;
    }

    private boolean withdraw(Player p, long want) {
        long bal = balance(p.getUniqueId());
        if (bal <= 0L) {
            p.sendMessage(Component.text("Deine Bank ist leer.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return false;
        }
        long left = Math.min(want, bal);
        long given = 0L;
        while (left > 0L) {
            int amt = (int) Math.min(64L, left);
            Map<Integer, ItemStack> rest = p.getInventory().addItem(new ItemStack(Material.DIAMOND, amt));
            int notGiven = 0;
            for (ItemStack r : rest.values()) {
                notGiven += r.getAmount();
            }
            given += amt - notGiven;
            left -= amt;
            if (notGiven > 0) {
                break; // Inventar voll
            }
        }
        if (given == 0L) {
            p.sendMessage(Component.text("Dein Inventar ist voll.", NamedTextColor.RED));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1.0f, 1.0f);
            return false;
        }
        Account a = accounts.get(p.getUniqueId());
        a.diamonds -= given;
        if (a.diamonds <= 0L) {
            accounts.remove(p.getUniqueId());
        }
        p.sendActionBar(Component.text("-" + nf.format(given) + " abgehoben - Kontostand: " + nf.format(balance(p.getUniqueId())), NamedTextColor.GOLD));
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1.0f, 1.0f);
        return true;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Account a = accounts.get(e.getPlayer().getUniqueId());
        if (a != null && !a.name.equals(e.getPlayer().getName())) {
            a.name = e.getPlayer().getName();
            saveAsync();
        }
    }

    // --------------------------------------------------------------- Schilder

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    private boolean isBankSign(Block b) {
        return signs.containsKey(key(b));
    }

    private static String plain(Component c) {
        return c == null ? "" : PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Die vier Zeilen eines Schildes: rank 0 = Bank-Schild, 1..10 = Rang-Schild. */
    private List<Component> lines(int rank, List<Row> rows) {
        if (rank == 0) {
            return List.of(
                    Component.empty(),
                    Component.text("Diamantenbank", NamedTextColor.DARK_AQUA, TextDecoration.BOLD),
                    Component.text("Rechtsklick", NamedTextColor.DARK_GRAY),
                    Component.empty());
        }
        TextColor color = switch (rank) {
            case 1 -> NamedTextColor.GOLD;
            case 2 -> NamedTextColor.GRAY;
            case 3 -> TextColor.color(0xCD7F32);
            default -> NamedTextColor.DARK_AQUA;
        };
        Row r = rows.size() >= rank ? rows.get(rank - 1) : null;
        return List.of(
                Component.text("#" + rank, color, TextDecoration.BOLD),
                r == null ? Component.text("-", NamedTextColor.DARK_GRAY) : Component.text(r.name(), NamedTextColor.BLACK),
                r == null ? Component.empty() : Component.text(nf.format(r.value()) + " Dias", NamedTextColor.DARK_AQUA),
                Component.text("Klick: Bank", NamedTextColor.DARK_GRAY));
    }

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        if (e.getSide() != Side.FRONT) {
            return;
        }
        String l0 = plain(e.line(0)).trim().toLowerCase(Locale.ROOT);
        boolean top = l0.equals("[banktop]");
        if (!top && !l0.equals("[bank]")) {
            return;
        }
        Player p = e.getPlayer();
        if (!p.hasPermission(ADMIN_PERM)) {
            return; // normales Schild ohne Funktion
        }
        int rank = 0;
        if (top) {
            try {
                rank = Integer.parseInt(plain(e.line(1)).trim().replace("#", ""));
            } catch (NumberFormatException ex) {
                rank = -1;
            }
            if (rank < 1 || rank > TOP) {
                p.sendMessage(Component.text("Zeile 1: [BankTop], Zeile 2: eine Zahl von 1 bis " + TOP + ".", NamedTextColor.RED));
                return;
            }
        }
        List<Component> ls = lines(rank, top(TOP));
        for (int i = 0; i < 4; i++) {
            e.line(i, ls.get(i));
        }
        signs.put(key(e.getBlock()), rank);
        saveAsync();
        // naechster Tick: Schild wird versiegelt, damit niemand den Text aendern kann
        Bukkit.getScheduler().runTask(plugin, this::refreshSigns);
        p.sendMessage(Component.text(top ? "Rang-Schild #" + rank + " erstellt." : "Bank-Schild erstellt.", NamedTextColor.GREEN));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSignClick(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block b = e.getClickedBlock();
        if (b == null || !isBankSign(b)) {
            return;
        }
        e.setCancelled(true);
        open(e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        String k = key(e.getBlock());
        if (!signs.containsKey(k)) {
            return;
        }
        if (!e.getPlayer().hasPermission(ADMIN_PERM)) {
            e.setCancelled(true);
            return;
        }
        signs.remove(k);
        rendered.remove(k);
        saveAsync();
        e.getPlayer().sendMessage(Component.text("Bank-Schild entfernt.", NamedTextColor.YELLOW));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(this::isBankSign);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(this::isBankSign);
    }

    /** Schreibt Rangliste bzw. Beschriftung auf alle registrierten Schilder in geladenen Chunks. */
    public void refreshSigns() {
        if (signs.isEmpty()) {
            return;
        }
        List<Row> rows = top(TOP);
        boolean removed = false;
        Iterator<Map.Entry<String, Integer>> it = signs.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Integer> en = it.next();
            String[] parts = en.getKey().split(";");
            if (parts.length < 4) {
                it.remove();
                removed = true;
                continue;
            }
            int n = parts.length;
            int x;
            int y;
            int z;
            try {
                x = Integer.parseInt(parts[n - 3]);
                y = Integer.parseInt(parts[n - 2]);
                z = Integer.parseInt(parts[n - 1]);
            } catch (NumberFormatException ex) {
                it.remove();
                removed = true;
                continue;
            }
            World w = Bukkit.getWorld(String.join(";", Arrays.copyOfRange(parts, 0, n - 3)));
            if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) {
                continue; // Welt/Chunk nicht geladen: Schild behalten, spaeter nochmal
            }
            Block b = w.getBlockAt(x, y, z);
            if (!(b.getState() instanceof Sign sign)) {
                it.remove();
                rendered.remove(en.getKey());
                removed = true;
                continue;
            }
            List<Component> ls = lines(en.getValue(), rows);
            StringBuilder sig = new StringBuilder();
            for (Component c : ls) {
                sig.append(plain(c)).append('\n');
            }
            if (sig.toString().equals(rendered.get(en.getKey())) && sign.isWaxed()) {
                continue; // nichts zu tun
            }
            SignSide side = sign.getSide(Side.FRONT);
            for (int i = 0; i < 4; i++) {
                side.line(i, ls.get(i));
            }
            sign.setWaxed(true);
            sign.update();
            rendered.put(en.getKey(), sig.toString());
        }
        if (removed) {
            saveAsync();
        }
    }
}
