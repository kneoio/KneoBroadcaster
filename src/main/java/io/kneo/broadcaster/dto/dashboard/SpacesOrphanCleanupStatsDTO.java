package io.kneo.broadcaster.dto.dashboard;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Statistics for DigitalOcean Spaces orphan file cleanup operations
 */
@Getter
@Setter
public class SpacesOrphanCleanupStatsDTO implements Serializable {

    // Current cleanup session stats
    private long orphanFilesDeleted;
    private long spaceFreedBytes;
    private long totalFilesScanned;
    private long cleanupDurationMs;

    // Cumulative stats (across all cleanup sessions)
    private long totalOrphanFilesDeleted;
    private long totalSpaceFreedBytes;
    private long totalFilesScannedAllTime;

    // Metadata
    private LocalDateTime lastCleanupTime;
    private LocalDateTime nextScheduledCleanup;
    private boolean cleanupInProgress;
    private String lastError;

    // Database vs Spaces comparison
    private long databaseFileCount;
    private long spacesFileCount;

    private SpacesOrphanCleanupStatsDTO() {
        this.orphanFilesDeleted = 0;
        this.spaceFreedBytes = 0;
        this.totalFilesScanned = 0;
        this.cleanupDurationMs = 0;
        this.totalOrphanFilesDeleted = 0;
        this.totalSpaceFreedBytes = 0;
        this.totalFilesScannedAllTime = 0;
        this.cleanupInProgress = false;
        this.databaseFileCount = 0;
        this.spacesFileCount = 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Get space freed in MB for display purposes
     */
    public double getSpaceFreedMB() {
        return (double) spaceFreedBytes / (1024 * 1024);
    }

    /**
     * Get total space freed in MB for display purposes
     */
    public double getTotalSpaceFreedMB() {
        return (double) totalSpaceFreedBytes / (1024 * 1024);
    }

    /**
     * Get orphan ratio as percentage
     */
    public double getOrphanRatio() {
        if (totalFilesScanned == 0) return 0.0;
        return ((double) orphanFilesDeleted / totalFilesScanned) * 100;
    }

    /**
     * Check if there's a discrepancy between database and Spaces file counts
     */
    public boolean hasFileCountDiscrepancy() {
        return databaseFileCount > 0 && spacesFileCount > 0 &&
                Math.abs(databaseFileCount - spacesFileCount) > 0;
    }

    public static class Builder {
        private final SpacesOrphanCleanupStatsDTO stats;

        private Builder() {
            this.stats = new SpacesOrphanCleanupStatsDTO();
        }

        public Builder currentSession(long orphansDeleted, long spaceFreed, long filesScanned, long durationMs) {
            stats.orphanFilesDeleted = orphansDeleted;
            stats.spaceFreedBytes = spaceFreed;
            stats.totalFilesScanned = filesScanned;
            stats.cleanupDurationMs = durationMs;
            return this;
        }

        public Builder cumulativeStats(long totalOrphansDeleted, long totalSpaceFreed, long totalScanned) {
            stats.totalOrphanFilesDeleted = totalOrphansDeleted;
            stats.totalSpaceFreedBytes = totalSpaceFreed;
            stats.totalFilesScannedAllTime = totalScanned;
            return this;
        }

        public Builder lastCleanupTime(LocalDateTime lastCleanup) {
            stats.lastCleanupTime = lastCleanup;
            return this;
        }

        public Builder nextScheduledCleanup(LocalDateTime nextCleanup) {
            stats.nextScheduledCleanup = nextCleanup;
            return this;
        }

        public Builder cleanupInProgress(boolean inProgress) {
            stats.cleanupInProgress = inProgress;
            return this;
        }

        public Builder lastError(String error) {
            stats.lastError = error;
            return this;
        }

        public Builder fileCounts(long databaseCount, long spacesCount) {
            stats.databaseFileCount = databaseCount;
            stats.spacesFileCount = spacesCount;
            return this;
        }

        public SpacesOrphanCleanupStatsDTO build() {
            return stats;
        }
    }
}