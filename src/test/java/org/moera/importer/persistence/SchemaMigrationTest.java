package org.moera.importer.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class SchemaMigrationTest {

    private static final PostgreSQLContainer POSTGRES = startPostgres();

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SchemaMigrationTest::jdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

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
            "media",
            "destinations",
            "mirrors",
            "publications",
            "publication_messages",
            "publication_media"
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
        return DriverManager.getConnection(jdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static PostgreSQLContainer postgresContainer() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:18.6-alpine");
        String host = System.getProperty("test.postgres.host");
        Integer hostPort = Integer.getInteger("test.postgres.host-port");
        if (host != null && !host.isBlank() && hostPort != null) {
            container.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
                new PortBinding(
                    Ports.Binding.bindIpAndPort(host, hostPort),
                    ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT)
                )
            ));
        }
        return container;
    }

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = postgresContainer();
        container.start();
        return container;
    }

    private static String jdbcUrl() {
        String host = System.getProperty("test.postgres.host");
        if (host == null || host.isBlank()) {
            return POSTGRES.getJdbcUrl();
        }
        String jdbcHost = host.contains(":") ? "[" + host + "]" : host;
        return "jdbc:postgresql://%s:%d/%s".formatted(
            jdbcHost,
            POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
            POSTGRES.getDatabaseName()
        );
    }
}
