package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.game.OpenScreenEvent;
import meteordevelopment.orbit.EventPriority;
import xaero.common.minimap.waypoints.Waypoint;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.phys.Vec3;
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

import java.io.*;
import java.util.*;

import static com.stash.hunt.Utils.sendWebhook;

public class BetterStashFinder extends Module
{
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public List<Chunk> chunks = new ArrayList<>();

    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgGeneral.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Select the storage blocks to search for.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumStorageCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-storage-count")
        .description("The minimum amount of storage blocks in a chunk to record the chunk.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .build()
    );

    private final Setting<Boolean> shulkerInstantHit = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-instant-hit")
        .description("If a single shulker counts as a stash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> crafterInstantHit = sgGeneral.add(new BoolSetting.Builder()
        .name("crafter-instant-hit")
        .description("If a single auto crafter counts as a stash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> disableOnTeleport = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-teleport-or-death")
        .description("If on, will disable this module when respawning or teleporting to try to prevent coord leaks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreTrialChambers = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-trial-chambers")
        .description("Attempts to ignore trial chambers, but may cause false negatives if someone made their base to look like a trial chamber.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumDistance = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-distance")
        .description("The minimum distance you must be from spawn to record a certain chunk.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> onlyOldchunks = sgGeneral.add(new BoolSetting.Builder()
        .name("only-old-chunks")
        .description("Checks that the chunks it scans have already been loaded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> saveToWaypoints = sgGeneral.add(new BoolSetting.Builder()
        .name("save-to-waypoints")
        .description("Creates xaeros minimap waypoints for stash finds.")
        .defaultValue(false)
        .onChanged(this::waypointSettingChanged)
        .build()
    );

    private final Setting<Boolean> sendNotifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Sends Minecraft notifications when new stashes are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("The mode to use for notifications.")
        .defaultValue(Mode.Both)
        .visible(sendNotifications::get)
        .build()
    );

    private final Setting<Boolean> sendWebhook = sgGeneral.add(new BoolSetting.Builder()
        .name("send-webhook")
        .description("Sends a webhook when a stash is found.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-link")
        .description("A discord webhook link. Looks like this: https://discord.com/api/webhooks/webhookUserId/webHookTokenOrSomething")
        .defaultValue("")
        .visible(sendWebhook::get)
        .build()
    );

    public final Setting<Boolean> advancedLogging = sgGeneral.add(new BoolSetting.Builder()
        .name("advanced-logging")
        .description("Will log more information, including the amount of each container found.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> ping = sgGeneral.add(new BoolSetting.Builder()
        .name("ping-for-stash-finder")
        .description("Pings you for stash finder and base finder messages")
        .defaultValue(false)
        .visible(sendWebhook::get)
        .build()
    );

    public final Setting<String> discordId = sgGeneral.add(new StringSetting.Builder()
        .name("discord-ID")
        .description("Your discord ID")
        .defaultValue("")
        .visible(() -> sendWebhook.get() && ping.get())
        .build()
    );

    public BetterStashFinder()
    {
        super(Addon.CATEGORY, "better-stash-finder", "Meteors StashFinder but with more features.");
    }

    private Vec3 lastPosition = null;

    @Override
    public void onActivate() {
        lastPosition = null;
        XaeroPlus.EVENT_BUS.register(this);
        load();
    }

    @Override
    public void onDeactivate() {
        XaeroPlus.EVENT_BUS.unregister(this);
    }

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event) {
        if (event.seenChunk()) return;
        // Check the distance.
        double chunkXAbs = Math.abs(event.chunk().getPos().x() * 16);
        double chunkZAbs = Math.abs(event.chunk().getPos().z() * 16);
        if (Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) < minimumDistance.get()) return;

        Chunk chunk = new Chunk(event.chunk().getPos());

        ResourceKey<Level> currentDimension = mc.level.dimension();

        ChunkPos chunkPos = chunk.chunkPos;
        PaletteNewChunks paletteNewChunks = ModuleManager.getModule(PaletteNewChunks.class);
        boolean is119NewChunk = paletteNewChunks
            .isNewChunk(
                chunkPos.x(),
                chunkPos.z(),
                currentDimension
            );

        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(
                chunkPos.x(),
                chunkPos.z(),
                currentDimension
            );

        // Check that the chunk is in old chunks
        if (onlyOldchunks.get() && (is119NewChunk && !is112OldChunk)) return;

        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (!storageBlocks.get().contains(blockEntity.getType())) continue;

            Block blockUnder = mc.level.getBlockState(blockEntity.getBlockPos().below()).getBlock();
            if (ignoreTrialChambers.get() && blockUnder.equals(Blocks.CUT_COPPER.waxed().oxidized()) ||
                blockUnder.equals(Blocks.TUFF_BRICKS) || blockUnder.equals(Blocks.COPPER_BLOCK.waxed().unaffected()) ||
                blockUnder.equals(Blocks.COPPER_BLOCK.waxed().oxidized()))
            {
                continue;
            }

            if (blockEntity instanceof ChestBlockEntity) chunk.chests++;
            else if (blockEntity instanceof BarrelBlockEntity) chunk.barrels++;
            else if (blockEntity instanceof ShulkerBoxBlockEntity) chunk.shulkers++;
            else if (blockEntity instanceof EnderChestBlockEntity) chunk.enderChests++;
            else if (blockEntity instanceof AbstractFurnaceBlockEntity) chunk.furnaces++;
            else if (blockEntity instanceof DispenserBlockEntity) chunk.dispensersDroppers++;
            else if (blockEntity instanceof HopperBlockEntity) chunk.hoppers++;
            else if (blockEntity instanceof CrafterBlockEntity) chunk.crafters++;
        }

