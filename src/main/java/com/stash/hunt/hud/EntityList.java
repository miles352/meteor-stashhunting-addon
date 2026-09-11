package com.stash.hunt.hud;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import java.util.*;

public class EntityList extends HudElement {
    public static final HudElementInfo INFO = new HudElementInfo<>(
        Addon.HUD_GROUP,
        "EntityList",
        "Shows nearby entities in the HUD like in RusherHack",
        EntityList::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Text scale.")
        .defaultValue(1.0)
        .min(0.5)
        .sliderMax(3.0)
        .build()
    );

    private final Setting<Boolean> gradient = sgGeneral.add(new BoolSetting.Builder()
        .name("gradient")
        .description("Enable vertical gradient color.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> solidColor = sgGeneral.add(new ColorSetting.Builder()
        .name("color")
        .description("Text color (only used when gradient is off).")
        .defaultValue(new Color(255, 255, 255, 255))
        .visible(() -> !gradient.get())
        .build()
    );

    private final Setting<SettingColor> gradientStart = sgGeneral.add(new ColorSetting.Builder()
        .name("gradient-start")
        .description("Top color of the gradient.")
        .defaultValue(new Color(255, 255, 255, 255))
        .visible(gradient::get)
        .build()
    );

    private final Setting<SettingColor> gradientEnd = sgGeneral.add(new ColorSetting.Builder()
        .name("gradient-end")
        .description("Bottom color of the gradient.")
        .defaultValue(new Color(150, 150, 150, 255))
        .visible(gradient::get)
        .build()
    );

    private final Map<String, Integer> entityCounts = new HashMap<>();

    public EntityList() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        entityCounts.clear();
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null || mc.player == null) return;

        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player || e.isRemoved()) continue;

            String name = e.getName().getString();

            entityCounts.merge(name, 1, Integer::sum);
        }

        if (entityCounts.isEmpty()) return;

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(entityCounts.entrySet());
        entries.sort(Map.Entry.<String, Integer>comparingByValue().reversed());

        double lineHeight = renderer.textHeight(true, scale.get());
        double maxWidth = 0;

        for (Map.Entry<String, Integer> entry : entries) {
            String line = entry.getKey();
            if (entry.getValue() > 1) line += " (" + entry.getValue() + ")";
            maxWidth = Math.max(maxWidth, renderer.textWidth(line, true, scale.get()));
        }

        double padding = 4 * scale.get();
        setSize(maxWidth + padding * 2, lineHeight * entries.size() + padding * 2);

        Color start = gradientStart.get();
        Color end = gradientEnd.get();

        double total = Math.max(1, entries.size() - 1);

        double currentY = y + padding;
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Integer> entry = entries.get(i);

            String line = entry.getKey();
            if (entry.getValue() > 1) line += " (" + entry.getValue() + ")";

            Color textColor;

            if (gradient.get()) {
                double t = i / total;
                textColor = new Color(
                    (int) (start.r + (end.r - start.r) * t),
                    (int) (start.g + (end.g - start.g) * t),
                    (int) (start.b + (end.b - start.b) * t),
                    (int) (start.a + (end.a - start.a) * t)
                );
            } else {
                textColor = solidColor.get();
            }

            renderer.text(line, x + padding, currentY, textColor, true, scale.get());
            currentY += lineHeight;
        }
    }
}
