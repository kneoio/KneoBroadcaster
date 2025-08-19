package io.kneo.broadcaster.repository.soundfragment;

import io.kneo.broadcaster.repository.file.IFileStorage;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.SOUND_FRAGMENT;

@ApplicationScoped
public class SoundFragmentFileRepository {

    private static final Logger LOGGER = LoggerFactory.getLogger(SoundFragmentFileRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(SOUND_FRAGMENT);

    private final PgPool client;
    private final IFileStorage fileStorage;
    private final SoundFragmentFileHandler fileHandler;
    private final RLSRepository rlsRepository;

    @Inject
    public SoundFragmentFileRepository(PgPool client, @Named("hetzner") IFileStorage fileStorage,
                                       SoundFragmentFileHandler fileHandler, RLSRepository rlsRepository) {
        this.client = client;
        this.fileStorage = fileStorage;
        this.fileHandler = fileHandler;
        this.rlsRepository = rlsRepository;
    }


}