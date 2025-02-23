package io.kneo.broadcaster.repository.table;

import io.kneo.core.repository.table.EntityData;
import io.kneo.core.repository.table.TableNameResolver;

public class KneoBroadcasterNameResolver extends TableNameResolver {
    public static final String SOUND_FRAGMENT = "sound fragment";
    public static final String LISTENER = "listener";
    public static final String RADIO_STATION = "radio station";

    private static final String SOUND_FRAGMENT_TABLE_NAME = "kneobroadcaster__sound_fragments";
    private static final String SOUND_FRAGMENT_ACCESS_TABLE_NAME = "kneobroadcaster__sound_fragment_readers";
    private static final String SOUND_FRAGMENT_FILES_TABLE_NAME = "kneobroadcaster__sound_fragment_files";
    private static final String LISTENER_TABLE_NAME = "kneobroadcaster__listeners";
    private static final String LISTENER_ACCESS_TABLE_NAME = "kneobroadcaster__listener_readers";
    private static final String RADIO_STATION_TABLE_NAME = "kneobroadcaster__brands";
    private static final String RADIO_STATION_ACCESS_TABLE_NAME = "kneobroadcaster__brand_readers";

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
            default -> super.getEntityNames(type);
        };
    }

    public static KneoBroadcasterNameResolver create() {
        return new KneoBroadcasterNameResolver();
    }
}
