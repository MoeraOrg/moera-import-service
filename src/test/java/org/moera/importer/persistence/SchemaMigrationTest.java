package org.moera.importer.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
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
    void channelMetadataIsDedicatedNonNullJsonb() throws SQLException {
        Set<String> column = queryStrings("""
            SELECT data_type || ':' || is_nullable || ':' || column_default
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = 'channels'
              AND column_name = 'metadata'
            """);

        assertThat(column).containsExactly("jsonb:NO:'{}'::jsonb");
    }

    @Test
    void channelMetadataMigrationSeparatesLegacyProfileFromSourceData() throws SQLException {
        String schema = "metadata_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, MigrationVersion.fromVersion("3")).migrate();
            try (Connection connection = openConnection();
                 var statement = connection.prepareStatement("""
                     INSERT INTO %s.channels (
                         source_type, source_id, source_uri, source_data, access_state, subscription_state
                     ) VALUES ('TELEGRAM', 'channel-1', 'https://t.me/channel-1', ?::jsonb, 'GRANTED', 'ACTIVE')
                     """.formatted(schema))) {
                statement.setString(1, """
                    {"initialImportMode":"FULL","profile":{"title":"News","description":"Description"}}
                    """);
                statement.executeUpdate();
            }

            flyway(schema, null).migrate();

            try (Connection connection = openConnection();
                 var statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("""
                     SELECT metadata ->> 'title', source_data ->> 'initialImportMode', source_data ? 'profile'
                     FROM %s.channels
                     """.formatted(schema))) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("News");
                assertThat(resultSet.getString(2)).isEqualTo("FULL");
                assertThat(resultSet.getBoolean(3)).isFalse();
            }
        } finally {
            try (Connection connection = openConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
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

    private static Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
            .dataSource(jdbcUrl(), postgres().getUsername(), postgres().getPassword())
            .schemas(schema)
            .defaultSchema(schema)
            .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

}
