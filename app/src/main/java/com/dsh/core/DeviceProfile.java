package com.dsh.core;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.dsh.install.EnvInstaller;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Probes device ABI, SoC CPU features, and memory capacity to select safe
 * compilation/runtime tuning across 32-bit ARMv7, legacy ARMv8.0 (e.g. Cortex-A53/A57),
 * and modern Snapdragon/flagship ARMv8.2+ devices.
 */
public final class DeviceProfile {

    private static final String TAG = "DeviceProfile";

    public enum Tier {
        CONSTRAINED("constrained"),
        BALANCED("balanced"),
        PERFORMANCE("performance");

        public final String id;

        Tier(String id) {
            this.id = id;
        }
    }

    /** ARMv8.0 CPU part IDs (lack LSE atomics / newer extensions on standalone clusters). */
    private static final Set<String> LEGACY_ARMV8_PARTS = new HashSet<>(Arrays.asList(
            "0xd03", // Cortex-A53
            "0xd04", // Cortex-A35
            "0xd07", // Cortex-A57
            "0xd08", // Cortex-A72
            "0xd09", // Cortex-A73
            "0x201", "0x205", "0x211", // Qualcomm Kryo / Kryo 260
            "0x800", "0x801"           // Qualcomm Kryo 260/280 silver/gold
    ));

    private static volatile Boolean sHasArmCrypto;
    private static volatile CpuProbe sCpuProbe;

    public final Tier tier;
    public final String socFamily;
    public final boolean is32Bit;
    public final boolean hasHardwareCryptoAndLse;
    public final boolean isLegacyArmv8Only;
    public final boolean isLowRamDevice;
    public final long totalRamMb;
    public final int nodeMaxOldSpaceMb;
    public final int uvThreadpoolSize;
    public final boolean sidecarsSupported;

    private DeviceProfile(Tier tier, String socFamily, boolean is32Bit,
                          boolean hasHardwareCryptoAndLse, boolean isLegacyArmv8Only,
                          boolean isLowRamDevice, long totalRamMb,
                          int nodeMaxOldSpaceMb, int uvThreadpoolSize,
                          boolean sidecarsSupported) {
        this.tier = tier;
        this.socFamily = socFamily;
        this.is32Bit = is32Bit;
        this.hasHardwareCryptoAndLse = hasHardwareCryptoAndLse;
        this.isLegacyArmv8Only = isLegacyArmv8Only;
        this.isLowRamDevice = isLowRamDevice;
        this.totalRamMb = totalRamMb;
        this.nodeMaxOldSpaceMb = nodeMaxOldSpaceMb;
        this.uvThreadpoolSize = uvThreadpoolSize;
        this.sidecarsSupported = sidecarsSupported;
    }

    public static DeviceProfile detect(Context context) {
        boolean is32Bit = EnvInstaller.is32BitUserspace();
        boolean hasCrypto = hasArmHardwareCrypto();
        CpuProbe cpu = probeCpu();
        String socFamily = detectSocFamily();

        boolean lowRam = false;
        long totalRamMb = -1L;
        if (context != null) {
            try {
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    lowRam = am.isLowRamDevice();
                    ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    totalRamMb = mi.totalMem / (1024L * 1024L);
                }
            } catch (Throwable t) {
                Log.w(TAG, "could not query ActivityManager memory info: " + t.getMessage());
            }
        }

        boolean legacyOnly = !hasCrypto || cpu.legacyCoresOnly;
        boolean sidecars = hasCrypto && !is32Bit && !lowRam && totalRamMb >= 3500L;

        Tier tier;
        int maxOldSpaceMb;
        int uvThreads;
        if (is32Bit || lowRam || (totalRamMb > 0 && totalRamMb < 3500L)) {
            tier = Tier.CONSTRAINED;
            maxOldSpaceMb = (is32Bit || (totalRamMb > 0 && totalRamMb < 2500L)) ? 512 : 768;
            uvThreads = 2;
        } else if (legacyOnly || (totalRamMb > 0 && totalRamMb < 5500L)) {
            tier = Tier.BALANCED;
            maxOldSpaceMb = 1024;
            uvThreads = 4;
        } else {
            tier = Tier.PERFORMANCE;
            maxOldSpaceMb = (totalRamMb >= 7500L) ? 2048 : 1536;
            uvThreads = (totalRamMb >= 7500L) ? 6 : 4;
        }

