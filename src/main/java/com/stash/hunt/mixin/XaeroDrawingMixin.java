package com.stash.hunt.mixin;

import com.stash.hunt.modules.TrailMaker;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaeroplus.Globals;
import xaeroplus.module.impl.Drawing;

@Mixin(Drawing.class)
public class XaeroDrawingMixin
{
    @Inject(method= "addHighlight(II)V", at = @At("HEAD"), remap = false)
    public void addHighlight(int chunkX, int chunkZ, CallbackInfo ci)
    {
        TrailMaker trailMaker = Modules.get().get(TrailMaker.class);
        if (!trailMaker.isRecording()) return;
        trailMaker.dimension = Globals.getCurrentDimensionId();

        trailMaker.points.add(new ChunkPos(chunkX, chunkZ));
    }
}
