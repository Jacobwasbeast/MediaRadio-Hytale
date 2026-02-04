package dev.jacobwasbeast.util;

import com.hypixel.hytale.function.supplier.CachedSupplier;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.universe.Universe;
import java.lang.reflect.Field;
import java.util.logging.Level;

public final class CommonAssetUtil {
    private CommonAssetUtil() {
    }

    public static <T extends CommonAsset> void addCommonAssetSilent(String pack, T asset, boolean log) {
        CommonAssetModule module = CommonAssetModule.get();
        if (module == null || asset == null) {
            return;
        }
        CommonAssetRegistry.AddCommonAssetResult result = CommonAssetRegistry.addCommonAsset(pack, asset);
        CommonAssetRegistry.PackAsset newAsset = result.getNewPackAsset();
        CommonAssetRegistry.PackAsset oldAsset = result.getPreviousNameAsset();
        if (oldAsset != null && oldAsset.asset().getHash().equals(newAsset.asset().getHash())) {
            if (log) {
                module.getLogger().at(Level.INFO).log("Didn't change: %s", asset.getName());
            }
            return;
        }

        if (log) {
            if (oldAsset == null) {
                module.getLogger().at(Level.INFO).log("Created: %s", newAsset);
            } else {
                module.getLogger().at(Level.INFO).log("Reloaded: %s - Old Hash: %s",
                        newAsset, oldAsset.asset().getHash());
            }
        }

        if (result.getActiveAsset().equals(newAsset)) {
            invalidateAssetCache(module);
            BlockyAnimationCache.invalidate(newAsset.asset().getName());
            if (Universe.get().getPlayerCount() > 0) {
                module.sendAsset(newAsset.asset(), false);
            }
        }
    }

    private static void invalidateAssetCache(CommonAssetModule module) {
        try {
            Field assetsField = CommonAssetModule.class.getDeclaredField("assets");
            assetsField.setAccessible(true);
            Object cached = assetsField.get(module);
            if (cached instanceof CachedSupplier<?>) {
                ((CachedSupplier<?>) cached).invalidate();
            }
        } catch (ReflectiveOperationException e) {
            module.getLogger().at(Level.FINE).withCause(e).log("Failed to invalidate common asset cache.");
        }
    }
}
