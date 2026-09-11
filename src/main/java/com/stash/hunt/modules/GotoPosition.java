package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.world.phys.Vec3;


public class GotoPosition extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<BlockPos> target = sgGeneral.add(new BlockPosSetting.Builder()
        .name("target-position")
        .description("Coords to go to. Y is ignored.")
        .defaultValue(new BlockPos(0,0,0))
        .build()
    );

    public final Setting<Boolean> disconnectOnComplete = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-when-complete")
        .description("Disconnects when you get to the target")
        .defaultValue(false)
        .build()
    );


    public GotoPosition()
    {
        super(Addon.CATEGORY, "goto-position", "Goes in a straight line towards the position you give and stops once there.");
    }

    @Override
    public void onActivate()
    {
        double distance = Math.sqrt(mc.player.blockPosition().distToLowCornerSqr(target.get().getX(), mc.player.getY(), target.get().getZ()));
        long totalSeconds = (long)(distance / 70);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        info("Completion will take an estimated %02d hours %02d minutes %02d seconds at an average speed of 70bps", hours, minutes, seconds);
    }

    @Override
    public void onDeactivate()
    {
        mc.options.keyUp.setDown(false);
        Input.setKeyState(mc.options.keyUp, false);
        mc.player.setDeltaMovement(0, 0, 0);
    }

//    @Override
//    public WWidget getWidget(GuiTheme theme)
//    {
//        WVerticalList list = theme.verticalList();
//        WButton clear = list.add(theme.button("Clear Coordinates")).widget();
//
//        clear.action = () -> {
//            target.reset();
//        };
//
//        return list;
//    }

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        if (Math.sqrt(mc.player.blockPosition().distToLowCornerSqr(target.get().getX(), mc.player.getY(), target.get().getZ())) > 5)
        {
            mc.player.setYRot((float) Rotations.getYaw(new Vec3(target.get().getX(), (int) mc.player.getY(), target.get().getZ())));
            mc.options.keyUp.setDown(true);
            Input.setKeyState(mc.options.keyUp, true);
        }
        else
        {
            mc.options.keyUp.setDown(false);
            Input.setKeyState(mc.options.keyUp, false);
            mc.player.setDeltaMovement(0, 0, 0);
            if (disconnectOnComplete.get())
            {
                mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal("[GotoPosition] You are at your destination!")));
            }
            target.reset();
            this.toggle();
        }

    }

}
