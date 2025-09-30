package io.kneo.broadcaster.model.soundfragment;

import io.kneo.broadcaster.model.cnst.ApprovalStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;

@Getter
@Setter
public class ContributionWorkflow {
    private String contributor;
    private ZonedDateTime contributionTime;
    private ApprovalStatus status;
    private List<ApprovalStatusChange> statusChangeHistory;


    public void setStatus(ApprovalStatus newStatus) {
        if (this.status != null) {
            statusChangeHistory.add(new ApprovalStatusChange(
                    LocalDateTime.now(),
                    this.status,
                    newStatus
            ));
        }
        this.status = newStatus;
    }

}

