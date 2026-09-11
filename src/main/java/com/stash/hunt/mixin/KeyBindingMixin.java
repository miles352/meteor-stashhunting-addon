package com.stash.hunt.mixin;

import com.stash.hunt.modules.ElytraFlyPlusPlus;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(KeyMapping.class)
public abstract class KeyBindingMixin {

    @Final
    @Shadow
    private String name;

    @Inject(at = @At("RETURN"), method = "isDown", cancellable = true)
    public void isPressed(CallbackInfoReturnable<Boolean> cir)
    {
        if (Modules.get() != null)
        {
            ElytraFlyPlusPlus efly = Modules.get().get(ElytraFlyPlusPlus.class);
            if (efly != null && efly.isActive() && efly.enabled() && name.equals("key.forward"))
            {
                cir.setReturnValue(true);
            }
        }
    }
}
