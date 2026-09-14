package dtm.ide.plugins.notes.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesSettingsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void doesNotRestoreOpenNotesByDefault() {
        assertFalse(new NotesSettings(temporaryDirectory).isRestoreOpenNotes());
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
        settings.restoreDefaults();

        assertFalse(settings.isRestoreOpenNotes());
    }
}
