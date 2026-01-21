package dev.jacobwasbeast.interaction;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.SwitchActiveSlotEvent;
import com.hypixel.hytale.server.core.inventory.Inventory;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import dev.jacobwasbeast.MediaRadioPlugin;
import dev.jacobwasbeast.util.RadioItemUtil;
import javax.annotation.Nonnull;
import com.hypixel.hytale.component.ArchetypeChunk;

public class RadioHeldItemSwitchSystem extends EntityEventSystem<EntityStore, SwitchActiveSlotEvent> {

    public RadioHeldItemSwitchSystem() {
        super(SwitchActiveSlotEvent.class);
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull SwitchActiveSlotEvent event) {
        int sectionId = event.getInventorySectionId();
        if (sectionId != Inventory.HOTBAR_SECTION_ID && sectionId != Inventory.UTILITY_SECTION_ID) {
            return;
        }

        var ref = archetypeChunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (player == null || playerRef == null) {
            return;
        }

        var playbackManager = MediaRadioPlugin.getInstance().getPlaybackManager();
        if (playbackManager == null || playbackManager.getSession(playerRef.getUuid()) == null) {
            return;
        }

        if (!isRadioHeldAfterSwitch(player, event)) {
            playbackManager.pauseForUnheld(playerRef);
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }

    private boolean isRadioHeldAfterSwitch(Player player, SwitchActiveSlotEvent event) {
        var inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInHand();
        ItemStack offHand = inventory.getUtilityItem();

        if (event.getInventorySectionId() == Inventory.HOTBAR_SECTION_ID) {
            mainHand = getSlotItem(inventory, Inventory.HOTBAR_SECTION_ID, event.getNewSlot());
        } else if (event.getInventorySectionId() == Inventory.UTILITY_SECTION_ID) {
            offHand = getSlotItem(inventory, Inventory.UTILITY_SECTION_ID, event.getNewSlot());
        }

        return RadioItemUtil.isRadioItem(mainHand) || RadioItemUtil.isRadioItem(offHand);
    }

    private ItemStack getSlotItem(Inventory inventory, int sectionId, byte slot) {
        if (slot < 0) {
            return null;
        }
        return inventory.getSectionById(sectionId).getItemStack(slot);
    }
}
