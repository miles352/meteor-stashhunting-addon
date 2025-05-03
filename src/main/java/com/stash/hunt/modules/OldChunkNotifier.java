package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.map.mods.SupportMods;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;
import xaero.common.minimap.waypoints.Waypoint;

import java.util.ArrayDeque;

import static com.stash.hunt.Utils.*;


public class OldChunkNotifier extends Module {

    // Trigger if CHUNK_TRIGGER_THRESHOLD old chunks are detected within
    // CHUNK_RESET_INTERVAL ms, decrease spam + decrease false positives
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private int oldChunkCount = 0;
    private long lastResetTime = System.currentTimeMillis();
    private static final int CHUNK_RESET_INTERVAL = 3000;
    private static final int CHUNK_TRIGGER_THRESHOLD = 3;
    // changed anyChunks boolean to a chunkType enum
    private final Setting<ChunkType> chunkType = sgGeneral.add(new EnumSetting.Builder<ChunkType>()
        .name("Chunk Type")
        .description("Which chunk type to receive notifications for")
        .defaultValue(ChunkType.V1_12)
        .build()
    );

    private final Setting<Boolean> notifyOffHighway = sgGeneral.add(new BoolSetting.Builder()
        .name("Notify Trails Off Highway")
        .description("Whether to notify you of old chunks off the highway.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> directionOfTravel = sgGeneral.add(new DoubleSetting.Builder()
        .name("Direction of Travel")
        .description("The direction of travel (yaw) in degrees.")
        .defaultValue(0)
        .min(-180)
        .max(180)
        .visible(notifyOffHighway::get)
        .build()
    );

    private final Setting<Double> distanceOffAxis = sgGeneral.add(new DoubleSetting.Builder()
        .name("Distance Off Axis")
        .description("The distance in chunks off the axis of movement from the player to check for old chunks.")
        .defaultValue(13)
        .sliderRange(0, 15)
        .visible(notifyOffHighway::get)
        .build()
    );

    private final Setting<LogType> logType = sgGeneral.add(new EnumSetting.Builder<LogType>()
        .name("Log Type")
        .description("What to do when an old chunk is detected.")
        .defaultValue(LogType.Marker)
        .build()
    );

    private final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("Webhook Link")
        .description("A discord webhook link. Looks like this: https://discord.com/api/webhooks/webhookUserId/webHookTokenOrSomething")
        .defaultValue("")
        .visible(() -> logType.get() == LogType.Webhook || logType.get() == LogType.Both)
        .build()
    );

