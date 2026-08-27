package org.moera.importer.moera;

public class MoeraMediaTooLargeException extends MoeraPermanentException {

    public MoeraMediaTooLargeException(Throwable cause) {
        super("Moera rejected media because it is too large", cause);
    }

}
