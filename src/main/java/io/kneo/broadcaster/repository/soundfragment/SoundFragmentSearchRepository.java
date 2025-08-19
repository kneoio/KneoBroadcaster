package io.kneo.broadcaster.repository.soundfragment;

import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.repository.table.EntityData;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SOUND_FRAGMENT;

@ApplicationScoped
public class SoundFragmentSearchRepository {

    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    private final PgPool client;
    private final SoundFragmentQueryBuilder queryBuilder;

    @Inject
    public SoundFragmentSearchRepository(PgPool client, SoundFragmentQueryBuilder queryBuilder) {
        this.client = client;
        this.queryBuilder = queryBuilder;
    }




}