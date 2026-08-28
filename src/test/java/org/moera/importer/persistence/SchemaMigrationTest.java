package org.moera.importer.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchemaMigrationTest extends PostgresIntegrationTest {

    @Test
    void springBootAppliesMigrationAndCreatesAllInitialRelations() throws SQLException {
        Set<String> tableNames = queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            """);

        assertThat(tableNames).contains(
            "channels",
            "messages",
            "message_link_previews",
            "media",
            "destinations",
            "mirrors",
            "publications",
            "publication_messages",
            "publication_media"
        );
    }

    @Test
    void messagePinningAndLinkPreviewSchemaIsAvailable() throws SQLException {
        Set<String> pinnedColumn = queryStrings("""
            SELECT is_nullable || ':' || column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'messages'
              AND column_name = 'pinned'
            """);
        Set<String> previewColumns = queryStrings("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'message_link_previews'
            """);

        assertThat(pinnedColumn).containsExactly("NO:false");
        assertThat(previewColumns).contains(
            "message_id",
            "position",
            "site_name",
            "url",
            "title",
            "description",
            "published_at",
            "image_media_id"
        );
    }

    @Test
    void sourceTypeContainsOnlyTelegram() throws SQLException {
        Set<String> sourceTypes = queryStrings("""
            SELECT enumlabel
            FROM pg_enum
            JOIN pg_type ON pg_type.oid = pg_enum.enumtypid
            WHERE pg_type.typname = 'source_type'
            """);

        assertThat(sourceTypes).containsExactly("TELEGRAM");
    }

    @Test
    void destinationCreateRateStateIsNullableTimestampWithTimeZone() throws SQLException {
        Set<String> column = queryStrings("""
            SELECT data_type || ':' || is_nullable
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'destinations'
              AND column_name = 'next_create_at'
            """);

        assertThat(column).containsExactly("timestamp with time zone:YES");
    }

    @Test
    void migrationAllowsUnresolvedChannelWithoutSourceId() throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.prepareStatement("""
                 INSERT INTO channels (
                     source_type,
                     source_id,
                     source_uri,
                     access_state,
                     subscription_state
                 ) VALUES ('TELEGRAM', NULL, ?, 'UNKNOWN', 'INACTIVE')
                 """)) {
            statement.setString(1, "https://example.invalid/private-channel");

            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private Set<String> queryStrings(String sql) throws SQLException {
        try (Connection connection = openConnection();
             var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            Set<String> values = new HashSet<>();
            while (resultSet.next()) {
                values.add(resultSet.getString(1));
            }
            return values;
        }
    }

    private static Connection openConnection() throws SQLException {
        return DriverManager.getConnection(
            jdbcUrl(),
            postgres().getUsername(),
            postgres().getPassword()
        );
    }

}
