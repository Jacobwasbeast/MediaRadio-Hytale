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
import javax.annotation.Nullable;
import java.lang.reflect.Method;

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
                Vector3i pos = invokeVector3i(context, "getTargetBlock");
                if (pos != null) {
                        return pos;
                }
                pos = invokeVector3i(context, "getBlockPosition");
                if (pos != null) {
                        return pos;
                }
                Object target = invokeObject(context, "getTarget");
                if (target != null) {
                        Vector3i targetPos = invokeVector3i(target, "getBlockPosition");
                        if (targetPos != null) {
                                return targetPos;
                        }
                        targetPos = invokeVector3i(target, "getPosition");
                        if (targetPos != null) {
                                return targetPos;
                        }
                }
                return null;
        }

        private Vector3i invokeVector3i(Object target, String methodName) {
                Object result = invokeObject(target, methodName);
                if (result instanceof Vector3i vec) {
                        return vec;
                }
                return null;
        }

        private Object invokeObject(Object target, String methodName) {
                try {
                        Method method = target.getClass().getMethod(methodName);
                        return method.invoke(target);
                } catch (ReflectiveOperationException ignored) {
                        return null;
                }
        }
}