        if ((chunk.getTotal() >= minimumStorageCount.get()) || (shulkerInstantHit.get() && chunk.shulkers > 0) || (crafterInstantHit.get() && chunk.crafters > 0)) {
            Chunk prevChunk = null;
            int i = chunks.indexOf(chunk);

            if (i < 0) chunks.add(chunk);
            else prevChunk = chunks.set(i, chunk);

            saveJson();
            saveCsv();

            if (!chunk.equals(prevChunk) || !chunk.countsEqual(prevChunk)) {
                if (sendNotifications.get())
                {
                    switch (notificationMode.get())
                    {
                        case Chat -> info("Found stash at (highlight)%s(default), (highlight)%s(default).", chunk.x, chunk.z);
                        case Toast -> {
                            MeteorToast toast = new MeteorToast.Builder(title).icon(Items.CHEST).text("Found Stash!").build();
                            mc.gui.toastManager().addToast(toast);
                        }
                        case Both -> {
                            info("Found stash at (highlight)%s(default), (highlight)%s(default).", chunk.x, chunk.z);
                            MeteorToast toast = new MeteorToast.Builder(title).icon(Items.CHEST).text("Found Stash!").build();
                            mc.gui.toastManager().addToast(toast);
                        }
                    }
                }

                if (sendWebhook.get() && !webhookLink.get().isEmpty())
                {
                    if (advancedLogging.get())
                    {
                        String chunkType = "";
                        if (is119NewChunk && !is112OldChunk) chunkType = "new";
                        else if (is119NewChunk && is112OldChunk) chunkType = "unfollowed 1.12";
                        else if (!is119NewChunk && !is112OldChunk) chunkType = "1.19";
                        else if (!is119NewChunk && is112OldChunk) chunkType = "followed 1.12";
                        String json = "{\"embeds\": [{" +
                            "\"title\": \"Stash Found!\"," +
                            "\"color\": 2154012," +
                            "\"description\": \"Coordinates: || X: " + chunk.x + " Z: " + chunk.z + "|| in " + chunkType + " chunks\"," +
                            "\"fields\": [" +
                                "{" +
                                    "\"name\": \"Chests\"," +
                                    "\"value\": " + chunk.chests + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Barrels\"," +
                                    "\"value\": " + chunk.barrels + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Shulkers\"," +
                                    "\"value\": " + chunk.shulkers + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Ender Chests\"," +
                                    "\"value\": " + chunk.enderChests + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Hoppers\"," +
                                    "\"value\": " + chunk.hoppers + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Dispensers/Droppers\"," +
                                    "\"value\": " + chunk.dispensersDroppers + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Furnaces\"," +
                                    "\"value\": " + chunk.furnaces + "," +
                                    "\"inline\": true" +
                                "}," +
                                "{" +
                                    "\"name\": \"Crafters\"," +
                                    "\"value\": " + chunk.crafters + "," +
                                    "\"inline\": true" +
                                "}" +
                            "]" +
                        "}]}";

                        new Thread(() -> sendWebhook(webhookLink.get(), json, ping.get() ? discordId.get() : null)).start();
                    }
                    else
                    {
                        String message = "Found stash at " + chunk.x + ", " + chunk.z + ".";
                        new Thread(() -> sendWebhook(webhookLink.get(), title, message, ping.get() ? discordId.get() : null, mc.player.getGameProfile().name())).start();
                    }
                }

                if (saveToWaypoints.get())
                {
                    WaypointSet waypointSet = getWaypointSet();
                    if (waypointSet == null) return;
                    addToWaypoints(waypointSet, chunk);
                    SupportMods.xaeroMinimap.requestWaypointsRefresh();
                }
            }
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        // Sort
        chunks.sort(Comparator.comparingInt(value -> -value.getTotal()));

        WVerticalList list = theme.verticalList();

        // Clear
        WButton clear = list.add(theme.button("Clear")).widget();

        WTable table = new WTable();
        if (!chunks.isEmpty()) list.add(table);

        clear.action = () -> {
            removeAllStashWaypoints(chunks);
            chunks.clear();
            table.clear();
        };

        // Chunks
        fillTable(theme, table);

        return list;
    }

