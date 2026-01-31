package dev.jacobwasbeast.util;

public final class VolumeUtil {
    public static final int MAX_PERCENT = 200;
    public static final int DEFAULT_PERCENT = 100;
    public static final float EVENT_DB_MIN = -10.0f;
    public static final float EVENT_DB_MAX = 10.0f;
    public static final float LAYER_DB_MIN = -10.0f;
    public static final float LAYER_DB_MAX = 10.0f;

    private VolumeUtil() {
    }

    public static float clampPercent(float percent) {
        if (percent < 0.0f) {
            return 0.0f;
        }
        if (percent > MAX_PERCENT) {
            return MAX_PERCENT;
        }
        return percent;
    }

    public static float percentToEventDb(float percent) {
        float clamped = clampPercent(percent);
        float t = clamped / MAX_PERCENT;
        return EVENT_DB_MIN + t * (EVENT_DB_MAX - EVENT_DB_MIN);
    }

    public static float percentToLayerDb(float percent) {
        float clamped = clampPercent(percent);
        float t = clamped / MAX_PERCENT;
        return LAYER_DB_MIN + t * (LAYER_DB_MAX - LAYER_DB_MIN);
    }

    public static float eventDbToPercent(float db) {
        float t = (db - EVENT_DB_MIN) / (EVENT_DB_MAX - EVENT_DB_MIN);
        return t * MAX_PERCENT;
    }
}
