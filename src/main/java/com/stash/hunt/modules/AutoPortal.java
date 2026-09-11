package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public class AutoPortal extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final List<BlockPos> waitingForBreak = new ArrayList<>();


    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks between each obsidian placement.")
        .defaultValue(1)
        .sliderRange(1, 20)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .description("How many blocks to place each tick.")
        .defaultValue(1)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders the portal frame as it's being placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the box is rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(100, 100, 255, 10))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(100, 100, 255, 255))
        .build()
    );

    private final List<BlockPos> portalBlocks = new ArrayList<>();
    private int delay = 0;
    private int index = 0;

    public AutoPortal() {
        super(Addon.CATEGORY, "auto-portal", "For the Base Hunter who has places to be.");
    }

    @Override
    public void onActivate() {
        int obsidianCount = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.OBSIDIAN) {
                obsidianCount += mc.player.getInventory().getItem(i).getCount();
            }
        }

        if (obsidianCount < 10) {
            error("Not enough obsidian to build the portal (need at least 10)!");
            toggle();
            return;
        }
        portalBlocks.clear();
        index = 0;
        delay = 0;

        // directly in front + block position check
        Direction forward = mc.player.getDirection();
        Direction right = forward.getClockWise();
        BlockPos standingPos = mc.player.blockPosition(); // temp mutable ref
        BlockPos blockBelow = standingPos.below();
        double blockHeight = mc.level.getBlockState(blockBelow).getCollisionShape(mc.level, blockBelow).max(Direction.Axis.Y);
        // (height < 1.0)
        if (blockHeight < 1.0) {
            standingPos = standingPos.above();
        }
        BlockPos base = standingPos
            .relative(forward, 2)
            .relative(right, -1);
        // duplicate check
        int obsidianCheck = 0;

        List<BlockPos> checkPositions = List.of(
            base.relative(right, 1), base.relative(right, 2),
            base.relative(right, 0).above(1), base.relative(right, 0).above(2), base.relative(right, 0).above(3),
            base.relative(right, 3).above(1), base.relative(right, 3).above(2), base.relative(right, 3).above(3),
            base.relative(right, 1).above(4), base.relative(right, 2).above(4)
        );
        // block obstruction check (temporary until fixed)
        boolean obstructed = checkPositions.stream().anyMatch(pos -> !mc.level.getBlockState(pos).canBeReplaced());
        // will remove later once we fix portal block obstruction
        if (obstructed) {
            error("Portal area obstructed. Move and try again.");
            portalBlocks.clear();
            portalBlocks.addAll(checkPositions); // just render blocked frame
            index = checkPositions.size(); // skip building
            return;
        }

        for (BlockPos checkPos : checkPositions) {
            if (mc.level.getBlockState(checkPos).getBlock().asItem() == Items.OBSIDIAN) {
                obsidianCheck++;
            }
        }

        if (obsidianCheck >= checkPositions.size()) {
            error("A portal already exists here!");
            toggle();
            return;
        }

        portalBlocks.add(base.relative(right, 1));
        portalBlocks.add(base.relative(right, 2));

        for (int i = 1; i <= 3; i++) {
            portalBlocks.add(base.relative(right, 0).above(i));
        }

        for (int i = 1; i <= 3; i++) {
            portalBlocks.add(base.relative(right, 3).above(i));
        }

        portalBlocks.add(base.relative(right, 1).above(4));
        portalBlocks.add(base.relative(right, 2).above(4));

        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).getItem() == Items.OBSIDIAN) {
                mc.player.getInventory().setSelectedSlot(i);
                break;
            }
        }
    }

    @Override
    public void onDeactivate() {
        portalBlocks.clear();
        index = 0;
        delay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.level == null) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof BlockItem blockItem)) return;
        if (blockItem.getBlock().asItem() != Items.OBSIDIAN) return;

        if (index >= portalBlocks.size()) {
            toggle();
            return;
        }

        delay++;
        if (delay < placeDelay.get()) return;
        for (int i = 0; i < blocksPerTick.get() && index < portalBlocks.size(); i++, index++) {
            BlockPos pos = portalBlocks.get(index);
            // prevent faulty portal placements (not being used due to boolean obstruction check above, but will fix in the future)
            if (!mc.level.getBlockState(pos).canBeReplaced()) {
                if (!waitingForBreak.contains(pos) && mc.level.getBlockState(pos).getBlock().asItem() != Items.OBSIDIAN) {
                    if (mc.gameMode != null) {
                        mc.gameMode.startDestroyBlock(pos, Direction.UP);
                        mc.player.swing(InteractionHand.MAIN_HAND);
                        waitingForBreak.add(pos);
                    }
                }
                index--; // loop again to finish placing
                return;
            }

            waitingForBreak.remove(pos);

            BlockHitResult bhr = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);

            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            mc.player.connection.send(new ServerboundUseItemOnPacket(
                InteractionHand.OFF_HAND, bhr, mc.player.containerMenu.getStateId() + 2));
            mc.player.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
            mc.player.swing(InteractionHand.MAIN_HAND);
        }
        delay = 0;

        if (index >= portalBlocks.size()) {
            // auto light
            for (int i = 0; i < 9; i++) {
                if (mc.player.getInventory().getItem(i).getItem() == Items.FLINT_AND_STEEL) {
                    mc.player.getInventory().setSelectedSlot(i);

                    BlockPos firePos = portalBlocks.get(0).above();
                    BlockHitResult fireHit = new BlockHitResult(Vec3.atCenterOf(firePos), Direction.UP, firePos, false);

                    mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, fireHit);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                    break;
                }
            }
            info("Portal complete. AutoPortal disabled.");
            toggle();
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get()) return;
        for (int i = index; i < portalBlocks.size(); i++) {
            BlockPos pos = portalBlocks.get(i);
            event.renderer.box(pos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
        }
    }
}
