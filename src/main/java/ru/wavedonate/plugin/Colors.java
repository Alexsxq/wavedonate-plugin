package ru.wavedonate.plugin;

import org.bukkit.ChatColor;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Colors {
    private static final Pattern HEX = Pattern.compile("&#([0-9a-fA-F]{6})");

    private static final int[] LEGACY_RGB = {
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
    };
    private static final String LEGACY_CODES = "0123456789abcdef";

    private Colors() {
    }

    static String apply(String text) {
        if (text == null || text.isEmpty()) return "";
        Matcher m = HEX.matcher(text);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String replacement;
            if (Compat.HEX) {
                StringBuilder hex = new StringBuilder("§x");
                for (char c : m.group(1).toCharArray()) hex.append('§').append(c);
                replacement = hex.toString();
            } else {
                replacement = "§" + nearest(Integer.parseInt(m.group(1), 16));
            }
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return ChatColor.translateAlternateColorCodes('&', out.toString());
    }

    private static char nearest(int rgb) {
        int r = rgb >> 16 & 0xFF, g = rgb >> 8 & 0xFF, b = rgb & 0xFF;
        int best = 15;
        long bestDist = Long.MAX_VALUE;
        for (int i = 0; i < LEGACY_RGB.length; i++) {
            int c = LEGACY_RGB[i];
            long dr = r - (c >> 16 & 0xFF), dg = g - (c >> 8 & 0xFF), db = b - (c & 0xFF);
            long dist = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return LEGACY_CODES.charAt(best);
    }
}
