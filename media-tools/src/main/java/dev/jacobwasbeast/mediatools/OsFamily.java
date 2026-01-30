package dev.jacobwasbeast.mediatools;

public enum OsFamily {
    WINDOWS("windows", "Windows"),
    MAC("macos", "macOS"),
    LINUX("linux", "Linux");

    private final String resourceFolder;
    private final String displayName;

    OsFamily(String resourceFolder, String displayName) {
        this.resourceFolder = resourceFolder;
        this.displayName = displayName;
    }

    public String resourceFolder() {
        return resourceFolder;
    }

    public String displayName() {
        return displayName;
    }

    public static OsFamily detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return WINDOWS;
        }
        if (os.contains("mac")) {
            return MAC;
        }
        return LINUX;
    }
}
