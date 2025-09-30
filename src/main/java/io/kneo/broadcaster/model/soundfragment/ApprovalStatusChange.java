package io.kneo.broadcaster.model.soundfragment;

import io.kneo.broadcaster.model.cnst.ApprovalStatus;

import java.time.LocalDateTime;

public record ApprovalStatusChange(LocalDateTime timestamp, ApprovalStatus oldStatus,
                                   ApprovalStatus newStatus) {
}
