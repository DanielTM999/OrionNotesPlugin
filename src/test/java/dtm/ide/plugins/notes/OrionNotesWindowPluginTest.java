package dtm.ide.plugins.notes;

import dtm.ide.plugins.notes.store.NotesStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrionNotesWindowPluginTest {

    @Test
    void keepsShortTabTitlesUnchanged() {
        assertEquals("Titulo curto", OrionNotesWindowPlugin.tabTitle("Titulo curto"));
    }

    @Test
    void truncatesLongTabTitlesWithEllipsis() {
        String title = "Uma primeira linha muito longa que nao deve ocupar toda a barra de abas";

        assertEquals("Uma primeira linha muito longa que…", OrionNotesWindowPlugin.tabTitle(title));
    }

    @Test
    void usesUntitledFallbackForMissingTitles() {
        assertEquals(NotesStore.UNTITLED, OrionNotesWindowPlugin.tabTitle(null));
        assertEquals(NotesStore.UNTITLED, OrionNotesWindowPlugin.tabTitle("   "));
    }

    @Test
    void recognizesArgumentsThatRequestANewNote() {
        assertTrue(OrionNotesWindowPlugin.hasNewNoteArgument(List.of("notepad")));
        assertTrue(OrionNotesWindowPlugin.hasNewNoteArgument(List.of("-n")));
        assertTrue(OrionNotesWindowPlugin.hasNewNoteArgument(List.of("NOTEPAD")));
    }

    @Test
    void ignoresUnrelatedOrMissingArguments() {
        assertFalse(OrionNotesWindowPlugin.hasNewNoteArgument(List.of("--help")));
        assertFalse(OrionNotesWindowPlugin.hasNewNoteArgument(Arrays.asList(null, "")));
        assertFalse(OrionNotesWindowPlugin.hasNewNoteArgument(null));
    }
}
