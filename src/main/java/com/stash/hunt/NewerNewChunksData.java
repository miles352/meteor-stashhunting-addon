package com.stash.hunt;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Reads chunk detection data from the Trouser-Streak "NewerNewChunks" addon.
 *
 * <p>Tries to read the live module's in-memory sets via reflection first, and falls back to the
 * chunk data files that the module persists to disk when "SaveChunkData" is enabled (default).
 */
public class NewerNewChunksData
{
    private static final long FILE_RELOAD_MS = 5000;

    private final Minecraft mc = Minecraft.getInstance();

    // Live module reflection accessors
    private boolean reflectionInitialized = false;
    private boolean reflectionAvailable = false;
    private Module module;
    private Field fNewChunks;
    private Field fOldChunks;
    private Field fBeingUpdatedOldChunks;
    private Field fOldGenerationOldChunks;

    // File-based fallback, keyed by dimension id
    private final Map<String, CachedSet> newChunks = new HashMap<>();
    private final Map<String, CachedSet> oldChunks = new HashMap<>();
    private final Map<String, CachedSet> beingUpdated = new HashMap<>();
    private final Map<String, CachedSet> oldGeneration = new HashMap<>();

    private static class CachedSet {
        long lastLoad;
        Set<Long> chunks = new HashSet<>();
    }

    public void init()
    {
        if (reflectionInitialized) return;
        reflectionInitialized = true;

        try {
            Module newerNewChunks = Modules.get().get("NewerNewChunks");
            if (newerNewChunks == null) return;

            Class<?> cls = newerNewChunks.getClass();
            fNewChunks = getAccessibleField(cls, "newChunks");
            fOldChunks = getAccessibleField(cls, "oldChunks");
            fBeingUpdatedOldChunks = getAccessibleField(cls, "beingUpdatedOldChunks");
            fOldGenerationOldChunks = getAccessibleField(cls, "OldGenerationOldChunks");

            if (fNewChunks != null && fOldChunks != null) {
                module = newerNewChunks;
                reflectionAvailable = true;
            }
        }
        catch (Throwable t) {
            reflectionAvailable = false;
        }
    }

    public boolean isLiveModuleAvailable()
    {
        init();
        return reflectionAvailable;
    }

    private static Field getAccessibleField(Class<?> cls, String name)
    {
        try {
            Field field = cls.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }
        catch (Throwable t) {
            return null;
        }
    }

    public boolean isNewChunk(int x, int z, ResourceKey<Level> dimension)
    {
        return contains(fNewChunks, "NewChunkData.txt", x, z, dimension);
    }

    public boolean isOldChunk(int x, int z, ResourceKey<Level> dimension)
    {
        return contains(fOldChunks, "OldChunkData.txt", x, z, dimension)
            || contains(fOldGenerationOldChunks, "OldGenerationChunkData.txt", x, z, dimension);
    }

    public boolean isInverseChunk(int x, int z, ResourceKey<Level> dimension)
    {
        return contains(fBeingUpdatedOldChunks, "BeingUpdatedChunkData.txt", x, z, dimension)
            || contains(fOldChunks, "OldChunkData.txt", x, z, dimension);
    }

    @SuppressWarnings("unchecked")
    private boolean contains(Field field, String fileName, int x, int z, ResourceKey<Level> dimension)
    {
        init();

        if (field != null && reflectionAvailable && module != null) {
            try {
                Object value = field.get(module);
                if (value instanceof Set<?> set) {
                    return set.contains(new ChunkPos(x, z));
                }
            }
            catch (Throwable t) {
                // fall back to the saved data files
            }
        }

        return fileSet(fileName, dimension).contains(ChunkPos.pack(x, z));
    }

    private Set<Long> fileSet(String fileName, ResourceKey<Level> dimension)
    {
        String dimensionId = dimension.identifier().toString();
        Map<String, CachedSet> cache = cacheFor(fileName);

        long now = System.currentTimeMillis();
        CachedSet cached = cache.computeIfAbsent(dimensionId, k -> new CachedSet());
        if (cached.lastLoad == 0 || now - cached.lastLoad > FILE_RELOAD_MS) {
            cached.lastLoad = now;
            cached.chunks = loadFile(fileName, dimensionId);
        }

        return cached.chunks;
    }

    private Map<String, CachedSet> cacheFor(String fileName)
    {
        return switch (fileName) {
            case "NewChunkData.txt" -> newChunks;
            case "OldChunkData.txt" -> oldChunks;
            case "BeingUpdatedChunkData.txt" -> beingUpdated;
            default -> oldGeneration;
        };
    }

    private Set<Long> loadFile(String fileName, String dimensionId)
    {
        Set<Long> result = new HashSet<>();
        try {
            Path baseDir = baseDir(dimensionId);
            if (baseDir == null) return result;

            Path file = baseDir.resolve(fileName);
            if (!Files.exists(file)) return result;

            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line == null || line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length == 2) {
                    try {
                        int x = Integer.parseInt(parts[0].trim());
                        int z = Integer.parseInt(parts[1].trim());
                        result.add(ChunkPos.pack(x, z));
                    }
                    catch (NumberFormatException e) {
                        // skip malformed line
                    }
                }
            }
        }
        catch (IOException | RuntimeException e) {
            // ignore, empty set will be returned
        }

        return result;
    }

    private Path baseDir(String dimensionId)
    {
        try {
            return FabricLoader.getInstance().getGameDir()
                .resolve("TrouserStreak")
                .resolve("NewChunks")
                .resolve(serverKey())
                .resolve(sanitize(dimensionId));
        }
        catch (Throwable t) {
            return null;
        }
    }

    private String serverKey()
    {
        try {
            if (mc.isLocalServer() && mc.getSingleplayerServer() != null) {
                String levelName = mc.getSingleplayerServer().getWorldData().getLevelName();
                if (levelName != null && !levelName.isEmpty()) return sanitize(levelName);
                return "singleplayer";
            }

            ServerData serverData = mc.getCurrentServer();
            return serverData != null ? sanitize(serverData.ip) : "unknown";
        }
        catch (Throwable t) {
            return "unknown";
        }
    }

    private static String sanitize(String value)
    {
        return value.replaceAll("[^a-zA-Z0-9._\\-]", "_");
    }
}