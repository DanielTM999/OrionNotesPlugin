package dtm.ide.plugins.notes.store;

import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NotesSessionState;
import dtm.ide.plugins.notes.model.TitleMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsPersistsSearchesAndReloadsNotes() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem folder = store.createFolder(null, "Ideias");
        NoteItem note = store.createNote(folder.getId());

        NotesStore.SaveResult saved = store.saveContent(note.getId(), "Primeira linha\nconteudo pesquisavel", note.getRevision());

        assertFalse(saved.hasConflict());
        assertEquals("Primeira linha", saved.item().getTitle());
        assertEquals(1, saved.item().getRevision());
        assertEquals(List.of(note.getId()), store.search("pesquisavel", false).stream().map(NoteItem::getId).toList());

        NotesStore reloaded = new NotesStore(temporaryDirectory);
        assertEquals("Primeira linha\nconteudo pesquisavel", reloaded.loadNote(note.getId()).content());
        assertEquals(folder.getId(), reloaded.find(note.getId()).orElseThrow().getParentId());
    }

    @Test
    void manualRenameStopsAutomaticTitleChanges() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem note = store.createNote(null);
        NoteItem renamed = store.rename(note.getId(), "Meu titulo");
        NotesStore.SaveResult saved = store.saveContent(note.getId(), "Outra primeira linha", renamed.getRevision());

        assertEquals(TitleMode.MANUAL, saved.item().getTitleMode());
        assertEquals("Meu titulo", saved.item().getTitle());
    }

    @Test
    void trashAndRestorePreserveFolderSubtree() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem parent = store.createFolder(null, "Pai");
        NoteItem child = store.createFolder(parent.getId(), "Filha");
        NoteItem note = store.createNote(child.getId());

        store.moveToTrash(parent.getId());
        assertTrue(store.find(note.getId()).orElseThrow().isDeleted());

        store.restore(parent.getId());
        assertFalse(store.find(parent.getId()).orElseThrow().isDeleted());
        assertFalse(store.find(child.getId()).orElseThrow().isDeleted());
        assertFalse(store.find(note.getId()).orElseThrow().isDeleted());
    }

    @Test
    void preventsFolderCycles() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem parent = store.createFolder(null, "Pai");
        NoteItem child = store.createFolder(parent.getId(), "Filha");

        assertThrows(IllegalArgumentException.class, () -> store.move(parent.getId(), child.getId(), 0));
    }

    @Test
    void staleRevisionCreatesConflictCopy() throws Exception {
        NotesStore first = new NotesStore(temporaryDirectory);
        NoteItem note = first.createNote(null);
        NotesStore second = new NotesStore(temporaryDirectory);

        NotesStore.SaveResult current = first.saveContent(note.getId(), "externo", note.getRevision());
        NotesStore.SaveResult conflict = second.saveContent(note.getId(), "local", note.getRevision());

        assertEquals("externo", first.loadNote(note.getId()).content());
        assertTrue(conflict.hasConflict());
        assertEquals("local", first.loadNote(conflict.conflictCopy().getId()).content());
        assertEquals(current.item().getRevision(), conflict.item().getRevision());
    }

    @Test
    void sessionIsStoredPerContext() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NotesSessionState state = new NotesSessionState();
        state.setOpenNoteIds(List.of("a", "b"));
        state.setActiveNoteId("b");
        state.setExpandedFolderIds(Set.of("folder"));
        store.saveSession("project-a", state);

        NotesStore reloaded = new NotesStore(temporaryDirectory);
        assertEquals(List.of("a", "b"), reloaded.loadSession("project-a").getOpenNoteIds());
        assertTrue(reloaded.loadSession("project-b").getOpenNoteIds().isEmpty());
    }

    @Test
    void corruptIndexIsPreservedAndContentsAreRecovered() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem note = store.createNote(null);
        store.saveContent(note.getId(), "Recuperada", note.getRevision());
        Files.writeString(temporaryDirectory.resolve("index.json"), "{invalido", StandardCharsets.UTF_8);

        NotesStore recovered = new NotesStore(temporaryDirectory);

        assertEquals(1, recovered.list(false).size());
        assertEquals("Recuperada", recovered.list(false).getFirst().getTitle());
        try (var files = Files.list(temporaryDirectory)) {
            assertNotNull(files.filter(path -> path.getFileName().toString().startsWith("index.json.corrupt-")).findFirst().orElse(null));
        }
    }
}
