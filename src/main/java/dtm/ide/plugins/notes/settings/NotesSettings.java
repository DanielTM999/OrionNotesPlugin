package dtm.ide.plugins.notes.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

public final class NotesSettings {
    private static final String FILE_NAME = "settings.properties";
    private static final String RESTORE_OPEN_NOTES = "restoreOpenNotes";

    private final Path settingsFile;
    private boolean restoreOpenNotes;

    public NotesSettings(Path settingsDirectory) {
        settingsFile = settingsDirectory == null ? null : settingsDirectory.resolve(FILE_NAME);
        load();
    }

    public synchronized boolean isRestoreOpenNotes() {
        return restoreOpenNotes;
    }

    public synchronized void setRestoreOpenNotes(boolean restoreOpenNotes) {
        this.restoreOpenNotes = restoreOpenNotes;
    }

    public synchronized void restoreDefaults() {
        restoreOpenNotes = false;
    }

    public synchronized void load() {
        restoreDefaults();
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            restoreOpenNotes = Boolean.parseBoolean(properties.getProperty(RESTORE_OPEN_NOTES, "false"));
        } catch (IOException ignored) {
            restoreDefaults();
        }
    }

    public synchronized void save() {
        if (settingsFile == null) return;
        Properties properties = new Properties();
        properties.setProperty(RESTORE_OPEN_NOTES, Boolean.toString(restoreOpenNotes));
        Path temporary = null;
        try {
            Files.createDirectories(settingsFile.getParent());
            temporary = settingsFile.resolveSibling(FILE_NAME + "." + UUID.randomUUID() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                properties.store(output, "Orion Notes");
            }
            try {
                Files.move(temporary, settingsFile,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, settingsFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Nao foi possivel salvar as configuracoes do Orion Notes", failure);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }
}
