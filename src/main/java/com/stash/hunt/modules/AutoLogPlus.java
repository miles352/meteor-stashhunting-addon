package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.world.ServerConnectBeginEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PickItemFromEntityC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

public class AutoLogPlus extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> logOnY = sgGeneral.add(new BoolSetting.Builder()
        .name("Log on Y")
        .description("Logs out if you are below a certain Y level.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> yLevel = sgGeneral.add(new DoubleSetting.Builder()
        .name("Auto Log out if below this Y")
        .defaultValue(256)
        .min(-128)
        .sliderRange(-128, 320)
        .visible(logOnY::get)
        .build()
    );

    private final Setting<Boolean> logArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("Log Armor")
        .description("Logs out if you have no armor.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("Ignore Elytra")
        .description("Ignores the elytra when checking for armor.")
        .defaultValue(false)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Double> armorPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("Auto Log out if armor is below this percent")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 100)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Boolean> logPortal = sgGeneral.add(new BoolSetting.Builder()
        .name("Log on Portal")
        .description("Logs out if you are in a portal for too long.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> portalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("Portal Ticks")
        .description("The amount of ticks in a portal before you get kicked (It takes 80 ticks to go through a portal).")
        .defaultValue(30)
        .min(1)
        .sliderMax(70)
        .visible(logPortal::get)
        .build()
    );

    private final Setting<Boolean> logPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("Log Position")
        .description("Logs out if you are within x blocks of this position. Y Position is not included")
        .defaultValue(false)
        .build()
    );

    private final Setting<BlockPos> position = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Position")
        .description("The position to log out at. Y position is ignored.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("Distance")
        .description("The distance from the position to log out at.")
        .defaultValue(100)
        .sliderRange(0, 1000)
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Boolean> serverNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("Server Not Responding")
        .description("Logs out if the server is not responding.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> serverNotRespondingSecs = sgGeneral.add(new DoubleSetting.Builder()
        .name("Seconds Not Responding")
        .description("The amount of seconds the server is not responding before you log out.")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Boolean> reconnectAfterNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("Reconnect After Not Responding")
        .description("Reconnects after the server is not responding.")
        .defaultValue(false)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Double> secondsToReconnect = sgGeneral.add(new DoubleSetting.Builder()
        .name("Reconnect After Seconds")
        .description("The amount of seconds to wait before reconnecting (Will temporarily overwrite Meteor's AutoReconnect.")
        .defaultValue(60)
        .min(10)
        .sliderMax(60 * 5)
        .visible(() -> reconnectAfterNotResponding.get() && serverNotResponding.get())
        .build()
    );

    private final Setting<Boolean> illegalDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("Illegal Disconnect")
        .description("Disconnects from the server using the slot method.")
        .defaultValue(false)
        .build()
    );

    public AutoLogPlus()
    {
        super(Addon.CATEGORY, "auto-log-plus", "Provides some additional triggers to log out.");
    }

    @Override
    public void onActivate() {
        currPortalTicks = 0;
        if (waitingForReconnection) // onGameJoin event never triggered for some reason so i put it here
        {
            waitingForReconnection = false;
            AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
            Setting<Double> delay = ((Setting<Double>)autoReconnect.settings.get("delay"));
            delay.set(oldDelay);
            if (!autoReconnectEnabled && autoReconnect.isActive())
            {
                autoReconnect.toggle();
            }
        }

    }

    private int currPortalTicks = 0;
    private double oldDelay;
    private boolean autoReconnectEnabled;
    private boolean waitingForReconnection = false;

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        // If in the 2b2t queue
        if (mc.player == null || mc.player.getAbilities().allowFlying) return;

        if (serverNotResponding.get() && !waitingForReconnection)
        {
            if (TickRate.INSTANCE.getTimeSinceLastTick() > serverNotRespondingSecs.get())
            {
                if (reconnectAfterNotResponding.get())
                {
                    AutoReconnect autoReconnect = Modules.get().get(AutoReconnect.class);
                    autoReconnectEnabled = autoReconnect.isActive();
                    Setting<Double> delay = ((Setting<Double>)autoReconnect.settings.get("delay"));
                    oldDelay = delay.get();
                    delay.set(secondsToReconnect.get());
                    if (!autoReconnectEnabled)
                    {
                        autoReconnect.toggle();
                    }
                    waitingForReconnection = true;
                }
                logOut("Server was not responding for " + serverNotRespondingSecs.get() + " seconds.", !reconnectAfterNotResponding.get());
                return;
            }
        }

        if (logPortal.get() && mc.player.portalManager != null)
        {
            if (mc.player.portalManager.isInPortal())
            {
                currPortalTicks++;
                if (currPortalTicks > portalTicks.get())
                {
                    logOut("Player was in a portal for " + currPortalTicks + " ticks.", true);
                    return;
                }
            }
            else
            {
                currPortalTicks = 0;
            }
        }

        if (logOnY.get() && mc.player.getY() < yLevel.get())
        {
            logOut("Player was at Y=" + mc.player.getY() + " which is below your limit of Y=" + yLevel.get(), true);
            return;
        }
        if (logArmor.get())
        {
            for (int i = 0; i < 4; i++)
            {
                ItemStack armorPiece = mc.player.getInventory().getArmorStack(i);
                if (ignoreElytra.get() && armorPiece.getItem() == Items.ELYTRA) continue;
                if (armorPiece.isDamageable())
                {
                    int max = armorPiece.getMaxDamage();
                    int current = armorPiece.getDamage();
                    double percentUndamaged = 100 - ((double) current / max) * 100;
                    if (percentUndamaged < armorPercent.get())
                    {
                        logOut("You had low armor", true);
                        return;
                    }
                }
            }
        }
        if (logPosition.get())
        {
            double distanceToTarget = mc.player.getPos().multiply(1,0,1).distanceTo(position.get().toCenterPos().multiply(1,0,1));
            if (distanceToTarget < distance.get())
            {
                logOut("Player was within " + distanceToTarget + " blocks of the target position.", true);
                return;
            }
        }
    }

    private void logOut(String reason, boolean turnOffReconnect)
    {
        if (mc.player == null) return;
        if (turnOffReconnect)
        {
            if (Modules.get().get(AutoReconnect.class).isActive())
            {
                Modules.get().get(AutoReconnect.class).toggle();
            }
        }

        if (illegalDisconnect.get())
        {
            mc.player.networkHandler.sendChatMessage(String.valueOf((char)0));
        }
        else
        {
            mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[AutoLogPlus] " + reason)));
        }
    }
}
