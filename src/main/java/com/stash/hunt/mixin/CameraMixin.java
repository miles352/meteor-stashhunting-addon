package com.stash.hunt.mixin;

import org.spongepowered.asm.mixin.Mixin;
import net.minecraft.client.render.Camera;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Unique;
import com.stash.hunt.modules.ElytraFlyPlusPlus;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import meteordevelopment.meteorclient.mixininterface.ICamera;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.FreeLook;

@Mixin(Camera.class)
public abstract class CameraMixin implements ICamera {

    @Unique
    @Nullable
    FreeLook freeLook = null;

    @Unique
    @Nullable
    ElytraFlyPlusPlus bounce = null;

    @ModifyArgs(method = "update", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Camera;setRotation(FF)V"))
    private void injectSetRotation(Args args) {
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
            if (bounce.spoofYaw.get())
            {
                args.set(0, bounce.camYaw);
            }
            if (bounce.shouldSpoofPitch())
            {
                args.set(1, bounce.camPitch);
            }
        }
    }
}
