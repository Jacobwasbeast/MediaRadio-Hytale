package dev.jacobwasbeast.util;

/**
 * Block type / ID helpers for boombox detection.
 */
public final class BlockTypeUtil {
    private BlockTypeUtil() {
    }

    public static boolean isBoomboxBlockId(String blockId) {
        if (blockId == null || blockId.isEmpty()) {
            return false;
        }
        return "media_radio:boombox".equals(blockId)
                || "boombox".equals(blockId)
                || blockId.endsWith(":boombox");
    }
}
