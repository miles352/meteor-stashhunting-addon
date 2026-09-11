package com.stash.hunt.modules.searcharea.modes;

import com.stash.hunt.modules.searcharea.SearchAreaMode;
import com.stash.hunt.modules.searcharea.SearchAreaModes;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.io.*;

import static com.stash.hunt.Utils.*;
import static meteordevelopment.meteorclient.utils.player.ChatUtils.info;

public class Spiral extends SearchAreaMode
{
    private PathingDataSpiral pd;
    private boolean goingToStart = true;
    private long startTime;

    public Spiral()
    {
        super(SearchAreaModes.Spiral);
    }

    @Override
    public void onActivate()
    {
            startTime = System.nanoTime();
            goingToStart = true;
            File file = getJsonFile(super.toString());
            if (file == null || !file.exists())
            {
                pd = new PathingDataSpiral(mc.player.blockPosition(), mc.player.blockPosition(), -90.0f, true, 0, 0);
            }
            else
            {
                try {
                    FileReader reader = new FileReader(file);
                    pd = GSON.fromJson(reader, PathingDataSpiral.class);
                    reader.close();
                    info("Loaded previously saved path, heading to where you left off.");
                } catch (IOException e) {
                    info("Failed to load saved path, check logs for more details. Disabling module.");
                    e.printStackTrace();
                    this.disable();
                }
            }

    }

    @Override
    public void onDeactivate()
    {
        super.onDeactivate();
        super.saveToJson(goingToStart, pd);

    }

    @Override
    public void onTick()
    {
        // autosave every 10 minutes in case of crashes
        if (System.nanoTime() - startTime > 6e11)
        {
            startTime = System.nanoTime();
            super.saveToJson(goingToStart, pd);
        }

        if (System.nanoTime() < paused)
        {
            setPressed(mc.options.keyUp, false);
            return;
        }


        if (goingToStart)
        {

            if (Math.sqrt(mc.player.blockPosition().distToLowCornerSqr(pd.currPos.getX(), mc.player.getY(), pd.currPos.getZ())) < 5)
            {
                goingToStart = false;
                mc.player.setDeltaMovement(0, 0, 0);
            }
            else
            {
                mc.player.setYRot((float) Rotations.getYaw(Vec3.atCenterOf(pd.currPos)));
                setPressed(mc.options.keyUp, true);
            }
            return;
        }

        setPressed(mc.options.keyUp, true);
        mc.player.setYRot(pd.yawDirection);
        int blockGap = 16 * searchArea.rowGap.get();
        if (pd.mainPath && Math.abs(mc.player.getX() - pd.initialPos.getX()) >= (blockGap + pd.spiralWidth))
        {
            pd.yawDirection += 90.0f;
            pd.initialPos = new BlockPos((int)mc.player.getX(), pd.initialPos.getY(), pd.initialPos.getZ());
            pd.spiralWidth += blockGap;
            pd.mainPath = false;
            mc.player.setDeltaMovement(0, 0, 0);
        }
        else if (!pd.mainPath && Math.abs(mc.player.getZ() - pd.initialPos.getZ()) >= (blockGap + pd.spiralHeight))
        {
            pd.yawDirection += 90.0f;
            pd.initialPos = new BlockPos(pd.initialPos.getX(), pd.initialPos.getY(), (int)mc.player.getZ());
            pd.spiralHeight += blockGap;
            pd.mainPath = true;
            mc.player.setDeltaMovement(0, 0, 0);
        }
    }

    public static class PathingDataSpiral extends PathingData
    {
        public int spiralWidth = 0;
        public int spiralHeight = 0;

        public PathingDataSpiral(BlockPos initialPos, BlockPos currPos, float yawDirection, boolean mainPath, int spiralWidth, int spiralHeight)
        {
            this.initialPos = initialPos;
            this.currPos = currPos;
            this.yawDirection = yawDirection;
            this.mainPath = mainPath;
            this.spiralWidth = spiralWidth;
            this.spiralHeight = spiralHeight;
        }
    }
}
