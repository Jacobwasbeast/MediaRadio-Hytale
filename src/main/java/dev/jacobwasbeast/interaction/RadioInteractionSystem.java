package dev.jacobwasbeast.interaction;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import javax.annotation.Nonnull;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public class RadioInteractionSystem extends EntityEventSystem<EntityStore, UseBlockEvent.Pre> {

    public RadioInteractionSystem() {
        super(UseBlockEvent.Pre.class);
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull UseBlockEvent.Pre event) {
        String blockId = event.getBlockType().getId();
        if (blockId == null) {
            return;
        }
        if (!"media_radio:boombox".equals(blockId) && !"boombox".equals(blockId)
                && !blockId.endsWith(":boombox")) {
            return;
        }

        var ref = archetypeChunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());

        if (player != null && playerRef != null) {
            player.getPageManager().openCustomPage(ref, store,
                    new dev.jacobwasbeast.ui.RadioConfigPage(playerRef, event.getTargetBlock()));
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }
}
