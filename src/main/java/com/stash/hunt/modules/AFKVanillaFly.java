package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import com.stash.hunt.Utils;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.item.Items;

public class AFKVanillaFly extends Module {
    private long lastRocketUse = 0;
    private boolean launched = false;
    private double yTarget = -1;
    private float targetPitch = 0;

    public AFKVanillaFly() {
        super(Addon.CATEGORY, "AFKVanillaFly", "Maintains a level Y-flight with fireworks and smooth pitch control.");
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> fireworkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("timed-delay")
        .description("The delay between firework usages in milliseconds.")
        .defaultValue(4000)
        .sliderRange(0, 10000)
        .build()
    );

    private final Setting<Boolean> useManualY = sgGeneral.add(new BoolSetting.Builder()
        .name("use-manual-y-level")
        .description("Use a manually set Y level instead of the Y level when activated.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> manualYLevel = sgGeneral.add(new IntSetting.Builder()
        .name("manual-y-level")
        .description("The Y level to maintain when using manual Y level.")
        .defaultValue(256)
        .sliderRange(-64, 320)
        .visible(useManualY::get)
        .onChanged(val -> yTarget = val)
        .build()
    );

    @Override
    public void onActivate() {
        launched = false;
        yTarget = -1;

        if (mc.player == null || !mc.player.isFallFlying()) {
            info("You must be flying before enabling AFKVanillaFly.");
        }
    }

    public void tickFlyLogic() {
        if (mc.player == null) return;

        double currentY = mc.player.getY();

        if (mc.player.isFallFlying()) {
            if (yTarget == -1 || !launched) {
                if (useManualY.get()) {
                    yTarget = manualYLevel.get();
                } else {
                    yTarget = currentY;
                }
                launched = true;
            }

            // will prevent from flying straight down into the ground - adjust y range if player moves vertical
            // but only if not using manual Y level
            if (!useManualY.get()) {
                double yDiffFromLock = currentY - yTarget;
                if (Math.abs(yDiffFromLock) > 10.0) {
                    yTarget = currentY; // reset the current y-level to maintain
                    info("Y-lock reset due to altitude deviation.");
                }
            }

            double yDiff = currentY - yTarget;

            if (Math.abs(yDiff) > 10.0) {
                targetPitch = (float) (Math.atan2(yDiff, 100) * (180 / Math.PI));
            } else if (yDiff > 2.0) {
                targetPitch = 10f;
            } else if (yDiff < -2.0) {
                targetPitch = -10f;
            } else {
                targetPitch = 0f;
            }

            float currentPitch = mc.player.getXRot();
            float pitchDiff = targetPitch - currentPitch;
            mc.player.setXRot(currentPitch + pitchDiff * 0.1f);

            if (System.currentTimeMillis() - lastRocketUse > fireworkDelay.get()) {
                tryUseFirework();
            }
        } else {
            if (!launched) {
                mc.player.jumpFromGround();
                launched = true;
            } else if (System.currentTimeMillis() - lastRocketUse > 1000) {
                tryUseFirework();
            }
            yTarget = -1;
        }
    }

    public void resetYLock() {
        yTarget = -1;
        launched = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        tickFlyLogic();
    }

    private void tryUseFirework() {
        FindItemResult hotbar = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!hotbar.found()) {
            FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
            if (inv.found()) {
                int hotbarSlot = findEmptyHotbarSlot();
                if (hotbarSlot != -1) {
                    InvUtils.move().from(inv.slot()).to(hotbarSlot);
                } else {
                    info("No empty hotbar slot available to move fireworks.");
                    return;
                }
            } else {
                info("No fireworks found in hotbar or inventory.");
                return;
            }
        }
        Utils.firework(mc, false);
        lastRocketUse = System.currentTimeMillis();
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }
}
