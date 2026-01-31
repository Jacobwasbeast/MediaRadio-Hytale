package dev.jacobwasbeast.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.protocol.BlockPosition;
import javax.annotation.Nullable;

public record RadioConfigSupplier() implements OpenCustomUIInteraction.CustomPageSupplier {
        public static final Codec<RadioConfigSupplier> CODEC = BuilderCodec
                        .builder(RadioConfigSupplier.class, RadioConfigSupplier::new).build();

        @Override
        @Nullable
    public CustomUIPage tryCreate(Ref<EntityStore> ref, ComponentAccessor<EntityStore> componentAccessor,
                    PlayerRef playerRef, InteractionContext context) {
                Vector3i blockPos = resolveBlockPos(context);
                if (blockPos != null) {
                        return new RadioConfigPage(playerRef, blockPos);
                }
                return new RadioConfigPage(playerRef);
        }

        private Vector3i resolveBlockPos(InteractionContext context) {
                if (context == null) {
                        return null;
                }
                BlockPosition target = context.getTargetBlock();
                if (target == null) {
                        return null;
                }
                return new Vector3i(target.x, target.y, target.z);
        }
}
