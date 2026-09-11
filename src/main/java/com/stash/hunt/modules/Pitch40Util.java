package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.world.InteractionHand;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFlightModes;

import static com.stash.hunt.Utils.firework;


public class Pitch40Util extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<Boolean> autoBoundAdjust = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-adjust-bounds")
        .description("Adjusts your bounds to make you continue to gain height. Good for fixing falling on reconnect or lag, etc.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> boundGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("bound-gap")
        .description("The gap between the upper and lower bounds. Used when reconnecting, or when at max height if Auto Adjust Bounds is enabled.")
        .defaultValue(60)
        .sliderRange(50, 100)
        .build()
    );

    public final Setting<Boolean> autoFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-firework")
        .description("Uses a firework automatically if your velocity is too low.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> velocityThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("velocity-threshold")
        .description("Velocity must be below this value when going up for firework to activate.")
        .defaultValue(-0.05)
        .sliderRange(-0.5, 1)
        .visible(autoFirework::get)
        .build()
    );

    public final Setting<Integer> fireworkCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("cooldown-ticks")
        .description("Cooldown after using a firework in ticks.")
        .defaultValue(10)
        .sliderRange(0, 100)
        .visible(autoFirework::get)
        .build()
    );

    public Pitch40Util() {
        super(Addon.CATEGORY, "Pitch40Util", "Makes sure pitch 40 stays on when reconnecting to 2b2t, and sets your bounds as you reach highest point each climb.");
    }

    Module elytraFly = Modules.get().get(ElytraFly.class);

    private ElytraFlightModes oldValue;

    private Setting<ElytraFlightModes> elytraFlyMode = (Setting<ElytraFlightModes>)elytraFly.settings.get("mode");

    @Override
    public void onActivate()
    {
        oldValue = elytraFlyMode.get();

        // Make sure meteors ElytraFly is on pitch40 mode
        elytraFlyMode.set(ElytraFlightModes.Pitch40);
    }

    @Override
    public void onDeactivate()
    {
        if (elytraFly.isActive())
        {
            elytraFly.toggle();
        }
        elytraFlyMode.set(oldValue);
    }

    int fireworkCooldown = 0;

    boolean goingUp = true;

    int elytraSwapSlot = -1;

    private void resetBounds()
    {
        Setting<Double> upperBounds = (Setting<Double>) elytraFly.settings.get("pitch40-upper-bounds");
        upperBounds.set(mc.player.getY() - 5);
        Setting<Double> lowerBounds = (Setting<Double>) elytraFly.settings.get("pitch40-lower-bounds");
        lowerBounds.set(mc.player.getY() - 5 - boundGap.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event)
    {
        if (elytraFly.isActive())
        {

            if (fireworkCooldown > 0) {
                fireworkCooldown--;
            }

            if (elytraSwapSlot != -1)
            {
                InvUtils.swap(elytraSwapSlot, true);
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                InvUtils.swapBack();
                elytraSwapSlot = -1;
            }

            // this means the player fell below the lower bound, so we reset the bounds. this will only really happen if not using fireworks
            if (autoBoundAdjust.get() && mc.player.getY() <= (double)elytraFly.settings.get("pitch40-lower-bounds").get() - 10)
            {
                resetBounds();
                return;
            }

            // -40 pitch is facing upwards
            if (mc.player.getXRot() == -40)
            {
//                info("Velocity less than target: " + (mc.player.getVelocity().y < velocityThreshold.get()));
//                info("Y less than upper bounds: " + (mc.player.getY() < (double)elytraFlyModule.settings.get("pitch40-upper-bounds").get()));
                goingUp = true;
                if (autoFirework.get() && mc.player.getDeltaMovement().y < velocityThreshold.get() && mc.player.getY() < (double)elytraFly.settings.get("pitch40-upper-bounds").get())
                {
                    if (fireworkCooldown == 0) {
                        int launchStatus = firework(mc, false);
                        if (launchStatus >= 0)
                        {
                            fireworkCooldown = fireworkCooldownTicks.get();
                            // cant swap back to chestplate on the same tick
                            // stupid solution, but we need a number for non swapping return value.
                            if (launchStatus != 200) elytraSwapSlot = launchStatus;

                        }
                    }
                }
            }
            // waits until your at the highest point, when y velocity is 0, then sets min and max bounds based on your position
            else if (autoBoundAdjust.get() && goingUp && mc.player.getDeltaMovement().y <= 0) {
                goingUp = false;
                resetBounds();
            }
        }
        else
        {
            // waits for you to not be in queue, then turns elytrafly back on
            if (!mc.player.getAbilities().mayfly)
            {
                elytraFly.toggle();
                // always reset when rejoining
                resetBounds();
            }
        }

    }



}
