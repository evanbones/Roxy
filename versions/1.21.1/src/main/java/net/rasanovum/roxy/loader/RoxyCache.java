package net.rasanovum.roxy.loader;

import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

final class RoxyCache {
    private RoxyCache() {
    }

    static Path dir() {
        return FMLPaths.GAMEDIR.get().resolve(".cache").resolve("roxy");
    }

    static Path patchedJar(Path original, String modId, boolean fogStorage, Iterable<String> inputs) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(Files.readAllBytes(original));
            for (String input : inputs) {
                digest.update(input.getBytes(StandardCharsets.UTF_8));
                try (InputStream stream = RoxyCache.class.getClassLoader().getResourceAsStream(input)) {
                    if (stream == null) return null;
                    digest.update(stream.readAllBytes());
                }
            }
            digest.update(String.valueOf(System.getProperty("roxy.intermediaryMapping")).getBytes(StandardCharsets.UTF_8));
            digest.update(String.valueOf(System.getProperty("roxy.neoformMappings")).getBytes(StandardCharsets.UTF_8));
            digest.update((byte) (fogStorage ? 1 : 0));
            return dir().resolve("roxy-patched-" + modId + "-" + HexFormat.of().formatHex(digest.digest(), 0, 12) + ".jar");
        } catch (IOException | NoSuchAlgorithmException | RuntimeException e) {
            return null;
        }
    }

    static void store(Path source, Path target, String prefix) {
        try {
            Files.createDirectories(target.getParent());
            Path partial = Files.createTempFile(target.getParent(), "partial-", ".jar");
            Files.copy(source, partial, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                deleteQuietly(partial);
            }
            prune(prefix, target);
        } catch (IOException | RuntimeException ignored) {
        }
    }

    static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static void prune(String prefix, Path keep) {
        try (Stream<Path> files = Files.list(keep.getParent())) {
            files.filter(file -> file.getFileName().toString().startsWith(prefix) && !file.equals(keep))
                    .forEach(RoxyCache::deleteQuietly);
        } catch (IOException | RuntimeException ignored) {
        }
    }
}
