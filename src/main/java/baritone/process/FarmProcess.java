/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.process;

import baritone.Baritone;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.cache.WorldScanner;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.HypixelHelper;
import baritone.utils.NotificationHelper;
import net.minecraft.block.*;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Direction;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    enum FarmAction {
        Till,
        Plant,
        Harvest
    };

    private boolean active;

    private final List<Block> blocksToScan;
    private List<BlockPos> locations;
    private int tickCount;
    private boolean waitIslandTeleport;

    private boolean hasToPlant;
    private FarmAction cachedAction = FarmAction.Till;
    private List<BlockPos> cachedBlocks = new ArrayList<>();
    private BlockPos lastTarget;
    private int lastTargetTicks;

    private static final List<Item> FARMLAND_PLANTABLE = Arrays.asList(
            Items.POTATO
    );

    public FarmProcess(Baritone baritone) {
        super(baritone);

        blocksToScan = new ArrayList<>();
        for (Harvest harvest : Harvest.values()) {
            blocksToScan.add(harvest.block);
        }

        if (Baritone.settings().replantCrops.value) {
            blocksToScan.add(Blocks.FARMLAND);
        }

        blocksToScan.add(Blocks.DIRT);
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void farm() {
        active = true;
        locations = null;
    }

    private enum Harvest {
        POTATOES((CropsBlock) Blocks.POTATOES);

        public final Block block;
        public final Predicate<BlockState> readyToHarvest;

        Harvest(CropsBlock blockCrops) {
            // max age is 7 for wheat, carrots, and potatoes, but 3 for beetroot
            this.block = blockCrops;
            this.readyToHarvest = blockCrops::isMaxAge;
        }

        public boolean readyToHarvest(BlockState state) {
            return readyToHarvest.test(state);
        }
    }

    private boolean readyForHarvest(BlockState state) {
        for (Harvest harvest : Harvest.values()) {
            if (harvest.block == state.getBlock()) {
                return harvest.readyToHarvest(state);
            }
        }
        return false;
    }

    private boolean isPlantable(ItemStack stack) {
        return FARMLAND_PLANTABLE.contains(stack.getItem()) && EnchantmentHelper.getEnchantments(stack).isEmpty();
    }

    private boolean isEnhancedCrop(ItemStack stack) {
        return (FARMLAND_PLANTABLE.contains(stack.getItem()) || stack.getItem() == Items.BAKED_POTATO) &&
                !EnchantmentHelper.getEnchantments(stack).isEmpty();
    }

    private boolean isHoe(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof HoeItem);
    }

    private void onFail() {
        logDirect("Farm failed");
        if (Baritone.settings().desktopNotifications.value && Baritone.settings().notificationOnFarmFail.value) {
            NotificationHelper.notify("Farm failed", true);
        }
        onLostControl();
    }

    private boolean putCropInHotbar() {
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;

        int replaceableHotkeySlot = -1;
        for (int i = 0; i < 9; i++) {
            if (isEnhancedCrop(inv.get(i)) || inv.get(i).isEmpty()) {
                replaceableHotkeySlot = i;
                break;
            }
        }

        if (replaceableHotkeySlot != -1) {
            for (int i = 9; i < inv.size(); i++) {
                if (isPlantable(inv.get(i))) {
                    logDirect("Swapping " + replaceableHotkeySlot + " with " + i);

                    ctx.playerController().windowClick(
                            ctx.player().container.windowId,
                            i,
                            replaceableHotkeySlot,
                            ClickType.SWAP,
                            ctx.player());

                    return true;
                }
            }
        }

        return false;
    }

    private int getPlantablesInHotkeyBar() {
        return ctx.player().inventory.mainInventory.stream()
                .filter(this::isPlantable)
                .mapToInt(ItemStack::getCount)
                .sum();
    }

    private boolean isInvFull() {
        return ctx.player().inventory.mainInventory.stream()
                .noneMatch(ItemStack::isEmpty);
    }

    private List<BlockPos> GetLargestLevel(Map<Integer, List<BlockPos>> entries) {
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }

        return entries.entrySet().stream()
                .max(Map.Entry.comparingByValue(Comparator.comparingInt(List::size))).get().getValue();
    }

    private boolean shouldFindNewBlocks() {
        switch (cachedAction) {
            case Harvest:
                return cachedBlocks.isEmpty() || (hasToPlant && getPlantablesInHotkeyBar() > 64);
            case Plant:
                return (cachedBlocks.size() < 2) || (getPlantablesInHotkeyBar() == 0);
            case Till:
                return cachedBlocks.isEmpty();
            default:
                return false;
        }
    }

    private void validateCachedBlocks() {
        Predicate<BlockPos> validationPred;
        switch (cachedAction) {
            case Harvest:
                validationPred = x -> readyForHarvest(ctx.world().getBlockState(x));
                break;
            case Plant:
                validationPred = x -> (ctx.world().getBlockState(x).getBlock() == Blocks.FARMLAND) &&
                        (ctx.world().getBlockState(x.up()).getBlock() == Blocks.AIR);
                break;
            case Till:
                validationPred = x -> (ctx.world().getBlockState(x).getBlock() == Blocks.DIRT);
                break;
            default:
                onFail();
                return;
        }

        cachedBlocks = cachedBlocks.stream()
                .filter(validationPred)
                .collect(Collectors.toList());
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (isInvFull()) {
            logDirect("Inv is full, stopping...");
            onFail();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (!HypixelHelper.IsOnPrivateIsland()) {
            if (HypixelHelper.isInSkyblockGame() && !waitIslandTeleport) {
                logDirect("Warping back to island");
                mc.player.sendChatMessage("/warp home");
                waitIslandTeleport = true;
            }
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        } else {
            waitIslandTeleport = false;
        }

        if (putCropInHotbar()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        validateCachedBlocks();

        if (shouldFindNewBlocks()) {
            if (Baritone.settings().mineGoalUpdateInterval.value != 0 && tickCount++ % Baritone.settings().mineGoalUpdateInterval.value == 0) {
                Baritone.getExecutor().execute(() -> locations = WorldScanner.INSTANCE.scanChunkRadius(ctx, blocksToScan, 512, -1, 10));
            }

            if (locations == null) {
                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
            }

            Map<Integer, List<BlockPos>> breakLevels = new Hashtable<>();
            Map<Integer, List<BlockPos>> plantLevels = new Hashtable<>();
            Map<Integer, List<BlockPos>> tillLevels = new Hashtable<>();

            for (BlockPos pos : locations) {
                BlockState state = ctx.world().getBlockState(pos);
                boolean airAbove = ctx.world().getBlockState(pos.up()).getBlock() instanceof AirBlock;

                int keyValue = pos.getY();
                if ((state.getBlock() == Blocks.FARMLAND)) {
                    if (airAbove) {
                        if (!plantLevels.containsKey(keyValue)) {
                            plantLevels.put(keyValue, new ArrayList<>());
                        }
                        plantLevels.get(keyValue).add(pos);
                    }
                } else if (state.getBlock() == Blocks.DIRT) {
                    if (airAbove) {
                        if (!tillLevels.containsKey(keyValue)) {
                            tillLevels.put(keyValue, new ArrayList<>());
                        }
                        tillLevels.get(keyValue).add(pos);
                    }
                } else if (readyForHarvest(state)) {
                    if (!breakLevels.containsKey(keyValue)) {
                        breakLevels.put(keyValue, new ArrayList<>());
                    }
                    breakLevels.get(keyValue).add(pos);
                }
            }

            cachedAction = FarmAction.Till;
            cachedBlocks = GetLargestLevel(tillLevels);

            if (cachedBlocks.isEmpty() && (getPlantablesInHotkeyBar() >= 64)) {
                cachedAction = FarmAction.Plant;
                cachedBlocks = GetLargestLevel(plantLevels);
            }

            if (((cachedAction == FarmAction.Plant) && cachedBlocks.size() <= 10) ||
                    (cachedAction == FarmAction.Till && cachedBlocks.isEmpty())) {
                cachedAction = FarmAction.Harvest;
                cachedBlocks = GetLargestLevel(breakLevels);
            }

            if (!cachedBlocks.isEmpty()) {
                logDirect("Switched to " + cachedAction.name() + " on level " + cachedBlocks.get(0).getY() + " (" + cachedBlocks.size() + " actions)");
            } else {
                logDirect("No actions to take");
            }

            hasToPlant = GetLargestLevel(plantLevels).size() >= 15;
        }

        List<Goal> goals = new ArrayList<>();
        baritone.getInputOverrideHandler().clearAllKeys();
        switch (cachedAction) {
            case Till:
                for (int i = (cachedBlocks.size() - 1); i >= 0; i--) {
                    BlockPos pos = cachedBlocks.get(i);
                    Optional<Rotation> rot = RotationUtils.reachableOffset(
                            ctx.player(),
                            pos,
                            new Vector3d(pos.getX() + 0.5,
                                    pos.getY() + 1,
                                    pos.getZ() + 0.5),
                            ctx.playerController().getBlockReachDistance(),
                            false);
                    if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isHoe)) {
                        RayTraceResult result = RayTraceUtils.rayTraceTowards(
                                ctx.player(),
                                rot.get(),
                                ctx.playerController().getBlockReachDistance());
                        if (result instanceof BlockRayTraceResult && ((BlockRayTraceResult) result).getFace() == Direction.UP) {
                            baritone.getLookBehavior().updateTarget(rot.get(), true);
                            if (ctx.isLookingAt(pos)) {
                                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                            }
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }

                    if (ctx.world().getBlockState(pos.up().up()).getBlock() != Blocks.AIR) {
                        goals.add(new BuilderProcess.GoalPlace(pos.up()));
                    } else {
                        goals.add(new GoalBlock(pos.up()));
                    }
                }
                break;
            case Plant:
                for (int i = (cachedBlocks.size() - 1); i >= 0; i--) {
                    BlockPos pos = cachedBlocks.get(i);
                    Optional<Rotation> rot = RotationUtils.reachableOffset(
                            ctx.player(),
                            pos,
                            new Vector3d(pos.getX() + 0.5,
                                    pos.getY() + 1,
                                    pos.getZ() + 0.5),
                            ctx.playerController().getBlockReachDistance(),
                            false);
                    if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isPlantable)) {
                        RayTraceResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), ctx.playerController().getBlockReachDistance());
                        if (result instanceof BlockRayTraceResult && ((BlockRayTraceResult) result).getFace() == Direction.UP) {
                            baritone.getLookBehavior().updateTarget(rot.get(), true);
                            if (ctx.isLookingAt(pos)) {
                                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                            }

                            if (lastTarget.equals(pos)) {
                                lastTargetTicks += 1;

                                if (lastTargetTicks > 1200) {
                                    cachedBlocks.remove(i);
                                }
                            } else {
                                lastTargetTicks = 0;
                            }

                            lastTarget = pos;

                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }

                    goals.add(new BuilderProcess.GoalPlace(pos.up()));
                }
                break;
            case Harvest:
                for (int i = (cachedBlocks.size() - 1); i >= 0; i--) {
                    BlockPos pos = cachedBlocks.get(i);
                    Optional<Rotation> rot = RotationUtils.reachable(ctx, pos);
                    if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isHoe)) {
                        RayTraceResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), ctx.playerController().getBlockReachDistance());
                        if (result instanceof BlockRayTraceResult && ((BlockRayTraceResult) result).getFace() == Direction.UP) {
                            baritone.getLookBehavior().updateTarget(rot.get(), true);
                            if (ctx.isLookingAt(pos)) {
                                baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_LEFT, true);
                            }
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }

                    if (ctx.world().getBlockState(pos.up()).getBlock() != Blocks.AIR) {
                        goals.add(new BuilderProcess.GoalPlace(pos));
                    } else {
                        goals.add(new GoalBlock(pos));
                    }
                }
                break;
        }

        if (!goals.isEmpty()) {

            return new PathingCommand(new GoalComposite(goals.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
        }
        return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
    }

    @Override
    public void onLostControl() {
        cachedAction = FarmAction.Till;
        cachedBlocks = new ArrayList<>();
        active = false;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }
}