        return new DeviceProfile(tier, socFamily, is32Bit, hasCrypto, legacyOnly,
                lowRam, totalRamMb, maxOldSpaceMb, uvThreads, sidecars);
    }

    /**
     * Checks whether the current CPU supports ARMv8 Cryptography Extensions (AES & PMULL)
     * and LSE atomics. On ARMv8.0 cores without hardware crypto (e.g. MediaTek Helio A22/G25,
     * Cortex-A53/A57 without crypto), executing those instructions raises SIGILL (exit code 132).
     */
    public static synchronized boolean hasArmHardwareCrypto() {
        if (sHasArmCrypto != null) {
            return sHasArmCrypto;
        }
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.US);
        if (!arch.contains("aarch64") && !arch.contains("arm64") && !arch.contains("arm")) {
            sHasArmCrypto = true;
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            String[] abis64 = Build.SUPPORTED_64_BIT_ABIS;
            if (abis64 == null || abis64.length == 0) {
                Log.i(TAG, "device reports no 64-bit ABIs; disabling hardware crypto/LSE");
                sHasArmCrypto = false;
                return false;
            }
        }
        String hardware = safeLower(Build.HARDWARE);
        String board = safeLower(Build.BOARD);
        if (hardware.startsWith("mt676") || board.startsWith("mt676")
                || hardware.contains("mt6761") || hardware.contains("mt6762")) {
            Log.i(TAG, "detected MediaTek Helio MT676x SoC without crypto extension");
            sHasArmCrypto = false;
            return false;
        }
        CpuProbe cpu = probeCpu();
        sHasArmCrypto = cpu.hasAes && cpu.hasPmull && cpu.hasAtomics;
        return sHasArmCrypto;
    }

    private static String detectSocFamily() {
        String combined = safeLower(Build.HARDWARE) + " " + safeLower(Build.BOARD) + " "
                + safeLower(getSocModel()) + " " + safeLower(Build.MANUFACTURER);
        if (combined.contains("qcom") || combined.contains("qualcomm")
                || combined.contains("snapdragon") || combined.contains("msm")
                || combined.contains("sdm") || combined.contains("sm8")
                || combined.contains("sm7") || combined.contains("sm6")
                || combined.contains("kona") || combined.contains("lahaina")
                || combined.contains("taro") || combined.contains("kalama")
                || combined.contains("pineapple") || combined.contains("sun")) {
            return "snapdragon";
        }
        if (combined.contains("mt6") || combined.contains("mt8") || combined.contains("mediatek")) {
            return "mediatek";
        }
        if (combined.contains("exynos") || combined.contains("s5e") || combined.contains("universal")) {
            return "exynos";
        }
        if (combined.contains("tensor") || combined.contains("gs1") || combined.contains("gs2")
                || combined.contains("zuma")) {
            return "tensor";
        }
        if (combined.contains("kirin") || combined.contains("hi3") || combined.contains("hi6")) {
            return "kirin";
        }
        return "generic";
    }

    private static String getSocModel() {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                return (String) Build.class.getField("SOC_MODEL").get(null);
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private static CpuProbe probeCpu() {
        CpuProbe cached = sCpuProbe;
        if (cached != null) {
            return cached;
        }
        boolean hasAes = false;
        boolean hasPmull = false;
        boolean hasAtomics = false;
        boolean sawFeatures = false;
        boolean sawCpuPart = false;
        boolean sawModernCore = false;

        File cpuinfo = new File("/proc/cpuinfo");
        try (BufferedReader reader = new BufferedReader(new FileReader(cpuinfo))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String lower = line.trim().toLowerCase(Locale.US);
                int colon = lower.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                String key = lower.substring(0, colon).trim();
                String val = lower.substring(colon + 1).trim();
                if (!sawFeatures && (key.equals("features") || key.equals("flags"))) {
                    sawFeatures = true;
                    hasAes = hasToken(val, "aes");
                    hasPmull = hasToken(val, "pmull");
                    hasAtomics = hasToken(val, "atomics");
                } else if (key.equals("cpu part")) {
                    sawCpuPart = true;
                    if (!LEGACY_ARMV8_PARTS.contains(val)) {
                        sawModernCore = true;
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "could not read /proc/cpuinfo: " + t.getMessage());
        }

        boolean legacyOnly = sawCpuPart && !sawModernCore;
        CpuProbe probe = new CpuProbe(hasAes, hasPmull, hasAtomics, legacyOnly);
        sCpuProbe = probe;
        return probe;
    }

    private static boolean hasToken(String spaceSeparated, String token) {
        String[] parts = spaceSeparated.split("\\s+");
        for (String p : parts) {
            if (token.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private static String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }

    private static final class CpuProbe {
        final boolean hasAes;
        final boolean hasPmull;
        final boolean hasAtomics;
        final boolean legacyCoresOnly;

        CpuProbe(boolean hasAes, boolean hasPmull, boolean hasAtomics, boolean legacyCoresOnly) {
            this.hasAes = hasAes;
            this.hasPmull = hasPmull;
            this.hasAtomics = hasAtomics;
            this.legacyCoresOnly = legacyCoresOnly;
        }
    }
}
