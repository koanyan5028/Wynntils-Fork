/*
 * Copyright © Wynntils 2023-2025.
 * This file is released under LGPLv3. See LICENSE for full license details.
 */
package com.wynntils.models.trademarket;

import com.wynntils.core.WynntilsMod;
import com.wynntils.core.components.Handlers;
import com.wynntils.core.components.Model;
import com.wynntils.core.components.Models;
import com.wynntils.core.persisted.Persisted;
import com.wynntils.core.persisted.storage.Storage;
import com.wynntils.core.text.StyledText;
import com.wynntils.mc.event.ScreenOpenedEvent;
import com.wynntils.models.containers.containers.TradeMarketSellContainer;
import com.wynntils.models.trademarket.type.TradeMarketPriceInfo;
import com.wynntils.screens.trademarket.TradeMarketSearchResultHolder;
import com.wynntils.utils.mc.LoreUtils;
import com.wynntils.utils.mc.McUtils;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;

public class TradeMarketModel extends Model {
    private static final Pattern[] ITEM_NAME_PATTERNS = {
        // Item on the create buy order menu or create sell offer menu
        Pattern.compile("^§6(?:Buying|Selling) [^ ]+ (.+?)(?:§6)? for .+ Each$"),
        // Items on the trade overview menu
        Pattern.compile("^§6(?:Buying|Selling) [^ ]+ (.+)$"),
        // Item on the view existing sell offer menu (on the right side)
        Pattern.compile("^§7§l[^ ]+x (.+)$")
    };

    private static final Pattern TRADE_MARKET_FILTER_SCREEN_TITLE_PATTERN =
            Pattern.compile("\\[Pg\\. \\d+\\] Filter Items");

    // Price Parsing
    private static final int TRADE_MARKET_PRICE_LINE = 1;
    private static final Pattern PRICE_STR = Pattern.compile("§6Price:");

    public static final int TM_SELL_PRICE_SLOT = 28;
    private static final Pattern TM_SELL_PRICE_PATTERN = Pattern.compile("- §7Per Unit:§f (\\d{1,3}(?:,\\d{3})*)");
    private static final int TM_PRICE_CHECK_SLOT = 51;
    private static final Pattern TM_PRICE_CHECK_PATTERN =
            Pattern.compile("§7Cheapest Sell Offer: §f(\\d{1,3}(?:,\\d{3})*)");

    // Test in TradeMarketModel_PRICE_PATTERN
    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "§[67] - (?:§f(?<amount>[\\d,]+) §7x )?§(?:(?:(?:c✖|a✔) §f)|f§m|f)(?<price>[\\d,]+)§7(?:§m)?²(?:§b ✮ (?<silverbullPrice>[\\d,]+)§3²)?(?: .+)?");

    @Persisted
    private final Storage<Map<Integer, String>> presetFilters = new Storage<>(new TreeMap<>());

    private String lastSearchFilter = "";

    public TradeMarketModel() {
        super(List.of());

        Handlers.Item.addSimplifiablePatterns(ITEM_NAME_PATTERNS);
        Handlers.WrappedScreen.registerWrappedScreen(new TradeMarketSearchResultHolder());
    }

    @SubscribeEvent
    public void onScreenOpen(ScreenOpenedEvent.Post event) {
        if (!isFilterScreen(event.getScreen().getTitle())) return;

        // If we open a new filter screen, reset the last search filter
        lastSearchFilter = "";
    }

    public boolean isFilterScreen(Component component) {
        return StyledText.fromComponent(component).matches(TRADE_MARKET_FILTER_SCREEN_TITLE_PATTERN);
    }

    public String getLastSearchFilter() {
        return lastSearchFilter;
    }

    public void setLastSearchFilter(String lastSearchFilter) {
        this.lastSearchFilter = lastSearchFilter;
    }

    public Optional<String> getPresetFilter(int presetId) {
        return Optional.ofNullable(presetFilters.get().get(presetId));
    }

    public void setPresetFilter(int presetId, String filter) {
        presetFilters.get().put(presetId, filter);
        presetFilters.touched();
    }

    public TradeMarketPriceInfo calculateItemPriceInfo(ItemStack itemStack) {
        List<StyledText> loreLines = LoreUtils.getLore(itemStack);

        StyledText priceLine = loreLines.get(TRADE_MARKET_PRICE_LINE);

        if (priceLine == null || !priceLine.matches(PRICE_STR)) {
            WynntilsMod.warn("Trade Market item had an unexpected price line: " + priceLine);
            return TradeMarketPriceInfo.EMPTY;
        }

        StyledText priceValueLine = loreLines.get(TRADE_MARKET_PRICE_LINE + 1);

        Matcher matcher = priceValueLine.getMatcher(PRICE_PATTERN);
        if (!matcher.matches()) {
            WynntilsMod.warn("Trade Market item had an unexpected price value line: " + priceValueLine);
            return TradeMarketPriceInfo.EMPTY;
        }

        int price = Integer.parseInt(matcher.group("price").replace(",", ""));

        String silverbullPriceStr = matcher.group("silverbullPrice");
        int silverbullPrice =
                silverbullPriceStr == null ? price : Integer.parseInt(silverbullPriceStr.replace(",", ""));

        String amountStr = matcher.group("amount");
        int amount = amountStr == null ? 1 : Integer.parseInt(amountStr.replace(",", ""));

        return new TradeMarketPriceInfo(price, silverbullPrice, amount);
    }

    /**
     * @return The unit price of the item currently being sold in the sell screen.
     */
    public int getUnitPrice() {
        if (!(McUtils.mc().screen instanceof ContainerScreen cs)) return -1;
        if (!(Models.Container.getCurrentContainer() instanceof TradeMarketSellContainer)) return -1;

        ItemStack priceCheckItem = cs.getMenu().getItems().get(TM_SELL_PRICE_SLOT);
        if (priceCheckItem.isEmpty()) return -1;

        String lore = LoreUtils.getStringLore(priceCheckItem).getString();
        Matcher priceCheckMatcher = TM_SELL_PRICE_PATTERN.matcher(lore);
        if (priceCheckMatcher.find()) {
            String priceCheckString = priceCheckMatcher.group(1);
            return Integer.parseInt(priceCheckString.replace(",", ""));
        }

        return -1;
    }

    /**
     * @return The TM's lowest price for the current item from the price check slot.
     */
    public int getLowestPrice() {
        if (!(McUtils.mc().screen instanceof ContainerScreen cs)) return -1;
        if (!(Models.Container.getCurrentContainer() instanceof TradeMarketSellContainer)) return -1;

        ItemStack priceCheckItem = cs.getMenu().getItems().get(TM_PRICE_CHECK_SLOT);
        if (priceCheckItem.isEmpty()) return -1;

        String lore = LoreUtils.getStringLore(priceCheckItem).getString();
        Matcher priceCheckMatcher = TM_PRICE_CHECK_PATTERN.matcher(lore);
        if (priceCheckMatcher.find()) {
            String priceCheckString = priceCheckMatcher.group(1);
            return Integer.parseInt(priceCheckString.replace(",", ""));
        }

        return -1;
    }
}
