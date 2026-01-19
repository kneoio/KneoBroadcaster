package io.kneo.broadcaster.model.stream;

import io.kneo.broadcaster.model.brand.Brand;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumMap;
import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class RadioStream extends AbstractStream {
    private static final Logger LOGGER = LoggerFactory.getLogger(RadioStream.class);

    public RadioStream(Brand brand) {
        this.masterBrand = brand;
        this.id = brand.getId();
        this.slugName = brand.getSlugName();
        this.localizedName = new EnumMap<>(brand.getLocalizedName());
        this.timeZone = brand.getTimeZone();
        this.bitRate = brand.getBitRate();
        this.managedBy = brand.getManagedBy();
        this.createdAt = LocalDateTime.now();
        this.popularityRate = brand.getPopularityRate();
        this.timeZone = brand.getTimeZone();
        this.color = brand.getColor();
        this.aiAgentId = brand.getAiAgentId();
        this.profileId = brand.getProfileId();
        this.bitRate = brand.getBitRate();
        this.aiOverriding = brand.getAiOverriding();
        this.profileOverriding = brand.getProfileOverriding();
        this.country = brand.getCountry();
        this.scripts = brand.getScripts();
    }

    @Override
    public LiveScene findActiveScene() {
        if (streamSchedule == null) {
            LOGGER.warn("Station '{}': No stream schedule available", slugName);
            return null;
        }

        LocalTime now = LocalTime.now(timeZone);
        List<LiveScene> scenes = streamSchedule.getLiveScenes();
        
        for (int i = 0; i < scenes.size(); i++) {
            LiveScene entry = scenes.get(i);
            LiveScene nextEntry = (i < scenes.size() - 1) ? scenes.get(i + 1) : null;
            
            if (entry.isActiveAt(now, nextEntry != null ? nextEntry.getOriginalStartTime() : null)) {
                LOGGER.debug("Station '{}': Scene '{}' is active at time {}",
                        slugName, entry.getSceneTitle(), now);
                return entry;
            }
        }

        LOGGER.info("Station '{}': No active scene found at {}. Schedule may need refresh.", slugName, now);
        return null;
    }

    @Override
    public String toString() {
        return String.format("RadioStream[id: %s, slug: %s, baseBrand: %s]", id, slugName, masterBrand.getSlugName());
    }

}
