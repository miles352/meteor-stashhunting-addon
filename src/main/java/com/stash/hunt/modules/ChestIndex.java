package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.InventoryEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockIterator;
import meteordevelopment.meteorclient.utils.misc.Names;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.*;
import java.io.IOException;

import com.google.gson.*;

import static meteordevelopment.meteorclient.utils.Utils.getItemsInContainerItem;
import static meteordevelopment.meteorclient.utils.Utils.hasItems;

public class ChestIndex extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> searchRange = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Search chests within this range of the player.")
        .defaultValue(4)
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in ticks between chest interactions.")
        .defaultValue(5)
        .min(0)
        .max(40)
        .build()
    );

    private final Setting<DisplayType> displayType = sgGeneral.add(new EnumSetting.Builder<DisplayType>()
        .name("chat-output-type")
        .description("Unit to use when displaying results.")
        .defaultValue(DisplayType.ItemCount)
        .build()
    );

    private final Setting<Boolean> highlightSearched = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-searched-blocks")
        .defaultValue(true)
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


    private HashSet<BlockPos> searched;

    private HashMap<String, Integer> blocks;

    private boolean awaiting;

    // up to 2 if it's a double chest
    private BlockPos[] currPos;

    private int tickCounter;

    public ChestIndex()
    {
        super(Addon.CATEGORY, "chest-index", "Displays a total count of blocks in your chests (buggy and will probably break for lots of chests)");
        searched = new HashSet<BlockPos>();
        blocks = new HashMap<String, Integer>();
    }

    private void saveToJson(Gson gson, String fileName, JsonObject json) throws IOException
    {
        String timeStamp = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss").format(new java.util.Date());
        File file = new File(new File(MeteorClient.FOLDER, "ChestIndex"), fileName + "-" + timeStamp + ".json");
        file.getParentFile().mkdirs();
        Writer writer = new FileWriter(file);
        gson.toJson(json, writer);
        writer.close();
    }

    private List<Map.Entry<String, Integer>> sortBlocks()
    {
        // Sort blocks by value (count) in descending order
        return blocks.entrySet().stream()
            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
            .toList();
    }

    @Override
    public WWidget getWidget(GuiTheme theme)
    {
        WVerticalList list = theme.verticalList();



        WButton showBlocks = list.add(theme.button("Display the blocks logged")).widget();
        showBlocks.action = () -> {
            info("showing blocks");
            ArrayList<Map.Entry<String, Integer>> blockList = new ArrayList<>(blocks.entrySet());
            blockList.sort(Map.Entry.comparingByValue());
            Collections.reverse(blockList);

            double factor = switch (displayType.get()) {
                case DubCount -> 64.0 * 27.0 * 27.0 * 2.0;
                case ShulkerCount -> 64.0 * 27.0;
                default -> 1.0;
            };
            for (Map.Entry<String, Integer> block : blockList)
            {
                info(block.getKey() + ": " + block.getValue() / factor);
            }
        };

        WButton clearBlocks = list.add(theme.button("Clear Blocks")).widget();
        clearBlocks.action = () -> {
            searched = new HashSet<>();
            blocks = new HashMap<>();
        };

        // Create a Gson instance for pretty printing without escaping characters
        final Gson gson = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping() // This prevents escaping characters like `'`
            .create();

        // Button: Export (ID)
        WButton exportByID = list.add(theme.button("Export (ID)")).widget();
        exportByID.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    json.add(entry.getKey(), new JsonPrimitive(entry.getValue()));
                }
                saveToJson(gson, "blocks_id", json);
                info("Exported blocks to ChestIndex/blocks_id.json using IDs (sorted, pretty-printed).");
            } catch (IOException e) {
                error("Failed to export blocks (ID): " + e.getMessage());
            }
        };

        // Button: Export (Name)
        WButton exportByName = list.add(theme.button("Export (Name)")).widget();
        exportByName.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    // Convert key to Identifier
                    Identifier identifier = Identifier.tryParse(entry.getKey());

                    // Get the item from the registry
                    Item item = BuiltInRegistries.ITEM.getValue(identifier);

                    // Get the display name (human-readable)
                    String displayName = Names.get(item);
                    json.add(displayName, new JsonPrimitive(entry.getValue()));
                }
                saveToJson(gson, "blocks_name", json);
                info("Exported blocks to ChestIndex/blocks_name.json using human-readable names (sorted, pretty-printed).");
            } catch (IOException e) {
                error("Failed to export blocks (Name): " + e.getMessage());
            }
        };

        // Button: Export (Dubs)
        WButton exportDubs = list.add(theme.button("Export (Dubs)")).widget();
        exportDubs.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    // Convert key to Identifier
                    Identifier identifier = Identifier.tryParse(entry.getKey());

                    // Get the item from the registry
                    Item item = BuiltInRegistries.ITEM.getValue(identifier);

                    // Get the display name (human-readable)
                    String displayName = Names.get(item);

                    // Calculate stack size
                    int stackSize = item.getDefaultMaxStackSize();

                    // Correct calculation for Dubs (shulkers full of items in dubs)
                    double dubs = entry.getValue() / (stackSize * 27.0 * 54.0); // 1458 slots per double chest
                    json.add(displayName, new JsonPrimitive(String.format("%.2f", dubs)));
                }

                // Write the pretty-printed JSON
                saveToJson(gson, "blocks_dubs", json);
                info("Exported blocks to ChestIndex/blocks_dubs.json (calculated as dubs with shulkers).");
            } catch (IOException e) {
                error("Failed to export blocks (Dubs): " + e.getMessage());
            }
        };

        WButton exportShulkers = list.add(theme.button("Export (Shulkers)")).widget();
        exportShulkers.action = () -> {
            try {
                List<Map.Entry<String, Integer>> sortedBlocks = sortBlocks();

                JsonObject json = new JsonObject();
                for (Map.Entry<String, Integer> entry : sortedBlocks) {
                    try {
                        // Convert key to Identifier
                        Identifier identifier = Identifier.tryParse(entry.getKey());

                        // Get the item from the registry
                        Item item = BuiltInRegistries.ITEM.getValue(identifier);

                        // Get the display name (human-readable)
                        String displayName = Names.get(item);

                        // Calculate stack size
                        int stackSize = item.getDefaultMaxStackSize();

                        // Correct calculation for Shulkers
                        double shulkers = entry.getValue() / (stackSize * 27.0); // 27 slots per shulker
                        json.add(displayName, new JsonPrimitive(String.format("%.2f", shulkers)));
                    } catch (Exception e) {
                        json.add(entry.getKey(), new JsonPrimitive("0.00"));
                    }
                }

                // Write the pretty-printed JSON
                saveToJson(gson, "blocks_shulkers", json);
                info("Exported blocks to ChestIndex/blocks_shulkers.json (calculated as shulkers, by name).");
            } catch (IOException e) {
                error("Failed to export blocks (Shulkers): " + e.getMessage());
            }
        };

        return list;
    }



    @EventHandler
    private void onRender(Render3DEvent event) {
        if (highlightSearched.get()){
            for (BlockPos blockPos : searched)
            {
                RenderUtils.renderTickingBlock(blockPos.immutable(), sideColor.get(), lineColor.get(), shapeMode.get(), 0, 8, true, false);
            }
        }
    }

    @Override
    public void onActivate()
    {
//        searched = new HashSet<BlockPos>();
//        blocks = new HashMap<String, Integer>();
        awaiting = false;
        currPos = new BlockPos[2];
        tickCounter = 0;
    }

    // only open new chest if awaiting = false;
    // after opening set awaiting to true
    // when inventory opened set awaiting to false;

    @EventHandler
    private void onTick(TickEvent.Pre event)
    {
        if (mc.gui.screen() instanceof ContainerScreen) return;

        if (tickCounter < delay.get())
        {
            tickCounter++;
            return;
        }
        else
        {
            tickCounter = 0;
        }


        BlockIterator.register(searchRange.get(), searchRange.get(), (blockPos, blockState) ->
        {
            // might be too many packets from not checking if menu already open
            if (!awaiting &&
                !searched.contains(blockPos.immutable()) &&
                (blockState.getBlock() == Blocks.CHEST ||
                    blockState.getBlock() == Blocks.TRAPPED_CHEST ||
                    blockState.getBlock() == Blocks.BARREL ||
                    blockState.getBlock() instanceof ShulkerBoxBlock))
            {




                Vec3 vec = new Vec3(blockPos.getX(), blockPos.getY(), blockPos.getZ());
                BlockHitResult hitResult = new BlockHitResult(vec, Direction.UP, blockPos, false);
                if (mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hitResult) == InteractionResult.SUCCESS)
                {
                    awaiting = true;
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    // find way to see when the interaction fails
                    info("interacted");
                    currPos[0] = blockPos.immutable();
                    if (blockState.getBlock() == Blocks.CHEST || blockState.getBlock() == Blocks.TRAPPED_CHEST)
                    {
                        ChestType chestType = blockState.getValue(ChestBlock.TYPE);
                        if (chestType == ChestType.LEFT || chestType == ChestType.RIGHT)
                        {
                            Direction facing = blockState.getValue(ChestBlock.FACING);
                            BlockPos otherPartPos = blockPos.relative(chestType == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise());

                            currPos[1] = otherPartPos;
                        }
                    }
                }
            }
        });
    }
    @EventHandler
    private void onInventory(InventoryEvent event) {
        AbstractContainerMenu handler = mc.player.containerMenu;
        awaiting = false;
        for (BlockPos blockPos : currPos)
        {
            if (blockPos != null) searched.add(blockPos);
        }
        NonNullList<Slot> slots = handler.slots;
        for (int i = 0; i < slots.size() - 36; i++)
        {
            ItemStack stack = slots.get(i).getItem();
            if (!stack.isEmpty())
            {

                if (hasItems(stack))
                {
                    ItemStack[] items = new ItemStack[27];
                    getItemsInContainerItem(stack, items);
                    for (ItemStack item : items)
                    {
                        if (!item.isEmpty())
                        {
                            String nameOfblock = item.getItem().toString();
                            blocks.compute(nameOfblock, (k, currentCount) -> (currentCount == null) ? item.getCount() : currentCount + item.getCount());
                        }
                    }
                }
                else
                {
                    String nameOfblock = stack.getItem().toString();
                    blocks.compute(nameOfblock, (k, currentCount) -> (currentCount == null) ? stack.getCount() : currentCount + stack.getCount());
                }

            }
        }
        mc.player.closeContainer();
    }

    private enum DisplayType
    {
        ItemCount,
        DubCount,
        ShulkerCount
    }
}
