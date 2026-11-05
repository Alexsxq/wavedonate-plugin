package ru.wavedonate.plugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

final class PendingOrder {
    final long id;
    final String player;
    final String product;
    final String amount;
    final String giftFrom;
    final String quantity;
    final List<String> commands;

    PendingOrder(long id, String player, String product, String amount, String giftFrom, String quantity, List<String> commands) {
        this.id = id;
        this.player = player;
        this.product = product == null ? "Товар" : product;
        this.amount = amount == null ? "0" : amount;
        this.giftFrom = giftFrom;
        this.quantity = quantity;
        this.commands = commands == null ? Collections.<String>emptyList() : commands;
    }

    PendingOrder withCommands(List<String> newCommands) {
        return new PendingOrder(id, player, product, amount, giftFrom, quantity, newCommands);
    }

    static PendingOrder fromJson(Map<String, Object> o) {
        long id = Json.number(o.get("id"), -1);
        String player = Json.string(o.get("player"), null);
        if (id <= 0 || player == null || player.isEmpty()) {
            return null;
        }
        List<String> commands = new ArrayList<String>();
        for (Object c : Json.array(o.get("commands"))) {
            if (c != null) commands.add(String.valueOf(c));
        }
        return new PendingOrder(id, player, Json.string(o.get("product_name"), null), Json.string(o.get("amount"), "0"),
            Json.string(o.get("gift_from"), null), Json.string(o.get("quantity"), null), commands);
    }

    String apply(String text) {
        if (text == null) return "";
        return text
            .replace("%player%", player)
            .replace("%product%", product)
            .replace("%amount%", amount)
            .replace("%order%", String.valueOf(id))
            .replace("%from%", giftFrom == null ? player : giftFrom)
            .replace("%quantity%", quantity == null ? "1" : quantity);
    }
}
