package com.stash.hunt.modules.searcharea;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import static com.stash.hunt.Utils.*;
import java.io.*;

import static com.stash.hunt.Utils.sendWebhook;

public class SearchAreaMode
{

    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    protected final SearchArea searchArea;
    protected final Minecraft mc;
    private final SearchAreaModes type;
    protected long paused = 0;

    public SearchAreaMode(SearchAreaModes type) {
        this.searchArea = Modules.get().get(SearchArea.class);
        this.mc = Minecraft.getInstance();
        this.type = type;
    }

    public void onTick()
    {

    }

    public void onActivate()
    {

    }

    public void onDeactivate()
    {
        setPressed(mc.options.keyUp, false);
    }

    public void disable()
    {
        if (searchArea.isActive()) searchArea.toggle();
    }

    protected File getJsonFile(String fileName) {
        try
        {
            return new File(new File(new File(MeteorClient.FOLDER, "search-area"), searchArea.saveLocation.get()), fileName + ".json");
        }
        catch (NullPointerException e)
        {
            return null;
        }
    }

    protected void saveToJson(boolean goingToStart, PathingData pd)
    {
        // Fix issue where "null" gets saved to the file creating crashes when it gets loaded next time.
        if (pd == null) return;
        // last pos doesn't matter if disconnecting while going to start
        if (!goingToStart) pd.currPos = mc.player.blockPosition();
        try {
            File file = getJsonFile(type.toString());
            if (file == null) return;
            file.getParentFile().mkdirs();
            Writer writer = new FileWriter(file);
            GSON.toJson(pd, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    protected static class PathingData
    {
        public BlockPos initialPos;
        public BlockPos currPos;
        public float yawDirection;
        public boolean mainPath;
    }

    public void clear()
    {
        File file = getJsonFile(type.toString());
        file.delete();
    }

    public void clear(String mode)
    {
        File file = getJsonFile(mode);
        file.delete();
    }

    public void clearAll()
    {
        for (SearchAreaModes mode : SearchAreaModes.values())
        {
            clear(mode.toString());
        }
    }

    public String toString()
    {
        return type.toString();
    }

}
