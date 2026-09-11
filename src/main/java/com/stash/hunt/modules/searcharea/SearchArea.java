package com.stash.hunt.modules.searcharea;

import com.stash.hunt.Addon;
import com.stash.hunt.modules.searcharea.modes.Rectangle;
import com.stash.hunt.modules.searcharea.modes.Spiral;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;

public class SearchArea extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();


    public final Setting<SearchAreaModes> chunkLoadMode = sgGeneral.add(new EnumSetting.Builder<SearchAreaModes>()
        .name("mode")
        .description("The mode chunks are loaded.")
        .defaultValue(SearchAreaModes.Rectangle)
        .onModuleActivated(chunkMode -> onModeChanged(chunkMode.get()))
        .onChanged(this::onModeChanged)
        .build()
    );

    public final Setting<BlockPos> startPos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("start-position")
        .description("The coordinates to start the rectangle at. Y Pos is ignored")
        .defaultValue(new BlockPos(0,0,0))
        .visible(() -> chunkLoadMode.get() == SearchAreaModes.Rectangle)
        .build()
    );

    public final Setting<BlockPos> targetPos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("end-position")
        .description("The coordinates to end the rectangle at. Y Pos is ignored")
        .defaultValue(new BlockPos(0,0,0))
        .visible(() -> chunkLoadMode.get() == SearchAreaModes.Rectangle)
        .build()
    );

    public final Setting<Integer> rowGap = sgGeneral.add(new IntSetting.Builder()
        .name("path-gap")
        .description("The amount of chunks to space between each chunk path.")
        .defaultValue(12)
        .min(1)
        .sliderRange(0, 32)
        .build()
    );

    public final Setting<String> saveLocation = sgGeneral.add(new StringSetting.Builder()
        .name("save-name")
        .description("The name to use for the folder that saves data, if you leave it blank, no data will be saved.")
        .defaultValue("")
        .build()
    );

    public final Setting<Boolean> disconnectOnCompletion = sgGeneral.add(new BoolSetting.Builder()
        .name("disconnect-on-completion")
        .description("Whether to disconnect after the path is complete. This will turn autoreconnect off when disconnecting.")
        .defaultValue(false)
        .visible(() -> chunkLoadMode.get() == SearchAreaModes.Rectangle)
        .build()
    );


    public SearchArea() {
        super(Addon.CATEGORY, "search-area", "Either loads chunks in a rectangle to a certain point from you, or spirals endlessly from you. Useful with Stash Finder or other map saving mods.");
    }

    private SearchAreaMode currentMode = new Rectangle();

    @Override
    public WWidget getWidget(GuiTheme theme)
    {
        WVerticalList list = theme.verticalList();
        WButton clear = list.add(theme.button("Clear Currently Selected")).widget();

        clear.action = () -> currentMode.clear();

        WButton clearAll = list.add(theme.button("Clear All")).widget();

        clearAll.action = () -> currentMode.clearAll();

        return list;
    }

    @Override
    public void onActivate() {
        currentMode.onActivate();
    }

    @Override
    public void onDeactivate()
    {
        currentMode.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        currentMode.onTick();
    }

    private void onModeChanged(SearchAreaModes mode) {
        switch (mode) {
            case Rectangle -> currentMode = new Rectangle();
            case Spiral -> currentMode = new Spiral();
        }
    }

    public enum WebhookSettings
    {
        Off,
        LogChat,
        LogStashes,
        LogBoth
    }

}
