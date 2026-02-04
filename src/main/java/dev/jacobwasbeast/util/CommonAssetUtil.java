package dev.jacobwasbeast.util;

import com.hypixel.hytale.common.util.PathUtil;
import com.hypixel.hytale.common.util.PatternUtil;
import com.hypixel.hytale.function.supplier.CachedSupplier;
import com.hypixel.hytale.protocol.Asset;
import com.hypixel.hytale.protocol.packets.setup.RemoveAssets;
import com.hypixel.hytale.protocol.packets.setup.RequestCommonAssetsRebuild;
import com.hypixel.hytale.server.core.asset.AssetModule;
import com.hypixel.hytale.server.core.asset.common.BlockyAnimationCache;
import com.hypixel.hytale.server.core.asset.common.CommonAsset;
import com.hypixel.hytale.server.core.asset.common.CommonAssetModule;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry;
import com.hypixel.hytale.server.core.asset.common.CommonAssetRegistry.PackAsset;
import com.hypixel.hytale.server.core.asset.monitor.AssetMonitor;
import com.hypixel.hytale.server.core.asset.monitor.AssetMonitorHandler;
import com.hypixel.hytale.server.core.asset.monitor.EventKind;
import com.hypixel.hytale.server.core.universe.Universe;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class CommonAssetUtil {
    private CommonAssetUtil() {
    }

    private static final AtomicBoolean RUNTIME_DELETE_MONITOR_REGISTERED = new AtomicBoolean(false);

    public static void registerRuntimeDeletionMonitor(String packName, Path commonRoot, Path audioRoot,
            Path modelRoot) {
        if (packName == null || packName.isEmpty() || commonRoot == null || audioRoot == null || modelRoot == null) {
            return;
        }
        if (!RUNTIME_DELETE_MONITOR_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        AssetModule assetModule = AssetModule.get();
        if (assetModule == null) {
            return;
        }
        AssetMonitor monitor = assetModule.getAssetMonitor();
        if (monitor == null) {
            return;
        }
        if (!Files.isDirectory(commonRoot)) {
            return;
        }
        monitor.monitorDirectoryFiles(commonRoot,
                new RuntimeCommonDeletionMonitor(packName, commonRoot, audioRoot, modelRoot));
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

    public static void removeCommonAssetSilent(String pack, String assetName) {
        if (pack == null || pack.isEmpty() || assetName == null || assetName.isEmpty()) {
            return;
        }
        CommonAssetModule module = CommonAssetModule.get();
        if (module == null) {
            return;
        }
        var removed = CommonAssetRegistry.removeCommonAssetByName(pack, assetName);
        if (removed == null) {
            return;
        }
        if (removed.firstBoolean()) {
            module.sendAssets(List.of(removed.second().asset()), false);
            return;
        }
        sendRemoveCommonAssetsSilent(List.of(removed.second()));
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

    private static final class RuntimeCommonDeletionMonitor implements AssetMonitorHandler {
        private final String packName;
        private final Path commonRoot;
        private final Path audioRoot;
        private final Path modelRoot;

        private RuntimeCommonDeletionMonitor(String packName, Path commonRoot, Path audioRoot, Path modelRoot) {
            this.packName = packName;
            this.commonRoot = commonRoot.toAbsolutePath().normalize();
            this.audioRoot = audioRoot.toAbsolutePath().normalize();
            this.modelRoot = modelRoot.toAbsolutePath().normalize();
        }

        @Override
        public Object getKey() {
            return this;
        }

        @Override
        public boolean test(Path path, EventKind eventKind) {
            if (eventKind != EventKind.ENTRY_DELETE) {
                return false;
            }
            Path normalized = path.toAbsolutePath().normalize();
            return normalized.startsWith(audioRoot) || normalized.startsWith(modelRoot);
        }

        @Override
        public void accept(Map<Path, EventKind> map) {
            List<PackAsset> removedAssets = new ArrayList<>();
            List<CommonAsset> updatedAssets = new ArrayList<>();

            for (Map.Entry<Path, EventKind> entry : map.entrySet()) {
                if (entry.getValue() != EventKind.ENTRY_DELETE) {
                    continue;
                }
                Path normalized = entry.getKey().toAbsolutePath().normalize();
                if (!normalized.startsWith(audioRoot) && !normalized.startsWith(modelRoot)) {
                    continue;
                }
                Path relative = PathUtil.relativize(commonRoot, normalized);
                String name = PatternUtil.replaceBackslashWithForwardSlash(relative.toString());
                var removed = CommonAssetRegistry.removeCommonAssetByName(packName, name);
                if (removed != null) {
                    if (removed.firstBoolean()) {
                        updatedAssets.add(removed.second().asset());
                    } else {
                        removedAssets.add(removed.second());
                    }
                }
            }

            if (!removedAssets.isEmpty()) {
                sendRemoveCommonAssetsSilent(removedAssets);
            }
            if (!updatedAssets.isEmpty()) {
                CommonAssetModule module = CommonAssetModule.get();
                if (module != null) {
                    module.sendAssets(updatedAssets, false);
                }
            }
        }
    }

    private static void sendRemoveCommonAssetsSilent(List<PackAsset> assets) {
        if (assets == null || assets.isEmpty()) {
            return;
        }
        Asset[] packets = new Asset[assets.size()];
        for (int i = 0; i < assets.size(); i++) {
            packets[i] = assets.get(i).asset().toPacket();
        }
        Universe.get().broadcastPacket(new RemoveAssets(packets));
        Universe.get().broadcastPacketNoCache(new RequestCommonAssetsRebuild());
    }
}
