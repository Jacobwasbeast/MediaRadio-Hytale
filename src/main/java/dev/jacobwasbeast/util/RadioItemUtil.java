package dev.jacobwasbeast.util;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;

public final class RadioItemUtil {
    public static final String PORTABLE_RADIO_ITEM_ID = "media_radio:radio";

    private RadioItemUtil() {
    }

    public static boolean isRadioItem(ItemStack itemStack) {
        if (itemStack == null || itemStack.isEmpty()) {
            return false;
        }
        String itemId = itemStack.getItemId();
        if (itemId == null || itemId.isEmpty()) {
            return false;
        }
        return PORTABLE_RADIO_ITEM_ID.equals(itemId)
                || "radio".equals(itemId)
                || itemId.endsWith(":radio");
    }

    public static boolean isRadioHeld(Player player) {
        if (player == null) {
            return false;
        }
        var inventory = player.getInventory();
        return isRadioItem(inventory.getItemInHand())
                || isRadioItem(inventory.getActiveHotbarItem())
                || isRadioItem(inventory.getUtilityItem());
    }
}
