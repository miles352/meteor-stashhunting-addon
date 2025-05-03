package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;
import net.minecraft.text.Text;


import static com.stash.hunt.Utils.sendWebhook;


public class OldChunkNotifier extends Module {

    // Trigger if CHUNK_TRIGGER_THRESHOLD old chunks are detected within
    // CHUNK_RESET_INTERVAL ms, decrease spam + decrease false positives
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private int oldChunkCount = 0;
    private long lastResetTime = System.currentTimeMillis();
    private static final int CHUNK_RESET_INTERVAL = 3000;
    private static final int CHUNK_TRIGGER_THRESHOLD = 3;


    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("Webhook Link")
        .description("A discord webhook link. Looks like this: https://discord.com/api/webhooks/webhookUserId/webHookTokenOrSomething")
        .defaultValue("")
        .build()
    );

    public final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("Ping")
        .description("Whether to ping you or not.")
        .defaultValue(false)
        .build()
    );
    // added an autolog option upon a trail render
    public final Setting<Boolean> autoLog = sgGeneral.add(new BoolSetting.Builder()
        .name("Trail AutoLog")
        .description("Automatically disconnects when an old chunk trail is detected.")
        .defaultValue(false)
        .build()
    );

    public final Setting<ChunkMode> chunkMode = sgGeneral.add(new EnumSetting.Builder<ChunkMode>()
        .name("Chunk Type")
        .description("Which chunk age detection to use.")
        .defaultValue(ChunkMode.V1_12)
        .build()
    );

    public final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("Discord ID")
        .description("Your discord ID")
        .defaultValue("")
        .visible(ping::get)
        .build()
    );

    public OldChunkNotifier() {
        super(Addon.CATEGORY, "OldChunkNotifier", "Sends a webhook message, optionally auto-log upon trail detection.");
    }

    public enum ChunkMode {
        V1_12("1.12 Chunks"),
        V1_19_PLUS("1.19+ Chunks");

        private final String label;

        ChunkMode(String label) {
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
    }

    @Override
    public void onDeactivate()
    {
        XaeroPlus.EVENT_BUS.unregister(this);
    }

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event)
    {
        // avoid 2b2t end loading screen
        if (mc.player.getAbilities().allowFlying) return;

        if (webhookLink.get().isEmpty()) return;
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

        // added chunk type logic for enum option
        String message = "";

        if (is112OldChunk && !is119NewChunk) {
            message = "1.12 Followed in 1.19+ Chunk Detected";
        } else if (is112OldChunk && is119NewChunk) {
            message = "1.12 Unfollowed in 1.19+ Chunk Detected";
        } else {
            message = "1.19+ Chunk Detected";
        }

        long currentTime = System.currentTimeMillis();
        if (currentTime - lastResetTime > CHUNK_RESET_INTERVAL) {
            oldChunkCount = 0;
            lastResetTime = currentTime;
        }

        if (chunkMode.get() == ChunkMode.V1_12 && !is112OldChunk) return;
        if (chunkMode.get() == ChunkMode.V1_19_PLUS && is119NewChunk) return;


        oldChunkCount++;
        if (oldChunkCount < CHUNK_TRIGGER_THRESHOLD) return;

        oldChunkCount = 0;
        lastResetTime = currentTime;

        // use threads so if a ton of chunks come at once it doesnt lag the game
        String finalMessage = message;
        String discordID = discordId.get().isBlank() ? null : discordId.get();
        new Thread(() -> sendWebhook(webhookLink.get(), "Chunk Detected", finalMessage + " at " + mc.player.getPos().toString(), discordID, mc.player.getGameProfile().getName())).start();
        if (autoLog.get()) {
            mc.execute(() -> {
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().getConnection().disconnect(Text.literal("A chunk trail has been detected."));
                }
            });
        }
    }

}
