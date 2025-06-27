package com.stash.hunt.mixin;

import com.stash.hunt.modules.ElytraFlyPlusPlus;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.brain.Brain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static meteordevelopment.meteorclient.utils.player.ChatUtils.info;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin
{
    @Shadow
    private int jumpingCooldown;

    @Shadow
    public abstract Brain<?> getBrain();

    Module noJumpDelay = Modules.get().get(com.stash.hunt.modules.NoJumpDelay.class);
    ElytraFlyPlusPlus efly = Modules.get().get(ElytraFlyPlusPlus.class);

    @Inject(at = @At("HEAD"), method = "Lnet/minecraft/entity/LivingEntity;tickMovement()V")
    private void tickMovement(CallbackInfo ci)
    {
        if (mc.player != null && mc.player.getBrain().equals(this.getBrain()) && efly != null && efly.enabled() || noJumpDelay.isActive())
        {
            this.jumpingCooldown = 0;
        }
    }

    @Inject(at = @At("HEAD"), method = "Lnet/minecraft/entity/LivingEntity;isGliding()Z", cancellable = true)
    private void isGliding(CallbackInfoReturnable<Boolean> cir)
    {
        if (mc.player != null && mc.player.getBrain().equals(this.getBrain()) && efly != null && efly.enabled())
        {
            cir.setReturnValue(true);
        }
    }
}
