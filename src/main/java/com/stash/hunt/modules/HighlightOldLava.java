package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;

import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import java.io.*;
import java.util.HashSet;

import static com.stash.hunt.Utils.sendWebhook;


public class HighlightOldLava extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> searchAbove = sgGeneral.add(new IntSetting.Builder()
        .name("search-above")
        .description("Y value to look above")
        .sliderRange(0, 120)
        .build()
    );

    private final Setting<Integer> lavaHeight = sgGeneral.add(new IntSetting.Builder()
        .name("lava-height")
        .description("The height of the lava to count as already loaded")
        .sliderRange(1, 30)
        .build()
    );

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("How far away to render the blocks.")
        .sliderRange(0, 512)
        .build()
    );

    private final Setting<Boolean> disconnectOnFound = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-on-found")
        .description("Will auto disconnect you if old lava is found. (Good for afking)")
        .defaultValue(false)
        .build()
    );

    public final Setting<Mode> logMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("log-mode")
        .description("How results are shown.")
        .defaultValue(Mode.Highlight)
        .build()
    );

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-link")
        .description("A discord webhook link. Looks like this: https://discord.com/api/webhooks/webhookUserId/webHookTokenOrSomething")
        .defaultValue("")
        .visible(() -> logMode.get() == Mode.LogWebhook || logMode.get() == Mode.Both)
        .build()
    );

    public final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("ping-on-lava-found")
        .description("Pings you when lava that matches your search is found.")
        .defaultValue(false)
        .visible(() -> logMode.get() == Mode.LogWebhook || logMode.get() == Mode.Both)
        .build()
    );

    public final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("discord-ID")
        .description("Your discord ID")
        .defaultValue("")
        .visible(() -> logMode.get() == Mode.LogWebhook || logMode.get() == Mode.Both)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("box-render-mode")
        .description("How the shape for the bounding box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The side color of the bounding box.")
        .defaultValue(new SettingColor(16,106,144, 100))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The line color of the bounding box.")
        .defaultValue(new SettingColor(16,106,144, 255))
        .build()
    );

    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HashSet<BlockPos> oldLava;

    private HashSet<Vec3> loadedChunks;

    public HighlightOldLava()
    {
        super(Addon.CATEGORY, "highlight-old-lava", "Highlights lava that has already flowed down");
        oldLava = new HashSet<BlockPos>();
        loadedChunks = new HashSet<Vec3>();
    }

    @Override
    public WWidget getWidget(GuiTheme theme)
    {
        WVerticalList list = theme.verticalList();
        WButton clearLog = list.add(theme.button("Clear saved data.")).widget();

        clearLog.action = () -> {
            File lavaPosFile = new File(new File(new File(MeteorClient.FOLDER, "HighlightOldLava"), Utils.getFileWorldName()),  "lavaPos.json");
            File loadedChunksFile = new File(new File(new File(MeteorClient.FOLDER, "HighlightOldLava"), Utils.getFileWorldName()),  "loadedChunks.json");

            lavaPosFile.delete();
            loadedChunksFile.delete();
        };

        return list;
    }

    @Override
    public void onActivate()
    {
        oldLava = new HashSet<BlockPos>();
        loadedChunks = new HashSet<Vec3>();


        try {
            File lavaPosFile = new File(new File(new File(MeteorClient.FOLDER, "HighlightOldLava"), Utils.getFileWorldName()),  "lavaPos.json");
            FileReader reader = new FileReader(lavaPosFile);
            oldLava = GSON.fromJson(reader, new TypeToken<HashSet<BlockPos>>(){}.getType());
            reader.close();

            File loadedChunksFile = new File(new File(new File(MeteorClient.FOLDER, "HighlightOldLava"), Utils.getFileWorldName()),  "loadedChunks.json");
            FileReader reader2 = new FileReader(loadedChunksFile);
            loadedChunks = GSON.fromJson(reader2, new TypeToken<HashSet<Vec3>>(){}.getType());
            reader2.close();
        } catch (Exception ignored) {

        }
    }

    @Override
    public void onDeactivate()
    {
        try
        {
            File lavaPosFile = new File(new File(new File(MeteorClient.FOLDER, "HighlightOldLava"), Utils.getFileWorldName()),  "lavaPos.json");
            lavaPosFile.getParentFile().mkdirs();
            Writer writer = new FileWriter(lavaPosFile);
            GSON.toJson(oldLava, writer);
            writer.close();

            File loadedChunksFile = new File(new File(new File(MeteorClient.FOLDER, "HighlightOldLava"), Utils.getFileWorldName()),  "loadedChunks.json");
            loadedChunksFile.getParentFile().mkdirs();
            Writer writer2 = new FileWriter(loadedChunksFile);
            GSON.toJson(loadedChunks, writer2);
            writer2.close();
        }
        catch (NullPointerException | IOException e) {

        }
    }


    @EventHandler
    private void onRender(Render3DEvent event) {
        if ((logMode.get() == Mode.Highlight) || (logMode.get() == Mode.Both)) {
            for (BlockPos blockPos : oldLava) {
                if (Math.sqrt(mc.player.distanceToSqr(Vec3.atCenterOf(blockPos))) <= renderDistance.get()) {
                    RenderUtils.renderTickingBlock(blockPos.immutable(), sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
                }
            }
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event)
    {
        ChunkAccess chunk = event.chunk();
        Vec3 chunkPos = Vec3.atCenterOf(chunk.getPos().getWorldPosition());
        // don't check chunks loaded by player
        if (loadedChunks.contains(chunkPos)) return;
        loadedChunks.add(chunkPos);
        HashSet<BlockPos> toAdd = new HashSet<BlockPos>();
        boolean chunkLogged = false;
        for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++)
        {
            for (int z = chunk.getPos().getMinBlockZ(); z <= chunk.getPos().getMaxBlockZ(); z++)
            {
                int height = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE).getFirstAvailable(x - chunk.getPos().getMinBlockX(), z - chunk.getPos().getMinBlockZ());

                for (int y = height; y > searchAbove.get(); y--)
                {
                    BlockPos blockPos = new BlockPos(x, y, z);

                    BlockState blockState = chunk.getBlockState(blockPos);
                    // try to prevent bastions or nether fortresses being detected
                    if (blockState.getBlock() == Blocks.POLISHED_BLACKSTONE_BRICKS ||
                        blockState.getBlock() == Blocks.NETHER_BRICKS) return;


                    if (blockState.getBlock() == Blocks.LAVA)
                    {
                        boolean heightFound = true;
                        for (int i = 1; i < lavaHeight.get(); i++)
                        {
                            if (chunk.getBlockState(blockPos.offset(0, i, 0)).getBlock() != Blocks.LAVA)
                            {
                                heightFound = false;
                                break;
                            }
                        }
                        if (heightFound)
                        {
                            if ((logMode.get() == Mode.LogWebhook || logMode.get() == Mode.Both) && !webhookLink.get().isEmpty())
                            {
                                new Thread(() -> sendWebhook(webhookLink.get(), "Old Chunk Found", "At: " + blockPos.getX() + " " + blockPos.getZ(), (ping.get() ? discordId.get() : null), mc.player.getGameProfile().name())).start();
                            }
                            if (disconnectOnFound.get()) mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal("[HighlightOldLava] Old lava was found.")));
                            oldLava.add(blockPos);
                            return;
                        }
                    }


                }
            }
        }
    }


    public enum Mode
    {
        Highlight,
        LogWebhook,
        Both
    }
}
