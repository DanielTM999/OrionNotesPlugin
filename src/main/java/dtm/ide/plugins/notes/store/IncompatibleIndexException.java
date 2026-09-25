package dtm.ide.plugins.notes.store;

import java.io.IOException;

/** O indice foi gravado por uma versao mais nova do plugin; ele nao esta corrompido e deve ser preservado. */
public final class IncompatibleIndexException extends IOException {
    private final int schemaVersion;

    public IncompatibleIndexException(int schemaVersion, int supportedVersion) {
        super("O indice de notas usa a versao " + schemaVersion + ", mas esta versao do plugin suporta ate a "
                + supportedVersion + ". Atualize o Orion Notes.");
        this.schemaVersion = schemaVersion;
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }
}
