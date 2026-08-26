package org.moera.importer.persistence;

import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = startPostgres();

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop, "postgres-testcontainer-stop"));
    }

    @DynamicPropertySource
    static void configureDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PostgresIntegrationTest::jdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("moera.workers.scheduling-enabled", () -> false);
    }

    private static PostgreSQLContainer startPostgres() {
        PostgreSQLContainer container = new PostgreSQLContainer("postgres:18.6-alpine");
        String host = System.getProperty("test.postgres.host");
        Integer hostPort = Integer.getInteger("test.postgres.host-port");
        if (host != null && !host.isBlank() && hostPort != null) {
            container.withCreateContainerCmdModifier(PostgresIntegrationTest::configureContainer);
        }
        container.start();
        return container;
    }

    private static void configureContainer(CreateContainerCmd command) {
        String host = System.getProperty("test.postgres.host");
        int hostPort = Integer.getInteger("test.postgres.host-port");
        command.getHostConfig().withPortBindings(new PortBinding(
            Ports.Binding.bindIpAndPort(host, hostPort),
            ExposedPort.tcp(PostgreSQLContainer.POSTGRESQL_PORT)
        ));
    }

    protected static PostgreSQLContainer postgres() {
        return POSTGRES;
    }

    protected static String jdbcUrl() {
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
