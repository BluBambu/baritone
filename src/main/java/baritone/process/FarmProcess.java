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
import baritone.api.pathing.goals.GoalTwoBlocks;
import baritone.api.process.IFarmProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.RayTraceUtils;
import baritone.api.utils.Rotation;
import baritone.api.utils.RotationUtils;
import baritone.api.utils.input.Input;
import baritone.cache.WorldScanner;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.NotificationHelper;
import net.minecraft.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.inventory.container.ClickType;
import net.minecraft.item.HoeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.Direction;
import net.minecraft.util.NonNullList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraft.world.World;

import java.util.*;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class FarmProcess extends BaritoneProcessHelper implements IFarmProcess {

    private boolean active;

    private List<BlockPos> locations;
    private int tickCount;
    private boolean waitIslandTeleport;

    private static final List<Item> FARMLAND_PLANTABLE = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.MELON_SEEDS,
            Items.WHEAT_SEEDS,
            Items.PUMPKIN_SEEDS,
            Items.POTATO,
            Items.CARROT
    );

    private static final List<Item> PICKUP_DROPPED = Arrays.asList(
            Items.BEETROOT_SEEDS,
            Items.BEETROOT,
            Items.MELON_SEEDS,
            Items.MELON_SLICE,
            Blocks.MELON.asItem(),
            Items.WHEAT_SEEDS,
            Items.WHEAT,
            Items.PUMPKIN_SEEDS,
            Blocks.PUMPKIN.asItem(),
            Items.POTATO,
            Items.CARROT,
            Items.NETHER_WART,
            Blocks.SUGAR_CANE.asItem(),
            Blocks.CACTUS.asItem()
    );

    public FarmProcess(Baritone baritone) {
        super(baritone);
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
        WHEAT((CropsBlock) Blocks.WHEAT),
        CARROTS((CropsBlock) Blocks.CARROTS),
        POTATOES((CropsBlock) Blocks.POTATOES),
        BEETROOT((CropsBlock) Blocks.BEETROOTS),
        PUMPKIN(Blocks.PUMPKIN, state -> true),
        MELON(Blocks.MELON, state -> true),
        SUGARCANE(Blocks.SUGAR_CANE, null) {
            @Override
            public boolean readyToHarvest(World world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.down()).getBlock() instanceof SugarCaneBlock;
                }
                return true;
            }
        },
        CACTUS(Blocks.CACTUS, null) {
            @Override
            public boolean readyToHarvest(World world, BlockPos pos, BlockState state) {
                if (Baritone.settings().replantCrops.value) {
                    return world.getBlockState(pos.down()).getBlock() instanceof CactusBlock;
                }
                return true;
            }
        };
        public final Block block;
        public final Predicate<BlockState> readyToHarvest;

        Harvest(CropsBlock blockCrops) {
            this(blockCrops, blockCrops::isMaxAge);
            // max age is 7 for wheat, carrots, and potatoes, but 3 for beetroot
        }

        Harvest(Block block, Predicate<BlockState> readyToHarvest) {
            this.block = block;
            this.readyToHarvest = readyToHarvest;
        }

        public boolean readyToHarvest(World world, BlockPos pos, BlockState state) {
            return readyToHarvest.test(state);
        }
    }

    private boolean readyForHarvest(World world, BlockPos pos, BlockState state) {
        for (Harvest harvest : Harvest.values()) {
            if (harvest.block == state.getBlock()) {
                return harvest.readyToHarvest(world, pos, state);
            }
        }
        return false;
    }

    private boolean isPlantable(ItemStack stack) {
        return FARMLAND_PLANTABLE.contains(stack.getItem()) && EnchantmentHelper.getEnchantments(stack).isEmpty();
    }

    private boolean isHoe(ItemStack stack) {
        return !stack.isEmpty() && (stack.getItem() instanceof HoeItem);
    }

    private static String getSuffixFromContainingTeam(Scoreboard scoreboard, String member) {
        String suffix = null;
        for (ScorePlayerTeam team : scoreboard.getTeams()) {
            if (team.getMembershipCollection().contains(member)) {
                suffix = team.getPrefix().getString() + team.getSuffix().getString();
                break;
            }
        }
        return (suffix == null ? "" : suffix);
    }

    private boolean shouldTeleport() {
        final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

        World world = Minecraft.getInstance().world;
        if (world == null) {
            logDirect("No world is currently loaded, pausing");
            return false;
        }

        Scoreboard scoreboard = world.getScoreboard();
        ScoreObjective sidebarObjective = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebarObjective == null) {
            logDirect("No scoreboard loaded, pausing");
            return false;
        }

        if (!STRIP_COLOR_PATTERN.matcher(sidebarObjective.getDisplayName().getString()).replaceAll("").trim().toLowerCase().contains("skyblock"))
        {
            logDirect("Not in skyblock, failing out");
            onFail();
            return false;
        }

        Collection<Score> scoreboardLines = scoreboard.getSortedScores(sidebarObjective);

        List<String> found = scoreboardLines.stream()
                .filter(score -> score.getObjective().getName().equals(sidebarObjective.getName()))
                .map(score -> score.getPlayerName() + getSuffixFromContainingTeam(scoreboard, score.getPlayerName()))
                .collect(Collectors.toList());

        for (String line : found) {
            final Pattern SCOREBOARD_CHARACTERS = Pattern.compile("[^a-z A-Z:0-9/'.]");

            String strippedLine = SCOREBOARD_CHARACTERS.matcher(STRIP_COLOR_PATTERN.matcher(line).replaceAll("")).replaceAll("").trim().toLowerCase();
            if (strippedLine.contains("your island")) {
                waitIslandTeleport = false;
                return false;
            }
        }

        return true;
    }

    private boolean checkAndWarpIsland() {
        if (shouldTeleport() && !waitIslandTeleport) {
            mc.player.sendChatMessage("/warp home");
            waitIslandTeleport = true;
            return true;
        }

        return waitIslandTeleport;
    }

    private void onFail() {
        logDirect("Farm failed");
        if (Baritone.settings().desktopNotifications.value && Baritone.settings().notificationOnFarmFail.value) {
            NotificationHelper.notify("Farm failed", true);
        }
        onLostControl();
    }

    private boolean swapEnchantedCarrots() {
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;

        int enchantedCarrotSlot = -1;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.get(i);
            if (stack.isEmpty()) {
                continue;
            }

            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
            if (enchants.isEmpty() || (stack.getItem() != Items.CARROT)) {
                continue;
            }

            enchantedCarrotSlot = i;
        }

        if (enchantedCarrotSlot != -1) {
            for (int i = 9; i < inv.size(); i++) {
                ItemStack stack = inv.get(i);
                if (stack.isEmpty()) {
                    continue;
                }

                Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
                if (!enchants.isEmpty()) {
                    continue;
                }

                if (stack.getItem() == Items.CARROT) {
                    logDirect("Swapping " + enchantedCarrotSlot + " with " + i);

                    ctx.playerController().windowClick(
                            ctx.player().container.windowId,
                            i < 9 ? i + 36 : i,
                            enchantedCarrotSlot,
                            ClickType.SWAP,
                            ctx.player());

                    return true;
                }
            }
        }

        return false;
    }

    private int getCarrotCountInHotkeys() {
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;

        int count = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.get(i);

            if (stack.isEmpty()) {
                continue;
            }

            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments(stack);
            if (!enchants.isEmpty()) {
                continue;
            }

            if (stack.getItem() == Items.CARROT) {
                count += stack.getCount();
            }
        }

        return count;
    }

    private boolean isInvFull() {
        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;

        for (ItemStack item : inv) {
            if (item.isEmpty()) {
                return false;
            }
        }

        return true;
    }

    enum FarmAction {
        Till,
        Plant,
        Harvest
    };

    private boolean hasToPlant = false;
    private FarmAction cachedAction = FarmAction.Till;
    private List<BlockPos> cachedBlocks = new ArrayList<>();

    private List<BlockPos> GetLargestLevel(Map<Integer, List<BlockPos>> entries) {
        if (entries.isEmpty()) {
            return new ArrayList<>();
        }

        int largestEntry = 0;
        int largestEntryLevel = 0;
        for (Map.Entry<Integer, List<BlockPos>> entry : entries.entrySet()) {
            if (entry.getValue().size() > largestEntry) {
                largestEntry = entry.getValue().size();
                largestEntryLevel = entry.getKey();
            }
        }

        return entries.get(largestEntryLevel);
    }

    private boolean shouldFindNewBlocks() {
        switch (cachedAction) {
            case Harvest:
                return cachedBlocks.isEmpty() || (hasToPlant && getCarrotCountInHotkeys() > 64);
            case Plant:
                if (getCarrotCountInHotkeys() == 0) {
                    return true;
                }

                for (BlockPos pos : cachedBlocks) {
                    double deltaX = Math.abs(ctx.player().getPosX() - pos.getX());
                    double deltaY = Math.abs(ctx.player().getPosZ() - pos.getZ());
                    double deltaDistance = Math.hypot(deltaX, deltaY);

                    if (deltaDistance >= 1.6) {
                        return false;
                    }
                }
                return true;
            case Till:
                return cachedBlocks.isEmpty();
        }

        return false;
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (isInvFull()) {
            logDirect("Inv is full, stopping...");
            onFail();
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (checkAndWarpIsland()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (swapEnchantedCarrots()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (shouldFindNewBlocks()) {
            ArrayList<Block> scan = new ArrayList<>();
            for (Harvest harvest : Harvest.values()) {
                scan.add(harvest.block);
            }

            if (Baritone.settings().replantCrops.value) {
                scan.add(Blocks.FARMLAND);
            }

            scan.add(Blocks.DIRT);

            if (Baritone.settings().mineGoalUpdateInterval.value != 0 && tickCount++ % Baritone.settings().mineGoalUpdateInterval.value == 0) {
                Baritone.getExecutor().execute(() -> locations = WorldScanner.INSTANCE.scanChunkRadius(ctx, scan, 512, -1, 10));
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
                boolean airAboveAbove = ctx.world().getBlockState(pos.up().up()).getBlock() instanceof AirBlock;

                int keyValue = pos.getY();
                if ((state.getBlock() == Blocks.FARMLAND)) {
                    if (airAbove) {
                        if (!plantLevels.containsKey(keyValue)) {
                            plantLevels.put(keyValue, new ArrayList<>());
                        }
                        plantLevels.get(keyValue).add(pos);
                    }
                } else if (state.getBlock() == Blocks.DIRT) {
                    if (airAbove && airAboveAbove) {
                        if (!tillLevels.containsKey(keyValue)) {
                            tillLevels.put(keyValue, new ArrayList<>());
                        }
                        tillLevels.get(keyValue).add(pos);
                    }
                } else if (readyForHarvest(ctx.world(), pos, state)) {
                    if (!breakLevels.containsKey(keyValue)) {
                        breakLevels.put(keyValue, new ArrayList<>());
                    }
                    breakLevels.get(keyValue).add(pos);
                }
            }

            cachedAction = FarmAction.Till;
            cachedBlocks = GetLargestLevel(tillLevels);

            if (cachedBlocks.isEmpty() && (getCarrotCountInHotkeys() >= 64)) {
                cachedAction = FarmAction.Plant;
                cachedBlocks = GetLargestLevel(plantLevels);
            }

            if (((cachedAction == FarmAction.Plant) && cachedBlocks.size() <= 5) ||
                    (cachedAction == FarmAction.Till && cachedBlocks.isEmpty())) {
                cachedAction = FarmAction.Harvest;
                cachedBlocks = GetLargestLevel(breakLevels);
            }

            if (!cachedBlocks.isEmpty()) {
                logDirect("Switched to " + cachedAction.name() + " on level " + cachedBlocks.get(0).getY() + " (" + cachedBlocks.size() + " actions)");
            } else {
                logDirect("No actions to take");
            }

            hasToPlant = GetLargestLevel(plantLevels).size() >= 5;
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
                                cachedBlocks.remove(i);
                            }
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }

                    goals.add(new GoalBlock(pos.up()));
                }
                break;
            case Plant:
                for (int i = (cachedBlocks.size() - 1); i >= 0; i--) {
                    BlockPos pos = cachedBlocks.get(i);
                    double deltaX = Math.abs(ctx.player().getPosX() - pos.getX());
                    double deltaY = Math.abs(ctx.player().getPosZ() - pos.getZ());
                    double deltaDistance = Math.hypot(deltaX, deltaY);

                    if (deltaDistance >= 1.6) {
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
                                    cachedBlocks.remove(i);
                                }
                                return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                            }
                        }

                        goals.add(new GoalBlock(pos.up()));
                    }
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
                                cachedBlocks.remove(i);
                            }
                            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                        }
                    }

                    goals.add(new BuilderProcess.GoalBreak(pos));
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