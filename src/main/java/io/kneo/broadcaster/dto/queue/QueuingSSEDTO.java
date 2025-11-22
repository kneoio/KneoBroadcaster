package io.kneo.broadcaster.dto.queue;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueuingSSEDTO {
    private String id;
    private String name;
    private String status; // "pending"|"processing"|"queued"|"error"
    private String errorMessage;
}