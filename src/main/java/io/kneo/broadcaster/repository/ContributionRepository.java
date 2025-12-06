package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.SqlConnection;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

@ApplicationScoped
public class ContributionRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContributionRepository.class);

    @Inject
    public ContributionRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }


    public Uni<Void> insertContributionAndAgreementTx(UUID soundFragmentId,
                                                      String contributorEmail,
                                                      String attachedMessage,
                                                      boolean shareable,
                                                      String email,
                                                      String countryCode,
                                                      String ipAddress,
                                                      String userAgent,
                                                      String agreementVersion,
                                                      String termsText,
                                                      Long userId) {
        String insertContributionSql = "INSERT INTO kneobroadcaster__contributions (author, last_mod_user, contributorEmail, sound_fragment_id, attached_message, shareable) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id";
        String insertAgreementSql = "INSERT INTO kneobroadcaster__upload_agreements (author, last_mod_user, contribution_id, email, country, ip_address, user_agent, agreement_version, terms_text) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)";

        return client.withTransaction((SqlConnection tx) ->
                tx.preparedQuery(insertContributionSql)
                        .execute(Tuple.of(userId, userId, contributorEmail, soundFragmentId, attachedMessage, shareable ? 1 : 0))
                        .onItem().transform(rs -> rs.iterator().next().getUUID("id"))
                        .onItem().transformToUni(contributionId ->
                                tx.preparedQuery(insertAgreementSql)
                                        .execute(Tuple.tuple()
                                                .addLong(userId)
                                                .addLong(userId)
                                                .addUUID(contributionId)
                                                .addString(email)
                                                .addString(countryCode)
                                                .addString(ipAddress)
                                                .addString(userAgent)
                                                .addString(agreementVersion)
                                                .addString(termsText)
                                        )
                        )
                        .onItem().ignore().andContinueWithNull()
        );
    }
}