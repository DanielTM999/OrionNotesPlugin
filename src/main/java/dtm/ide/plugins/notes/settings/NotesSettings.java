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
    public static final long KEEP_TRASH_FOREVER = 0;
    public static final long DEFAULT_TRASH_RETENTION_HOURS = 24;

    private static final String FILE_NAME = "settings.properties";
    private static final String RESTORE_OPEN_NOTES = "restoreOpenNotes";
    private static final String TRASH_RETENTION_HOURS = "trashRetentionHours";

    private final Path settingsFile;
    private boolean restoreOpenNotes;
    private long trashRetentionHours;

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

    public synchronized long getTrashRetentionHours() {
        return trashRetentionHours;
    }

    public synchronized void setTrashRetentionHours(long trashRetentionHours) {
        if (trashRetentionHours != KEEP_TRASH_FOREVER && trashRetentionHours < 1) {
            throw new IllegalArgumentException("O prazo da lixeira deve ser de pelo menos 1 hora");
        }
        this.trashRetentionHours = trashRetentionHours;
    }

    public synchronized void restoreDefaults() {
        restoreOpenNotes = false;
        trashRetentionHours = DEFAULT_TRASH_RETENTION_HOURS;
    }

    public synchronized void load() {
        restoreDefaults();
        if (settingsFile == null || !Files.isRegularFile(settingsFile)) return;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            restoreOpenNotes = Boolean.parseBoolean(properties.getProperty(RESTORE_OPEN_NOTES, "false"));
            trashRetentionHours = parseTrashRetentionHours(properties.getProperty(TRASH_RETENTION_HOURS));
        } catch (IOException ignored) {
            restoreDefaults();
        }
    }

    public synchronized void save() {
        if (settingsFile == null) return;
        Properties properties = new Properties();
        properties.setProperty(RESTORE_OPEN_NOTES, Boolean.toString(restoreOpenNotes));
        properties.setProperty(TRASH_RETENTION_HOURS, Long.toString(trashRetentionHours));
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

    private static long parseTrashRetentionHours(String value) {
        if (value == null || value.isBlank()) return DEFAULT_TRASH_RETENTION_HOURS;
        try {
            long hours = Long.parseLong(value);
            return hours == KEEP_TRASH_FOREVER || hours >= 1 ? hours : DEFAULT_TRASH_RETENTION_HOURS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_TRASH_RETENTION_HOURS;
        }
    }
}
