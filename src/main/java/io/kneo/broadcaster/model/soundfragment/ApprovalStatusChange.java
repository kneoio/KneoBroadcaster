package io.kneo.broadcaster.model.soundfragment;

import java.time.LocalDateTime;

public record ApprovalStatusChange(LocalDateTime timestamp, ApprovalStatus oldStatus,
                                   ApprovalStatus newStatus) {
}
