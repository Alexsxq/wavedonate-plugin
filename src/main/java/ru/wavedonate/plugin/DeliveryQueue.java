package ru.wavedonate.plugin;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

final class DeliveryQueue {
    static final class Entry {
        final PendingOrder order;
        int next;

        Entry(PendingOrder order, int next) {
            this.order = order;
            this.next = next;
        }
    }

    private final File file;
    private final Logger log;
    private final Map<Long, Entry> entries = new LinkedHashMap<Long, Entry>();

    DeliveryQueue(File dataFolder, Logger log) {
        this.file = new File(dataFolder, "queue.yml");
        this.log = log;
    }

    void load() {
        entries.clear();
        if (!file.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yml.getConfigurationSection("orders");
        if (root == null) return;
        for (String key : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(key);
            if (s == null) continue;
            long id;
            try {
                id = Long.parseLong(key);
            } catch (NumberFormatException e) {
                continue;
            }
            PendingOrder order = new PendingOrder(id, s.getString("player", ""), s.getString("product"), s.getString("amount"),
                s.getString("from"), s.getString("quantity"), new ArrayList<String>(s.getStringList("commands")));
            if (!order.player.isEmpty()) entries.put(id, new Entry(order, Math.max(0, s.getInt("next", 0))));
        }
    }

    boolean contains(long id) {
        return entries.containsKey(id);
    }

    Entry get(long id) {
        return entries.get(id);
    }

    List<Entry> all() {
        return new ArrayList<Entry>(entries.values());
    }

    int size() {
        return entries.size();
    }

    void add(PendingOrder order) {
        entries.put(order.id, new Entry(order, 0));
        save();
    }

    void progress(long id, int next) {
        Entry e = entries.get(id);
        if (e == null) return;
        e.next = next;
        save();
    }

    void remove(long id) {
        if (entries.remove(id) != null) save();
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Entry e : entries.values()) {
            String p = "orders." + e.order.id + ".";
            yml.set(p + "player", e.order.player);
            yml.set(p + "product", e.order.product);
            yml.set(p + "amount", e.order.amount);
            yml.set(p + "from", e.order.giftFrom);
            yml.set(p + "quantity", e.order.quantity);
            yml.set(p + "commands", e.order.commands);
            yml.set(p + "next", e.next);
        }
        try {
            if (entries.isEmpty()) {
                if (file.exists() && !file.delete()) yml.save(file);
            } else {
                yml.save(file);
            }
        } catch (IOException ex) {
            log.warning("Не удалось сохранить очередь выдачи (queue.yml): " + ex.getMessage());
        }
    }
}
