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
import javax.annotation.Nullable;

public record RadioConfigSupplier() implements OpenCustomUIInteraction.CustomPageSupplier {
        public static final Codec<RadioConfigSupplier> CODEC = BuilderCodec
                        .builder(RadioConfigSupplier.class, RadioConfigSupplier::new).build();

        @Override
        @Nullable
    public CustomUIPage tryCreate(Ref<EntityStore> ref, ComponentAccessor<EntityStore> componentAccessor,
                    PlayerRef playerRef, InteractionContext context) {
                return new RadioConfigPage(playerRef);
        }
}