    private void fillTable(GuiTheme theme, WTable table) {
        for (Chunk chunk : chunks) {
            table.add(theme.label("Pos: " + chunk.x + ", " + chunk.z));
            table.add(theme.label("Total: " + chunk.getTotal()));

            WButton open = table.add(theme.button("Open")).widget();
            open.action = () -> mc.gui.setScreen(new ChunkScreen(theme, chunk));

            WButton gotoBtn = table.add(theme.button("Goto")).widget();
            gotoBtn.action = () -> PathManagers.get().moveTo(new BlockPos(chunk.x, 0, chunk.z), true);

            WMinus delete = table.add(theme.minus()).widget();
            delete.action = () -> {
                if (chunks.remove(chunk)) {
                    table.clear();
                    fillTable(theme, table);

                    saveJson();
                    saveCsv();
                    Waypoint waypoint = getWaypointByCoordinate(chunk.x, chunk.z);
                    if (waypoint != null)
                    {
                        WaypointSet waypointSet = getWaypointSet();
                        if (waypointSet != null)
                        {
                            waypointSet.remove(waypoint);
                            SupportMods.xaeroMinimap.requestWaypointsRefresh();
                        }
                    }
                }
            };

            table.row();
        }
    }

    private void load() {
        boolean loaded = false;

        // Try to load json
        File file = getJsonFile();
        if (file.exists()) {
            try {
                FileReader reader = new FileReader(file);
                chunks = GSON.fromJson(reader, new TypeToken<List<Chunk>>() {}.getType());
                reader.close();

                for (Chunk chunk : chunks) chunk.calculatePos();

                loaded = true;
            } catch (Exception ignored) {
                if (chunks == null) chunks = new ArrayList<>();
            }
        }

        // Try to load csv
        file = getCsvFile();
        if (!loaded && file.exists()) {
            try {
                BufferedReader reader = new BufferedReader(new FileReader(file));
                reader.readLine();

                String line;
                while ((line = reader.readLine()) != null) {
                    String[] values = line.split(" ");
                    Chunk chunk = new Chunk(new ChunkPos(Integer.parseInt(values[0]), Integer.parseInt(values[1])));

                    chunk.chests = Integer.parseInt(values[2]);
                    chunk.shulkers = Integer.parseInt(values[3]);
                    chunk.enderChests = Integer.parseInt(values[4]);
                    chunk.furnaces = Integer.parseInt(values[5]);
                    chunk.dispensersDroppers = Integer.parseInt(values[6]);
                    chunk.hoppers = Integer.parseInt(values[7]);

                    chunks.add(chunk);
                }

                reader.close();
            } catch (Exception ignored) {
                if (chunks == null) chunks = new ArrayList<>();
            }
        }
        // TODO: Add all stashes as waypoints
    }

