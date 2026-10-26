package net.rasanovum.roxy.loader;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipFile;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.locating.IDependencyLocator;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

public final class RoxyDependencyLocator implements IDependencyLocator {
    private static final String RELOCATED_JARS = "META-INF/roxy-jars/";
    private static final String FABRIC_STUBS = "META-INF/jars/roxy-fabric-stubs.jar";
    private static final String FABRIC_COMMAND_STUBS = "META-INF/jars/roxy-fabric-command-stubs.jar";
    private static final String CANDIDATE_LOCATOR_SERVICE = "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator";
    private static final String MACOS_NATIVE_PATH_PROPERTY = "roxy.macos.nativePath";
    private static final List<String> MACOS_ARM64_NATIVE_JARS = List.of(
            "roxy-lwjgl-lmdb-natives-macos-arm64.jar",
            "roxy-lwjgl-zstd-natives-macos-arm64.jar",
            "roxy-rocksdbjni-10.2.1-natives-macos-arm64.jar");
    private static final List<String> MACOS_ARM64_NATIVE_ENTRIES = List.of(
            "macos/arm64/org/lwjgl/lmdb/liblwjgl_lmdb.dylib",
            "macos/arm64/org/lwjgl/zstd/liblwjgl_zstd.dylib",
            "librocksdbjni-osx-arm64.jnilib");
    private static final List<String> MACOS_ARM64_NATIVE_FILE_HINTS = List.of(
            "lwjgl-lmdb-3.3.3-natives-macos-arm64.jar",
            "lwjgl-zstd-3.3.3-natives-macos-arm64.jar",
            "rocksdbjni-10.2.1.jar");

