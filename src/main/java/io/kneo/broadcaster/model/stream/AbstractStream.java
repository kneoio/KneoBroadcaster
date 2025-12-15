package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.dto.cnst.RadioStationStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;

@Setter
@Getter
public abstract class AbstractStream implements IStream {
    protected RadioStationStatus status = RadioStationStatus.OFF_LINE;
    protected List<StatusChangeRecord> statusHistory = new LinkedList<>();
    protected LocalDateTime startTime;


    @Override
    public void setStatus(RadioStationStatus newStatus) {
        if (this.status != newStatus) {
            StatusChangeRecord record = new StatusChangeRecord(
                    LocalDateTime.now(),
                    this.status,
                    newStatus
            );
            if (statusHistory.isEmpty()) {
                startTime = record.timestamp();
            }
            statusHistory.add(record);
            this.status = newStatus;
        }
    }


}
