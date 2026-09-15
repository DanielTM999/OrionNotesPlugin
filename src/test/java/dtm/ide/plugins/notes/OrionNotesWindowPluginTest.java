package dtm.ide.plugins.notes;

import dtm.ide.plugins.notes.store.NotesStore;
import dtm.ide.plugins.notes.model.NoteItem;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    @Test
    void derivesProjectIdentityAndLabelFromTheNormalizedPath() {
        Path project = Path.of("projects", "sample", "..", "notes-project");

        assertEquals(project.toAbsolutePath().normalize().toString(), OrionNotesWindowPlugin.projectId(project));
        assertEquals("notes-project", OrionNotesWindowPlugin.projectLabel(project));
    }

    @Test
    void globalNotesAreAlwaysVisibleButProjectNotesAreContextual() {
        NoteItem global = new NoteItem();
        NoteItem project = new NoteItem();
        project.setProjectId("project-a");

        assertTrue(OrionNotesWindowPlugin.isVisibleInProject(global, "project-b"));
        assertTrue(OrionNotesWindowPlugin.isVisibleInProject(project, "project-a"));
        assertFalse(OrionNotesWindowPlugin.isVisibleInProject(project, "project-b"));
    }
}
