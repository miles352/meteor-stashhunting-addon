package com.stash.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.entity.player.PlayerMoveEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.events.world.PlaySoundEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixininterface.IVec3;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.player.ChestSwap;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.HashedStack;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.game.ClientboundContainerClosePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import com.stash.hunt.Addon;
import java.util.List;

import static com.stash.hunt.Utils.*;

public class ElytraFlyPlusPlus extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgObstaclePasser = settings.createGroup("Obstacle Passer");

    private final Setting<Boolean> bounce = sgGeneral.add(new BoolSetting.Builder()
        .name("bounce")
        .description("Automatically does bounce efly.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> motionYBoost = sgGeneral.add(new BoolSetting.Builder()
        .name("motion-y-boost")
        .description("Greatly increases speed by cancelling Y momentum.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Boolean> onlyWhileColliding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-colliding")
        .description("Only enables motion y boost if colliding with a wall.")
        .defaultValue(true)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build()
    );

    private final Setting<Boolean> tunnelBounce = sgGeneral.add(new BoolSetting.Builder()
        .name("tunnel-bounce")
        .description("Allows you to bounce in 1x2 tunnels. This should not be on if you are not in a tunnel.")
        .defaultValue(false)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build()
    );

    private final Setting<Double> speed = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("The speed in blocks per second to keep you at.")
        .defaultValue(100.0)
        .sliderRange(20, 250)
        .visible(() -> bounce.get() && motionYBoost.get())
        .build()
    );

    private final Setting<Boolean> lockPitch = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-pitch")
        .description("Whether to lock your pitch when bounce is enabled.")
        .defaultValue(true)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Double> pitch = sgGeneral.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("The pitch to set when bounce is enabled.")
        .defaultValue(90.0)
        .sliderRange(-90, 90)
        .visible(() -> bounce.get() && lockPitch.get())
        .build()
    );

    private final Setting<Boolean> lockYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("lock-yaw")
        .description("Whether to lock your yaw when bounce is enabled.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Boolean> useCustomYaw = sgGeneral.add(new BoolSetting.Builder()
        .name("use-custom-yaw")
        .description("Enable this if you want to use a yaw that isn't a factor of 45. WARNING: This effects the baritone goal for obstacle passer, " +
            "use the default Rotations module if you only want a different yawlock.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Double> yaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("yaw")
        .description("The yaw to set when bounce is enabled. This is auto set to the closest 45 deg angle to you unless Use Custom Yaw is enabled. " +
            "WARNING: This effects the baritone goal for obstacle passer, use the default Rotations module if you only want a different yawlock.")
        .defaultValue(0.0)
        .sliderRange(0, 359)
        .visible(() -> bounce.get() && useCustomYaw.get())
        .build()
    );

    private final Setting<Boolean> highwayObstaclePasser = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("highway-obstacle-passer")
        .description("Uses baritone to pass obstacles.")
        .defaultValue(false)
        .visible(bounce::get)
        .build()
    );

    private final Setting<Boolean> useCustomStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("use-custom-start-position")
        .description("Enable and set this ONLY if you are on a ringroad or don't want to be locked to a highway. Otherwise (0, 0) is the start position and will be automatically used.")
        .defaultValue(false)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<BlockPos> startPos = sgObstaclePasser.add(new BlockPosSetting.Builder()
        .name("start-position")
        .description("The start position to use when using a custom start position.")
        .defaultValue(new BlockPos(0,0,0))
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && useCustomStartPos.get())
        .build()
    );

    private final Setting<Boolean> awayFromStartPos = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("away-from-start-position")
        .description("If true, will go away from the start position instead of towards it. The start pos is (0,0) if it is not set to a custom start pos.")
        .defaultValue(true)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Double> distance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("distance")
        .description("The distance to set the baritone goal for path realignment.")
        .defaultValue(10.0)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Integer> targetY = sgObstaclePasser.add(new IntSetting.Builder()
        .name("y-level")
        .description("The Y level to bounce at. This must be correct or bounce will not start properly.")
        .defaultValue(120)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Boolean> avoidPortalTraps = sgObstaclePasser.add(new BoolSetting.Builder()
        .name("avoid-portal-traps")
        .description("Will attempt to detect portal traps on chunk load and avoid them.")
        .defaultValue(false)
        .visible(() -> bounce.get() && highwayObstaclePasser.get())
        .build()
    );

    private final Setting<Double> portalAvoidDistance = sgObstaclePasser.add(new DoubleSetting.Builder()
        .name("portal-avoid-distance")
        .description("The distance to a portal trap where the obstacle passer will takeover and go around it.")
        .defaultValue(20)
        .min(0)
        .sliderMax(50)
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Integer> portalScanWidth = sgObstaclePasser.add(new IntSetting.Builder()
        .name("portal-scan-width")
        .description("The width on the axis of the highway that will be scanned for portal traps.")
        .defaultValue(5)
        .min(3)
        .sliderMax(10)
        .visible(() -> bounce.get() && highwayObstaclePasser.get() && avoidPortalTraps.get())
        .build()
    );

    private final Setting<Boolean> fakeFly = sgGeneral.add(new BoolSetting.Builder()
        .name("chestplate-fakefly")
        .description("Lets you fly using a chestplate to use almost 0 elytra durability. Must have elytra in hotbar.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> toggleElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("toggle-elytra")
        .description("Equips an elytra on activate, and a chestplate on deactivate.")
        .defaultValue(false)
        .visible(() -> !fakeFly.get())
        .build()
    );

    public ElytraFlyPlusPlus() {
        super(
            Addon.CATEGORY,
            "ElytraFlyPlusPlus",
            "Elytra fly with some more features."
        );
    }

    private boolean startSprinting;
    private BlockPos portalTrap = null;
    private boolean paused = false;

    private boolean elytraToggled = false;

    private Vec3 lastUnstuckPos;
    private int stuckTimer = 0;

    @EventHandler
    private void onReceivePacket(PacketEvent.Receive event)
    {
        if (event.packet instanceof ClientboundPlayerPositionPacket packet)
        {
//            onActivate();
        }
        else if (event.packet instanceof ClientboundContainerClosePacket)
        {
            event.cancel();
        }
    }

    @Override
    public void onActivate()
    {
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

        startSprinting = mc.player.isSprinting();
        tempPath = null;
        portalTrap = null;
        paused = false;
        waitingForChunksToLoad = false;
        elytraToggled = false;
        lastPos = mc.player.position();
        lastUnstuckPos = mc.player.position();
        stuckTimer = 0;

        // I don't know any other way to fix this stupid shit
        if (bounce.get() && mc.player.position().multiply(1, 0, 1).length() >= 100)
        {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null)
            {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }

            if (!useCustomStartPos.get())
            {
                startPos.set(new BlockPos(0, 0, 0));
            }

            if (!useCustomYaw.get())
            {
                // If less than 100 blocks from the start pos, angle calculation may be wrong, so just use players yaw
                if (mc.player.blockPosition().distSqr(startPos.get()) < 10_000 || !highwayObstaclePasser.get())
                {
                    double playerAngleNormalized = angleOnAxis(mc.player.getYRot());
                    yaw.set(playerAngleNormalized);
                }
                else
                {
                    // Otherwise use the angle from the starting position to the players position
                    BlockPos directionVec = mc.player.blockPosition().subtract(startPos.get());
                    double angle = Math.toDegrees(Math.atan2(-directionVec.getX(), directionVec.getZ()));
                    double angleNormalized = angleOnAxis(angle);
                    if (!awayFromStartPos.get())
                    {
                        angleNormalized += 180;
                    }

                    yaw.set(angleNormalized);
                }
            }
        }
    }

    private Vec3 lastPos;

    @EventHandler
    private void onPlayerMove(PlayerMoveEvent event) {
        if (mc.player == null || event.type != MoverType.SELF || !enabled() || !motionYBoost.get() || !bounce.get()) return;

        if (onlyWhileColliding.get() && !mc.player.horizontalCollision) return;

        if (lastPos != null)
        {
            double speedBps = mc.player.position().subtract(lastPos).multiply(20, 0, 20).length();

            Timer timer = Modules.get().get(Timer.class);
            if (timer.isActive()) {
                speedBps *= timer.getMultiplier();
            }

            if (mc.player.onGround() && mc.player.isSprinting() && speedBps < speed.get())
            {
                if (speedBps > 20 || tunnelBounce.get())
                {
                    ((IVec3) event.movement).meteor$setY(0);
                }
                mc.player.setDeltaMovement(mc.player.getDeltaMovement().x, 0.0, mc.player.getDeltaMovement().z);
            }
        }

        lastPos = mc.player.position();
    }

    @Override
    public void onDeactivate()
    {
        if (mc.player == null) return;

        if (bounce.get())
        {
            if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null)
            {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
        }

        mc.player.setSprinting(startSprinting);

        if (toggleElytra.get() && !fakeFly.get())
        {
            if (!mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().toString().contains("chestplate")) {
                Modules.get().get(ChestSwap.class).swap();
            }
        }
    }

    // 5 chunks forwards
    private final double maxDistance = 16 * 5;

    // a path used when there are no valid blocks in range.
    // it will instead path to this and then when it gets close it will look for a valid block again
    private BlockPos tempPath = null;

    private boolean waitingForChunksToLoad;

    @EventHandler
    private void onTick(TickEvent.Pre event)
    {
        if (mc.player == null || mc.player.getAbilities().mayfly) return;

        if (toggleElytra.get() && !fakeFly.get() && !elytraToggled)
        {
            if (!(mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA)))
            {
                Modules.get().get(ChestSwap.class).swap();
            }
            else
            {
                elytraToggled = true;
            }
        }

        if (enabled()) mc.player.setSprinting(true);

        if (bounce.get())
        {
            if (tempPath != null && mc.player.blockPosition().distSqr(tempPath) < 500)
            {
                tempPath = null;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoal(null);
            }
            else if (tempPath != null)
            {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(tempPath));
                return;
            }

            // if still pathing, wait for that to complete
            if (highwayObstaclePasser.get() && BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().getGoal() != null)
            {
                return;
            }

            if (mc.player.distanceToSqr(lastUnstuckPos) < 25)
            {
                stuckTimer++;
            }
            else
            {
                stuckTimer = 0;
                lastUnstuckPos = mc.player.position();
            }

            if (highwayObstaclePasser.get() && mc.player.position().length() > 100 && // > 100 check needed bc server sends queue coordinates when joining in first tick causing goal coordinates to be set to (0, 0)
                (mc.player.getY() < targetY.get() || mc.player.getY() > targetY.get() + 2 || (mc.player.horizontalCollision && !mc.player.minorHorizontalCollision) // collisions / out of highway
                || (portalTrap != null && portalTrap.distSqr(mc.player.blockPosition()) < portalAvoidDistance.get() * portalAvoidDistance.get()) // portal trap detection
                || waitingForChunksToLoad // waiting for chunks to load
                || stuckTimer > 50))
            {
                waitingForChunksToLoad = false;
                paused = true;
                BlockPos goal = mc.player.blockPosition();
                double currDistance = distance.get(); // Keep checking farther distances until a goal is found that has a block beneath it

                if (portalTrap != null) {
                    currDistance += mc.player.position().distanceTo(Vec3.atCenterOf(portalTrap));
                    portalTrap = null;
                    info("Pathing around portal.");
                }

                do
                {
                    if (currDistance > maxDistance)
                    {
                        tempPath = goal;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
                        return;
                    }
                    Vec3 unitYawVec = yawToDirection(yaw.get());
                    Vec3 travelVec = mc.player.position().subtract(Vec3.atCenterOf(startPos.get()));

                    double parallelCurrPosDot = travelVec.multiply(new Vec3(1, 0, 1)).dot(unitYawVec);
                    Vec3 parallelCurrPosComponent = unitYawVec.scale(parallelCurrPosDot);

                    Vec3 pos = Vec3.atCenterOf(startPos.get()).add(parallelCurrPosComponent);
                    pos = positionInDirection(pos, yaw.get(), currDistance);

                    goal = new BlockPos((int)(Math.floor(pos.x)), targetY.get(), (int)Math.floor(pos.z));
                    currDistance++;

                    // Blocks in unloaded chunks are void air, for some reason checking if the chunk is loaded was always true, so I check this instead
                    if (mc.level.getBlockState(goal).getBlock() == Blocks.VOID_AIR)
                    {
                        waitingForChunksToLoad = true;
                        return;
                    }
                }
                // avoid pathing on air cause baritone freaks out, and dont path into portals in case a mod is avoiding portals
                while (!mc.level.getBlockState(goal.below()).isRedstoneConductor(mc.level, goal.below()) ||
                    mc.level.getBlockState(goal).getBlock() == Blocks.NETHER_PORTAL ||
                    !mc.level.getBlockState(goal).isAir());
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(goal));
            }
            else
            {
                // keep jumping
                paused = false;
                if (!enabled()) return;

                if (!fakeFly.get())
                {
                    if (mc.player.onGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get()))
                    {
                        mc.player.jumpFromGround();
                    }
                }

                // set yaw and pitch
                if (lockYaw.get())
                {
                    mc.player.setYRot(yaw.get().floatValue());
                }
                if (lockPitch.get())
                {
                    mc.player.setXRot(pitch.get().floatValue());
                }
            }
        }

        if (enabled())
        {
            if (fakeFly.get())
            {
                doGrimEflyStuff();
            }
            else
            {
                sendStartFlyingPacket();
            }
        }
    }

    public boolean enabled()
    {
        return this.isActive() && !paused && mc.player != null && (fakeFly.get() || mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem().equals(Items.ELYTRA));
    }

    private void doGrimEflyStuff()
    {
        FindItemResult itemResult = InvUtils.findInHotbar(Items.ELYTRA);
        if (!itemResult.found()) return;

        swapToItem(itemResult.slot());

        sendStartFlyingPacket();

        if (bounce.get() && mc.player.onGround() && (!motionYBoost.get() || Utils.getPlayerSpeed().multiply(1, 0, 1).length() < speed.get()))
        {
            mc.player.jumpFromGround();
        }

        swapToItem(itemResult.slot());

    }

    @EventHandler
    private void onPlaySound(PlaySoundEvent event)
    {
        if (!fakeFly.get()) return;
        List<Identifier> armorEquipSounds = List.of(
            Identifier.parse("minecraft:item.armor.equip_generic"),
            Identifier.parse("minecraft:item.armor.equip_netherite"),
            Identifier.parse("minecraft:item.armor.equip_elytra"),
            Identifier.parse("minecraft:item.armor.equip_diamond"),
            Identifier.parse("minecraft:item.armor.equip_gold"),
            Identifier.parse("minecraft:item.armor.equip_iron"),
            Identifier.parse("minecraft:item.armor.equip_chain"),
            Identifier.parse("minecraft:item.armor.equip_leather"),
            Identifier.parse("minecraft:item.elytra.flying")
        );
        for (Identifier identifier : armorEquipSounds) {
            if (identifier.equals(event.sound.getIdentifier())) {
                event.cancel();
                break;
            }
        }
    }

    // 38 is the meteor mapping for chestplate
    // serverside uses default mappings: https://imgs.search.brave.com/cyvAxjIhLweeF1qeRXpC_8ESRlImhUmMGWbV_n2to_A/rs:fit:860:0:0:0/g:ce/aHR0cHM6Ly9jNGsz/LmdpdGh1Yi5pby93/aWtpLnZnL2ltYWdl/cy8xLzEzL0ludmVu/dG9yeS1zbG90cy5w/bmc
    private void swapToItem(int slot) {
        ItemStack chestItem = mc.player.getInventory().getItem(38);
        ItemStack hotbarSwapItem = mc.player.getInventory().getItem(slot);

        Int2ObjectMap<HashedStack> changedSlots = new Int2ObjectOpenHashMap<>();
        changedSlots.put(6, HashedStack.create(hotbarSwapItem, mc.getConnection().decoratedHashOpsGenenerator()));
        changedSlots.put(slot + 36, HashedStack.create(chestItem, mc.getConnection().decoratedHashOpsGenenerator()));

        sendSwapPacket(changedSlots, (byte)slot);
    }

    private void sendStartFlyingPacket() {
        if (mc.player == null) return;
        mc.player.connection.send(new ServerboundPlayerCommandPacket(
            mc.player,
            ServerboundPlayerCommandPacket.Action.START_FALL_FLYING
        ));
    }

    private void sendSwapPacket(Int2ObjectMap<HashedStack> changedSlots, byte buttonNum) {
        int syncId  = mc.player.containerMenu.containerId;
        int stateId = mc.player.containerMenu.getStateId();

        mc.player.connection.send(new ServerboundContainerClickPacket(
            syncId,
            stateId,
            (short) 6,
            buttonNum,
            ContainerInput.SWAP,
            changedSlots,
            HashedStack.EMPTY
        ));
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event)
    {
        if (!avoidPortalTraps.get() || !highwayObstaclePasser.get()) return;
        ChunkPos pos = event.chunk().getPos();

        BlockPos centerPos = pos.getMiddleBlockPosition(targetY.get());

        // Check if chunk is on the players path
        Vec3 moveDir = yawToDirection(yaw.get());
        double distanceToHighway = distancePointToDirection(Vec3.atLowerCornerOf(centerPos), moveDir, mc.player.position());

        if (distanceToHighway > 21) return;

        for (int x = 0; x < 16; x++)
        {
            for (int z = 0; z < 16; z++)
            {
                for (int y = targetY.get(); y < targetY.get() + 3; y++)
                {
                    BlockPos position = new BlockPos(pos.x() * 16 + x, y, pos.z() * 16 + z);

                    if (distancePointToDirection(Vec3.atLowerCornerOf(position), moveDir, mc.player.position()) > portalScanWidth.get()) continue;

                    if (mc.level.getBlockState(position).getBlock().equals(Blocks.NETHER_PORTAL)) // TODO: This position could be unloaded
                    {
                        BlockPos posBehind = new BlockPos((int)Math.floor(position.getX() + moveDir.x), position.getY(), (int) Math.floor(position.getZ() + moveDir.z));

                        // Trap is detected when a portal has a solid block or another portal behind it
                        if (mc.level.getBlockState(posBehind).isRedstoneConductor(mc.level, posBehind) ||
                            mc.level.getBlockState(posBehind).getBlock() == Blocks.NETHER_PORTAL)
                        {
                            if (portalTrap == null || (
                                portalTrap.distSqr(posBehind) > 100 &&
                                    mc.player.blockPosition().distSqr(posBehind) < mc.player.blockPosition().distSqr(portalTrap))
                            )
                            {
                                portalTrap = posBehind;
                            }
                        }
                    }
                }
            }
        }
    }
}
