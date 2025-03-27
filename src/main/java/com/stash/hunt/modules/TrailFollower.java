package com.stash.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import com.stash.hunt.Addon;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stash.hunt.Utils;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.item.Items;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;

import java.time.Duration;
import java.util.ArrayDeque;

import static com.stash.hunt.Utils.positionInDirection;
import static com.stash.hunt.Utils.sendWebhook;

public class TrailFollower extends Module
{
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // TODO: Set this automatically either by looking at the rate of chunk loads or by using yaw instead of block pos so size doesnt negatively effect result
    public final Setting<Integer> maxTrailLength = sgGeneral.add(new IntSetting.Builder()
        .name("Max Trail Length")
        .description("The number of trail points to keep for the average. Adjust to change how quickly the average will change. More does not necessarily equal better because if the list is too long it will contain chunks behind you.")
        .defaultValue(20)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Integer> chunksBeforeStarting = sgGeneral.add(new IntSetting.Builder()
        .name("Chunks Before Starting")
        .description("Useful for afking looking for a trail. The amount of chunks before it gets detected as a trail.")
        .defaultValue(10)
        .sliderRange(1, 50)
        .build()
    );

    public final Setting<Integer> chunkConsiderationWindow = sgGeneral.add(new IntSetting.Builder()
        .name("Chunk Timeframe")
        .description("The amount of time in seconds that the chunks must be found in before starting.")
        .defaultValue(5)
        .sliderRange(1, 20)
        .build()
    );

    public final Setting<TrailEndBehavior> trailEndBehavior = sgGeneral.add(new EnumSetting.Builder<TrailEndBehavior>()
        .name("Trail End Behavior")
        .description("What to do when the trail ends.")
        .defaultValue(TrailEndBehavior.DISABLE)
        .build()
    );

    public final Setting<Double> trailEndYaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Trail End Yaw")
        .description("The direction to go after the trail is abandoned.")
        .defaultValue(0.0)
        .sliderRange(0.0, 359.9)
        .visible(() -> trailEndBehavior.get() == TrailEndBehavior.FLY_TOWARDS_YAW)
        .build()
    );

    public final Setting<Boolean> vanillaFlyOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("Vanilla Fly Only (No Boost)")
        .description("Uses AFKVanillaFly flight mechanics instead of pitch40 for trail following.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> pitch40 = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Pitch 40")
        .description("Incorporates pitch 40 into the follower.")
        .defaultValue(true)
        .visible(() -> !vanillaFlyOnly.get())
        .build()
    );

    public final Setting<Boolean> pitch40Firework = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Firework")
        .description("Uses a firework automatically if your velocity is too low.")
        .defaultValue(true)
        .visible(() -> pitch40.get())
        .visible(() -> !vanillaFlyOnly.get() && pitch40.get())
        .build()
    );

    public final Setting<Double> rotateScaling = sgGeneral.add(new DoubleSetting.Builder()
        .name("Rotate Scaling")
        .description("Scaling of how fast the yaw changes. 1 = instant, 0 = doesn't change")
        .defaultValue(0.1)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public final Setting<Boolean> oppositeDimension = sgGeneral.add(new BoolSetting.Builder()
        .name("Opposite Dimension")
        .description("Follows trails from the opposite dimension (Requires that you've already loaded the other dimension with XP).")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> autoElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("[Baritone] Auto Start Baritone Elytra")
        .description("Starts baritone elytra for you.")
        .defaultValue(false)
        .build()
    );

    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);

    public final Setting<Double> pathDistance = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Path Distance")
        .description("The distance to add trail positions in the direction the player is facing. (Ignored when following overworld from nether)")
        .defaultValue(500)
        .sliderRange(100, 2000)
        .onChanged(value -> pathDistanceActual = value)
        .build()
    );

    public final Setting<Double> startDirectionWeighting = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Start Direction Weight")
        .description("The weighting of the direction the player is facing when starting the trail. 0 for no weighting (not recommended) 1 for max weighting (will take a bit for direction to change)")
        .defaultValue(0.5)
        .min(0)
        .sliderMax(1)
        .build()
    );

    public final Setting<DirectionWeighting> directionWeighting = sgAdvanced.add(new EnumSetting.Builder<DirectionWeighting>()
        .name("Direction Weighting")
        .description("How the chunks found should be weighted. Useful for path splits. Left will weight chunks to the left of the player higher, right will weigh chunks to the right higher, and none will be in the middle/random. ")
        .defaultValue(DirectionWeighting.NONE)
        .build()
    );

    public final Setting<Integer> directionWeightingMultiplier = sgAdvanced.add(new IntSetting.Builder()
        .name("Direction Weighting Multiplier")
        .description("The multiplier for how much weight should be given to chunks in the direction specified. Values are capped to be in the range [2, maxTrailLength].")
        .defaultValue(2)
        .min(2)
        .sliderMax(10)
        .visible(() -> directionWeighting.get() != DirectionWeighting.NONE)
        .build()
    );

