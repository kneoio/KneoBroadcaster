package io.kneo.broadcaster.repository.soundfragment;

import io.kneo.broadcaster.model.SoundFragmentFilter;
import io.kneo.core.model.user.IUser;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class SoundFragmentQueryBuilder {

    public String buildGetAllQuery(String tableName, String rlsName, IUser user, boolean includeArchived,
                                   SoundFragmentFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT * FROM ").append(tableName).append(" t, ").append(rlsName).append(" rls ")
                .append("WHERE t.id = rls.entity_id AND rls.reader = ").append(user.getId());

        if (!includeArchived) {
            sql.append(" AND (t.archived IS NULL OR t.archived = 0)");
        }

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append(" ORDER BY t.last_mod_date DESC");

        if (limit > 0) {
            sql.append(String.format(" LIMIT %s OFFSET %s", limit, offset));
        }

        return sql.toString();
    }

    public String buildSearchQuery(String tableName, String rlsName, String searchTerm, boolean includeArchived,
                                   SoundFragmentFilter filter, int limit, int offset) {
        StringBuilder sql = new StringBuilder()
                .append("SELECT * FROM ").append(tableName).append(" t, ").append(rlsName).append(" rls ")
                .append("WHERE t.id = rls.entity_id AND rls.reader = $1");

        if (searchTerm != null && !searchTerm.trim().isEmpty()) {
            sql.append(" AND (LOWER(t.title) LIKE $2 OR LOWER(t.artist) LIKE $3 OR LOWER(t.genre) LIKE $4 OR CAST(t.id AS TEXT) LIKE $5)");
        }

        if (!includeArchived) {
            sql.append(" AND (t.archived IS NULL OR t.archived = 0)");
        }

        if (filter != null && filter.isActivated()) {
            sql.append(buildFilterConditions(filter));
        }

        sql.append(" ORDER BY t.last_mod_date DESC");

        if (limit > 0) {
            sql.append(String.format(" LIMIT %s OFFSET %s", limit, offset));
        }

        return sql.toString();
    }

    String buildFilterConditions(SoundFragmentFilter filter) {
        StringBuilder conditions = new StringBuilder();

        if (filter.getGenres() != null && !filter.getGenres().isEmpty()) {
            conditions.append(" AND t.genre IN (");
            for (int i = 0; i < filter.getGenres().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getGenres().get(i).replace("'", "''")).append("'");
            }
            conditions.append(")");
        }

        if (filter.getSources() != null && !filter.getSources().isEmpty()) {
            conditions.append(" AND t.source IN (");
            for (int i = 0; i < filter.getSources().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getSources().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        if (filter.getTypes() != null && !filter.getTypes().isEmpty()) {
            conditions.append(" AND t.type IN (");
            for (int i = 0; i < filter.getTypes().size(); i++) {
                if (i > 0) conditions.append(", ");
                conditions.append("'").append(filter.getTypes().get(i).name()).append("'");
            }
            conditions.append(")");
        }

        return conditions.toString();
    }
}