    private final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("Ping")
        .description("Whether to ping you or not.")
        .defaultValue(false)
        .visible(() -> logType.get() == LogType.Webhook || logType.get() == LogType.Both)
        .build()
    );
    // added an auto-log option upon chunk detection
    private final Setting<Boolean> autoLog = sgGeneral.add(new BoolSetting.Builder()
        .name("Trail AutoLog")
        .description("Automatically disconnects when a chunk trail is detected.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("Discord ID")
        .description("Your discord ID")
        .defaultValue("")
        .visible(() -> ping.get() && (logType.get() == LogType.Webhook || logType.get() == LogType.Both))
        .build()
    );

    public OldChunkNotifier() {
        super(Addon.CATEGORY, "OldChunkNotifier", "Sends a webhook message and optionally pings you when an old chunk is detected.");
    }

    public enum ChunkType {
        V1_12("1.12 Chunks"),
        V1_19_PLUS("1.19+ Chunks");


        private final String label;

        ChunkType(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    public void onActivate()
    {
        XaeroPlus.EVENT_BUS.register(this);
        oldChunks.clear();
    }

    @Override
    public void onDeactivate()
    {
        XaeroPlus.EVENT_BUS.unregister(this);
    }

    // Prevent the same chunk being sent multiple times.
    private final ArrayDeque<ChunkPos> oldChunks = new ArrayDeque<>();

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event)
    {
        if (event.seenChunk()) return;

        // avoid 2b2t end loading screen
        if (mc.player.getAbilities().allowFlying) return;

        if (oldChunks.size() > 1000) {
            oldChunks.removeFirst();
        }

        if (oldChunks.contains(event.chunk().getPos())) return;
        oldChunks.add(event.chunk().getPos());

        boolean is119NewChunk = ModuleManager.getModule(PaletteNewChunks.class)
            .isNewChunk(
                event.chunk().getPos().x,
                event.chunk().getPos().z,
                event.chunk().getWorld().getRegistryKey()
            );

        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(
                event.chunk().getPos().x,
                event.chunk().getPos().z,
                event.chunk().getWorld().getRegistryKey()
            );

        if (is119NewChunk && !is112OldChunk) return;

        if (chunkType.get() == ChunkType.V1_12 && !is112OldChunk) return;
        if (chunkType.get() == ChunkType.V1_19_PLUS && is119NewChunk) return;

        // prevents a lot of chunk-trail false positive notifications using a small chunk threshold (won't affect off-highway trail notifications)
        if (!notifyOffHighway.get()) {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastResetTime > CHUNK_RESET_INTERVAL) {
                oldChunkCount = 0;
                lastResetTime = currentTime;
            }
            oldChunkCount++;
            if (oldChunkCount < CHUNK_TRIGGER_THRESHOLD) return;

            oldChunkCount = 0;
            lastResetTime = currentTime;
        }


        {
            if (logType.get() == LogType.Both || logType.get() == LogType.Marker)
            {
                createMapMarker(event.chunk().getPos().x, event.chunk().getPos().z);
            }
            if (logType.get() == LogType.Both || logType.get() == LogType.Webhook)
            {
                String message = "";
                if (is112OldChunk && !is119NewChunk) {
                    message = "1.12 Followed in 1.19+ Chunk Detected";
                } else if (is112OldChunk && is119NewChunk) {
                    message = "1.12 Unfollowed in 1.19+ Chunk Detected";
                } else {
                    message = "1.19+ Chunk Detected";
                }
                String finalMessage = message; // must be final for thread operations
                // use threads so if a ton of chunks come at once it doesnt lag the game
                String discordID = !ping.get() || discordId.get().isBlank() ? null : discordId.get();
                new Thread(() -> sendWebhook(webhookLink.get(), "Old Chunk Detected", finalMessage + " at " + mc.player.getPos().toString(), discordID, mc.player.getGameProfile().getName())).start();
                if (autoLog.get()) {
                    mc.execute(() -> {
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().getConnection().disconnect(Text.literal("Chunk trail detected."));
                        }
                    });
                }
            }
        }

        if (notifyOffHighway.get())
        {
            ChunkPos chunkPos = event.chunk().getPos();
            Vec3d direction = yawToDirection(directionOfTravel.get());
            ChunkPos playerChunkPos = mc.player.getChunkPos();
            double distance = distancePointToDirection(new Vec3d(chunkPos.x, 0, chunkPos.z), direction, new Vec3d(playerChunkPos.x, 0, playerChunkPos.z));
            if (distance > distanceOffAxis.get())
            {
                if (logType.get() == LogType.Both || logType.get() == LogType.Marker)
                {
                    createMapMarker(chunkPos.x, chunkPos.z);
                }
                if (logType.get() == LogType.Both || logType.get() == LogType.Webhook)
                {
                    String discordID = !ping.get() || discordId.get().isBlank() ? null : discordId.get();
                    new Thread(() -> sendWebhook(webhookLink.get(), "Old Chunk Detected", "Old chunk detected off the highway at " + chunkPos.x * 16 + " " + chunkPos.z * 16, discordID, mc.player.getGameProfile().getName())).start();
                }
            }
        }
    }

    private void createMapMarker(int x, int z)
    {
        MinimapSession minimapSession = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (minimapSession == null) return;
        MinimapWorld currentWorld = minimapSession.getWorldManager().getCurrentWorld();
        if (currentWorld == null) return;
        WaypointSet waypointSet = currentWorld.getCurrentWaypointSet();
        if (waypointSet == null) return;
        Waypoint waypoint = new Waypoint(
            x * 16,
            70,
            z * 16,
            "Old Chunk",
            "O",
            5,
            0,
            false);
        waypointSet.add(waypoint);
        SupportMods.xaeroMinimap.requestWaypointsRefresh();
    }

    private enum LogType
    {
        Webhook,
        Marker,
        Both
    }
}
