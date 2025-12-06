package io.kneo.broadcaster.repository.soundfragment;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.cnst.PlaylistItemType;
import io.kneo.broadcaster.model.soundfragment.BrandSoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragment;
import io.kneo.broadcaster.model.soundfragment.SoundFragmentFilter;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.rls.RLSRepository;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.SqlResult;
import io.vertx.mutiny.sqlclient.Tuple;

import java.util.List;
import java.util.UUID;

public class SoundFragmentBrandRepository extends SoundFragmentRepositoryAbstract {

    public SoundFragmentBrandRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<BrandSoundFragment>> findForBrandBySimilarity(UUID brandId, String keyword, final int limit, final int offset,
                                                                  boolean includeArchived, IUser user) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.rated_by_brand_count, bsf.last_time_played_by_brand, " +
                "similarity(t.search_name, $3) AS sim " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND  t.archived = 0 ";
        }

        sql += " AND (t.search_name ILIKE '%' || $3 || '%' OR similarity(t.search_name, $3) > 0.05)";
        sql += " ORDER BY sim DESC";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(brandId, user.getId(), keyword))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<SoundFragment> soundFragmentUni = from(row, true, false, true);
                    return soundFragmentUni.onItem().transform(soundFragment -> {
                        BrandSoundFragment brandSoundFragment = createBrandSoundFragment(row, brandId);
                        brandSoundFragment.setSoundFragment(soundFragment);
                        return brandSoundFragment;
                    });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<List<BrandSoundFragment>> findForBrand(UUID brandId, final int limit, final int offset,
                                                      boolean includeArchived, IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.rated_by_brand_count, bsf.last_time_played_by_brand";
        
        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql += ", similarity(t.search_name, $3) AS sim";
        }
        
        sql += " FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql += " ORDER BY sim DESC";
        } else {
            sql += " ORDER BY t.reg_date DESC";
        }

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        Tuple params = (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty())
                ? Tuple.of(brandId, user.getId(), filter.getSearchTerm())
                : Tuple.of(brandId, user.getId());

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<SoundFragment> soundFragmentUni = from(row, false, false, false);
                    return soundFragmentUni.onItem().transform(soundFragment -> {
                        BrandSoundFragment brandSoundFragment = createBrandSoundFragment(row, brandId);
                        brandSoundFragment.setSoundFragment(soundFragment);
                        return brandSoundFragment;
                    });
                })
                .concatenate()
                .collect().asList();
    }

    public Uni<Integer> findForBrandCount(UUID brandId, boolean includeArchived, IUser user, SoundFragmentFilter filter) {
        String sql = "SELECT COUNT(*) " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "JOIN " + entityData.getRlsName() + " rls ON t.id = rls.entity_id " +
                "WHERE bsf.brand_id = $1 AND rls.reader = $2";

        if (!includeArchived) {
            sql += " AND t.archived = 0";
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql += " AND (t.search_name ILIKE '%' || $3 || '%' OR similarity(t.search_name, $3) > 0.05)";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        Tuple params = (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty())
                ? Tuple.of(brandId, user.getId(), filter.getSearchTerm())
                : Tuple.of(brandId, user.getId());

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transform(rows -> rows.iterator().next().getInteger(0));
    }

    @Deprecated
    public Uni<List<BrandSoundFragment>> getBrandSongs(UUID brandId, final int limit, final int offset, SoundFragmentFilter filter) {
        String sql = "SELECT t.*, bsf.played_by_brand_count, bsf.rated_by_brand_count, bsf.last_time_played_by_brand";
        
        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql += ", similarity(t.search_name, $2) AS sim";
        }
        
        sql += " FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0";

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql += " AND (t.search_name ILIKE '%' || $2 || '%' OR similarity(t.search_name, $2) > 0.05)";
        }

        if (filter != null && filter.isActivated()) {
            sql += buildFilterConditions(filter);
        }

        if (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty()) {
            sql += " ORDER BY sim DESC";
        } else {
            sql += " ORDER BY " +
                    "bsf.played_by_brand_count ASC, " +
                    "COALESCE(bsf.last_time_played_by_brand, '1970-01-01'::timestamp) ASC";
        }

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        Tuple params = (filter != null && filter.getSearchTerm() != null && !filter.getSearchTerm().trim().isEmpty())
                ? Tuple.of(brandId, filter.getSearchTerm())
                : Tuple.of(brandId);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> {
                    Uni<SoundFragment> soundFragmentUni = from(row, false, false, false);
                    return soundFragmentUni.onItem().transform(soundFragment -> {
                        BrandSoundFragment brandSoundFragment = createBrandSoundFragment(row, brandId);
                        brandSoundFragment.setSoundFragment(soundFragment);
                        return brandSoundFragment;
                    });
                })
                .concatenate()
                .collect().asList();
    }
    public Uni<List<SoundFragment>> getBrandSongs(UUID brandId, PlaylistItemType fragmentType, final int limit, final int offset) {
        String sql = "SELECT t.* " +
                "FROM " + entityData.getTableName() + " t " +
                "JOIN kneobroadcaster__brand_sound_fragments bsf ON t.id = bsf.sound_fragment_id " +
                "WHERE bsf.brand_id = $1 AND t.archived = 0 AND t.type = $2 " +
                "ORDER BY bsf.played_by_brand_count";

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        Tuple params = Tuple.of(brandId, fragmentType);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transformToUni(row -> from(row, true, true, true))
                .concatenate()
                .collect().asList();
    }

    private BrandSoundFragment createBrandSoundFragment(Row row, UUID brandId) {
        BrandSoundFragment brandSoundFragment = new BrandSoundFragment();
        brandSoundFragment.setId(row.getUUID("id"));
        brandSoundFragment.setDefaultBrandId(brandId);
        brandSoundFragment.setPlayedByBrandCount(row.getInteger("played_by_brand_count"));
        brandSoundFragment.setRatedByBrandCount(row.getInteger("rated_by_brand_count"));
        brandSoundFragment.setPlayedTime(row.getLocalDateTime("last_time_played_by_brand"));
        return brandSoundFragment;
    }

    public Uni<Integer> updateRatedByBrandCount(UUID brandId, UUID soundFragmentId, int delta, IUser user) {
        return rlsRepository.findById(entityData.getRlsName(), user.getId(), soundFragmentId)
                .onItem().transformToUni(permissions -> {
                    if (!permissions[0]) {
                        return Uni.createFrom().failure(new io.kneo.core.repository.exception.DocumentModificationAccessException(
                                "User does not have edit permission", user.getUserName(), soundFragmentId
                        ));
                    }

                    String selectSql = "SELECT rated_by_brand_count, last_rated_at FROM kneobroadcaster__brand_sound_fragments " +
                            "WHERE brand_id = $1 AND sound_fragment_id = $2";

                    return client.preparedQuery(selectSql)
                            .execute(Tuple.of(brandId, soundFragmentId))
                            .onItem().transformToUni(rowSet -> {
                                int currentRating = 100;
                                java.time.LocalDateTime lastRatedAt = null;
                                
                                if (rowSet.iterator().hasNext()) {
                                    Row row = rowSet.iterator().next();
                                    Integer dbValue = row.getInteger("rated_by_brand_count");
                                    if (dbValue != null) {
                                        currentRating = dbValue;
                                    }
                                    lastRatedAt = row.getLocalDateTime("last_rated_at");
                                }

                                if (lastRatedAt != null) {
                                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                                    long secondsSinceLastRating = java.time.Duration.between(lastRatedAt, now).getSeconds();
                                    
                                    if (secondsSinceLastRating < 2) {
                                        boolean sameDirection = (delta > 0 && currentRating > 100) || (delta < 0 && currentRating < 100);
                                        if (sameDirection) {
                                            return Uni.createFrom().failure(new IllegalStateException(
                                                    "Please wait before rating again."
                                            ));
                                        }
                                    }
                                }

                                int newRating = currentRating + delta;
                                if (newRating < 0) {
                                    newRating = 0;
                                } else if (newRating > 200) {
                                    newRating = 200;
                                }

                                String updateSql = "UPDATE kneobroadcaster__brand_sound_fragments " +
                                        "SET rated_by_brand_count = $1, last_rated_at = NOW() " +
                                        "WHERE brand_id = $2 AND sound_fragment_id = $3";

                                Tuple updateParams = Tuple.of(newRating, brandId, soundFragmentId);

                                return client.preparedQuery(updateSql)
                                        .execute(updateParams)
                                        .onItem().transform(SqlResult::rowCount);
                            });
                });
    }
}
