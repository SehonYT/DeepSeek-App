package com.dsh.install;

import android.content.res.AssetManager;

import com.dsh.util.IoUtil;
import com.dsh.util.Sha256;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Parsed, deterministic metadata for the APK-bundled Bionic environment. */
public final class EnvBundleManifest {

    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern PACKAGE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9+._-]*$");
    private static final int SUPPORTED_SCHEMA = 1;

    public final String bundleId;
    public final String bootstrapSha256;
    public final String bootstrapArmSha256;
    public final String bootstrapX86Sha256;
    public final String bootstrapX64Sha256;
    public final String overlaySha256;
    public final String pkgSha256;
    public final int bootstrapSymlinkCount;
    public final int bootstrapArmSymlinkCount;
    public final int bootstrapX86SymlinkCount;
    public final int bootstrapX64SymlinkCount;
    public final String prepatchedPackage;
    public final List<String> seedPackages;

    private EnvBundleManifest(String bundleId, String bootstrapSha256, String bootstrapArmSha256,
                              String bootstrapX86Sha256, String bootstrapX64Sha256,
                              String overlaySha256, String pkgSha256,
                              int bootstrapSymlinkCount, int bootstrapArmSymlinkCount,
                              int bootstrapX86SymlinkCount, int bootstrapX64SymlinkCount,
                              String prepatchedPackage, List<String> seedPackages) {
        this.bundleId = bundleId;
        this.bootstrapSha256 = bootstrapSha256;
        this.bootstrapArmSha256 = bootstrapArmSha256;
        this.bootstrapX86Sha256 = bootstrapX86Sha256;
        this.bootstrapX64Sha256 = bootstrapX64Sha256;
        this.overlaySha256 = overlaySha256;
        this.pkgSha256 = pkgSha256;
        this.bootstrapSymlinkCount = bootstrapSymlinkCount;
        this.bootstrapArmSymlinkCount = bootstrapArmSymlinkCount;
        this.bootstrapX86SymlinkCount = bootstrapX86SymlinkCount;
        this.bootstrapX64SymlinkCount = bootstrapX64SymlinkCount;
        this.prepatchedPackage = prepatchedPackage;
        this.seedPackages = Collections.unmodifiableList(seedPackages);
    }

    public static EnvBundleManifest load(AssetManager assets) {
        try (InputStream in = assets.open(EnvPaths.ASSET_MANIFEST)) {
            JSONObject json = new JSONObject(IoUtil.readAll(in, "UTF-8"));
            int schema = json.getInt("schema");
            if (schema != SUPPORTED_SCHEMA) {
                throw new IllegalStateException("unsupported env manifest schema: " + schema);
            }
            String bundleId = requireSha256(json.getString("bundleId"), "bundleId");
            String bootstrapSha = optSha256(json, "bootstrapSha256");
            String bootstrapArmSha = optSha256(json, "bootstrapArmSha256");
            String bootstrapX86Sha = optSha256(json, "bootstrapX86Sha256");
            String bootstrapX64Sha = optSha256(json, "bootstrapX64Sha256");
            if (bootstrapSha == null && bootstrapArmSha == null) {
                throw new IllegalStateException("env manifest contains neither bootstrapSha256 nor bootstrapArmSha256");
            }
            String overlaySha = requireSha256(json.getString("overlaySha256"), "overlaySha256");
            String pkgSha = requireSha256(json.getString("pkgSha256"), "pkgSha256");
            int symlinks = json.optInt("bootstrapSymlinkCount", 0);
            if (bootstrapSha != null && symlinks < 1) {
                throw new IllegalStateException("env manifest contains no arm64 bootstrap symlinks");
            }
            int armSymlinks = json.optInt("bootstrapArmSymlinkCount", 0);
            int x86Symlinks = json.optInt("bootstrapX86SymlinkCount", 0);
            int x64Symlinks = json.optInt("bootstrapX64SymlinkCount", 0);
            if (bootstrapArmSha != null && armSymlinks < 1) {
                throw new IllegalStateException("env manifest contains no arm32 bootstrap symlinks");
            }
            JSONArray packagesJson = json.getJSONArray("seedPackages");
            List<String> packages = new ArrayList<>();
            for (int i = 0; i < packagesJson.length(); i++) {
                String name = packagesJson.getString(i);
                if (!PACKAGE.matcher(name).matches()) {
                    throw new IllegalStateException("invalid seed package name: " + name);
                }
                if (!packages.contains(name)) {
                    packages.add(name);
                }
            }
            String prepatchedPackage = json.optString("prepatchedPackage", null);
            if (prepatchedPackage != null && prepatchedPackage.isEmpty()) {
                prepatchedPackage = null;
            }
            return new EnvBundleManifest(bundleId, bootstrapSha, bootstrapArmSha,
                    bootstrapX86Sha, bootstrapX64Sha, overlaySha,
                    pkgSha, symlinks, armSymlinks, x86Symlinks, x64Symlinks,
                    prepatchedPackage, packages);
        } catch (IOException e) {
            throw new IllegalStateException("required asset missing: " + EnvPaths.ASSET_MANIFEST, e);
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("invalid env manifest: " + e.getMessage(), e);
        }
    }

    public String expectedBootstrapSha256(boolean isArm) {
        if (isArm && bootstrapArmSha256 != null) {
            return bootstrapArmSha256;
        }
        return bootstrapSha256 != null ? bootstrapSha256 : bootstrapArmSha256;
    }

    public int expectedSymlinkCount(boolean isArm) {
        if (isArm && bootstrapArmSymlinkCount > 0) {
            return bootstrapArmSymlinkCount;
        }
        return bootstrapSymlinkCount > 0 ? bootstrapSymlinkCount : bootstrapArmSymlinkCount;
    }

    /** Manifest hash for the bootstrap of a given ABI family, if declared. */
    public String sha256ForArch(EnvInstaller.Arch arch) {
        switch (arch) {
            case ARM64:
                return bootstrapSha256 != null ? bootstrapSha256 : bootstrapArmSha256;
            case ARM32:
                return bootstrapArmSha256 != null ? bootstrapArmSha256 : bootstrapSha256;
            case X86_64:
                return bootstrapX64Sha256 != null ? bootstrapX64Sha256 : bootstrapSha256;
            case X86:
            default:
                return bootstrapX86Sha256 != null ? bootstrapX86Sha256 : bootstrapSha256;
        }
    }

    /** Expected symlink count for the bootstrap of a given ABI family. */
    public int symlinkCountForArch(EnvInstaller.Arch arch) {
        switch (arch) {
            case ARM64:
                return bootstrapSymlinkCount > 0 ? bootstrapSymlinkCount : bootstrapArmSymlinkCount;
            case ARM32:
                return bootstrapArmSymlinkCount > 0 ? bootstrapArmSymlinkCount : bootstrapSymlinkCount;
            case X86_64:
                return bootstrapX64SymlinkCount > 0 ? bootstrapX64SymlinkCount : bootstrapSymlinkCount;
            case X86:
            default:
                return bootstrapX86SymlinkCount > 0 ? bootstrapX86SymlinkCount : bootstrapSymlinkCount;
        }
    }

    public void verifyBootstrap(File archive) throws IOException {
        try {
            String actual = Sha256.of(archive);
            boolean matchesArm64 = bootstrapSha256 != null && bootstrapSha256.equals(actual);
            boolean matchesArm32 = bootstrapArmSha256 != null && bootstrapArmSha256.equals(actual);
            if (!matchesArm64 && !matchesArm32) {
                throw new IOException("bootstrap sha256 mismatch: got " + actual
                        + " want " + expectedBootstrapSha256(false));
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("cannot hash bootstrap archive", e);
        }
    }

    private static String optSha256(JSONObject json, String name) {
        String value = json.optString(name, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        return requireSha256(value, name);
    }

    private static String requireSha256(String value, String name) {
        if (value == null || !SHA256.matcher(value).matches()) {
            throw new IllegalStateException("invalid env manifest field: " + name);
        }
        return value;
    }
}
