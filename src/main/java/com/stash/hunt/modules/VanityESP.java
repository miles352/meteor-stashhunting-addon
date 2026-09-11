package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;

public class VanityESP extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors = settings.createGroup("Colors");

    private final Setting<Boolean> highlightItemFrames = sgGeneral.add(new BoolSetting.Builder()
        .name("item-frames")
        .description("highlights item frames that contain maps.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> highlightBanners = sgGeneral.add(new BoolSetting.Builder()
        .name("banners")
        .description("highlights banners.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> mapColor = sgColors.add(new ColorSetting.Builder()
        .name("map-color")
        .description("fill color for item frames containing maps.")
        .defaultValue(new SettingColor(255, 255, 0, 50))
        .build()
    );

    private final Setting<SettingColor> mapOutlineColor = sgColors.add(new ColorSetting.Builder()
        .name("map-outline-color")
        .description("outline color for item frames containing maps.")
        .defaultValue(new SettingColor(255, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> bannerColor = sgColors.add(new ColorSetting.Builder()
        .name("banner-fill")
        .description("fill color for banners.")
        .defaultValue(new SettingColor(255, 0, 0, 50))
        .build()
    );

    private final Setting<SettingColor> bannerOutline = sgColors.add(new ColorSetting.Builder()
        .name("banner-outline")
        .description("outline color for banners.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .build()
    );

    public VanityESP() {
        super(Addon.CATEGORY, "VanityESP", "Highlights maparts and banners");
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.level == null || mc.player == null) return;

        if (highlightItemFrames.get()) {
            for (ItemFrame frame : mc.level.getEntitiesOfClass(ItemFrame.class, mc.player.getBoundingBox().inflate(64),
                e -> e.getItem().getItem().getDescriptionId().equals("item.minecraft.filled_map"))) {

                // fixed mapart on-ground rendering bug
                AABB box;
                float pitch = frame.getXRot();
                if (pitch == 90 || pitch == -90) {
                    box = frame.getBoundingBox().inflate(0.12, 0.01, 0.12);
                } else {
                    box = frame.getBoundingBox().inflate(0.12, 0.12, 0.01);
                }

                Color fill = new Color(mapColor.get());
                Color outline = new Color(mapOutlineColor.get());
                event.renderer.box(box, fill, outline, ShapeMode.Both, 0);
            }
        }
    // redid shaderbox rendering, 4 wall mount facing directions, 4 standing facing directions
        if (highlightBanners.get()) {
            int radius = 8;
            BlockPos playerPos = mc.player.blockPosition();

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    LevelChunk chunk = mc.level.getChunk(playerPos.getX() / 16 + dx, playerPos.getZ() / 16 + dz);
                    if (chunk == null) continue;

                    for (BlockEntity be : chunk.getBlockEntities().values()) {
                        if (!(be instanceof BannerBlockEntity banner)) continue;

                        BlockPos pos = banner.getBlockPos();
                        BlockState state = mc.level.getBlockState(pos);
                        AABB box;

                        Color fill = new Color(bannerColor.get());
                        Color outline = new Color(bannerOutline.get());

                        if (state.hasProperty(WallBannerBlock.FACING)) {
                            Direction facing = state.getValue(WallBannerBlock.FACING);
                            double centerX = pos.getX() + 0.5;
                            double centerZ = pos.getZ() + 0.5;
                            double offset = 0.1;
                            double depth = 0.03;
                            double width = 0.45;
                            double y1 = pos.getY() - 0.95;
                            double y2 = pos.getY() + 0.85;

                            switch (facing) {
                                case NORTH:
                                    box = new AABB(centerX - width, y1, pos.getZ() + 1 - offset - depth, centerX + width, y2, pos.getZ() + 1 - offset);
                                    break;
                                case SOUTH:
                                    box = new AABB(centerX - width, y1, pos.getZ() + offset, centerX + width, y2, pos.getZ() + offset + depth);
                                    break;
                                case WEST:
                                    box = new AABB(pos.getX() + 1 - offset - depth, y1, centerZ - width, pos.getX() + 1 - offset, y2, centerZ + width);
                                    break;
                                case EAST:
                                    box = new AABB(pos.getX() + offset, y1, centerZ - width, pos.getX() + offset + depth, y2, centerZ + width);
                                    break;
                                default:
                                    continue;
                            }

                            event.renderer.box(box, fill, outline, ShapeMode.Both, 0);
                        } else if (state.hasProperty(BannerBlock.ROTATION)) {
                            int rotation = state.getValue(BannerBlock.ROTATION);
                            double centerX = pos.getX() + 0.5;
                            double centerZ = pos.getZ() + 0.5;
                            double y1 = pos.getY();
                            double y2 = pos.getY() + 1.85;

                            if (rotation == 0 || rotation == 8) {
                                double width = 0.45;
                                double depth = 0.03;
                                box = new AABB(centerX - width, y1, centerZ - depth, centerX + width, y2, centerZ + depth);
                            } else if (rotation == 4 || rotation == 12) {
                                double width = 0.03;
                                double depth = 0.45;
                                box = new AABB(centerX - width, y1, centerZ - depth, centerX + width, y2, centerZ + depth);
                            } else {
                                double size = 0.3;
                                box = new AABB(centerX - size, y1, centerZ - size, centerX + size, y2, centerZ + size);
                            }

                            event.renderer.box(box, fill, outline, ShapeMode.Both, 0);
                            }
                        }
                    }
                }
            }
        }
    }

