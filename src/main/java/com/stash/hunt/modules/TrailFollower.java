package com.stash.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import com.stash.hunt.Addon;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stash.hunt.NewerNewChunksData;
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
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
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
        .name("max-trail-length")
        .description("The number of trail points to keep for the average. Adjust to change how quickly the average will change. More does not necessarily equal better because if the list is too long it will contain chunks behind you.")
        .defaultValue(20)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Integer> chunksBeforeStarting = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-before-starting")
        .description("Useful for afking looking for a trail. The amount of chunks before it gets detected as a trail.")
        .defaultValue(10)
        .sliderRange(1, 50)
        .build()
    );

    public final Setting<Integer> chunkConsiderationWindow = sgGeneral.add(new IntSetting.Builder()
        .name("chunk-timeframe")
        .description("The amount of time in seconds that the chunks must be found in before starting.")
        .defaultValue(5)
        .sliderRange(1, 20)
        .build()
    );

    public final Setting<TrailEndBehavior> trailEndBehavior = sgGeneral.add(new EnumSetting.Builder<TrailEndBehavior>()
        .name("trail-end-behavior")
        .description("What to do when the trail ends.")
        .defaultValue(TrailEndBehavior.DISABLE)
        .build()
    );

    public final Setting<Double> trailEndYaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("trail-end-yaw")
        .description("The direction to go after the trail is abandoned.")
        .defaultValue(0.0)
        .sliderRange(0.0, 359.9)
        .visible(() -> trailEndBehavior.get() == TrailEndBehavior.FLY_TOWARDS_YAW)
        .build()
    );

    // changed to an enum dropdown for fly selection
    public enum OverworldFlightMode {
        VANILLA,
        PITCH40,
        BARITONE_ELYTRA,
        OTHER
    }

    public enum ChunkSource {
        XaeroPlus,
        NewerNewChunks
    }

    public enum NetherPathMode {
        AVERAGE,
        OTHER
    }

    public final Setting<OverworldFlightMode> overworldFlightMode = sgGeneral.add(new EnumSetting.Builder<OverworldFlightMode>()
        .name("overworld-flight-mode")
        .description("Choose how TrailFollower flies in Overworld. If other is selected then nothing will be automatically enabled, instead just your yaw will be changed to point towards the trail. Baritone elytra uses baritone to fly along the trail and requires baritone to be installed.")
        .defaultValue(OverworldFlightMode.PITCH40)
        .build()
    );

    public final Setting<NetherPathMode> netherPathMode = sgGeneral.add(new EnumSetting.Builder<NetherPathMode>()
        .name("nether-path-mode")
        .description("Choose how TrailFollower does baritone pathing in Nether. If other is selected then nothing will be automatically enabled, instead just your yaw will be changed to point towards the trail.")
        .defaultValue(NetherPathMode.AVERAGE)
        .build()
    );

    public final Setting<Boolean> pitch40Firework = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-firework")
        .description("Uses a firework automatically if your velocity is too low.")
        .defaultValue(true)
        .visible(() -> overworldFlightMode.get() == OverworldFlightMode.PITCH40)
        .build()
    );

    public final Setting<Double> rotateScaling = sgGeneral.add(new DoubleSetting.Builder()
        .name("rotate-scaling")
        .description("Scaling of how fast the yaw changes. 1 = instant, 0 = doesn't change")
        .defaultValue(0.1)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public final Setting<Boolean> oppositeDimension = sgGeneral.add(new BoolSetting.Builder()
        .name("opposite-dimension")
        .description("Follows trails from the opposite dimension (Requires that you've already loaded the other dimension with XP).")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> autoElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-start-baritone-elytra")
        .description("Starts baritone elytra for you.")
        .defaultValue(false)
        .build()
    );

    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);

    public final Setting<Double> pathDistance = sgAdvanced.add(new DoubleSetting.Builder()
        .name("path-distance")
        .description("The distance to add trail positions in the direction the player is facing. (Ignored when following overworld from nether)")
        .defaultValue(500)
        .sliderRange(100, 2000)
        .onChanged(value -> pathDistanceActual = value)
        .build()
    );

    public final Setting<FollowMode> flightMethod = sgAdvanced.add(new EnumSetting.Builder<FollowMode>()
        .name("flight-method")
        .description("Decided how the goals will be used. Leave this on AUTO unless you want to use yaw lock in the nether for example.")
        .defaultValue(FollowMode.AUTO)
        .build()
    );

    public final Setting<Double> startDirectionWeighting = sgAdvanced.add(new DoubleSetting.Builder()
        .name("start-direction-weight")
        .description("The weighting of the direction the player is facing when starting the trail. 0 for no weighting (not recommended) 1 for max weighting (will take a bit for direction to change)")
        .defaultValue(0.5)
        .min(0)
        .sliderMax(1)
        .build()
    );

    public final Setting<DirectionWeighting> directionWeighting = sgAdvanced.add(new EnumSetting.Builder<DirectionWeighting>()
        .name("direction-weighting")
        .description("How the chunks found should be weighted. Useful for path splits. Left will weight chunks to the left of the player higher, right will weigh chunks to the right higher, and none will be in the middle/random. ")
        .defaultValue(DirectionWeighting.NONE)
        .build()
    );

    public final Setting<Integer> directionWeightingMultiplier = sgAdvanced.add(new IntSetting.Builder()
        .name("direction-weighting-multiplier")
        .description("The multiplier for how much weight should be given to chunks in the direction specified. Values are capped to be in the range [2, maxTrailLength].")
        .defaultValue(2)
        .min(2)
        .sliderMax(10)
        .visible(() -> directionWeighting.get() != DirectionWeighting.NONE)
        .build()
    );

    public final Setting<Boolean> only112 = sgAdvanced.add(new BoolSetting.Builder()
        .name("follow-only-1.12")
        .description("Will only follow 1.12 chunks and will ignore other ones.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> chunkFoundTimeout = sgAdvanced.add(new DoubleSetting.Builder()
        .name("chunk-found-timeout")
        .description("The amount of MS without a chunk found to trigger circling.")
        .defaultValue(1000 * 5)
        .min(1000)
        .sliderMax(1000 * 10)
        .build()
    );

    public final Setting<Double> circlingDegPerTick = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Circling-degrees-per-tick")
        .description("The amount of degrees to change per tick while circling.")
        .defaultValue(2.0)
        .min(1.0)
        .sliderMax(20.0)
        .build()
    );

    public final Setting<Double> trailTimeout = sgAdvanced.add(new DoubleSetting.Builder()
        .name("trail-timeout")
        .description("The amount of MS without a chunk found to stop following the trail.")
        .defaultValue(1000 * 30)
        .min(1000 * 10)
        .sliderMax(1000 * 60)
        .build()
    );
    // added trail deviation slider now that baritone is locked to trail pathing
    public final Setting<Double> maxTrailDeviation = sgAdvanced.add(new DoubleSetting.Builder()
        .name("max-trail-deviation")
        .description("Maximum allowed angle (in degrees) from the original trail direction. Helps avoid switching to intersecting trails.")
        .defaultValue(180.0)
        .min(1.0)
        .sliderMax(270.0)
        .build()
    );

    public final Setting<ChunkSource> chunkSource = sgAdvanced.add(new EnumSetting.Builder<ChunkSource>()
        .name("chunk-source")
        .description("Which addon to use as the source of chunk detection. XaeroPlus requires the XaeroPlus New Chunks and Old Chunks modules enabled. NewerNewChunks reads live data from the Trouser-Streak NewerNewChunks addon if it is running, or its saved chunk data files as a fallback.")
        .defaultValue(ChunkSource.XaeroPlus)
        .build()
    );

    public final Setting<Integer> chunkCacheLength = sgAdvanced.add(new IntSetting.Builder()
        .name("chunk-cache-length")
        .description("The amount of chunks to keep in the cache. (Won't be applied until deactivating)")
        .defaultValue(100_000)
        .sliderRange(0, 10_000_000)
        .build()
    );

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-link")
        .description("Will send all updates to the webhook link. Leave blank to disable.")
        .defaultValue("")
        .build()
    );

    public final Setting<Integer> baritoneUpdateTicks = sgAdvanced.add(new IntSetting.Builder()
        .name("baritone-path-update-ticks")
        .description("The amount of ticks between updates to the baritone goal. Low values may cause high instability.")
        .defaultValue(5 * 20) // 5 seconds
        .sliderRange(20, 30 * 20)
        .build()
    );

    public final Setting<Boolean> debug = sgAdvanced.add(new BoolSetting.Builder()
        .name("debug")
        .description("Debug mode.")
        .defaultValue(false)
        .build()
    );

    // TODO: Auto disconnect at certain chunk load speed

    private boolean oldAutoFireworkValue;

    private FollowMode followMode;

    private boolean followingTrail = false;

    private ArrayDeque<Vec3> trail = new ArrayDeque<>();
    private ArrayDeque<Vec3> possibleTrail = new ArrayDeque<>();

    private long lastFoundTrailTime;
    private long lastFoundPossibleTrailTime;

    private double pathDistanceActual = pathDistance.get();

    private Cache<Long, Byte> seenChunksCache = Caffeine.newBuilder()
        .maximumSize(chunkCacheLength.get())
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    private final NewerNewChunksData newerNewChunksData = new NewerNewChunksData();

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

    boolean started = false;

    @Override
    public void onActivate()
    {
        resetTrail();
        XaeroPlus.EVENT_BUS.register(this);
        newerNewChunksData.init();

        if (started)
        {
            if (mc.player != null && mc.level != null)
            {
                ResourceKey<Level> currentDimension = mc.level.dimension();
                if (oppositeDimension.get())
                {
                    if (currentDimension.equals(Level.END))
                    {
                        info("There is no opposite dimension to the end. Disabling TrailFollower");
                        this.toggle();
                        return;
                    }
                    else if (currentDimension.equals(Level.NETHER))
                    {
                        info("Following overworld trails from the nether is not supported yet, sorry. Disabling TrailFollower");
                        this.toggle();
                        return;
                    }
                }
                if (flightMethod.get() != FollowMode.AUTO)
                {
                    followMode = flightMethod.get();
                }
                else
                {
                    if (!currentDimension.equals(Level.NETHER))
                    {
                        if (overworldFlightMode.get() == OverworldFlightMode.BARITONE_ELYTRA)
                        {
                            try {
                                Class.forName("baritone.api.BaritoneAPI");
                                followMode = FollowMode.BARITONE;
                                info("You are in the overworld or end, baritone elytra mode will be used.");
                            } catch (ClassNotFoundException e) {
                                info("Baritone is required to use baritone elytra. Disabling TrailFollower");
                                this.toggle();
                                return;
                            }
                        }
                        else
                        {
                            followMode = FollowMode.YAWLOCK;
                            info("You are in the overworld or end, basic yaw mode will be used.");
                        }
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
                }

                if (followMode == FollowMode.YAWLOCK && !mc.level.dimension().equals(Level.NETHER)) {
                    if (overworldFlightMode.get() == OverworldFlightMode.PITCH40) {
                        Class<? extends Module> pitch40Util = Pitch40Util.class;
                        Module pitch40UtilModule = Modules.get().get(pitch40Util);
                        if (!pitch40UtilModule.isActive()) {
                            pitch40UtilModule.toggle();
                            if (pitch40Firework.get()) {
                                Setting<Boolean> setting = ((Setting<Boolean>) pitch40UtilModule.settings.get("auto-firework"));
                                info("Auto Firework enabled, if you want to change the velocity threshold or the firework cooldown check the settings under Pitch40Util.");
                                oldAutoFireworkValue = setting.get();
                                setting.set(true);
                            }
                        }
                    } else if (overworldFlightMode.get() == OverworldFlightMode.VANILLA) {
                        AFKVanillaFly afkVanillaFly = Modules.get().get(AFKVanillaFly.class);
                        if (!afkVanillaFly.isActive()) {
                            afkVanillaFly.toggle();
                        }
                    }
                }
                // set original pos to pathDistance blocks in the direction the player is facing
                Vec3 offset = (new Vec3(Math.sin(-mc.player.getYRot() * Math.PI / 180), 0, Math.cos(-mc.player.getYRot() * Math.PI / 180)).normalize()).scale(pathDistance.get());
                Vec3 targetPos = mc.player.position().add(offset);
                for (int i = 0; i < (maxTrailLength.get() * startDirectionWeighting.get()); i++)
                {
                    trail.add(targetPos);
                }
                targetYaw = getActualYaw(mc.player.getYRot());
            }
            else
            {
                this.toggle();
            }
            started = true;
        }
    }

    @Override
    public void onDeactivate()
    {
        started = false;
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
            case YAWLOCK: {
                if (mc.level == null || mc.level.dimension().equals(Level.NETHER)) return;
                if (overworldFlightMode.get() == OverworldFlightMode.VANILLA) {
                    AFKVanillaFly afkVanillaFly = Modules.get().get(AFKVanillaFly.class);
                    if (afkVanillaFly != null) {
                        afkVanillaFly.resetYLock();
                        if (afkVanillaFly.isActive()) afkVanillaFly.toggle();
                    }
                } else if (overworldFlightMode.get() == OverworldFlightMode.PITCH40) {
                    Class<? extends Module> pitch40Util = Pitch40Util.class;
                    Module pitch40UtilModule = Modules.get().get(pitch40Util);
                    if (pitch40UtilModule.isActive()) {
                        pitch40UtilModule.toggle();
                    }
                    ((Setting<Boolean>) pitch40UtilModule.settings.get("auto-firework")).set(oldAutoFireworkValue);
                }
                break;
            }
        }
    }

    private double targetYaw;

    private int baritoneSetGoalTicks = 0;

    private void circle()
    {
        if (followMode == FollowMode.BARITONE) return;
        mc.player.setYRot(getActualYaw((float) (mc.player.getYRot() + circlingDegPerTick.get())));
        if (mc.player.tickCount % 100 == 0)
        {
            log("Circling to look for new chunks, abandoning trail in " + (trailTimeout.get() - (System.currentTimeMillis() - lastFoundTrailTime)) / 1000 + " seconds.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        if (!started)
        {
            started = true;
            onActivate();
        }
        if (mc.player == null || mc.level == null) return;
        if (followingTrail && System.currentTimeMillis() - lastFoundTrailTime > trailTimeout.get())
        {
            resetTrail();
            log("Trail timed out, stopping.");
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
                    mc.player.connection.handleDisconnect(new ClientboundDisconnectPacket(Component.literal("[TrailFollower] Trail timed out.")));
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
                    //instead of flying to a calculated offset from the player using pathDistanceActual, will directly set the last trail chunk detected
                    baritoneSetGoalTicks = baritoneUpdateTicks.get();
                    if (mc.level.dimension().equals(Level.NETHER)) {

                        if (!trail.isEmpty()) {
                            Vec3 baritoneTarget;
                            if (netherPathMode.get() == NetherPathMode.AVERAGE) {
                                Vec3 averagePos = calculateAveragePosition(trail);
                                Vec3 directionVec = averagePos.subtract(mc.player.position()).normalize();
                                Vec3 predictedPos = mc.player.position().add(directionVec.scale(10));
                                targetYaw = Rotations.getYaw(predictedPos);
                                baritoneTarget = positionInDirection(mc.player.position(), targetYaw, pathDistanceActual);
                            } else {
                                Vec3 lastPos = trail.getLast();
                                baritoneTarget = lastPos;
                            }

                            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                                .setGoalAndPath(new GoalXZ((int) baritoneTarget.x, (int) baritoneTarget.z));
                        }
                    } else {
                        // use average path for overworld with baritone elytra
                        Vec3 targetPos = positionInDirection(mc.player.position(), targetYaw, pathDistanceActual);
                        BaritoneAPI.getSettings().elytraTermsAccepted.value = true;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
                        BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().pathTo(new GoalXZ((int) targetPos.x, (int) targetPos.z));

                        targetYaw = Rotations.getYaw(targetPos); // smooth rotation target
                    }
                    if (autoElytra.get() && (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null))
                    {
                        BaritoneAPI.getSettings().elytraTermsAccepted.value = true;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("elytra");
                    }
                }
                break;
            }
            case YAWLOCK: {
                mc.player.setYRot(Utils.smoothRotation(getActualYaw(mc.player.getYRot()), targetYaw, rotateScaling.get()));
                break;
            }
        }

    }

    Vec3 posDebug;

    @EventHandler
    private void onRender(Render3DEvent event)
    {
        if (!debug.get()) return;
        Vec3 targetPos = positionInDirection(mc.player.position(), targetYaw, 10);
        // target line
        event.renderer.line(mc.player.getX(), mc.player.getY(), mc.player.getZ(), targetPos.x, targetPos.y, targetPos.z, new Color(255, 0, 0));
        // chunk
        if (posDebug != null) event.renderer.line(mc.player.getX(), mc.player.getY(), mc.player.getZ(), posDebug.x, targetPos.y, posDebug.z, new Color(0, 0, 255));
    }

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event)
    {
        if (event.seenChunk()) return;
        ResourceKey<Level> currentDimension = mc.level.dimension();
        LevelChunk chunk = event.chunk();
        ChunkPos chunkPos = chunk.getPos();
        long chunkLong = chunkPos.pack();

        // if found in the cache then ignore the chunk
        if (seenChunksCache.getIfPresent(chunkLong) != null) return;

        ChunkPos chunkDelta = new ChunkPos(chunkPos.x() - mc.player.chunkPosition().x(), chunkPos.z() - mc.player.chunkPosition().z());

        if (oppositeDimension.get())
        {
            if (currentDimension.equals(Level.OVERWORLD))
            {
                chunkPos = new ChunkPos(mc.player.chunkPosition().x() / 8 + chunkDelta.x(), mc.player.chunkPosition().z() / 8 + chunkDelta.z());
                currentDimension = Level.NETHER;
            }
            else if (currentDimension.equals(Level.NETHER))
            {
                chunkPos = new ChunkPos(mc.player.chunkPosition().x() * 8 + chunkDelta.x(), mc.player.chunkPosition().z() * 8 + chunkDelta.z());
//                log("ChunkPos: " + chunkPos.x + ", " + chunkPos.z);
                currentDimension = Level.OVERWORLD;
            }
        }
        // Check that the chunk is actually mapped, and that it is an old chunk
        if (!isValidChunk(chunkPos, currentDimension)) return;

        seenChunksCache.put(chunkLong, Byte.MAX_VALUE);

        // nether will get out of chunk render distance range of overworld. needs fix.
        // possible fix:
        // make sure baritone markers are on the trail, only look for new chunks when player is near the waypoint


        // use chunk.getPos() here instead of the dimension specific chunkPos because we have to path to blocks in our dimension
        Vec3 pos = Vec3.atCenterOf(chunk.getPos().getMiddleBlockPosition(0));
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
            }
            return;
        }


        // add chunks to the list

        double chunkAngle = Rotations.getYaw(pos);
        double angleDiff = Utils.angleDifference(targetYaw, chunkAngle);
        // was not able to add this before, but now can successfully filter out most other trails using the most recent chunk for pathing
        if (followingTrail && Math.abs(angleDiff) > maxTrailDeviation.get())
        {
            return;
        }
        lastFoundTrailTime = System.currentTimeMillis();
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


        // instead of a calculated average coordinate, will use latest chunk added to trail
        // *fix for overworld smoothing
        if (!trail.isEmpty()) {
            if (followMode == FollowMode.YAWLOCK) {
                Vec3 averagePos = calculateAveragePosition(trail);
                Vec3 positionVec = averagePos.subtract(mc.player.position()).normalize();
                Vec3 targetPos = mc.player.position().add(positionVec.scale(10));
                targetYaw = Rotations.getYaw(targetPos);
            } else {
                Vec3 lastTrailPoint = trail.getLast();
                targetYaw = Rotations.getYaw(lastTrailPoint);
            }
        }
    }

    private boolean isValidChunk(ChunkPos chunkPos, ResourceKey<Level> currentDimension)
    {
        if (chunkSource.get() == ChunkSource.NewerNewChunks)
        {
            return isValidChunkNewerNewChunks(chunkPos, currentDimension);
        }

        PaletteNewChunks paletteNewChunks = ModuleManager.getModule(PaletteNewChunks.class);
        boolean is119NewChunk = paletteNewChunks
            .isNewChunk(
                chunkPos.x(),
                chunkPos.z(),
                currentDimension
            );

        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(
                chunkPos.x(),
                chunkPos.z(),
                currentDimension
            );

        boolean isHighlighted = is119NewChunk || paletteNewChunks
            .isInverseNewChunk(
                chunkPos.x(),
                chunkPos.z(),
                currentDimension
            );

        return isHighlighted && ((!is119NewChunk && !only112.get()) || is112OldChunk);
    }

    private boolean isValidChunkNewerNewChunks(ChunkPos chunkPos, ResourceKey<Level> currentDimension)
    {
        boolean isNew = newerNewChunksData.isNewChunk(chunkPos.x(), chunkPos.z(), currentDimension);
        boolean isInverse = newerNewChunksData.isInverseChunk(chunkPos.x(), chunkPos.z(), currentDimension);
        boolean isOld = newerNewChunksData.isOldChunk(chunkPos.x(), chunkPos.z(), currentDimension);

        boolean isHighlighted = isNew || isInverse;
        return isHighlighted && ((!isNew && !only112.get()) || isOld);
    }

    // not using this method now but will keep it in case
    private Vec3 calculateAveragePosition(ArrayDeque<Vec3> positions)
    {
        double sumX = 0, sumZ = 0;
        for (Vec3 pos : positions) {
            sumX += pos.x;
            sumZ += pos.z;
        }
        return new Vec3(sumX / positions.size(), 0, sumZ / positions.size());
    }

    private float getActualYaw(float yaw)
    {
        return (yaw % 360 + 360) % 360;
    }

    private void log(String message)
    {
        info(message);
        if (!webhookLink.get().isEmpty())
        {
            sendWebhook(webhookLink.get(), "TrailFollower", message, null, mc.player.getGameProfile().name());
        }
    }

    public enum FollowMode
    {
        AUTO,
        BARITONE,
        YAWLOCK
    }

    public enum DirectionWeighting
    {
        LEFT,
        NONE,
        RIGHT
    }

//    public enum ChunkTypes
//    {
//        ONLY_OLD, // only 1.12 chunks that are not 1.19 chunks
//        OLD_AND_LOADED_IN_119, // 1.12 chunks that are loaded in 1.19
//        ONLY_NEW, // only 1.19 chunks that are not 1.12 chunks
//        ALL // all chunks
//    }

    public enum TrailEndBehavior
    {
        DISABLE,
        FLY_TOWARDS_YAW,
        DISCONNECT
    }
}
