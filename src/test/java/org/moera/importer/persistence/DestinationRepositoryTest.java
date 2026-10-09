package org.moera.importer.persistence;

import jakarta.inject.Inject;

import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.moera.importer.persistence.jooq.tables.Destinations.DESTINATIONS;

@SpringBootTest
class DestinationRepositoryTest extends PostgresIntegrationTest {

    @Inject
    private DestinationRepository destinationRepository;

    @Inject
    private DSLContext dslContext;

    @BeforeEach
    void clearDatabase() {
        dslContext.truncate(DESTINATIONS).restartIdentity().cascade().execute();
    }

    @ParameterizedTest
    @CsvSource({
        "destination, destination_0",
        "destination_0, destination_0",
        "destination_7, destination_7",
        "destination_007, destination_7"
    })
    void storesExpandedNodeName(String supplied, String expanded) {
        var destination = destinationRepository.create(supplied, false);

        assertThat(destination.nodeName()).isEqualTo(expanded);
        assertThat(destination.profileSync()).isFalse();
        assertThat(dslContext.select(DESTINATIONS.NODE_NAME).from(DESTINATIONS).fetchSingle().value1())
            .isEqualTo(expanded);
        assertThat(destinationRepository.find(destination.id()).orElseThrow().nodeName()).isEqualTo(expanded);
    }

    @ParameterizedTest
    @CsvSource({ "destination, destination_0", "destination_0, destination" })
    void shortAndExpandedNamesIdentifyTheSameDestination(String first, String second) {
        destinationRepository.create(first, true);

        assertThatThrownBy(() -> destinationRepository.create(second, true))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(destinationRepository.findAll()).hasSize(1);
    }

}
