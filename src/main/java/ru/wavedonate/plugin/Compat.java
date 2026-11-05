package ru.wavedonate.plugin;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class Compat {
    static final boolean HEX;

    static final boolean MODERN_SOUNDS;

    private static final Method TITLE_TIMED;
    private static final Method ACTION_BAR;
    private static final Object ACTION_BAR_TYPE;
    private static final Method FROM_LEGACY;
    private static final Class<?> COMPONENT;

    private static final Map<String, String> LEGACY_SOUNDS = new HashMap<String, String>();

    static {
        int minor = minorVersion();
        HEX = minor >= 16;
        MODERN_SOUNDS = minor >= 9;

        TITLE_TIMED = find(Player.class, "sendTitle", String.class, String.class, int.class, int.class, int.class);

        Method bar = null;
        Object barType = null;
        Method fromLegacy = null;
        Class<?> component = null;
        try {
            Class<?> type = Class.forName("net.md_5.bungee.api.ChatMessageType");
            component = Class.forName("net.md_5.bungee.api.chat.BaseComponent");
            Class<?> text = Class.forName("net.md_5.bungee.api.chat.TextComponent");
            Class<?> components = Array.newInstance(component, 0).getClass();
            bar = Player.Spigot.class.getMethod("sendMessage", type, components);
            barType = type.getField("ACTION_BAR").get(null);
            fromLegacy = text.getMethod("fromLegacyText", String.class);
        } catch (Exception ignored) {
            bar = null;
        }
        ACTION_BAR = bar;
        ACTION_BAR_TYPE = barType;
        FROM_LEGACY = fromLegacy;
        COMPONENT = component;

        LEGACY_SOUNDS.put("entity.player.levelup", "random.levelup");
        LEGACY_SOUNDS.put("entity.experience_orb.pickup", "random.orb");
        LEGACY_SOUNDS.put("block.note_block.pling", "note.pling");
        LEGACY_SOUNDS.put("block.note.pling", "note.pling");
        LEGACY_SOUNDS.put("ui.button.click", "random.click");
        LEGACY_SOUNDS.put("entity.firework_rocket.launch", "fireworks.launch");
        LEGACY_SOUNDS.put("entity.firework_rocket.blast", "fireworks.blast1");
        LEGACY_SOUNDS.put("ui.toast.challenge_complete", "random.levelup");
    }

    private Compat() {
    }

    static void title(Player player, String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        if (TITLE_TIMED != null) {
            try {
                TITLE_TIMED.invoke(player, title, subtitle, fadeIn, stay, fadeOut);
                return;
            } catch (Exception ignored) {
            }
        }
        sendTitleLegacy(player, title, subtitle);
    }

    @SuppressWarnings("deprecation")
    private static void sendTitleLegacy(Player player, String title, String subtitle) {
        player.sendTitle(title, subtitle);
    }

    static void actionBar(Player player, String text) {
        if (ACTION_BAR != null) {
            try {
                Object parts = FROM_LEGACY.invoke(null, text);
                Object array = Array.newInstance(COMPONENT, Array.getLength(parts));
                for (int i = 0; i < Array.getLength(parts); i++) Array.set(array, i, Array.get(parts, i));
                ACTION_BAR.invoke(player.spigot(), ACTION_BAR_TYPE, array);
                return;
            } catch (Exception ignored) {
            }
        }
        player.sendMessage(text);
    }

    static String soundKey(String raw) {
        String key = raw.trim();
        if (!key.contains(".") && !key.contains(":")) {
            key = key.toLowerCase().replace('_', '.');
        } else {
            key = key.toLowerCase();
        }
        if (key.startsWith("minecraft:")) key = key.substring("minecraft:".length());
        if (!MODERN_SOUNDS) {
            String legacy = LEGACY_SOUNDS.get(key);
            if (legacy != null) return legacy;
        }
        return key;
    }

    private static Method find(Class<?> owner, String name, Class<?>... args) {
        try {
            return owner.getMethod(name, args);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private static int minorVersion() {
        String v = Bukkit.getBukkitVersion();
        try {
            String[] parts = v.split("-")[0].split("\\.");
            int major = Integer.parseInt(parts[0]);
            if (major > 1) return 100 + major;
            return parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        } catch (RuntimeException e) {
            return 100;
        }
    }
}
