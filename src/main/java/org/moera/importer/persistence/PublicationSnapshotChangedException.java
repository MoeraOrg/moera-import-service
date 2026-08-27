package org.moera.importer.persistence;

public class PublicationSnapshotChangedException extends RuntimeException {

    public PublicationSnapshotChangedException() {
        super("Publication revision changed before its snapshot was loaded");
    }

}
