package io.kneo.broadcaster.dto.dashboard;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
public class FileMaintenanceStatsDTO implements Serializable {
    private long filesDeleted;
    private long spaceFreedBytes;
    private long directoriesDeleted;
    private long totalSpaceBytes;
    private long availableSpaceBytes;

    private FileMaintenanceStatsDTO() {
        this.filesDeleted = 0;
        this.spaceFreedBytes = 0;
        this.directoriesDeleted = 0;
        this.totalSpaceBytes = 0;
        this.availableSpaceBytes = 0;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final FileMaintenanceStatsDTO stats;

        private Builder() {
            this.stats = new FileMaintenanceStatsDTO();
        }

        public Builder fromService(long deleted, long free, long dirDeleted ) {
            stats.filesDeleted = deleted;
            stats.spaceFreedBytes = free;
            stats.directoriesDeleted = dirDeleted;
            return this;
        }

        public Builder totalSpaceBytes(long totalSpaceBytes) {
            stats.totalSpaceBytes = totalSpaceBytes;
            return this;
        }

        public Builder availableSpaceBytes(long availableSpaceBytes) {
            stats.availableSpaceBytes = availableSpaceBytes;
            return this;
        }

        public FileMaintenanceStatsDTO build() {
            return stats;
        }
    }
}