//    public final Setting<ChunkTypes> chunkType = sgAdvanced.add(new EnumSetting.Builder<ChunkTypes>()
//        .name("Chunk Types")
//        .description("The types of chunks to follow.")
//        .defaultValue(ChunkTypes.ALL)
//        .build()
//    );

    public final Setting<Double> chunkFoundTimeout = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Chunk Found Timeout")
        .description("The amount of MS without a chunk found to trigger circling.")
        .defaultValue(1000 * 5)
        .min(1000)
        .sliderMax(1000 * 10)
        .build()
    );

    public final Setting<Double> circlingDegPerTick = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Circling Degrees Per Tick")
        .description("The amount of degrees to change per tick while circling.")
        .defaultValue(2.0)
        .min(1.0)
        .sliderMax(20.0)
        .build()
    );

    public final Setting<Double> trailTimeout = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Trail Timeout")
        .description("The amount of MS without a chunk found to stop following the trail.")
        .defaultValue(1000 * 30)
        .min(1000 * 10)
        .sliderMax(1000 * 60)
        .build()
    );

    public final Setting<Integer> chunkCacheLength = sgAdvanced.add(new IntSetting.Builder()
        .name("Chunk Cache Length")
        .description("The amount of chunks to keep in the cache. (Won't be applied until deactivating)")
        .defaultValue(100_000)
        .sliderRange(0, 10_000_000)
        .build()
    );

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("Webhook Link")
        .description("Will send all updates to the webhook link. Leave blank to disable.")
        .defaultValue("")
        .build()
    );

    public final Setting<Integer> baritoneUpdateTicks = sgAdvanced.add(new IntSetting.Builder()
        .name("[Baritone] Baritone Path Update Ticks")
        .description("The amount of ticks between updates to the baritone goal. Low values may cause high instability.")
        .defaultValue(5 * 20) // 5 seconds
        .sliderRange(20, 30 * 20)
        .build()
    );

    public final Setting<Boolean> debug = sgAdvanced.add(new BoolSetting.Builder()
        .name("Debug")
        .description("Debug mode.")
        .defaultValue(false)
        .build()
    );

    // TODO: Auto disconnect at certain chunk load speed

    private boolean oldAutoFireworkValue;

    private FollowMode followMode;

    private boolean followingTrail = false;

    private ArrayDeque<Vec3d> trail = new ArrayDeque<>();
    private ArrayDeque<Vec3d> possibleTrail = new ArrayDeque<>();

    private long lastFoundTrailTime;
    private long lastFoundPossibleTrailTime;

    private double pathDistanceActual = pathDistance.get();

    private Cache<Long, Byte> seenChunksCache = Caffeine.newBuilder()
        .maximumSize(chunkCacheLength.get())
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    // Credit to WarriorLost: https://github.com/WarriorLost/meteor-client/tree/master

    public TrailFollower()
    {
        super(Addon.CATEGORY, "TrailFollower", "Automatically follows trails in all dimensions.");
    }

    void resetTrail()
    {
        baritoneSetGoalTicks = 0;
        followingTrail = false;
        trail = new ArrayDeque<>();
        possibleTrail = new ArrayDeque<>();
    }

    @Override
    public void onActivate()
    {
        resetTrail();
        XaeroPlus.EVENT_BUS.register(this);
        if (mc.player != null && mc.world != null)
        {
            RegistryKey<World> currentDimension = mc.world.getRegistryKey();
            if (oppositeDimension.get())
            {
                if (currentDimension.equals(World.END))
                {
                    info("There is no opposite dimension to the end. Disabling TrailFollower");
                    this.toggle();
                    return;
                }
                else if (currentDimension.equals(World.NETHER))
                {
                    info("Following overworld trails from the nether is not supported yet, sorry. Disabling TrailFollower");
                    this.toggle();
                    return;
                }
            }
            if (!currentDimension.equals(World.NETHER))
            {
                followMode = FollowMode.YAWLOCK;
                info("You are in the overworld or end, basic yaw mode will be used.");
            }
            else
            {
                try {
                    Class.forName("baritone.api.BaritoneAPI");
                    followMode = FollowMode.BARITONE;
                    info("You are in the nether, baritone mode will be used.");
                } catch (ClassNotFoundException e) {
                    info("Baritone is required to trail follow in the nether. Disabling TrailFollower");
                    this.toggle();
                    return;
                }

            }

            if (followMode == FollowMode.YAWLOCK)
            {
                // disable Pitch40 if user selects VanillaFly
                if (!vanillaFlyOnly.get()) {
                Class<? extends Module> pitch40Util = Pitch40Util.class;
                Module pitch40UtilModule = Modules.get().get(pitch40Util);
                if (pitch40.get() && !pitch40UtilModule.isActive()) {
                    pitch40UtilModule.toggle();
                    if (pitch40Firework.get()) {
                        Setting<Boolean> setting = ((Setting<Boolean>) pitch40UtilModule.settings.get("Auto Firework"));
                        info("Auto Firework enabled, if you want to change the velocity threshold or the firework cooldown check the settings under Pitch40Util.");
                        oldAutoFireworkValue = setting.get();
                        setting.set(true);
                        }
                    }
                }
            }
            // set original pos to pathDistance blocks in the direction the player is facing
            Vec3d offset = (new Vec3d(Math.sin(-mc.player.getYaw() * Math.PI / 180), 0, Math.cos(-mc.player.getYaw() * Math.PI / 180)).normalize()).multiply(pathDistance.get());
            Vec3d targetPos = mc.player.getPos().add(offset);
            for (int i = 0; i < (maxTrailLength.get() * startDirectionWeighting.get()); i++)
            {
                trail.add(targetPos);
            }
            targetYaw = getActualYaw(mc.player.getYaw());
        }
        else
        {
            this.toggle();
        }
    }

    @Override
    public void onDeactivate()
    {
        // do this at the end to free memory
        seenChunksCache = Caffeine.newBuilder()
            .maximumSize(chunkCacheLength.get())
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
        XaeroPlus.EVENT_BUS.unregister(this);
        trail.clear();
        // If follow mode was never set due to baritone not being present, etc.
        if (followMode == null) return;
        switch (followMode)
        {
            case BARITONE:
            {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("cancel");
                break;
            }
            case YAWLOCK:
            {
                Class<? extends Module> pitch40Util = Pitch40Util.class;
                Module pitch40UtilModule = Modules.get().get(pitch40Util);
                if (pitch40.get() && pitch40UtilModule.isActive())
                {
                    pitch40UtilModule.toggle();
                }
                ((Setting<Boolean>)pitch40UtilModule.settings.get("Auto Firework")).set(oldAutoFireworkValue);
            }
        }
    }

    private double targetYaw;

    private int baritoneSetGoalTicks = 0;

    private void circle()
    {
        if (followMode == FollowMode.BARITONE) return;
        mc.player.setYaw(getActualYaw((float) (mc.player.getYaw() + circlingDegPerTick.get())));
        if (mc.player.age % 100 == 0)
        {
            log("Circling to look for new chunks, abandoning trail in " + (trailTimeout.get() - (System.currentTimeMillis() - lastFoundTrailTime)) / 1000 + " seconds.");
        }
    }

    private long lastRocketUse = 0;
    private double targetPitch = 0;
    private double yTarget = -1;

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        if (mc.player == null || mc.world == null) return;
        if (followingTrail && System.currentTimeMillis() - lastFoundTrailTime > trailTimeout.get())
        {
            resetTrail();
            log("Trail timed out, stopping.");
            // TODO: Add options for what to do next
            switch (trailEndBehavior.get())
            {
                case DISABLE:
                {
                    this.toggle();
                    break;
                }
                case FLY_TOWARDS_YAW:
                {
                    targetYaw = trailEndYaw.get();
                    break;
                }
                case DISCONNECT:
                {
                    mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[TrailFollower] Trail timed out.")));
                    break;
                }
            }
        }
        if (followingTrail && System.currentTimeMillis() - lastFoundTrailTime > chunkFoundTimeout.get())
        {
            circle();
            return;
        }
        switch (followMode)
        {
            case BARITONE:
            {
                if (baritoneSetGoalTicks > 0)
                {
                    baritoneSetGoalTicks--;
                }
                else if (baritoneSetGoalTicks == 0)
                {
                    // if following overworld from nether we need to wait to set the goal until we are close to the current goal
                    // make sure targetPos is on an actual chunk
//                    if (mc.world.getRegistryKey().equals(World.NETHER) && oppositeDimension.get())
//                    {
//                        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() != null
//                            && !BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination().isWithinDistance(mc.player.getPos(), 200))
//                        {
//                            return;
//                        }
//                        else
//                        {
//                            boolean chunkFound = false;
//                            for (int i = 1000; i >= 0; i--)
//                            {
//                                Vec3d nextPosition = positionInDirection(mc.player.getPos().multiply(8), targetYaw, 16 * i);
//                                ChunkPos nextChunkPosition = new ChunkPos(new BlockPos((int)nextPosition.x, 0, (int)nextPosition.z));
//                                if (isValidChunk(nextChunkPosition, World.OVERWORLD))
//                                {
//                                    pathDistanceActual = (double) (16 * i) / 8;
//                                    chunkFound = true;
//                                    break;
//                                }
//                            }
//                            if (!chunkFound) return;
//                        }
//                    }
                    baritoneSetGoalTicks = baritoneUpdateTicks.get();
                    Vec3d targetPos = positionInDirection(mc.player.getPos(), targetYaw, pathDistanceActual);
                    BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ((int) targetPos.x, (int) targetPos.z));
                    if (autoElytra.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null)
                    {
                        // TODO: Fix this
                        log("The auto elytra mode is broken right now. If it's not working just turn it off and manually use #elytra to start.");
                        BaritoneAPI.getSettings().elytraTermsAccepted.value = true;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("elytra");
                    }
                }
                break;
            }
            case YAWLOCK: {
                mc.player.setYaw(smoothRotation(getActualYaw(mc.player.getYaw()), targetYaw));
                if (vanillaFlyOnly.get()) {
                    handleVanillaFly();
                }
                break;
            }
        }

    }

    Vec3d posDebug;

    @EventHandler
    private void onRender(Render3DEvent event)
    {
        if (!debug.get()) return;
        Vec3d targetPos = positionInDirection(mc.player.getPos(), targetYaw, 10);
        // target line
        event.renderer.line(mc.player.getX(), mc.player.getY(), mc.player.getZ(), targetPos.x, targetPos.y, targetPos.z, new Color(255, 0, 0));
        // chunk
        if (posDebug != null) event.renderer.line(mc.player.getX(), mc.player.getY(), mc.player.getZ(), posDebug.x, targetPos.y, posDebug.z, new Color(0, 0, 255));
    }

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event)
    {
        if (event.seenChunk()) return;
        RegistryKey<World> currentDimension = mc.world.getRegistryKey();
        WorldChunk chunk = event.chunk();
        ChunkPos chunkPos = chunk.getPos();
        long chunkLong = chunkPos.toLong();

        // if found in the cache then ignore the chunk
        if (seenChunksCache.getIfPresent(chunkLong) != null) return;



        ChunkPos chunkDelta = new ChunkPos(chunkPos.x - mc.player.getChunkPos().x, chunkPos.z - mc.player.getChunkPos().z);

        if (oppositeDimension.get())
        {
            if (currentDimension.equals(World.OVERWORLD))
            {
                chunkPos = new ChunkPos(mc.player.getChunkPos().x / 8 + chunkDelta.x, mc.player.getChunkPos().z / 8 + chunkDelta.z);
                currentDimension = World.NETHER;
            }
            else if (currentDimension.equals(World.NETHER))
            {
                chunkPos = new ChunkPos(mc.player.getChunkPos().x * 8 + chunkDelta.x, mc.player.getChunkPos().z * 8 + chunkDelta.z);
//                log("ChunkPos: " + chunkPos.x + ", " + chunkPos.z);
                currentDimension = World.OVERWORLD;
            }
        }

        // Check that the chunk is actually mapped, and that it is an old chunk
        if (!isValidChunk(chunkPos, currentDimension)) return;

        seenChunksCache.put(chunkLong, Byte.MAX_VALUE);

        // nether will get out of chunk render distance range of overworld. needs fix.
        // possible fix:
        // make sure baritone markers are on the trail, only look for new chunks when player is near the waypoint


        // use chunk.getPos() here instead of the dimension specific chunkPos because we have to path to blocks in our dimension
        Vec3d pos = chunk.getPos().getCenterAtY(0).toCenterPos();
        posDebug = pos;

        if (!followingTrail)
        {
            if (System.currentTimeMillis() - lastFoundPossibleTrailTime > chunkConsiderationWindow.get() * 1000)
            {
                possibleTrail.clear();
            }
            possibleTrail.add(pos);
            lastFoundPossibleTrailTime = System.currentTimeMillis();
            if (possibleTrail.size() > chunksBeforeStarting.get())
            {
                log("Trail found, starting to follow.");
                followingTrail = true;
                lastFoundTrailTime = System.currentTimeMillis();
                trail.addAll(possibleTrail);
                possibleTrail.clear();
                yTarget = -1; // this is only used for and effects Vanilla Fly if it is checked*
            }
            return;
        }


        // add chunks to the list

        double chunkAngle = Rotations.getYaw(pos);
        double angleDiff = angleDifference(targetYaw, chunkAngle);

//        if (Math.abs(angleDiff) > 90)
//        {
//            return;
//        }

        lastFoundTrailTime = System.currentTimeMillis();

        // free up one spot for a new chunk to be added
        while(trail.size() >= maxTrailLength.get())
        {
            trail.pollFirst();
        }

        if (angleDiff > 0 && angleDiff < 90 && directionWeighting.get() == DirectionWeighting.LEFT)
        {
            // add extra chunks to increase the weighting
            // TODO: Maybe redo this to use a map of chunk pos to weights
            for (int i = 0; i < directionWeightingMultiplier.get() - 1; i++)
            {
                trail.pollFirst();
                trail.add(pos);
            }
            trail.add(pos);
        }
        else if (angleDiff < 0 && angleDiff > -90 && directionWeighting.get() == DirectionWeighting.RIGHT)
        {
            for (int i = 0; i < directionWeightingMultiplier.get() - 1; i++)
            {
                trail.pollFirst();
                trail.add(pos);
            }
            trail.add(pos);
        }
        else
        {
            trail.add(pos);
        }


        // get average pos
        Vec3d averagePos = calculateAveragePosition(trail);

        Vec3d positionVec = averagePos.subtract(mc.player.getPos()).normalize();

        Vec3d targetPos = mc.player.getPos().add(positionVec.multiply(10));
        targetYaw = Rotations.getYaw(targetPos);
    }

    private boolean isValidChunk(ChunkPos chunkPos, RegistryKey<World> currentDimension)
    {
        PaletteNewChunks paletteNewChunks = ModuleManager.getModule(PaletteNewChunks.class);
        boolean is119NewChunk = paletteNewChunks
            .isNewChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        boolean isHighlighted = is119NewChunk || paletteNewChunks
            .isInverseNewChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        return isHighlighted && (!is119NewChunk || is112OldChunk);
    }

    private Vec3d calculateAveragePosition(ArrayDeque<Vec3d> positions)
    {
        double sumX = 0, sumZ = 0;
        for (Vec3d pos : positions) {
            sumX += pos.x;
            sumZ += pos.z;
        }
        return new Vec3d(sumX / positions.size(), 0, sumZ / positions.size());
    }

    private float getActualYaw(float yaw)
    {
        return (yaw % 360 + 360) % 360;
    }

    private float smoothRotation(double current, double target)
    {
        double difference = angleDifference(target, current);
        return (float) (current + difference * rotateScaling.get());
    }

    private double angleDifference(double target, double current)
    {
        double diff = (target - current + 180) % 360 - 180;
        return diff < -180 ? diff + 360 : diff;
    }

    private void log(String message)
    {
        info(message);
        if (!webhookLink.get().isEmpty())
        {
            sendWebhook(webhookLink.get(), "TrailFollower", message, null, mc.player.getGameProfile().getName());
        }
    }

    private enum FollowMode
    {
        BARITONE,
        YAWLOCK
    }

    public enum DirectionWeighting
    {
        LEFT,
        NONE,
        RIGHT
    }

    public enum ChunkTypes
    {
        ONLY_OLD, // only 1.12 chunks that are not 1.19 chunks
        OLD_AND_LOADED_IN_119, // 1.12 chunks that are loaded in 1.19
        ONLY_NEW, // only 1.19 chunks that are not 1.12 chunks
        ALL // all chunks
    }

    public enum TrailEndBehavior
    {
        DISABLE,
        FLY_TOWARDS_YAW,
        DISCONNECT
    }
    private void handleVanillaFly() {
        if (!mc.player.isFallFlying()) {
            mc.player.jump();
            return;
        }

        double currentY = mc.player.getY();
        if (yTarget == -1) yTarget = currentY;
        double yDiff = currentY - yTarget;

        if (Math.abs(yDiff) > 10.0) {
            targetPitch = -Math.atan2(yDiff, 100) * (180 / Math.PI);
        } else if (yDiff > 2.0) {
            targetPitch = 10f;
        } else if (yDiff < -2.0) {
            targetPitch = -10f;
        } else {
            targetPitch = 0f;
        }

        float currentPitch = mc.player.getPitch();
        float pitchDiff = (float) targetPitch - currentPitch;
        mc.player.setPitch(currentPitch + pitchDiff * 0.1f);

        if (System.currentTimeMillis() - lastRocketUse > 3000) {
            tryUseFirework();
        }
    }

    private void tryUseFirework() {
        FindItemResult hotbar = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (!hotbar.found()) {
            FindItemResult inv = InvUtils.find(Items.FIREWORK_ROCKET);
            if (inv.found()) {
                int hotbarSlot = findEmptyHotbarSlot();
                if (hotbarSlot != -1) {
                    InvUtils.move().from(inv.slot()).to(hotbarSlot);
                } else {
                    log("No empty hotbar slot available to move fireworks.");
                    return;
                }
            } else {
                log("No fireworks found in hotbar or inventory.");
                return;
            }
        }
        Utils.firework(mc, true);
        lastRocketUse = System.currentTimeMillis();
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }



}