    private void saveCsv() {
        try {
            File file = getCsvFile();
            file.getParentFile().mkdirs();
            Writer writer = new FileWriter(file);

            writer.write("X,Z,Chests,Barrels,Shulkers,EnderChests,Furnaces,DispensersDroppers,Hoppers\n");
            for (Chunk chunk : chunks) chunk.write(writer);

            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void saveJson() {
        try {
            File file = getJsonFile();
            file.getParentFile().mkdirs();
            Writer writer = new FileWriter(file);
            GSON.toJson(chunks, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getJsonFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "better-stash-finder"), Utils.getFileWorldName()), "stashes.json");
    }

    private File getCsvFile() {
        return new File(new File(new File(MeteorClient.FOLDER, "better-stash-finder"), Utils.getFileWorldName()), "stashes.csv");
    }

    @Override
    public String getInfoString() {
        return String.valueOf(chunks.size());
    }

    private Waypoint getWaypointByCoordinate(int x, int z)
    {
        WaypointSet waypointSet = getWaypointSet();
        if (waypointSet == null) return null;
        for (Waypoint waypoint : waypointSet.getWaypoints())
        {
            if (waypoint.getX() == x && waypoint.getZ() == z)
            {
                return waypoint;
            }
        }
        return null;
    }

    private void removeAllStashWaypoints(List<Chunk> chunks)
    {
        WaypointSet waypointSet = getWaypointSet();
        if (waypointSet == null) return;
        for (Chunk chunk : chunks)
        {
            Waypoint waypoint = getWaypointByCoordinate(chunk.x, chunk.z);
            if (waypoint != null)
            {
                waypointSet.remove(waypoint);
            }
        }
        SupportMods.xaeroMinimap.requestWaypointsRefresh();
    }

    private WaypointSet getWaypointSet()
    {
        MinimapSession minimapSession = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (minimapSession == null) return null;
        MinimapWorld currentWorld = minimapSession.getWorldManager().getCurrentWorld();
        if (currentWorld == null) return null;
        return currentWorld.getCurrentWaypointSet();
    }

    private void addToWaypoints(WaypointSet waypointSet, Chunk chunk)
    {
        int x = chunk.x;
        int z = chunk.z;

        // dont add waypoint that already exists
        if (getWaypointByCoordinate(x, z) != null) return;

        String waypointName = getWaypointName(chunk);

        // set color based on total storage blocks
        int color = 0;
        if (chunk.getTotal() < 15) color = 10; // green
        else if (chunk.getTotal() < 50) color = 14; // i forgot what these are lmao
        else if (chunk.getTotal() < 100) color = 12;
        else if (chunk.getTotal() >= 100) color = 4; // red i think

        Waypoint waypoint = new Waypoint(
            x,
            70,
            z,
            waypointName,
            "S",
            color,
            0,
            false);

        waypointSet.add(waypoint);
    }

    private static String getWaypointName(Chunk chunk) {
        String waypointName = "";
        if (chunk.chests > 0) waypointName += "C:" + chunk.chests;
        if (chunk.barrels > 0) waypointName += "B:" + chunk.barrels;
        if (chunk.shulkers > 0) waypointName += "S:" + chunk.shulkers;
        if (chunk.enderChests > 0) waypointName += "E:" + chunk.enderChests;
        if (chunk.hoppers > 0) waypointName += "H:" + chunk.hoppers;
        if (chunk.dispensersDroppers > 0) waypointName += "D:" + chunk.dispensersDroppers;
        if (chunk.furnaces > 0) waypointName += "F:" + chunk.furnaces;
        if (chunk.crafters > 0) waypointName += "A:" + chunk.crafters;
        return waypointName;
    }

    private void waypointSettingChanged(boolean enabled)
    {
        if (!enabled) {
            removeAllStashWaypoints(chunks);
        } else {
            WaypointSet waypointSet = getWaypointSet();
            if (waypointSet == null) return;
            for (Chunk chunk : chunks) {
                addToWaypoints(waypointSet, chunk);
            }
            SupportMods.xaeroMinimap.requestWaypointsRefresh();
        }
    }


    public enum Mode {
        Chat,
        Toast,
        Both
    }

    public static class Chunk {
        private static final StringBuilder sb = new StringBuilder();

        public ChunkPos chunkPos;
        public transient int x, z;
        public int chests, barrels, shulkers, enderChests, furnaces, dispensersDroppers, hoppers, crafters;

        public Chunk(ChunkPos chunkPos) {
            this.chunkPos = chunkPos;

            calculatePos();
        }

        public void calculatePos() {
            x = chunkPos.x() * 16 + 8;
            z = chunkPos.z() * 16 + 8;
        }

        public int getTotal() {
            return chests + barrels + shulkers + enderChests + furnaces + dispensersDroppers + hoppers + crafters;
        }

        public void write(Writer writer) throws IOException {
            sb.setLength(0);
            sb.append(x).append(',').append(z).append(',');
            sb.append(chests).append(',').append(barrels).append(',').append(shulkers).append(',').append(enderChests).append(',').append(furnaces).append(',').append(dispensersDroppers).append(',').append(hoppers).append(',').append(crafters).append('\n');
            writer.write(sb.toString());
        }

        public boolean countsEqual(Chunk c) {
            if (c == null) return false;
            return chests != c.chests || barrels != c.barrels || shulkers != c.shulkers || enderChests != c.enderChests || furnaces != c.furnaces || dispensersDroppers != c.dispensersDroppers || hoppers != c.hoppers || crafters != c.crafters;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Chunk chunk = (Chunk) o;
            return Objects.equals(chunkPos, chunk.chunkPos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkPos);
        }
    }

    private static class ChunkScreen extends WindowScreen {
        private final Chunk chunk;

        public ChunkScreen(GuiTheme theme, Chunk chunk) {
            super(theme, "Chunk at " + chunk.x + ", " + chunk.z);

            this.chunk = chunk;
        }

        @Override
        public void initWidgets() {
            WTable t = add(theme.table()).expandX().widget();

            // Total
            t.add(theme.label("Total:"));
            t.add(theme.label(chunk.getTotal() + ""));
            t.row();

            t.add(theme.horizontalSeparator()).expandX();
            t.row();

            // Separate
            t.add(theme.label("Chests:"));
            t.add(theme.label(chunk.chests + ""));
            t.row();

            t.add(theme.label("Barrels:"));
            t.add(theme.label(chunk.barrels + ""));
            t.row();

            t.add(theme.label("Shulkers:"));
            t.add(theme.label(chunk.shulkers + ""));
            t.row();

            t.add(theme.label("Ender Chests:"));
            t.add(theme.label(chunk.enderChests + ""));
            t.row();

            t.add(theme.label("Furnaces:"));
            t.add(theme.label(chunk.furnaces + ""));
            t.row();

            t.add(theme.label("Dispensers and droppers:"));
            t.add(theme.label(chunk.dispensersDroppers + ""));
            t.row();

            t.add(theme.label("Hoppers:"));
            t.add(theme.label(chunk.hoppers + ""));
            t.row();

            t.add(theme.label("Crafters:"));
            t.add(theme.label(chunk.crafters + ""));
        }
    }

    @meteordevelopment.orbit.EventHandler(priority = EventPriority.HIGH)
    private void onOpenScreenEvent(OpenScreenEvent event) {
        if (!(event.screen instanceof DeathScreen)) return;
        if (!disableOnTeleport.get()) return;

        this.toggle();
    }

    @meteordevelopment.orbit.EventHandler(priority = EventPriority.HIGH)
    private void onPlayerMove(PlayerMoveEvent event) {
        if (lastPosition != null)
        {
            if (disableOnTeleport.get() && mc.player.distanceToSqr(lastPosition) > 16 * 16) this.toggle();
        }
        lastPosition = mc.player.position();
    }
}
