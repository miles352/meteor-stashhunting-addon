package com.example.addon.modules;

import com.example.addon.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.movement.elytrafly.ElytraFly;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;

import static meteordevelopment.meteorclient.utils.Utils.rightClick;


public class Pitch40Util extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> boundGap = sgGeneral.add(new DoubleSetting.Builder()
        .name("Bound Gap")
        .description("The gap between the upper and lower bounds.")
        .defaultValue(60)
        .build()
    );

    public final Setting<Boolean> autoFirework = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Firework")
        .description("Uses a firework automatically if your velocity is too low.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> velocityThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("Auto Firework Velocity Threshold")
        .description("Velocity must be below this value when going up for firework to activate.")
        .defaultValue(-0.05)
        .visible(autoFirework::get)
        .build()
    );

    public final Setting<Integer> fireworkCooldownTicks = sgGeneral.add(new IntSetting.Builder()
        .name("Auto Firework Cooldown (ticks)")
        .description("Cooldown after using a firework in ticks.")
        .defaultValue(300)
        .visible(autoFirework::get)
        .build()
    );

    public Pitch40Util() {
        super(Addon.CATEGORY, "Pitch40Util", "Makes sure pitch 40 stays on when reconnecting to 2b2t, and sets your bounds as you reach highest point each climb.");
    }

    Class<? extends Module> elytraFly = ElytraFly.class;
    Module elytraFlyModule = Modules.get().get(elytraFly);

    @Override
    public void onActivate()
    {

    }

    @Override
    public void onDeactivate()
    {
        if (elytraFlyModule.isActive())
        {
            elytraFlyModule.toggle();
        }
    }

    int fireworkCooldown = 0;

    boolean goingUp = true;

    int elytraSwapSlot = -1;

    private void resetBounds()
    {
        Setting<Double> upperBounds = (Setting<Double>) elytraFlyModule.settings.get("pitch40-upper-bounds");
        upperBounds.set(mc.player.getY() - 5);
        Setting<Double> lowerBounds = (Setting<Double>) elytraFlyModule.settings.get("pitch40-lower-bounds");
        lowerBounds.set(mc.player.getY() - 5 - boundGap.get());
    }

    @EventHandler
    private void onTick(TickEvent.Pre event)
    {
        if (elytraFlyModule.isActive())
        {

            if (fireworkCooldown > 0) {
                fireworkCooldown--;
            }

            if (elytraSwapSlot != -1)
            {
                InvUtils.swap(elytraSwapSlot, true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
                elytraSwapSlot = -1;
            }

            // this means the player fell below the lower bound, so we reset the bounds. this will only really happen if not using fireworks
            if (mc.player.getY() <= (double)elytraFlyModule.settings.get("pitch40-lower-bounds").get() - 10)
            {
                resetBounds();
                return;
            }

            // -40 pitch is facing upwards
            if (mc.player.getPitch() == -40)
            {
                goingUp = true;
                if (autoFirework.get() && mc.player.getVelocity().y < velocityThreshold.get() && mc.player.getY() < (double)elytraFlyModule.settings.get("pitch40-upper-bounds").get())
                {
                    if (fireworkCooldown == 0) {
                        int launchStatus = firework();
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
            else if (goingUp && mc.player.getVelocity().y <= 0) {
                goingUp = false;
                resetBounds();
            }
        }
        else
        {
            // waits for you to not be in queue, then turns elytrafly back on
            if (!mc.player.getAbilities().allowFlying)
            {
                elytraFlyModule.toggle();
                resetBounds();
            }
        }

    }

    // returns -1 if fails, 1 if successful, and slot of chestplate if it had to swap (needed for mio grimdura)
    public int firework() {

        // cant use a rocket if not wearing an elytra
        int elytraSwapSlot = -1;
        if (!mc.player.getInventory().getArmorStack(2).isOf(Items.ELYTRA))
        {
            FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
            if (!itemResult.found()) {
                return -1;
            }
            else
            {
                elytraSwapSlot = itemResult.slot();
                InvUtils.swap(itemResult.slot(), true);
                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                InvUtils.swapBack();
//                mc.player.setJumping(false);
//                mc.player.setSprinting(true);
//                mc.player.jump();
                mc.getNetworkHandler().sendPacket(new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING));
            }
        }

        FindItemResult itemResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!itemResult.found()) return -1;

        if (itemResult.isOffhand()) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            mc.player.swingHand(Hand.OFF_HAND);
        } else {
            InvUtils.swap(itemResult.slot(), true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            mc.player.swingHand(Hand.MAIN_HAND);
            InvUtils.swapBack();
        }
        if (elytraSwapSlot != -1)
        {
            return elytraSwapSlot;
        }
        return 200;
    }

}
