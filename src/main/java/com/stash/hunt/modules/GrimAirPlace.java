package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

public class GrimAirPlace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRange = settings.createGroup("range");

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("The delay in ticks between block placements.")
        .defaultValue(0)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Renders a block overlay where the obsidian will be placed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides of the blocks being rendered.")
        .defaultValue(new SettingColor(204, 0, 0, 10))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines of the blocks being rendered.")
        .defaultValue(new SettingColor(204, 0, 0, 255))
        .build()
    );

    private final Setting<Boolean> customRange = sgRange.add(new BoolSetting.Builder()
        .name("custom-range")
        .description("Use custom range for air place.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> range = sgRange.add(new DoubleSetting.Builder()
        .name("range")
        .description("Custom range to place at.")
        .visible(customRange::get)
        .defaultValue(5)
        .min(0)
        .sliderMax(5.5)
        .build()
    );

    private HitResult hitResult;
    private int delay = 0;

    public GrimAirPlace() {
        super(Addon.CATEGORY, "grim-air-place", "Places a block where your crosshair is pointing at.");
    }

    @Override
    public void onActivate()
    {
        delay = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null) return;
        delay++;
        double r = customRange.get() ? range.get() : mc.player.blockInteractionRange();
        hitResult = mc.getCameraEntity().pick(r, 0, false);

        if (!(hitResult instanceof BlockHitResult blockHitResult) || !(mc.player.getMainHandItem().getItem() instanceof BlockItem) && !(mc.player.getMainHandItem().getItem() instanceof SpawnEggItem)) return;

        if (delay < placeDelay.get()) return;

        if (mc.options.keyUse.isDown()) {
            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, new BlockPos(0,0,0), Direction.DOWN));

            mc.player.connection.send(new ServerboundUseItemOnPacket(InteractionHand.OFF_HAND, blockHitResult, mc.player.containerMenu.getStateId() + 2));

            mc.player.connection.send(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, new BlockPos(0,0,0), Direction.DOWN));

            mc.player.swing(InteractionHand.MAIN_HAND);
            delay = 0;
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!(hitResult instanceof BlockHitResult blockHitResult)
            || !mc.level.getBlockState(blockHitResult.getBlockPos()).canBeReplaced()
            || !(mc.player.getMainHandItem().getItem() instanceof BlockItem) && !(mc.player.getMainHandItem().getItem() instanceof SpawnEggItem)
            || !render.get()) return;

        event.renderer.box(blockHitResult.getBlockPos(), sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }
}
