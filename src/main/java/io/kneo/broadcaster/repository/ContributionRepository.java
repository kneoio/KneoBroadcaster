package io.kneo.broadcaster.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import io.vertx.mutiny.sqlclient.SqlConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ContributionRepository extends AsyncRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(ContributionRepository.class);

    @Inject
    public ContributionRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<UUID> insertContribution(UUID soundFragmentId, String contributorEmail, String attachedMessage, boolean shareable, Long userId) {
        String sql = "INSERT INTO kneobroadcaster__contributions (author, last_mod_user, contributorEmail, sound_fragment_id, attached_message, shareable) VALUES ($1, $2, $3, $4, $5, $6) RETURNING id";
        String safeMessage = attachedMessage != null ? attachedMessage : "";
        Tuple params = Tuple.of(userId, userId, contributorEmail, soundFragmentId, safeMessage, shareable ? 1 : 0);
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(rs -> rs.iterator().next().getUUID("id"));
    }

    public Uni<Integer> updateContribution(UUID id, String contributorEmail, String attachedMessage, boolean shareable, Long userId) {
        String sql = "UPDATE kneobroadcaster__contributions SET last_mod_user=$1, last_mod_date=now(), contributorEmail=$2, attached_message=$3, shareable=$4 WHERE id=$5";
        String safeMessage = attachedMessage != null ? attachedMessage : "";
        Tuple params = Tuple.of(userId, contributorEmail, safeMessage, shareable ? 1 : 0, id);
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<List<JsonObject>> getBySoundFragmentId(UUID soundFragmentId, boolean includeArchived) {
        String sql = "SELECT id, author, reg_date, last_mod_user, last_mod_date, contributorEmail, sound_fragment_id, playing_history, attached_message, shareable, archived FROM kneobroadcaster__contributions WHERE sound_fragment_id = $1";
        if (!includeArchived) {
            sql += " AND (archived IS NULL OR archived = 0)";
        }
        sql += " ORDER BY last_mod_date DESC";
        return client.preparedQuery(sql)
                .execute(Tuple.of(soundFragmentId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::rowToJson)
                .collect().asList();
    }

    public Uni<Integer> archiveContribution(UUID id) {
        String sql = "UPDATE kneobroadcaster__contributions SET archived = 1, last_mod_date=now() WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    public Uni<UUID> insertUploadAgreement(UUID contributionId, String email, String countryCode, String ipAddress, String userAgent, String agreementVersion, String termsText, Long userId) {
        String sql = "INSERT INTO kneobroadcaster__upload_agreements (author, last_mod_user, contribution_id, email, country, ip_address, user_agent, agreement_version, terms_text) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9) RETURNING id";
        String safeAgreementVersion = agreementVersion != null ? agreementVersion : "v1.0";
        String safeTermsText = termsText != null ? termsText : "";
        String safeCountry = countryCode != null ? countryCode : "US";
        Tuple params = Tuple.tuple()
                .addLong(userId)
                .addLong(userId)
                .addUUID(contributionId)
                .addString(email)
                .addString(safeCountry)
                .addString(ipAddress)
                .addString(userAgent)
                .addString(safeAgreementVersion)
                .addString(safeTermsText);
        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(rs -> rs.iterator().next().getUUID("id"));
    }

    public Uni<Integer> archiveUploadAgreement(UUID id) {
        String sql = "UPDATE kneobroadcaster__upload_agreements SET archived = 1, last_mod_date=now() WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(RowSet::rowCount);
    }

    private JsonObject rowToJson(Row row) {
        return new JsonObject()
                .put("id", row.getUUID("id"))
                .put("author", row.getLong("author"))
                .put("reg_date", row.getLocalDateTime("reg_date"))
                .put("last_mod_user", row.getLong("last_mod_user"))
                .put("last_mod_date", row.getLocalDateTime("last_mod_date"))
                .put("contributorEmail", row.getString("contributorEmail"))
                .put("sound_fragment_id", row.getUUID("sound_fragment_id"))
                .put("playing_history", row.getJsonArray("playing_history"))
                .put("attached_message", row.getString("attached_message"))
                .put("shareable", row.getInteger("shareable"))
                .put("archived", row.getInteger("archived"));
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

        String safeMessage = attachedMessage != null ? attachedMessage : "";
        String safeAgreementVersion = agreementVersion != null ? agreementVersion : "v1.0";
        String safeTermsText = termsText != null ? termsText : "";
        String safeCountry = countryCode != null ? countryCode : "US";

        return client.withTransaction((SqlConnection tx) ->
                tx.preparedQuery(insertContributionSql)
                        .execute(Tuple.of(userId, userId, contributorEmail, soundFragmentId, safeMessage, shareable ? 1 : 0))
                        .onItem().transform(rs -> rs.iterator().next().getUUID("id"))
                        .onItem().transformToUni(contributionId ->
                                tx.preparedQuery(insertAgreementSql)
                                        .execute(Tuple.tuple()
                                                .addLong(userId)
                                                .addLong(userId)
                                                .addUUID(contributionId)
                                                .addString(email)
                                                .addString(safeCountry)
                                                .addString(ipAddress)
                                                .addString(userAgent)
                                                .addString(safeAgreementVersion)
                                                .addString(safeTermsText)
                                        )
                        )
                        .onItem().ignore().andContinueWithNull()
        );
    }
}