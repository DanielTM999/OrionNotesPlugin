package dtm.ide.plugins.notes.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesSettingsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void doesNotRestoreOpenNotesByDefault() {
        NotesSettings settings = new NotesSettings(temporaryDirectory);

        assertFalse(settings.isRestoreOpenNotes());
        assertEquals(24, settings.getTrashRetentionHours());
    }

    @Test
    void persistsRestoreOpenNotesChoice() {
        NotesSettings settings = new NotesSettings(temporaryDirectory);
        settings.setRestoreOpenNotes(true);
        settings.save();

        assertTrue(new NotesSettings(temporaryDirectory).isRestoreOpenNotes());
    }

    @Test
    void restoresDefaultChoice() {
        NotesSettings settings = new NotesSettings(temporaryDirectory);
        settings.setRestoreOpenNotes(true);
        settings.setTrashRetentionHours(1);
        settings.restoreDefaults();

        assertFalse(settings.isRestoreOpenNotes());
        assertEquals(24, settings.getTrashRetentionHours());
    }

    @Test
    void persistsTrashRetentionAndForeverChoice() {
        NotesSettings settings = new NotesSettings(temporaryDirectory);
        settings.setTrashRetentionHours(72);
        settings.save();

        assertEquals(72, new NotesSettings(temporaryDirectory).getTrashRetentionHours());

        settings.setTrashRetentionHours(NotesSettings.KEEP_TRASH_FOREVER);
        settings.save();

        assertEquals(NotesSettings.KEEP_TRASH_FOREVER,
                new NotesSettings(temporaryDirectory).getTrashRetentionHours());
    }

    @Test
    void rejectsRetentionBelowOneHourUnlessItIsForever() {
        NotesSettings settings = new NotesSettings(temporaryDirectory);

        assertThrows(IllegalArgumentException.class, () -> settings.setTrashRetentionHours(-1));
    }
}
