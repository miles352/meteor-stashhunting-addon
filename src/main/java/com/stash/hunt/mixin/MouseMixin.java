package com.stash.hunt.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import com.llamalad7.mixinextras.sugar.Local;
import com.stash.hunt.modules.ElytraFlyPlusPlus;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import static meteordevelopment.meteorclient.MeteorClient.mc;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;

@Mixin(value = Mouse.class)
public class MouseMixin {

    @Unique
    @Nullable
    FreeLook freeLook = null;

    @Unique
    @Nullable
    private ElytraFlyPlusPlus bounce = null;

    @Inject(method = "updateMouse", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/ClientPlayerEntity;changeLookDirection(DD)V"), cancellable = true)
    private void tickFreeLook(CallbackInfo ci, @Local(ordinal = 1) double k, @Local(ordinal = 2) double l) {
        if (mc.player == null) return;
        if (freeLook == null || bounce == null)
        {
            Modules mods = Modules.get();
            if (mods == null) return;
            if (freeLook == null)
            {
                freeLook = mods.get(FreeLook.class);
            }
            if (bounce == null)
            {
                bounce = mods.get(ElytraFlyPlusPlus.class);
            }

            if (freeLook == null || bounce == null) return;
        }

        if (freeLook.isActive()) return;
        if (!bounce.bounce.get()) return;
        if (bounce.enabled() && (bounce.shouldSpoofPitch() || bounce.spoofYaw.get()))
        {
            double invert = 1;
            if (mc.options.getInvertYMouse().getValue())
            {
                invert = -1;
            }

            ci.cancel();
            if (bounce.shouldSpoofPitch() && bounce.spoofYaw.get())
            {
                bounce.camYaw += (float) (k / 8.0);
                if (bounce.shouldSpoofPitch())
                {
                    bounce.camPitch += (float) ((l * invert) / 8.0);
                    if (Math.abs(bounce.camPitch) > 90.0F) bounce.camPitch = bounce.camPitch > 0.0F ? 90.0F : -90.0F;
                }
                else
                {
                    // pass-through pitch
                    mc.player.changeLookDirection(0.0, l * invert);
                }
            }
            else if (bounce.shouldSpoofPitch())
            {
                bounce.camPitch += (float) ((l * invert) / 8.0);
                if (Math.abs(bounce.camPitch) > 90.0F) bounce.camPitch = bounce.camPitch > 0.0F ? 90.0F : -90.0F;

                // pass-through yaw
                mc.player.changeLookDirection(k, 0.0);
            }
            else if (bounce.spoofYaw.get())
            {
                bounce.camYaw += (float) (k / 8.0);

                // pass-through pitch
                mc.player.changeLookDirection(0.0, l * invert);
            }
        }
    }
}
