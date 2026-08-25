package org.moera.importer.application;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

    private static final byte[] INPUT = "abc".getBytes(StandardCharsets.UTF_8);

    @Test
    void producesThirtyTwoByteDigest() {
        assertThat(Sha256.digest(INPUT)).hasSize(32);
    }

    @Test
    void producesLowerCaseHexDigest() {
        assertThat(Sha256.hex(INPUT))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
            .hasSize(64);
    }

}
