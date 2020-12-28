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

package baritone.utils;

import net.minecraft.client.Minecraft;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.world.World;

import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class HypixelHelper {
    static final Pattern STRIP_COLOR_PATTERN = Pattern.compile("(?i)§[0-9A-FK-OR]");

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

    private static Scoreboard GetScoreboard() {
        World world = Minecraft.getInstance().world;
        if (world == null) {
            return null;
        }

        return world.getScoreboard();
    }

    public static boolean isInSkyblockGame() {
        ScoreObjective sidebarObjective = GetScoreboard().getObjectiveInDisplaySlot(1);
        if (sidebarObjective == null) {
            return false;
        }

        return STRIP_COLOR_PATTERN.matcher(sidebarObjective.getDisplayName().getString()).replaceAll("").trim().toLowerCase().contains("skyblock");
    }

    public static boolean IsOnPrivateIsland() {
        if (!isInSkyblockGame()) {
            return false;
        }

        Scoreboard scoreboard = GetScoreboard();
        ScoreObjective sidebarObjective = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebarObjective == null) {
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
                return true;
            }
        }

        return false;
     }
}