    @Override
    public void scanMods(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        boolean voxyLoaded = containsVoxy(loadedMods);
        boolean forgifiedFabricLoader = containsForgifiedFabricLoader(loadedMods);
        boolean fabricCommandApi = containsFabricCommandApi(loadedMods);
        if (voxyLoaded) addOwnStubs(!forgifiedFabricLoader, !fabricCommandApi, pipeline);
        boolean c2meOpenCl = containsC2meOpenCl(loadedMods);
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) {
                continue;
            }
            try (Source source = Source.open(path)) {
                if (source == null) {
                    continue;
                }
                JsonArray jars = readJarsArray(source);
                if (jars == null) {
                    continue;
                }
                for (JsonElement entry : jars) {
                    if (!entry.isJsonObject()) {
                        continue;
                    }
                    JsonElement file = entry.getAsJsonObject().get("file");
                    if (file == null || !file.isJsonPrimitive()) {
                        continue;
                    }
                    String fileName = file.getAsString();
                    if (isCurrentPlatformJar(fileName) && !isProvidedLibrary(fileName, c2meOpenCl)) {
                        addLibrary(source, relocatedPath(fileName), pipeline);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        if (voxyLoaded) addPreparedMacOsArm64Natives(loadedMods, pipeline);
    }

    @Override
    public int getPriority() {
        return -1000;
    }

    private static void addOwnStubs(boolean loaderStubs, boolean commandStubs, IDiscoveryPipeline pipeline) {
        if (!loaderStubs && !commandStubs) {
            return;
        }
        for (Path path : RoxyLocator.ownModPaths()) {
            try (Source source = Source.open(path)) {
                if (source == null || source.read(FABRIC_STUBS) == null) {
                    continue;
                }
                if (loaderStubs) {
                    addLibrary(source, FABRIC_STUBS, pipeline);
                }
                if (commandStubs) {
                    addLibrary(source, FABRIC_COMMAND_STUBS, pipeline);
                }
                return;
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    private static void addLibrary(Source source, String innerPath, IDiscoveryPipeline pipeline) {
        try {
            Path extracted = source.extract(innerPath);
            if (extracted != null) {
                pipeline.addModFile(IModFile.create(SecureJar.from(JarContents.of(extracted)), JarModsDotTomlModFileReader::manifestParser, IModFile.Type.GAMELIBRARY, ModFileDiscoveryAttributes.DEFAULT));
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static boolean containsVoxy(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) continue;
            if (path.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("voxy")) return true;
            try (JarContents contents = JarContents.of(path);
                 InputStream input = RoxyJarContents.openFile(contents, "fabric.mod.json")) {
                if (input == null) continue;
                JsonObject metadata = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
                JsonElement id = metadata.get("id");
                if (id != null && id.isJsonPrimitive() && id.getAsString().equals("voxy")) return true;
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static void addPreparedMacOsArm64Natives(List<IModFile> loadedMods, IDiscoveryPipeline pipeline) {
        if (!isMacOsArm64()) return;

        List<Integer> missingNativeIndices = new java.util.ArrayList<>();
        for (int index = 0; index < MACOS_ARM64_NATIVE_JARS.size(); index++) {
            if (!containsBundledNativeResource(
                    loadedMods,
                    MACOS_ARM64_NATIVE_FILE_HINTS.get(index),
                    MACOS_ARM64_NATIVE_ENTRIES.get(index))) {
                missingNativeIndices.add(index);
            }
        }
        if (missingNativeIndices.isEmpty()) return;

        String configuredPath = System.getProperty(MACOS_NATIVE_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isBlank()) {
            System.err.println("[Roxy] Voxy's macOS arm64 native payloads are incomplete. Storage may fail to initialize. Run scripts/macos/prepare-natives.sh and set -D"
                    + MACOS_NATIVE_PATH_PROPERTY + "=<repo>/.roxy-macos/natives.");
            return;
        }

        Path nativeDirectory = Path.of(configuredPath).toAbsolutePath().normalize();
        for (int index : missingNativeIndices) {
            String jarName = MACOS_ARM64_NATIVE_JARS.get(index);
            Path nativeJar = nativeDirectory.resolve(jarName);
            if (!Files.isRegularFile(nativeJar)) {
                throw new IllegalStateException("[Roxy] Required macOS arm64 native payload is missing: " + nativeJar
                        + ". Run scripts/macos/prepare-natives.sh.");
            }
            try {
                validatePreparedNativeJar(nativeJar, MACOS_ARM64_NATIVE_ENTRIES.get(index));
                IModFile library = IModFile.create(
                        SecureJar.from(JarContents.of(nativeJar)),
                        JarModsDotTomlModFileReader::manifestParser,
                        IModFile.Type.GAMELIBRARY,
                        ModFileDiscoveryAttributes.DEFAULT);
                pipeline.addModFile(library);
            } catch (IOException | RuntimeException exception) {
                throw new IllegalStateException("[Roxy] Could not add prepared macOS native payload " + nativeJar, exception);
            }
        }
    }

    private static boolean containsBundledNativeResource(
            List<IModFile> loadedMods,
            String fileNameHint,
            String expectedEntry) {
        for (IModFile mod : loadedMods) {
            Path modPath = mod.getFilePath();
            if (modPath == null) continue;
            try (Source source = Source.open(modPath)) {
                if (source == null) continue;
                JsonArray jars = readJarsArray(source);
                if (jars == null) continue;
                for (JsonElement entry : jars) {
                    if (!entry.isJsonObject()) continue;
                    JsonElement file = entry.getAsJsonObject().get("file");
                    if (file == null || !file.isJsonPrimitive()) continue;
                    String fileName = file.getAsString();
                    if (!fileName.toLowerCase(Locale.ROOT).endsWith(fileNameHint)) continue;
                    byte[] nested = source.read(relocatedPath(fileName));
                    if (nested == null) continue;
                    try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(nested))) {
                        ZipEntry nestedEntry;
                        while ((nestedEntry = zip.getNextEntry()) != null) {
                            if (nestedEntry.getName().equals(expectedEntry)) return true;
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static void validatePreparedNativeJar(Path path, String expectedEntry) throws IOException {
        try (ZipFile archive = new ZipFile(path.toFile())) {
            List<String> files = archive.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(ZipEntry::getName)
                    .toList();
            if (!files.equals(List.of(expectedEntry))) {
                throw new IOException("Expected only native resource " + expectedEntry + " but found " + files);
            }
        }
    }

    private static boolean isMacOsArm64() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (os.contains("mac") || os.contains("darwin"))
                && (architecture.equals("aarch64") || architecture.equals("arm64"));
    }

    private static boolean containsForgifiedFabricLoader(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path != null) {
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.startsWith("forgified-fabric-loader-")) {
                    return true;
                }
            }
        }
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) {
                continue;
            }
            try (Source source = Source.open(path)) {
                if (source != null && source.anyEntry(RoxyDependencyLocator::isForgifiedFabricLoaderEntry)) {
                    return true;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static boolean containsFabricCommandApi(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path != null) {
                String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fileName.startsWith("forgified-fabric-api-") || fileName.startsWith("fabric-command-api-v2-")) {
                    return true;
                }
            }
        }
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) {
                continue;
            }
            try (Source source = Source.open(path)) {
                if (source != null && source.anyEntry(RoxyDependencyLocator::isFabricCommandApiEntry)) {
                    return true;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static boolean isFabricCommandApiEntry(String name) {
        return name.endsWith(".jar") && name.startsWith("META-INF/")
                && (name.contains("/forgified-fabric-api-") || name.contains("/fabric-command-api-v2-"));
    }

    private static boolean containsC2meOpenCl(List<IModFile> loadedMods) {
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) {
                continue;
            }
            String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.contains("c2me") && name.contains("accel-opencl")) {
                return true;
            }
        }
        for (IModFile mod : loadedMods) {
            Path path = mod.getFilePath();
            if (path == null) {
                continue;
            }
            try (Source source = Source.open(path)) {
                if (source == null) {
                    continue;
                }
                byte[] services = source.read(CANDIDATE_LOCATOR_SERVICE);
                if (services != null && new String(services, StandardCharsets.UTF_8).contains("c2me.opts.accel.opencl")) {
                    return true;
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static boolean isForgifiedFabricLoaderEntry(String name) {
        return name.startsWith("META-INF/jars/forgified-fabric-loader-") && name.endsWith("-full.jar");
    }

    private static JsonArray readJarsArray(Source source) throws IOException {
        byte[] bytes = source.read("fabric.mod.json");
        if (bytes == null) {
            return null;
        }
        try {
            JsonObject metadata = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement jars = metadata.get("jars");
            return jars != null && jars.isJsonArray() ? jars.getAsJsonArray() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String relocatedPath(String originalPath) {
        String normalized = originalPath.replace('\\', '/');
        return RELOCATED_JARS + normalized.substring(normalized.lastIndexOf('/') + 1);
    }

    private static boolean isCurrentPlatformJar(String path) {
        return RoxyNativePlatform.isCurrentPlatformJar(
                path,
                System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    private static boolean isProvidedLibrary(String path, boolean c2meOpenCl) {
        String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String fileName = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (fileName.startsWith("lz4-java-") && fileName.endsWith(".jar")) {
            return true;
        }
        return c2meOpenCl && fileName.startsWith("lwjgl-zstd-") && fileName.endsWith(".jar");
    }

    private interface EntryTest {
        boolean test(String name);
    }

    private abstract static class Source implements AutoCloseable {
        static Source open(Path path) throws IOException {
            if (path.getFileSystem() == FileSystems.getDefault() && Files.isRegularFile(path)) {
                return new ZipSource(path);
            }
            if (Files.exists(path)) {
                return new ContentsSource(path);
            }
            return null;
        }

        abstract byte[] read(String name) throws IOException;

        abstract boolean anyEntry(EntryTest test) throws IOException;

        abstract Path extract(String name) throws IOException;

        @Override
        public abstract void close() throws IOException;
    }

    private static final class ZipSource extends Source {
        private final Path path;
        private final ZipFile zip;

        ZipSource(Path path) throws IOException {
            this.path = path;
            this.zip = new ZipFile(path.toFile());
        }

        @Override
        byte[] read(String name) throws IOException {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            try (InputStream input = zip.getInputStream(entry)) {
                return input.readAllBytes();
            }
        }

        @Override
        boolean anyEntry(EntryTest test) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                if (test.test(entries.nextElement().getName())) {
                    return true;
                }
            }
            return false;
        }

        @Override
        Path extract(String name) throws IOException {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null) {
                return null;
            }
            String fileName = name.substring(name.lastIndexOf('/') + 1);
            String key = cacheKey(path.getFileName() + "|" + Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis() + "|" + name + "|" + entry.getSize() + "|" + entry.getCrc());
            Path target = RoxyCache.dir().resolve("jij").resolve(key + "-" + fileName);
            if (Files.isRegularFile(target) && Files.size(target) == entry.getSize()) {
                return target;
            }
            Files.createDirectories(target.getParent());
            Path partial = Files.createTempFile(target.getParent(), "partial-", ".jar");
            try (InputStream input = zip.getInputStream(entry)) {
                Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                RoxyCache.deleteQuietly(partial);
                if (!Files.isRegularFile(target)) {
                    throw e;
                }
            }
            return target;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    private static final class ContentsSource extends Source {
        private final JarContents contents;

        ContentsSource(Path path) {
            this.contents = JarContents.of(path);
        }

        @Override
        byte[] read(String name) throws IOException {
            if (!RoxyJarContents.containsFile(contents, name)) {
                return null;
            }
            try (InputStream input = RoxyJarContents.openFile(contents, name)) {
                return input.readAllBytes();
            }
        }

        @Override
        boolean anyEntry(EntryTest test) throws IOException {
            Path root = contents.getPrimaryPath();
            Path jars = root.resolve("META-INF/jars");
            if (!Files.isDirectory(jars)) {
                return false;
            }
            try (Stream<Path> files = Files.walk(jars, 1)) {
                return files.anyMatch(file -> test.test(root.relativize(file).toString().replace('\\', '/')));
            }
        }

        @Override
        Path extract(String name) throws IOException {
            if (!RoxyJarContents.containsFile(contents, name)) {
                return null;
            }
            String fileName = name.substring(name.lastIndexOf('/') + 1);
            Path output = Files.createTempFile("roxy-jij-", "-" + fileName);
            output.toFile().deleteOnExit();
            try (InputStream input = RoxyJarContents.openFile(contents, name)) {
                Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return output;
        }

        @Override
        public void close() throws IOException {
            contents.close();
        }
    }

    private static String cacheKey(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
