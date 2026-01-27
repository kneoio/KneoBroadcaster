package io.kneo.broadcaster.dto.status;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StatusMixplaDTO {
    private String name;
    private String slugName;
    private String managedBy;
    private String djName;
    private String djStatus;
    private String currentStatus;
    private String countryCode;
    private String color;
    private String titleFont;
    private AnimationStatusDTO animation;
}
