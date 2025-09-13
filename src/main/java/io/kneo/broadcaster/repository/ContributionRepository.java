package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.vertx.mutiny.pgclient.PgPool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static io.kneo.broadcaster.repository.table.KneoBroadcasterNameResolver.AI_AGENT;

@ApplicationScoped
public class ContributionRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContributionRepository.class);
    private static final EntityData entityData = KneoBroadcasterNameResolver.create().getEntityNames(AI_AGENT);

    @Inject
    public ContributionRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }


}