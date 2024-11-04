package io.kneo.broadcaster.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.broadcaster.model.FragmentType;
import io.kneo.broadcaster.model.SoundFragment;
import io.kneo.broadcaster.model.SourceType;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class AudioFileStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(AudioFileStore.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Inject
    DataSource dataSource;

    @PostConstruct
    public void init() {
        try (Connection conn = dataSource.getConnection()) {
            conn.createStatement().execute("""
                CREATE TABLE IF NOT EXISTS sound_fragments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    source TEXT,
                    status INTEGER,
                    file_uri TEXT,
                    local_path TEXT,
                    type TEXT,
                    name TEXT,
                    author TEXT,
                    created_at TEXT,
                    genre TEXT,
                    album TEXT,
                    file_data BLOB
                )
            """);
        } catch (Exception e) {
            LOGGER.error("Failed to create table", e);
        }
    }

    public SoundFragment saveFragment(SoundFragment fragment) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
                INSERT INTO sound_fragments 
                (source, status, file_uri, local_path, type, name, author, created_at, genre, album, file_data)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {

            stmt.setString(1, fragment.getSource().toString());
            stmt.setInt(2, fragment.getStatus());
            stmt.setString(3, fragment.getFileUri());
            stmt.setString(4, fragment.getLocalPath());
            stmt.setString(5, fragment.getType().name());
            stmt.setString(6, fragment.getName());
            stmt.setString(7, fragment.getAuthor());
            stmt.setString(8, fragment.getCreatedAt());
            stmt.setString(9, fragment.getGenre());
            stmt.setString(10, fragment.getAlbum());
            stmt.setBytes(11, fragment.getFile());

            stmt.executeUpdate();

            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                fragment.setId(rs.getInt(1));
            }
            return fragment;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public SoundFragment getFragment(int id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT * FROM sound_fragments WHERE id = ?")) {

            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();

            if (rs.next()) {
                return SoundFragment.builder()
                        .id(rs.getInt("id"))
                        .source(SourceType.valueOf(rs.getString("source")))
                        .status(rs.getInt("status"))
                        .fileUri(rs.getString("file_uri"))
                        .localPath(rs.getString("local_path"))
                        .type(FragmentType.valueOf(rs.getString("type")))
                        .name(rs.getString("name"))
                        .author(rs.getString("author"))
                        .createdAt(rs.getString("created_at"))
                        .genre(rs.getString("genre"))
                        .album(rs.getString("album"))
                        .file(rs.getBytes("file_data"))
                        .build();
            }
            return null;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public String getAllFragments() {

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, source, status, file_uri, local_path, type, name, author, created_at, genre, album FROM sound_fragments");
             ResultSet rs = stmt.executeQuery()) {

            List<SoundFragment> fragments = new ArrayList<>();
            while (rs.next()) {
                fragments.add(SoundFragment.builder()
                        .id(rs.getInt("id"))
                        .source(SourceType.valueOf(rs.getString("source")))
                        .status(rs.getInt("status"))
                        .fileUri(rs.getString("file_uri"))
                        .localPath(rs.getString("local_path"))
                        .type(FragmentType.valueOf(rs.getString("type")))
                        .name(rs.getString("name"))
                        .author(rs.getString("author"))
                        .createdAt(rs.getString("created_at"))
                        .genre(rs.getString("genre"))
                        .album(rs.getString("album"))
                        .build());
            }
            return objectMapper.writeValueAsString(fragments);
        } catch (SQLException | JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public void updateStatus(int id, int status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("""
             UPDATE sound_fragments 
             SET status = ?
             WHERE id = ?
             """)) {

            stmt.setInt(1, status);
            stmt.setInt(2, id);

            int rowsAffected = stmt.executeUpdate();
            if (rowsAffected == 0) {
                throw new RuntimeException("Failed to update status for sound fragment with id: " + id);
            }

        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public List<SoundFragment> getFragmentsByStatus(int status) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "SELECT id, source, status, file_uri, local_path, type, name, author, created_at, genre, album, file_data " +
                             "FROM sound_fragments WHERE status = " + status);
             ResultSet rs = stmt.executeQuery()) {

            List<SoundFragment> fragments = new ArrayList<>();
            while (rs.next()) {
                fragments.add(SoundFragment.builder()
                        .id(rs.getInt("id"))
                        .source(SourceType.valueOf(rs.getString("source")))
                        .status(rs.getInt("status"))
                        .fileUri(rs.getString("file_uri"))
                        .localPath(rs.getString("local_path"))
                        .type(FragmentType.valueOf(rs.getString("type")))
                        .name(rs.getString("name"))
                        .author(rs.getString("author"))
                        .createdAt(rs.getString("created_at"))
                        .genre(rs.getString("genre"))
                        .album(rs.getString("album"))
                        .file(rs.getBytes("file_data"))
                        .build());
            }
            return fragments;
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public void deleteFragment(int id) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(
                     "DELETE FROM sound_fragments WHERE id = ?")) {

            stmt.setInt(1, id);
            stmt.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }


}