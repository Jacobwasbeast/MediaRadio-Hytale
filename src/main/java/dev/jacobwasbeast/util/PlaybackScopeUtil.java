package dev.jacobwasbeast.util;

import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.UUID;

public final class PlaybackScopeUtil {
    private PlaybackScopeUtil() {
    }

    public static String playerScopeId(UUID playerId) {
        return playerId != null ? playerId.toString() : "";
    }

    public static String boomboxScopeId(World world, Vector3i pos) {
        return boomboxScopeId(resolveWorldName(world), pos);
    }

    public static String boomboxScopeId(String worldName, Vector3i pos) {
        if (pos == null) {
            return "boombox:world:0,0,0";
        }
        String name = (worldName == null || worldName.isEmpty()) ? "world" : worldName;
        return "boombox:" + name + ":" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public static String resolveWorldName(World world) {
        if (world == null) {
            return "world";
        }
        String name = world.getName();
        return (name == null || name.isEmpty()) ? "world" : name;
    }
}
