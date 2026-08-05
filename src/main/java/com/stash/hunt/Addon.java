package com.stash.hunt;

import com.stash.hunt.hud.EntityList;
import com.stash.hunt.hud.Weather;
import com.stash.hunt.modules.*;
import com.stash.hunt.modules.searcharea.SearchArea;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.settings.Settings;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

public class Addon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Jefff Mod");
    public static final HudGroup HUD_GROUP = new HudGroup("Jefff Mod");

    public final Settings settings = new Settings();

//    public static boolean hwid_checked = false;

    @Override
    public void onInitialize() {
        LOG.info("Initializing Jefff Mod");

        Modules.get().add(new SearchArea());
        Modules.get().add(new AutoLogPlus());
        Modules.get().add(new GotoPosition());
        Modules.get().add(new ChestIndex());
        Modules.get().add(new HighlightOldLava());
        Modules.get().add(new VanityESP());
        Modules.get().add(new AFKVanillaFly());
        Modules.get().add(new BetterAutoPortal());
        Modules.get().add(new Pitch40Util());
//        Modules.get().add(new AutoTrade());
//        Modules.get().add(new XPBot());
//        Modules.get().add(new UnknownAccountNotifier());
//
//        Modules.get().add(new Test());
//        Modules.get().add(new LavaFiller());

        Modules.get().add(new NoJumpDelay());
        Modules.get().add(new GrimAirPlace());
        Modules.get().add(new DiscordNotifs());
//        Modules.get().add(new EndermanItemDetector());
//        Modules.get().add(new GrimDuraFirework());
//        Modules.get().add(new PacketTester());
//        Modules.get().add(new StashMover2());
//        Modules.get().add(new StashMoverListener());
//        Modules.get().add(new PacketGrimFly());
        Modules.get().add(new AutoEXPPlus());

//        Modules.get().add(new YRelog());

        boolean baritoneLoaded = checkModLoaded("baritone", "baritone-meteor");
        boolean xaeroWorldMapLoaded = checkModLoaded("xaeroworldmap");
        boolean xaeroMinimapLoaded = checkModLoaded("xaerominimap");
        boolean xaeroPlusLoaded = checkModLoaded("xaeroplus");

        if (xaeroWorldMapLoaded && xaeroPlusLoaded)
        {
//            Modules.get().add(new MudCracker());
            if (xaeroMinimapLoaded)
            {
                Modules.get().add(new BetterStashFinder());
                Modules.get().add(new OldChunkNotifier());
//                Modules.get().add(new LavaESP());
                Modules.get().add(new TrailMaker());
            }
            if (baritoneLoaded)
            {
                Modules.get().add(new TrailFollower());
            }
        }

        if (baritoneLoaded)
        {
            Modules.get().add(new ElytraFlyPlusPlus());
        }

        Hud.get().register(Weather.INFO);

        Hud.get().register(EntityList.INFO);

    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.stash.hunt";
    }

    private boolean checkModLoaded(String... modIds)
    {
        boolean loaded = false;
        for (String id : modIds)
        {
            if (FabricLoader.getInstance().isModLoaded(id))
            {
                loaded = true;
                break;
            }
        }
        if (!loaded)
        {
            LOG.error("{} not found, disabling modules that require it.", modIds[0]);
        }
        return loaded;
    }
}
