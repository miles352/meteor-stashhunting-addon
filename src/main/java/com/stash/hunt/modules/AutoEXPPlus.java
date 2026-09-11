/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package com.stash.hunt.modules;

import baritone.api.BaritoneAPI;
import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.Scaffold;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.ArrayList;
import java.util.List;

public class AutoEXPPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Which items to repair.")
        .defaultValue(Mode.Both)
        .build()
    );

    private final Setting<Boolean> replenish = sgGeneral.add(new BoolSetting.Builder()
        .name("replenish")
        .description("Automatically replenishes exp into a selected hotbar slot.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> slot = sgGeneral.add(new IntSetting.Builder()
        .name("exp-slot")
        .description("The slot to replenish exp into.")
        .visible(replenish::get)
        .defaultValue(6)
        .range(1, 9)
        .sliderRange(1, 9)
        .build()
    );

    private final Setting<Integer> minThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("min-threshold")
        .description("The minimum durability percentage that an item needs to fall to, to be repaired.")
        .defaultValue(30)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Integer> maxThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("max-threshold")
        .description("The maximum durability percentage to repair items to.")
        .defaultValue(80)
        .range(1, 100)
        .sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> ignoreElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-elytra")
        .description("Ignore elytra when repairing.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> pauseBaritone = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-baritone")
        .description("Pauses baritone before repairing so the player stays still and resumes it afterwards.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseMovementModules = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-movement-modules")
        .description("Disables modules that move the player (AFKVanillaFly, ElytraFlyPlusPlus, GotoPosition, Pitch40Util, TrailFollower, TrailMaker) while repairing and re-enables them afterwards.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> scaffold = sgGeneral.add(new BoolSetting.Builder()
        .name("scaffold-platform")
        .description("Uses Meteor's Scaffold module to build a platform to stand on while repairing.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scaffoldRadius = sgGeneral.add(new DoubleSetting.Builder()
        .name("scaffold-radius")
        .description("How big of a platform to build around the player.")
        .defaultValue(3)
        .min(0)
        .sliderMax(6)
        .visible(scaffold::get)
        .build()
    );

    private final Setting<Integer> scaffoldTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("scaffold-timeout")
        .description("How long to wait for a platform to be built before repairing anyway.")
        .defaultValue(20 * 10)
        .range(20, 20 * 60)
        .sliderRange(20, 20 * 30)
        .visible(scaffold::get)
        .build()
    );

    private enum Phase {
        NONE,
        PAUSE,
        SCAFFOLD,
        REPAIR
    }

    private static final List<Class<? extends Module>> MOVEMENT_MODULES = List.of(
        AFKVanillaFly.class,
        ElytraFlyPlusPlus.class,
        GotoPosition.class,
        Pitch40Util.class,
        TrailFollower.class,
        TrailMaker.class
    );

    private int repairingI = -1;
    private Phase phase = Phase.NONE;
    private int pauseTicks = 0;
    private int scaffoldTicks = 0;

    private boolean baritonePaused = false;
    private boolean scaffoldEnabled = false;
    private boolean scaffoldWasActive = false;
    private Boolean oldAirPlace = null;
    private Double oldRadius = null;
    private Integer oldBlocksPerTick = null;
    private final List<Module> disabledDuringRepair = new ArrayList<>();

    public AutoEXPPlus() {
        super(Addon.CATEGORY, "auto-exp-plus", "Automatically repairs your armor and tools in pvp.");
    }

    @Override
    public void onActivate() {
        repairingI = -1;
        phase = Phase.NONE;
    }

    @Override
    public void onDeactivate() {
        finishRepair();
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (phase == Phase.NONE) {
            findRepairTarget();
            if (repairingI != -1) {
                startRepair();
            }
            return;
        }

        switch (phase) {
            case PAUSE -> {
                if (++pauseTicks < 10) return;
                pauseTicks = 0;
                if (scaffold.get()) {
                    enableScaffold();
                }
                scaffoldTicks = 0;
                phase = Phase.SCAFFOLD;
            }
            case SCAFFOLD -> {
                scaffoldTicks++;
                boolean ready = isOnPlatform();
                if (ready || scaffoldTicks >= scaffoldTimeout.get()) {
                    disableScaffold();
                    if (!ready) info("Could not build a platform, repairing in midair.");
                    phase = Phase.REPAIR;
                }
            }
            case REPAIR -> {
                if (!needsRepair(mc.player.getInventory().getItem(repairingI), maxThreshold.get())) {
                    repairingI = -1;
                    finishRepair();
                    return;
                }
                throwExperience();
            }
        }
    }

    private void findRepairTarget() {
        repairingI = -1;

        if (mode.get() != Mode.Hands) {
            for (EquipmentSlot slot : EquipmentSlotGroup.ARMOR) {
                ItemStack stack = mc.player.getItemBySlot(slot);
                if (needsRepair(stack, minThreshold.get())) {
                    repairingI = SlotUtils.ARMOR_START + slot.getIndex();
                    return;
                }
            }
        }

        if (mode.get() != Mode.Armor && repairingI == -1) {
            for (InteractionHand hand : InteractionHand.values()) {
                if (needsRepair(mc.player.getItemInHand(hand), minThreshold.get())) {
                    repairingI = hand == InteractionHand.MAIN_HAND ? mc.player.getInventory().getSelectedSlot() : SlotUtils.OFFHAND;
                    return;
                }
            }
        }
    }

    private void startRepair() {
        if (pauseBaritone.get()) {
            pauseBaritoneNow();
        }
        if (pauseMovementModules.get()) {
            pauseMovementModules();
        }
        pauseTicks = 0;
        phase = Phase.PAUSE;
    }

    private void finishRepair() {
        disableScaffold();
        restoreMovementModules();
        resumeBaritone();
        phase = Phase.NONE;
    }

    private void throwExperience() {
        FindItemResult exp = InvUtils.find(Items.EXPERIENCE_BOTTLE);

        if (exp.found()) {
            if (!exp.isHotbar() && !exp.isOffhand()) {
                if (!replenish.get()) return;
                InvUtils.move().from(exp.slot()).toHotbar(slot.get() - 1);
            }

            Rotations.rotate(mc.player.getYRot(), 90, () -> {
                if (exp.getHand() != null) {
                    mc.gameMode.useItem(mc.player, exp.getHand());
                }
                else {
                    InvUtils.swap(exp.slot(), true);
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    InvUtils.swapBack();
                }
            });
        }
    }

    private void pauseBaritoneNow() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("pause");
            baritonePaused = true;
        }
        catch (Throwable t) {
            info("Baritone not found, skipping pause.");
        }
    }

    private void resumeBaritone() {
        if (!baritonePaused) return;
        baritonePaused = false;
        try {
            Class.forName("baritone.api.BaritoneAPI");
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("resume");
        }
        catch (Throwable t) {
            // ignore
        }
    }

    private void pauseMovementModules() {
        disabledDuringRepair.clear();
        for (Class<? extends Module> moduleClass : MOVEMENT_MODULES) {
            Module module = Modules.get().get(moduleClass);
            if (module != null && module.isActive()) {
                module.toggle();
                disabledDuringRepair.add(module);
            }
        }
    }

    private void restoreMovementModules() {
        for (Module module : disabledDuringRepair) {
            if (module != null && !module.isActive()) {
                module.toggle();
            }
        }
        disabledDuringRepair.clear();
    }

    private void enableScaffold() {
        if (scaffoldEnabled) return;

        Module scaffoldModule = Modules.get().get(Scaffold.class);
        if (scaffoldModule == null) return;

        scaffoldWasActive = scaffoldModule.isActive();

        try {
            Setting<Boolean> airPlace = (Setting<Boolean>) scaffoldModule.settings.get("air-place");
            Setting<Double> radius = (Setting<Double>) scaffoldModule.settings.get("radius");
            Setting<Integer> blocksPerTick = (Setting<Integer>) scaffoldModule.settings.get("blocks-per-tick");

            if (airPlace != null) oldAirPlace = airPlace.get();
            if (radius != null) oldRadius = radius.get();
            if (blocksPerTick != null) oldBlocksPerTick = blocksPerTick.get();

            if (airPlace != null) airPlace.set(true);
            if (radius != null) radius.set(scaffoldRadius.get());
            if (blocksPerTick != null) blocksPerTick.set(3);
        }
        catch (Throwable t) {
            // leave scaffold settings untouched
        }

        if (!scaffoldWasActive) {
            scaffoldModule.toggle();
        }

        scaffoldEnabled = true;
    }

    private void disableScaffold() {
        if (!scaffoldEnabled) return;

        Module scaffoldModule = Modules.get().get(Scaffold.class);
        if (scaffoldModule != null) {
            try {
                if (oldAirPlace != null) ((Setting<Boolean>) scaffoldModule.settings.get("air-place")).set(oldAirPlace);
                if (oldRadius != null) ((Setting<Double>) scaffoldModule.settings.get("radius")).set(oldRadius);
                if (oldBlocksPerTick != null) ((Setting<Integer>) scaffoldModule.settings.get("blocks-per-tick")).set(oldBlocksPerTick);
            }
            catch (Throwable t) {
                // ignore
            }

            if (!scaffoldWasActive && scaffoldModule.isActive()) {
                scaffoldModule.toggle();
            }
        }

        scaffoldWasActive = false;
        scaffoldEnabled = false;
        oldAirPlace = null;
        oldRadius = null;
        oldBlocksPerTick = null;
    }

    private boolean isOnPlatform() {
        if (mc.player == null || mc.level == null) return false;
        if (mc.player.onGround()) return true;

        BlockPos below = mc.player.blockPosition().below();
        return !mc.level.getBlockState(below).isAir();
    }

    private boolean needsRepair(ItemStack itemStack, double threshold) {
        if (itemStack.isEmpty() || !Utils.hasEnchantments(itemStack, Enchantments.MENDING)) return false;
        return (itemStack.getMaxDamage() - itemStack.getDamageValue()) / (double) itemStack.getMaxDamage() * 100 <= threshold;
    }

    public enum Mode {
        Armor,
        Hands,
        Both
    }
}