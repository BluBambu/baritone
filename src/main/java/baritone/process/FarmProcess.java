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
import baritone.pathing.movement.MovementHelper;
import baritone.utils.BaritoneProcessHelper;
import baritone.utils.NotificationHelper;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.sun.org.apache.xerces.internal.util.ShadowedSymbolTable;
import net.minecraft.block.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.inventory.ChestScreen;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.item.ItemEntity;
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

    private boolean combineCarrots() {
//        NonNullList<ItemStack> inv = ctx.player().inventory.mainInventory;
//
//        boolean shouldCombine = false;
//        List<Integer> carrotSlots = new ArrayList<>();
//        for (int i = (inv.size() - 1); i >= 8; i--) {
//            ItemStack stack = inv.get(i);
//            if (stack.isEmpty()) {
//                continue;
//            }
//
//            Map<Enchantment, Integer> enchants = EnchantmentHelper.getEnchantments((stack));
//            if (enchants.size() > 0) {
//                continue;
//            }
//
//            if (stack.getCount() != 64) {
//                continue;
//            }
//
//            if (stack.getItem() != Items.CARROT) {
//                continue;
//            }
//
//            carrotSlots.add(i);
//            if (carrotSlots.size() == 5) {
//                shouldCombine = true;
//                break;
//            }
//        }
//
//        if (shouldCombine) {
//            if (carrotState == CarrotState.None) {
//                logDirect("Found 5 full stacks of carrots, attempting to combine");
//            }
//        } else {
//            return false;
//        }
//
//        if (carrotState == CarrotState.None) {
//            logDirect("Opening main menu to craft");
//            ctx.player().inventory.currentItem = 8;
//            KeyBinding.onTick(mc.gameSettings.keyBindUseItem.getDefault());
//            carrotState = CarrotState.OpeningMainMenu;
//            return true;
//        }
//
//        if (carrotState == CarrotState.OpeningMainMenu) {
//            Screen screen = mc.currentScreen;
//            if (!(screen instanceof  ChestScreen)) {
//                logDirect("No chest screen instance");
//                return true;
//            }
//
//            ChestScreen chestScreen = (ChestScreen) screen;
//
//            boolean foundCraftingChest = false;
//            for (ItemStack itemStack : chestScreen.getContainer().getInventory()) {
//                if (itemStack.getItem() == Items.CRAFTING_TABLE) {
//                    foundCraftingChest = true;
//                }
//            }
//
//            if (!foundCraftingChest) {
//                logDirect(("No crafting chest"));
//                return true;
//            }
//
//            logDirect("Opening crafting menu");
//            mc.playerController.windowClick(chestScreen.getContainer().windowId, 31, 0, ClickType.PICKUP, mc.player);
//            carrotState = CarrotState.OpeningCraftingMenu;
//            return true;
//        }
//
//        if (carrotState == CarrotState.OpeningCraftingMenu) {
//            ChestScreen chestScreen = (ChestScreen) mc.currentScreen;
//
//            for (ItemStack itemStack : chestScreen.getContainer().getInventory()) {
//                if (itemStack.getItem() == Items.CRAFTING_TABLE) {
//                    logDirect("Crafting menu hasn't opened yet");
//                    return true;
//                }
//            }
//
//            count = count + 1;
//            if (count < 100) {
//                logDebug(count + "");
//                return true;
//            }
//
//            List<Integer> craftSlots = Arrays.asList(10, 11, 12, 19, 20);
//            for (int i = 0; i < 5; i++) {
//                int craftSlot = craftSlots.get(i);
//                int invSlot = carrotSlots.get(i);
//                int newInvSlot = 6 * 9 + invSlot - 9;
//
//                logDirect(
//                        invSlot + " " + newInvSlot + " " + chestScreen.getContainer().getInventory().get(newInvSlot).getDisplayName().getString() + " " +
//                                invSlot + " " + craftSlot + " " + chestScreen.getContainer().getInventory().get(craftSlot).getDisplayName().getString()
//                );
//
//                if (i == 1) {
//                    ctx.playerController().windowClick(
//                            chestScreen.getContainer().windowId,
//                            newInvSlot,
//                            craftSlot,
//                            ClickType.SWAP,
//                            ctx.player());
//                }
//            }
//
//            carrotState = CarrotState.AddItems;
//            return true;
//        }
//    // 23
//        return true;
        return false;
    }

    private int count = 0;
    private CarrotState carrotState = CarrotState.None;

    enum CarrotState {
        None,
        OpeningMainMenu,
        OpeningCraftingMenu,
        AddItems,
        CraftCarrots,
        Exiting
    }

    @Override
    public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
        if (checkAndWarpIsland()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        if (combineCarrots()) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        ArrayList<Block> scan = new ArrayList<>();
        for (Harvest harvest : Harvest.values()) {
            scan.add(harvest.block);
        }

        if (Baritone.settings().replantCrops.value) {
            scan.add(Blocks.FARMLAND);
        }

        scan.add(Blocks.DIRT);

        if (Baritone.settings().mineGoalUpdateInterval.value != 0 && tickCount++ % Baritone.settings().mineGoalUpdateInterval.value == 0) {
            Baritone.getExecutor().execute(() -> locations = WorldScanner.INSTANCE.scanChunkRadius(ctx, scan, 512, 10, 10));
        }

        if (locations == null) {
            return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
        }

        List<BlockPos> toBreak = new ArrayList<>();
        List<BlockPos> openFarmland = new ArrayList<>();
        List<BlockPos> tillLand = new ArrayList<>();
        for (BlockPos pos : locations) {
            BlockState state = ctx.world().getBlockState(pos);
            boolean airAbove = ctx.world().getBlockState(pos.up()).getBlock() instanceof AirBlock;
            boolean airAboveAbove = ctx.world().getBlockState(pos.up().up()).getBlock() instanceof AirBlock;
            if ((state.getBlock() == Blocks.FARMLAND) &&
                    (Math.abs(ctx.player().getPosition().getZ() - pos.getZ()) +
                        Math.abs(ctx.player().getPosition().getX() - pos.getX())) > 0) {
                if (airAbove) {
                    openFarmland.add(pos);
                }
            } else if (state.getBlock() == Blocks.DIRT) {
                if (airAbove && airAboveAbove)
                {
                    tillLand.add(pos);
                }
            } else if (readyForHarvest(ctx.world(), pos, state)) {
                toBreak.add(pos);
            }
        }

        baritone.getInputOverrideHandler().clearAllKeys();
        for (BlockPos pos : toBreak) {
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
        }

        ArrayList<BlockPos> both = new ArrayList<>(openFarmland);
        for (BlockPos pos : both) {
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx.player(), pos, new Vector3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), ctx.playerController().getBlockReachDistance(), false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isPlantable)) {
                RayTraceResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), ctx.playerController().getBlockReachDistance());
                if (result instanceof BlockRayTraceResult && ((BlockRayTraceResult) result).getFace() == Direction.UP) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        for (BlockPos pos : tillLand) {
            Optional<Rotation> rot = RotationUtils.reachableOffset(ctx.player(), pos, new Vector3d(pos.getX() + 0.5, pos.getY() + 1, pos.getZ() + 0.5), ctx.playerController().getBlockReachDistance(), false);
            if (rot.isPresent() && isSafeToCancel && baritone.getInventoryBehavior().throwaway(true, this::isHoe)) {
                RayTraceResult result = RayTraceUtils.rayTraceTowards(ctx.player(), rot.get(), ctx.playerController().getBlockReachDistance());
                if (result instanceof BlockRayTraceResult && ((BlockRayTraceResult) result).getFace() == Direction.UP) {
                    baritone.getLookBehavior().updateTarget(rot.get(), true);
                    if (ctx.isLookingAt(pos)) {
                        baritone.getInputOverrideHandler().setInputForceState(Input.CLICK_RIGHT, true);
                    }
                    return new PathingCommand(null, PathingCommandType.REQUEST_PAUSE);
                }
            }
        }

        List<Goal> goalz = new ArrayList<>();
        for (BlockPos pos : toBreak) {
            goalz.add(new BuilderProcess.GoalBreak(pos));
        }
        if (baritone.getInventoryBehavior().throwaway(false, this::isPlantable)) {
            for (BlockPos pos : openFarmland) {
                goalz.add(new GoalBlock(pos.up()));
            }
        }
        for (Entity entity : ctx.entities()) {
            if (entity instanceof ItemEntity && entity.isOnGround()) {
                ItemEntity ei = (ItemEntity) entity;
                if (PICKUP_DROPPED.contains(ei.getItem().getItem())) {
                    // +0.1 because of farmland's 0.9375 dummy height lol
                    goalz.add(new GoalBlock(new BlockPos(entity.getPositionVec().x, entity.getPositionVec().y + 0.1, entity.getPositionVec().z)));
                }
            }
        }
        for (BlockPos pos : tillLand) {
            goalz.add(new GoalBlock(pos.up()));
        }

        return new PathingCommand(new GoalComposite(goalz.toArray(new Goal[0])), PathingCommandType.SET_GOAL_AND_PATH);
    }

    @Override
    public void onLostControl() {
        active = false;
    }

    @Override
    public String displayName0() {
        return "Farming";
    }
}