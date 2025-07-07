package io.kneo.broadcaster.repository.table;

import io.kneo.core.repository.table.EntityData;
import io.kneo.core.repository.table.TableNameResolver;

public class KneoBroadcasterNameResolver extends TableNameResolver {
    public static final String SOUND_FRAGMENT = "sound fragment";
    public static final String LISTENER = "listener";
    public static final String RADIO_STATION = "radio station";
    public static final String PROFILE = "profile";
    public static final String MEMORY = "memory";
    public static final String BRAND_STATS = "brand agent statistics";
    public static final String GENRE = "genre";
    public static final String AI_AGENT = "ai agent";
    public static final String EVENT = "event";

    private static final String SOUND_FRAGMENT_TABLE_NAME = "kneobroadcaster__sound_fragments";
    private static final String SOUND_FRAGMENT_ACCESS_TABLE_NAME = "kneobroadcaster__sound_fragment_readers";
    private static final String SOUND_FRAGMENT_FILES_TABLE_NAME = "kneobroadcaster__sound_fragment_files";
    private static final String LISTENER_TABLE_NAME = "kneobroadcaster__listeners";
    private static final String LISTENER_ACCESS_TABLE_NAME = "kneobroadcaster__listener_readers";
    private static final String RADIO_STATION_TABLE_NAME = "kneobroadcaster__brands";
    private static final String RADIO_STATION_ACCESS_TABLE_NAME = "kneobroadcaster__brand_readers";
    private static final String PROFILE_TABLE_NAME = "kneobroadcaster__profiles";
    private static final String PROFILE_ACCESS_TABLE_NAME = "kneobroadcaster__profile_readers";
    private static final String MEMORY_TABLE_NAME = "kneobroadcaster__memories";
    private static final String BRAND_STATS_TABLE_NAME = "kneobroadcaster__brand_agent_stats";
    private static final String GENRE_TABLE_NAME = "kneobroadcaster__genres";
    private static final String AI_AGENT_TABLE_NAME = "kneobroadcaster__ai_agents";
    private static final String AI_AGENT_ACCESS_TABLE_NAME = "kneobroadcaster__ai_agent_readers";
    private static final String EVENT_TABLE_NAME = "kneobroadcaster__events";
    private static final String EVENT_ACCESS_TABLE_NAME = "kneobroadcaster__event_readers";

    @Override
    public EntityData getEntityNames(String type) {
        return switch (type) {
            case SOUND_FRAGMENT -> new EntityData(
                    SOUND_FRAGMENT_TABLE_NAME,
                    SOUND_FRAGMENT_ACCESS_TABLE_NAME,
                    null,
                    SOUND_FRAGMENT_FILES_TABLE_NAME
            );
            case LISTENER -> new EntityData(
                    LISTENER_TABLE_NAME,
                    LISTENER_ACCESS_TABLE_NAME
            );
            case RADIO_STATION -> new EntityData(
                    RADIO_STATION_TABLE_NAME,
                    RADIO_STATION_ACCESS_TABLE_NAME
            );
            case PROFILE -> new EntityData(
                    PROFILE_TABLE_NAME,
                    PROFILE_ACCESS_TABLE_NAME
            );
            case MEMORY -> new EntityData(
                    MEMORY_TABLE_NAME
            );
            case BRAND_STATS -> new EntityData(
                    BRAND_STATS_TABLE_NAME
            );
            case GENRE -> new EntityData(
                    GENRE_TABLE_NAME
            );
            case AI_AGENT -> new EntityData(
                    AI_AGENT_TABLE_NAME,
                    AI_AGENT_ACCESS_TABLE_NAME
            );
            case EVENT -> new EntityData(
                    EVENT_TABLE_NAME,
                    EVENT_ACCESS_TABLE_NAME
            );
            default -> super.getEntityNames(type);
        };
    }

    public static KneoBroadcasterNameResolver create() {
        return new KneoBroadcasterNameResolver();
    }
}