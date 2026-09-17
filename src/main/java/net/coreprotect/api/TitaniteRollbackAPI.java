package net.coreprotect.api;

import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;

import net.coreprotect.consumer.Consumer;
import net.coreprotect.database.Database;
import net.coreprotect.database.rollback.Rollback;
import net.coreprotect.model.action.LookupActions;

/**
 * Titanite fork addition. Rollback and restore with two things the public
 * API withholds: an explicit action list that may include
 * {@link LookupActions#CONTAINER}, so chest contents come back, and an
 * absolute time window (epoch seconds, older bound first) instead of
 * "seconds ago" with no end, so a war rebuild touches only the war.
 * The engine already supports both; this class only passes them through.
 * Kept in its own file so upstream merges never conflict.
 */
public final class TitaniteRollbackAPI {

    /** One fixed key so a second caller is told ROLLBACK_RUNNING instead of running alongside. */
    private static final String KEY = "#titanite";

    public enum Result { DONE, ALREADY_RUNNING, PURGE_RUNNING, PERSISTENCE_HALTED, PRIMARY_THREAD, FAILED }

    /** Java 11 source level here, so a plain class rather than a record. */
    public static final class Outcome {
        private final Result result;
        private final List<String[]> rows;

        Outcome(Result result, List<String[]> rows) {
            this.result = result;
            this.rows = rows;
        }

        public Result result() {
            return result;
        }

        /** The engine's result rows, or null when nothing ran. */
        public List<String[]> rows() {
            return rows;
        }

        public int changes() {
            return rows == null ? 0 : rows.size();
        }
    }

    private TitaniteRollbackAPI() {
    }

    /**
     * Rolls back what matches to how it was at {@code startTime}.
     *
     * @param startTime epoch seconds, the older bound (exclusive)
     * @param endTime epoch seconds, the newer bound (inclusive); 0 means now
     * @param users usernames to include, or null for everyone in the area
     * @param excludeUsers usernames to leave alone, or null
     * @param restrictBlocks Materials or EntityTypes to include, or null
     * @param excludeBlocks Materials or EntityTypes to leave alone, or null
     * @param actions LookupActions ids; CONTAINER (4) is honoured; null or empty means blocks
     * @param radius blocks around center, or -1 for no radius
     * @param center required when radius is set
     */
    public static Outcome rollback(long startTime, long endTime, List<String> users, List<String> excludeUsers, List<Object> restrictBlocks, List<Object> excludeBlocks,
            List<Integer> actions, int radius, Location center) {
        return run(0, startTime, endTime, users, excludeUsers, restrictBlocks, excludeBlocks, actions, radius, center);
    }

    /** Restores (re-applies) what a rollback of the same window undid. Same arguments as {@link #rollback}. */
    public static Outcome restore(long startTime, long endTime, List<String> users, List<String> excludeUsers, List<Object> restrictBlocks, List<Object> excludeBlocks,
            List<Integer> actions, int radius, Location center) {
        return run(1, startTime, endTime, users, excludeUsers, restrictBlocks, excludeBlocks, actions, radius, center);
    }

    private static Outcome run(int rollbackType, long startTime, long endTime, List<String> users, List<String> excludeUsers, List<Object> restrictBlocks,
            List<Object> excludeBlocks, List<Integer> actions, int radius, Location center) {
        if (Bukkit.isPrimaryThread()) {
            return new Outcome(Result.PRIMARY_THREAD, null);
        }
        // Seconds, never millis: a millis value here would roll back the whole world.
        if (startTime > 100_000_000_000L || endTime > 100_000_000_000L) {
            throw new IllegalArgumentException("startTime and endTime are epoch seconds, not milliseconds");
        }
        if (startTime < 0 || (endTime != 0 && endTime < startTime)) {
            throw new IllegalArgumentException("endTime must be 0 or after startTime");
        }

        List<String> restrictUsers = users == null ? new ArrayList<>() : new ArrayList<>(users);
        List<String> excludeUserList = excludeUsers == null ? new ArrayList<>() : new ArrayList<>(excludeUsers);
        List<Integer> actionList = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
        if (actionList.isEmpty()) {
            actionList.add(LookupActions.BLOCK_BREAK);
            actionList.add(LookupActions.BLOCK_PLACE);
        }
        List<Object> restrictList = new ArrayList<>(parse(restrictBlocks).keySet());
        Map<Object, Boolean> excludeList = parse(excludeBlocks);

        if (restrictUsers.isEmpty()) {
            restrictUsers.add("#global");
        }
        if (radius < 1) {
            radius = -1;
        }
        if (restrictUsers.contains("#global") && radius == -1) {
            throw new IllegalArgumentException("everyone with no radius is refused, as in the public API");
        }
        if (radius > -1 && center == null) {
            throw new IllegalArgumentException("radius needs a center");
        }
        Integer[] argRadius = null;
        boolean restrictWorld = false;
        if (center != null && radius > 0) {
            int xMin = center.getBlockX() - radius;
            int xMax = center.getBlockX() + radius;
            int zMin = center.getBlockZ() - radius;
            int zMax = center.getBlockZ() + radius;
            argRadius = new Integer[] { radius, xMin, xMax, null, null, zMin, zMax, 0 };
            restrictWorld = true;
        }

        Consumer.OperationStartResult claim = Consumer.claimRollback(KEY);
        if (claim != Consumer.OperationStartResult.STARTED) {
            Result why;
            if (claim == Consumer.OperationStartResult.PURGE_RUNNING) {
                why = Result.PURGE_RUNNING;
            }
            else if (claim == Consumer.OperationStartResult.PERSISTENCE_HALTED) {
                why = Result.PERSISTENCE_HALTED;
            }
            else {
                why = Result.ALREADY_RUNNING;
            }
            return new Outcome(why, null);
        }
        try (Connection connection = Database.getConnection(false, 1000)) {
            if (connection == null) {
                return new Outcome(Result.FAILED, null);
            }
            try (Statement statement = connection.createStatement()) {
                List<String[]> rows = Rollback.performRollbackRestore(statement, null, new ArrayList<>(), restrictUsers, null, restrictList, excludeList, excludeUserList,
                        actionList, center, argRadius, startTime, endTime, restrictWorld, false, false, rollbackType, 0);
                return new Outcome(Result.DONE, rows);
            }
        }
        catch (Exception e) {
            net.coreprotect.utility.ErrorReporter.report(e);
            return new Outcome(Result.FAILED, null);
        }
        finally {
            Consumer.releaseRollback(KEY);
        }
    }

    private static Map<Object, Boolean> parse(List<Object> list) {
        Map<Object, Boolean> result = new HashMap<>();
        if (list != null) {
            for (Object value : list) {
                if (value instanceof Material || value instanceof EntityType || value instanceof String) {
                    result.put(value, false);
                }
            }
        }
        return result;
    }
}
