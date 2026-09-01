package com.nnpg.glazed.utils;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MarketUtils {
    private static Object menuOwner;
    private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KkMmBb])?");
    private static final Pattern LABELLED_MONEY = Pattern.compile(
        "(?i)(?:price|worth|payout|value|total|each|per\\s*item|per\\s*unit)[^0-9$]{0,20}\\$?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*([KkMmBb])?"
    );
    private static final Pattern QUANTITY = Pattern.compile("(?i)(?:amount|quantity|qty|items?)\\s*:?\\s*([0-9][0-9,]*)");

    private MarketUtils() {}

    public static synchronized boolean acquireMenu(Object requester) {
        if (menuOwner == null || menuOwner == requester) {
            menuOwner = requester;
            return true;
        }
        return false;
    }

    public static synchronized void releaseMenu(Object requester) {
        if (menuOwner == requester) menuOwner = null;
    }

    public enum PriceMode {
        Total,
        PerItem
    }

    public record Price(long total, long perItem) {
        public long forMode(PriceMode mode) {
            return mode == PriceMode.PerItem ? perItem : total;
        }
    }

    public record Listing(int slot, ItemStack stack, Price price) {}

    public static int containerSlots(ChestMenu menu) {
        return Math.min(menu.getRowCount() * 9, menu.slots.size());
    }

    public static List<String> textLines(ItemStack stack) {
        List<String> lines = new ArrayList<>();
        if (stack == null || stack.isEmpty()) return lines;

        lines.add(stack.getHoverName().getString());
        ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) lore.lines().forEach(line -> lines.add(line.getString()));
        return lines;
    }

    public static String allText(ItemStack stack) {
        return String.join(" ", textLines(stack)).toLowerCase(Locale.ROOT);
    }

    public static boolean matches(ItemStack stack, Item item, String searchTerm) {
        if (stack == null || stack.isEmpty()) return false;

        boolean itemEnabled = item != null && item != Items.AIR;
        boolean termEnabled = searchTerm != null && !searchTerm.isBlank();
        if (!itemEnabled && !termEnabled) return false;
        if (itemEnabled && !stack.is(item)) return false;
        return !termEnabled || allText(stack).contains(searchTerm.trim().toLowerCase(Locale.ROOT));
    }

    public static Price readPrice(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;

        List<Long> all = new ArrayList<>();
        long explicitTotal = -1;
        long explicitEach = -1;
        long quantity = Math.max(1, stack.getCount());

        for (String raw : textLines(stack)) {
            String lower = raw.toLowerCase(Locale.ROOT);
            Matcher quantityMatcher = QUANTITY.matcher(raw);
            if (quantityMatcher.find()) {
                try {
                    quantity = Math.max(1, Long.parseLong(quantityMatcher.group(1).replace(",", "")));
                } catch (NumberFormatException ignored) {}
            }

            List<Long> values = moneyValues(raw, MONEY);
            if (values.isEmpty()) values = moneyValues(raw, LABELLED_MONEY);
            if (values.isEmpty()) continue;

            all.addAll(values);
            long lineValue = values.stream().mapToLong(Long::longValue).max().orElse(-1);
            if (lower.contains("each") || lower.contains("per item") || lower.contains("per unit") || lower.contains("unit price")) {
                explicitEach = Math.max(explicitEach, lineValue);
            }
            if (lower.contains("total") || lower.contains("payout") || lower.contains("worth") || lower.contains("order value")) {
                explicitTotal = Math.max(explicitTotal, lineValue);
            }
        }

        if (all.isEmpty()) return null;

        long largest = all.stream().mapToLong(Long::longValue).max().orElse(-1);
        long smallest = all.stream().mapToLong(Long::longValue).min().orElse(-1);
        long total = explicitTotal > 0 ? explicitTotal : largest;
        long each = explicitEach > 0 ? explicitEach : (all.size() > 1 ? smallest : Math.max(1, total / quantity));

        if (explicitTotal <= 0 && explicitEach > 0) total = safeMultiply(explicitEach, quantity);
        if (explicitEach <= 0 && explicitTotal > 0) each = Math.max(1, explicitTotal / quantity);
        return total > 0 && each > 0 ? new Price(total, each) : null;
    }

    public static long parseConfiguredMoney(String text) {
        Double parsed = MoneyFmt.parse(text);
        if (parsed == null || !Double.isFinite(parsed) || parsed < 0 || parsed > Long.MAX_VALUE) return -1;
        return Math.round(parsed);
    }

    public static String query(String searchTerm, Item item) {
        if (searchTerm != null && !searchTerm.isBlank()) return searchTerm.trim();
        if (item == null || item == Items.AIR) return "";
        return item.getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT);
    }

    public static String stripSlash(String command) {
        String trimmed = command == null ? "" : command.trim();
        return trimmed.startsWith("/") ? trimmed.substring(1) : trimmed;
    }

    private static List<Long> moneyValues(String line, Pattern pattern) {
        List<Long> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
            long parsed = parseNumber(matcher.group(1), matcher.groupCount() >= 2 ? matcher.group(2) : null);
            if (parsed > 0) values.add(parsed);
        }
        return values;
    }

    private static long parseNumber(String number, String suffix) {
        try {
            double value = Double.parseDouble(number.replace(",", ""));
            if (suffix != null && !suffix.isEmpty()) {
                value *= switch (Character.toLowerCase(suffix.charAt(0))) {
                    case 'k' -> 1_000.0;
                    case 'm' -> 1_000_000.0;
                    case 'b' -> 1_000_000_000.0;
                    default -> 1.0;
                };
            }
            if (!Double.isFinite(value) || value <= 0 || value > Long.MAX_VALUE) return -1;
            return Math.round(value);
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static long safeMultiply(long left, long right) {
        if (left <= 0 || right <= 0) return -1;
        if (left > Long.MAX_VALUE / right) return Long.MAX_VALUE;
        return left * right;
    }
}
