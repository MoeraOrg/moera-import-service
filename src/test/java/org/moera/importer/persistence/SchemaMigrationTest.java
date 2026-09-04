package org.moera.importer.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void finalSchemaRetainsOnlyAdapterOwnedJsonb() throws SQLException {
        Set<String> jsonbColumns = queryStrings("""
            SELECT table_name || '.' || column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND data_type = 'jsonb'
            """);

        assertThat(jsonbColumns).containsExactlyInAnyOrder(
            "channels.source_data",
            "channels.access_data",
            "channels.subscription_data",
            "channels.waterline",
            "messages.source_data",
            "media.source_data"
        );
        assertThat(columns("channels")).doesNotContain("metadata");
        assertThat(columns("destinations")).doesNotContain("config", "data");
        assertThat(columns("mirrors")).doesNotContain("config");
        assertThat(columns("publications")).doesNotContain("destination_data");
        assertThat(columns("publication_media")).doesNotContain("destination_data");
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
                     SELECT title, initial_import_mode::text, source_data ? 'profile',
                            source_data ? 'initialImportMode'
                     FROM %s.channels
                     """.formatted(schema))) {
                assertThat(resultSet.next()).isTrue();
                assertThat(resultSet.getString(1)).isEqualTo("News");
                assertThat(resultSet.getString(2)).isEqualTo("FULL");
                assertThat(resultSet.getBoolean(3)).isFalse();
                assertThat(resultSet.getBoolean(4)).isFalse();
            }
        } finally {
            try (Connection connection = openConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void relationalStateColumnsAndStoryTableAreAvailable() throws SQLException {
        assertThat(columns("channels")).contains(
            "initial_import_mode",
            "title",
            "description_html",
            "public_username",
            "emblem_remote_id",
            "emblem_unique_id",
            "emblem_mime_type"
        );
        assertThat(columns("messages")).contains("external_source_uri", "forward_name", "forward_uri");
        assertThat(columns("destinations")).contains(
            "credential_ref",
            "profile_sync",
            "profile_fingerprint",
            "profile_source_emblem_id",
            "avatar_id",
            "avatar_media_id",
            "avatar_shape"
        );
        assertThat(columns("mirrors")).contains("feeds");
        assertThat(columns("publications")).contains("destination_pinned");
        assertThat(columns("publication_media")).contains("destination_hash", "compression_pending");
        assertThat(queryStrings("""
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public'
            """)).contains("publication_stories");
    }

    @Test
    void v005BackfillsRelationalStateWithoutLoss() throws SQLException {
        String schema = "relational_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, MigrationVersion.fromVersion("4")).migrate();
            insertLegacyRelationalState(schema);

            flyway(schema, MigrationVersion.fromVersion("5")).migrate();

            assertChannelBackfill(schema);
            assertMessageBackfill(schema);
            assertDestinationAndMirrorBackfill(schema);
            assertPublicationBackfill(schema);
        } finally {
            try (Connection connection = openConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v006RevalidatesBackfillsAndScrubsLegacyState() throws SQLException {
        String schema = "contract_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, MigrationVersion.fromVersion("4")).migrate();
            insertLegacyRelationalState(schema);
            flyway(schema, MigrationVersion.fromVersion("5")).migrate();
            updateLegacyRelationalState(schema);

            flyway(schema, null).migrate();

            assertContractedRelationalState(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v006PreservesLegacyKeyRemovalsAndImplicitDefaults() throws SQLException {
        String schema = "contract_removals_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, MigrationVersion.fromVersion("4")).migrate();
            insertLegacyRelationalState(schema);
            flyway(schema, MigrationVersion.fromVersion("5")).migrate();
            removeLegacyRelationalState(schema);

            flyway(schema, null).migrate();

            assertContractedLegacyRemovals(schema);
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void v006RejectsUnknownAndMalformedLegacyState() throws SQLException {
        List<InvalidLegacyState> invalidStates = List.of(
            new InvalidLegacyState("channels", "metadata", "{\"title\":\"News\",\"unknown\":true}",
                "Cannot migrate channels row 1 field metadata.unknown"),
            new InvalidLegacyState("destinations", "config",
                "{\"credentialRef\":\"env:TOKEN\",\"unknown\":true}",
                "Cannot migrate destinations row 1 field config.unknown"),
            new InvalidLegacyState("destinations", "data", "{\"unknown\":true}",
                "Cannot migrate destinations row 1 field data.unknown"),
            new InvalidLegacyState("mirrors", "config",
                "{\"feeds\":[\"timeline\"],\"unknown\":true}",
                "Cannot migrate mirrors row 1 field config.unknown"),
            new InvalidLegacyState("publications", "destination_data", "{\"unknown\":true}",
                "Cannot migrate publications row 1 field destination_data.unknown"),
            new InvalidLegacyState("publication_media", "destination_data", "{\"unknown\":true}",
                "Cannot migrate publication_media row (publication_id=1, media_id=1) "
                    + "field destination_data.unknown"),
            new InvalidLegacyState("mirrors", "config", "{\"feeds\":[\"timeline\",\"timeline\"]}",
                "Cannot migrate mirrors row 1 field config.feeds"),
            new InvalidLegacyState("publications", "destination_data",
                "{\"storyIds\":{\"timeline\":17}}",
                "Cannot migrate publications row 1 field storyIds.timeline"),
            new InvalidLegacyState("publication_media", "destination_data",
                "{\"compressionPending\":\"yes\"}",
                "Cannot migrate publication_media row (publication_id=1, media_id=1) field destination_data"),
            new InvalidLegacyState("mirrors", "config", "[]",
                "Cannot migrate mirrors row 1 field config.feeds"),
            new InvalidLegacyState("publications", "destination_data", "[]",
                "Cannot migrate publications row 1 field destination_data"),
            new InvalidLegacyState("publication_media", "destination_data", "[]",
                "Cannot migrate publication_media row (publication_id=1, media_id=1) field destination_data")
        );

        for (int index = 0; index < invalidStates.size(); index++) {
            InvalidLegacyState invalidState = invalidStates.get(index);
            String schema = "invalid_contract_" + index + "_" + UUID.randomUUID().toString().replace("-", "");
            try {
                flyway(schema, MigrationVersion.fromVersion("4")).migrate();
                insertLegacyRelationalState(schema);
                flyway(schema, MigrationVersion.fromVersion("5")).migrate();
                replaceLegacyJson(schema, invalidState);

                assertThatThrownBy(() -> flyway(schema, null).migrate())
                    .hasStackTraceContaining(invalidState.diagnostic());
            } finally {
                dropSchema(schema);
            }
        }
    }

    @Test
    void v005RejectsUnknownFixedJsonFields() throws SQLException {
        String schema = "invalid_relational_" + UUID.randomUUID().toString().replace("-", "");
        try {
            flyway(schema, MigrationVersion.fromVersion("4")).migrate();
            try (Connection connection = openConnection();
                 var statement = connection.prepareStatement("""
                     INSERT INTO %s.destinations (node_name, config)
                     VALUES ('news.example', ?::jsonb)
                     """.formatted(schema))) {
                statement.setString(1, """
                    {"credentialRef":"env:MOERA_ADMIN_TOKEN","unknown":"must-not-be-lost"}
                    """);
                statement.executeUpdate();
            }

            assertThatThrownBy(() -> flyway(schema, null).migrate())
                .hasStackTraceContaining("Cannot migrate destinations row 1 field config.unknown");
        } finally {
            try (Connection connection = openConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void v005RejectsMalformedLegacyRootsWithRowDiagnostics() throws SQLException {
        List<InvalidLegacyState> invalidStates = List.of(
            new InvalidLegacyState("mirrors", "config", "[]",
                "Cannot migrate mirrors row 1 field config.feeds"),
            new InvalidLegacyState("publications", "destination_data", "[]",
                "Cannot migrate publications row 1 field destination_data"),
            new InvalidLegacyState("publication_media", "destination_data", "[]",
                "Cannot migrate publication_media row (publication_id=1, media_id=1) field destination_data")
        );

        for (int index = 0; index < invalidStates.size(); index++) {
            InvalidLegacyState invalidState = invalidStates.get(index);
            String schema = "invalid_root_" + index + "_" + UUID.randomUUID().toString().replace("-", "");
            try {
                flyway(schema, MigrationVersion.fromVersion("4")).migrate();
                insertLegacyRelationalState(schema);
                replaceLegacyJson(schema, invalidState);

                assertThatThrownBy(() -> flyway(schema, MigrationVersion.fromVersion("5")).migrate())
                    .hasStackTraceContaining(invalidState.diagnostic());
            } finally {
                dropSchema(schema);
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

    private Set<String> columns(String table) throws SQLException {
        return queryStrings("""
            SELECT column_name
            FROM information_schema.columns
            WHERE table_schema = 'public'
              AND table_name = '%s'
            """.formatted(table));
    }

    private void insertLegacyRelationalState(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.prepareStatement("""
            WITH channel_row AS (
                INSERT INTO %1$s.channels (
                    source_type, source_id, source_uri, source_data, metadata, access_state, subscription_state
                ) VALUES ('TELEGRAM', 'channel-1', 'https://t.me/news', ?::jsonb, ?::jsonb, 'GRANTED', 'ACTIVE')
                RETURNING id
            ), message_row AS (
                INSERT INTO %1$s.messages (
                    channel_id, source_id, published_at, body, source_body_hash, source_data
                )
                SELECT id, 'message-1', now(), 'Body', decode(repeat('01', 32), 'hex'), ?::jsonb
                FROM channel_row
                RETURNING id, channel_id
            ), media_row AS (
                INSERT INTO %1$s.media (message_id, source_id, position, source_hash)
                SELECT id, 'media-1', 0, decode(repeat('02', 32), 'hex')
                FROM message_row
                RETURNING id, message_id
            ), destination_row AS (
                INSERT INTO %1$s.destinations (node_name, config, data)
                VALUES ('news.example', ?::jsonb, ?::jsonb)
                RETURNING id
            ), mirror_row AS (
                INSERT INTO %1$s.mirrors (channel_id, destination_id, config)
                SELECT channel_row.id, destination_row.id, ?::jsonb
                FROM channel_row CROSS JOIN destination_row
                RETURNING id
            ), publication_row AS (
                INSERT INTO %1$s.publications (mirror_id, destination_post_id, destination_data)
                SELECT id, 'post-1', ?::jsonb
                FROM mirror_row
                RETURNING id
            ), member_row AS (
                INSERT INTO %1$s.publication_messages (publication_id, message_id, position)
                SELECT publication_row.id, message_row.id, 0
                FROM publication_row CROSS JOIN message_row
            ), publication_media_row AS (
                INSERT INTO %1$s.publication_media (
                    publication_id, media_id, destination_media_id, destination_data
                )
                SELECT publication_row.id, media_row.id, 'media-destination-1', ?::jsonb
                FROM publication_row CROSS JOIN media_row
                RETURNING publication_id
            )
            SELECT publication_id FROM publication_media_row
            """.formatted(schema))) {
            statement.setString(1, """
                {"initialImportMode":"FULL","username":"news","inviteLink":"https://t.me/+secret",
                 "adapterHint":"preserve"}
                """);
            statement.setString(2, """
                {"title":"News","description":"A😀B <unsafe>\\r\\nhttps://e.x\\n\\nMail a@b.c and @news \\\"quoted\\\"",
                 "descriptionEntities":[
                     {"offset":15,"length":11,"type":"URL"},
                     {"offset":33,"length":5,"type":"EMAIL"},
                     {"offset":43,"length":5,"type":"MENTION"}],
                 "publicUsername":"news","emblem":{"remoteId":"remote-1","uniqueId":"unique-1",
                 "mimeType":"image/jpeg"}}
                """);
            statement.setString(3, """
                {"externalSourceUri":"https://t.me/news/1",
                 "forwardAttribution":{"name":"Origin","url":"https://t.me/origin/2"},"mediaAlbumId":17}
                """);
            statement.setString(4, """
                {"credentialRef":"env:MOERA_ADMIN_TOKEN","profileSync":false}
                """);
            statement.setString(5, """
                {"profile":{"fingerprint":"fingerprint-1","sourceEmblemUniqueId":"unique-1",
                 "avatarId":"avatar-1","avatarMediaId":"avatar-media-1","avatarShape":"circle"}}
                """);
            statement.setString(6, """
                {"feeds":["timeline","news"]}
                """);
            statement.setString(7, """
                {"pinned":true,"storyIds":{"timeline":"story-1","news":"story-2"}}
                """);
            statement.setString(8, """
                {"hash":"hash-1","compressionPending":true}
                """);
            assertThat(statement.executeQuery().next()).isTrue();
        }
    }

    private void updateLegacyRelationalState(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                UPDATE %1$s.channels
                SET source_data = '{"initialImportMode":"RECENT","adapterHint":"preserved-v6"}'::jsonb,
                    metadata = '{"title":"Updated","description":"Updated <safe>"}'::jsonb;
                UPDATE %1$s.messages
                SET source_data = '{"externalSourceUri":"https://t.me/news/2",\
                    "forwardAttribution":{"name":"New origin"},"mediaAlbumId":18}'::jsonb;
                UPDATE %1$s.destinations
                SET config = '{"credentialRef":"env:UPDATED_TOKEN","profileSync":true}'::jsonb,
                    data = '{"profile":{"fingerprint":"fingerprint-2"}}'::jsonb;
                UPDATE %1$s.mirrors SET config = '{"feeds":["other","timeline"]}'::jsonb;
                UPDATE %1$s.publications
                SET destination_data = '{"pinned":false,"storyIds":{"other":"story-3"}}'::jsonb;
                UPDATE %1$s.publication_media
                SET destination_data = '{"hash":"hash-2","compressionPending":false}'::jsonb
                """.formatted(schema));
        }
    }

    private void removeLegacyRelationalState(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement()) {
            statement.executeUpdate("""
                UPDATE %1$s.channels
                SET source_data = '{"adapterHint":"preserved-removal"}'::jsonb,
                    metadata = '{}'::jsonb;
                UPDATE %1$s.messages SET source_data = '{"mediaAlbumId":19}'::jsonb;
                UPDATE %1$s.destinations
                SET config = '{"credentialRef":"env:MOERA_ADMIN_TOKEN"}'::jsonb,
                    data = '{}'::jsonb;
                UPDATE %1$s.mirrors SET config = '{"feeds":["timeline"]}'::jsonb;
                UPDATE %1$s.publications SET destination_data = '{}'::jsonb;
                UPDATE %1$s.publication_media SET destination_data = '{}'::jsonb
                """.formatted(schema));
        }
    }

    private void assertContractedRelationalState(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT initial_import_mode::text, title, description_html, channels.source_data,
                        messages.external_source_uri, messages.forward_name, messages.forward_uri,
                        messages.source_data
                 FROM %1$s.channels
                 JOIN %1$s.messages ON messages.channel_id = channels.id
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("RECENT");
            assertThat(resultSet.getString(2)).isEqualTo("Updated");
            assertThat(resultSet.getString(3)).isEqualTo("<p>Updated &lt;safe&gt;</p>");
            assertThat(resultSet.getString(4)).isEqualTo("{\"adapterHint\": \"preserved-v6\"}");
            assertThat(resultSet.getString(5)).isEqualTo("https://t.me/news/2");
            assertThat(resultSet.getString(6)).isEqualTo("New origin");
            assertThat(resultSet.getString(7)).isNull();
            assertThat(resultSet.getString(8)).isEqualTo("{\"mediaAlbumId\": 18}");
        }
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT credential_ref, profile_sync, profile_fingerprint, avatar_id, mirrors.feeds,
                        publications.destination_pinned, publication_media.destination_hash,
                        publication_media.compression_pending
                 FROM %1$s.destinations
                 JOIN %1$s.mirrors ON mirrors.destination_id = destinations.id
                 JOIN %1$s.publications ON publications.mirror_id = mirrors.id
                 JOIN %1$s.publication_media ON publication_media.publication_id = publications.id
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("env:UPDATED_TOKEN");
            assertThat(resultSet.getBoolean(2)).isTrue();
            assertThat(resultSet.getString(3)).isEqualTo("fingerprint-2");
            assertThat(resultSet.getString(4)).isNull();
            assertThat((String[]) resultSet.getArray(5).getArray()).containsExactly("other", "timeline");
            assertThat(resultSet.getBoolean(6)).isFalse();
            assertThat(resultSet.getString(7)).isEqualTo("hash-2");
            assertThat(resultSet.getBoolean(8)).isFalse();
        }
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT feed_name, story_id FROM %s.publication_stories
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("other");
            assertThat(resultSet.getString(2)).isEqualTo("story-3");
            assertThat(resultSet.next()).isFalse();
        }
    }

    private void assertContractedLegacyRemovals(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT initial_import_mode::text, title, description_html, channels.source_data,
                        messages.external_source_uri, messages.forward_name, messages.forward_uri,
                        messages.source_data
                 FROM %1$s.channels
                 JOIN %1$s.messages ON messages.channel_id = channels.id
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("RECENT");
            assertThat(resultSet.getString(2)).isNull();
            assertThat(resultSet.getString(3)).isNull();
            assertThat(resultSet.getString(4)).isEqualTo("{\"adapterHint\": \"preserved-removal\"}");
            assertThat(resultSet.getString(5)).isNull();
            assertThat(resultSet.getString(6)).isNull();
            assertThat(resultSet.getString(7)).isNull();
            assertThat(resultSet.getString(8)).isEqualTo("{\"mediaAlbumId\": 19}");
        }
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT credential_ref, profile_sync, profile_fingerprint, avatar_id, mirrors.feeds,
                        publications.destination_pinned, publication_media.destination_hash,
                        publication_media.compression_pending,
                        (SELECT count(*) FROM %1$s.publication_stories)
                 FROM %1$s.destinations
                 JOIN %1$s.mirrors ON mirrors.destination_id = destinations.id
                 JOIN %1$s.publications ON publications.mirror_id = mirrors.id
                 JOIN %1$s.publication_media ON publication_media.publication_id = publications.id
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("env:MOERA_ADMIN_TOKEN");
            assertThat(resultSet.getBoolean(2)).isTrue();
            assertThat(resultSet.getString(3)).isNull();
            assertThat(resultSet.getString(4)).isNull();
            assertThat((String[]) resultSet.getArray(5).getArray()).containsExactly("timeline");
            assertThat(resultSet.getObject(6)).isNull();
            assertThat(resultSet.getString(7)).isNull();
            assertThat(resultSet.getBoolean(8)).isFalse();
            assertThat(resultSet.getLong(9)).isZero();
        }
    }

    private void replaceLegacyJson(String schema, InvalidLegacyState invalidState) throws SQLException {
        String sql = "UPDATE %s.%s SET %s = ?::jsonb".formatted(
            schema, invalidState.table(), invalidState.column()
        );
        try (Connection connection = openConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, invalidState.json());
            statement.executeUpdate();
        }
    }

    private void dropSchema(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private void assertChannelBackfill(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT initial_import_mode::text, title, description_html, public_username,
                        emblem_remote_id, emblem_unique_id, emblem_mime_type, source_data ->> 'adapterHint'
                 FROM %s.channels
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("FULL");
            assertThat(resultSet.getString(2)).isEqualTo("News");
            assertThat(resultSet.getString(3)).isEqualTo(
                "<p>A😀B &lt;unsafe&gt;<br><a href=\"https://e.x\">https://e.x</a></p>"
                    + "<p>Mail <a href=\"mailto:a@b.c\">a@b.c</a> and "
                    + "<a href=\"https://t.me/news\">@news</a> &quot;quoted&quot;</p>"
            );
            assertThat(resultSet.getString(4)).isEqualTo("news");
            assertThat(resultSet.getString(5)).isEqualTo("remote-1");
            assertThat(resultSet.getString(6)).isEqualTo("unique-1");
            assertThat(resultSet.getString(7)).isEqualTo("image/jpeg");
            assertThat(resultSet.getString(8)).isEqualTo("preserve");
        }
    }

    private void assertMessageBackfill(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT external_source_uri, forward_name, forward_uri, source_data ->> 'mediaAlbumId'
                 FROM %s.messages
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("https://t.me/news/1");
            assertThat(resultSet.getString(2)).isEqualTo("Origin");
            assertThat(resultSet.getString(3)).isEqualTo("https://t.me/origin/2");
            assertThat(resultSet.getString(4)).isEqualTo("17");
        }
    }

    private void assertDestinationAndMirrorBackfill(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT credential_ref, profile_sync, profile_fingerprint, profile_source_emblem_id,
                        avatar_id, avatar_media_id, avatar_shape, mirrors.feeds
                 FROM %1$s.destinations
                 JOIN %1$s.mirrors ON mirrors.destination_id = destinations.id
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("env:MOERA_ADMIN_TOKEN");
            assertThat(resultSet.getBoolean(2)).isFalse();
            assertThat(resultSet.getString(3)).isEqualTo("fingerprint-1");
            assertThat(resultSet.getString(4)).isEqualTo("unique-1");
            assertThat(resultSet.getString(5)).isEqualTo("avatar-1");
            assertThat(resultSet.getString(6)).isEqualTo("avatar-media-1");
            assertThat(resultSet.getString(7)).isEqualTo("circle");
            assertThat((String[]) resultSet.getArray(8).getArray()).containsExactly("timeline", "news");
        }
    }

    private void assertPublicationBackfill(String schema) throws SQLException {
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT publications.destination_pinned, publication_media.destination_hash,
                        publication_media.compression_pending
                 FROM %1$s.publications
                 JOIN %1$s.publication_media ON publication_media.publication_id = publications.id
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getBoolean(1)).isTrue();
            assertThat(resultSet.getString(2)).isEqualTo("hash-1");
            assertThat(resultSet.getBoolean(3)).isTrue();
        }
        try (Connection connection = openConnection(); var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("""
                 SELECT feed_name, story_id
                 FROM %s.publication_stories
                 ORDER BY feed_name
                 """.formatted(schema))) {
            assertThat(resultSet.next()).isTrue();
            assertThat(List.of(resultSet.getString(1), resultSet.getString(2)))
                .containsExactly("news", "story-2");
            assertThat(resultSet.next()).isTrue();
            assertThat(List.of(resultSet.getString(1), resultSet.getString(2)))
                .containsExactly("timeline", "story-1");
            assertThat(resultSet.next()).isFalse();
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

    private record InvalidLegacyState(String table, String column, String json, String diagnostic) {
    }

}
