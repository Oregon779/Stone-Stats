package dev.stonestats.plugin.model;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One player's stats, held entirely in memory. Every increment is a plain
 * lock-free atomic op - no disk I/O ever happens here, which is what keeps
 * hot events like block breaks cheap. {@link dev.stonestats.plugin.manager.StatsManager}
 * is responsible for periodically writing a snapshot of these values to disk.
 */
public class PlayerStats {

    private final AtomicInteger kills = new AtomicInteger();
    private final AtomicInteger deaths = new AtomicInteger();
    private final AtomicInteger mobKills = new AtomicInteger();
    private final AtomicInteger blocksBroken = new AtomicInteger();
    private final AtomicInteger blocksPlaced = new AtomicInteger();
    private final AtomicLong playtimeSeconds = new AtomicLong();

    private volatile long firstJoinMillis = 0L;
    private volatile long lastJoinMillis = 0L;

    // Not persisted - only used while the player is online to add the
    // current session's length on top of playtimeSeconds when displayed.
    private volatile long sessionStartMillis = -1L;

    public int getKills() {
        return kills.get();
    }

    public int incrementKills() {
        return kills.incrementAndGet();
    }

    public void setKills(int value) {
        kills.set(value);
    }

    public int getDeaths() {
        return deaths.get();
    }

    public int incrementDeaths() {
        return deaths.incrementAndGet();
    }

    public void setDeaths(int value) {
        deaths.set(value);
    }

    public int getMobKills() {
        return mobKills.get();
    }

    public int incrementMobKills() {
        return mobKills.incrementAndGet();
    }

    public void setMobKills(int value) {
        mobKills.set(value);
    }

    public int getBlocksBroken() {
        return blocksBroken.get();
    }

    public int incrementBlocksBroken() {
        return blocksBroken.incrementAndGet();
    }

    public void setBlocksBroken(int value) {
        blocksBroken.set(value);
    }

    public int getBlocksPlaced() {
        return blocksPlaced.get();
    }

    public int incrementBlocksPlaced() {
        return blocksPlaced.incrementAndGet();
    }

    public void setBlocksPlaced(int value) {
        blocksPlaced.set(value);
    }

    public long getPlaytimeSeconds() {
        return playtimeSeconds.get();
    }

    public void addPlaytimeSeconds(long seconds) {
        if (seconds > 0) {
            playtimeSeconds.addAndGet(seconds);
        }
    }

    /**
     * Playtime including the currently running session, if any - safe to
     * call whether the player is online or not.
     */
    public long getLivePlaytimeSeconds() {
        long base = playtimeSeconds.get();
        long start = sessionStartMillis;
        if (start < 0) {
            return base;
        }
        long sessionSeconds = (System.currentTimeMillis() - start) / 1000L;
        return base + Math.max(0, sessionSeconds);
    }

    /**
     * Clears the stored total and, if the player is online, restarts the
     * running session from now - otherwise the live value would still
     * include everything played since the last login.
     */
    public void resetPlaytime() {
        playtimeSeconds.set(0);
        if (sessionStartMillis >= 0) {
            sessionStartMillis = System.currentTimeMillis();
        }
    }

    public void startSession() {
        sessionStartMillis = System.currentTimeMillis();
    }

    public boolean isSessionRunning() {
        return sessionStartMillis >= 0;
    }

    /**
     * Ends the current session, folding its length into the persisted
     * total, and returns the session length in seconds.
     */
    public long endSession() {
        long start = sessionStartMillis;
        sessionStartMillis = -1L;
        if (start < 0) {
            return 0;
        }
        long sessionSeconds = (System.currentTimeMillis() - start) / 1000L;
        if (sessionSeconds > 0) {
            playtimeSeconds.addAndGet(sessionSeconds);
        }
        return Math.max(0, sessionSeconds);
    }

    public long getFirstJoinMillis() {
        return firstJoinMillis;
    }

    public void setFirstJoinMillis(long millis) {
        this.firstJoinMillis = millis;
    }

    public long getLastJoinMillis() {
        return lastJoinMillis;
    }

    public void setLastJoinMillis(long millis) {
        this.lastJoinMillis = millis;
    }
}
