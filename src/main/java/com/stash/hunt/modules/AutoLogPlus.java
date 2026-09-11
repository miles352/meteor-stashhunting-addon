package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.misc.AutoReconnect;
import meteordevelopment.meteorclient.utils.player.SlotUtils;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

public class AutoLogPlus extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> logOnY = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-y")
        .description("Logs out if you are below a certain Y level.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> yLevel = sgGeneral.add(new DoubleSetting.Builder()
        .name("y-level")
        .defaultValue(256)
        .min(-128)
        .sliderRange(-128, 320)
        .visible(logOnY::get)
        .build()
    );

    private final Setting<Boolean> logArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("log-armor")
        .description("Logs out if your armor goes below a certain durability amount.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-elytra")
        .description("Ignores the elytra when checking armor durability.")
        .defaultValue(false)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Double> armorPercent = sgGeneral.add(new DoubleSetting.Builder()
        .name("armor-percent")
        .defaultValue(5)
        .min(0)
        .sliderRange(0, 100)
        .visible(logArmor::get)
        .build()
    );

    private final Setting<Boolean> logPortal = sgGeneral.add(new BoolSetting.Builder()
        .name("log-on-portal")
        .description("Logs out if you are in a portal for too long.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> portalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("portal-ticks")
        .description("The amount of ticks in a portal before you get kicked (It takes 80 ticks to go through a portal).")
        .defaultValue(30)
        .min(1)
        .sliderMax(70)
        .visible(logPortal::get)
        .build()
    );

    private final Setting<Boolean> logPosition = sgGeneral.add(new BoolSetting.Builder()
        .name("log-position")
        .description("Logs out if you are within x blocks of this position. Y Position is not included")
        .defaultValue(false)
        .build()
    );

    private final Setting<BlockPos> position = sgGeneral.add(new BlockPosSetting.Builder()
        .name("position")
        .description("The position to log out at. Y position is ignored.")
        .defaultValue(new BlockPos(0, 0, 0))
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("The distance from the position to log out at.")
        .defaultValue(100)
        .sliderRange(0, 1000)
        .visible(logPosition::get)
        .build()
    );

    private final Setting<Boolean> serverNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("server-not-responding")
        .description("Logs out if the server is not responding.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> serverNotRespondingSecs = sgGeneral.add(new DoubleSetting.Builder()
        .name("seconds-not-responding")
        .description("The amount of seconds the server is not responding before you log out.")
        .defaultValue(10)
        .min(1)
        .sliderMax(60)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Boolean> reconnectAfterNotResponding = sgGeneral.add(new BoolSetting.Builder()
        .name("reconnect-after-not-responding")
        .description("Reconnects after the server is not responding.")
        .defaultValue(false)
        .visible(serverNotResponding::get)
        .build()
    );

    private final Setting<Double> secondsToReconnect = sgGeneral.add(new DoubleSetting.Builder()
        .name("reconnect-seconds")
        .description("The amount of seconds to wait before reconnecting (Will temporarily overwrite Meteor's AutoReconnect.")
        .defaultValue(60)
        .min(10)
        .sliderMax(60 * 5)
        .visible(() -> reconnectAfterNotResponding.get() && serverNotResponding.get())
        .build()
    );

    private final Setting<Boolean> illegalDisconnect = sgGeneral.add(new BoolSetting.Builder()
        .name("illegal-disconnect")
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
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

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

        if (logPortal.get() && mc.player.portalProcess != null)
        {
            if (mc.player.portalProcess.isInsidePortalThisTick())
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
                ItemStack armorPiece = mc.player.getInventory().getItem(SlotUtils.ARMOR_START + i);
                if (ignoreElytra.get() && armorPiece.getItem() == Items.ELYTRA) continue;
                if (armorPiece.isDamageableItem())
                {
                    int max = armorPiece.getMaxDamage();
                    int current = armorPiece.getDamageValue();
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
            double distanceToTarget = mc.player.position().multiply(1,0,1).distanceTo(Vec3.atCenterOf(position.get()).multiply(1,0,1));
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
            mc.player.connection.sendChat(String.valueOf((char)0));
        }
        else
        {
            mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal("[AutoLogPlus] " + reason)));
        }
    }
}
