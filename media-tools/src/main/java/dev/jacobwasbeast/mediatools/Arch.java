package dev.jacobwasbeast.mediatools;

public enum Arch {
    X86_64("x86_64"),
    X86("x86"),
    ARM64("arm64"),
    ARMV7L("armv7l"),
    ARM("arm");

    private final String resourceFolder;

    Arch(String resourceFolder) {
        this.resourceFolder = resourceFolder;
    }

    public String resourceFolder() {
        return resourceFolder;
    }

    public static Arch detect() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.equals("amd64") || arch.equals("x86_64")) {
            return X86_64;
        }
        if (arch.equals("x86") || arch.equals("i386") || arch.equals("i486") || arch.equals("i586")
                || arch.equals("i686")) {
            return X86;
        }
        if (arch.equals("aarch64") || arch.equals("arm64")) {
            return ARM64;
        }
        if (arch.startsWith("armv7") || arch.equals("armv7l")) {
            return ARMV7L;
        }
        if (arch.startsWith("arm")) {
            return ARM;
        }
        return X86_64;
    }
}
