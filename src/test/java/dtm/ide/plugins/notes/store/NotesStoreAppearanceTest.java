package dtm.ide.plugins.notes.store;

import dtm.ide.plugins.notes.model.NoteItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NotesStoreAppearanceTest {
    @TempDir Path root;

    @Test
    void persistsOnlyOverridesWithoutChangingNoteMetadataOrContent() throws Exception {
        NotesStore store = new NotesStore(root);
        NoteItem first = store.createNote(null);
        NoteItem second = store.createNote(null);
        NoteItem saved = store.saveContent(first.getId(), "Linha longa ".repeat(80), first.getRevision()).item();
        byte[] content = store.loadNote(first.getId()).data();
        byte[] index = Files.readAllBytes(root.resolve("index.bin"));
        assertTrue(store.loadEditorAppearance(first.getId()).getOptions().isEmpty());
        assertFalse(Files.exists(root.resolve("editor-appearance.bin")));

        store.setEditorAppearanceOption(first.getId(), "lineWrap", true);
        store.setEditorAppearanceOption(second.getId(), "lineWrap", false);
        // Outra janela altera uma opcao diferente sem sobrescrever as anteriores.
        NotesStore other = new NotesStore(root);
        other.setEditorAppearanceOption(first.getId(), "lineNumbers", false);
        store.setEditorAppearanceOption(first.getId(), "currentLine", false);

        NotesStore restarted = new NotesStore(root);
        assertEquals(Map.of("lineWrap", true, "lineNumbers", false, "currentLine", false),
                restarted.loadEditorAppearance(first.getId()).getOptions());
        assertEquals(Map.of("lineWrap", false), restarted.loadEditorAppearance(second.getId()).getOptions());
        assertArrayEquals(content, restarted.loadNote(first.getId()).data());
        assertArrayEquals(index, Files.readAllBytes(root.resolve("index.bin")));
        assertEquals(saved.getRevision(), restarted.find(first.getId()).orElseThrow().getRevision());

        restarted.loadEditorAppearance(first.getId()).getOptions().clear();
        assertEquals(3, restarted.loadEditorAppearance(first.getId()).getOptions().size());
    }

    @Test
    void retainsAppearanceInTrashAndAfterRestore() throws Exception {
        NotesStore store = new NotesStore(root);
        NoteItem note = store.createNote(null);
        store.setEditorAppearanceOption(note.getId(), "lineWrap", true);
        store.moveToTrash(note.getId());
        assertEquals(Map.of("lineWrap", true), store.loadEditorAppearance(note.getId()).getOptions());
        store.restore(note.getId());
        assertEquals(Map.of("lineWrap", true), new NotesStore(root).loadEditorAppearance(note.getId()).getOptions());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void everyPermanentDeletionPathRemovesAppearanceAndIgnoresLateWrites(int path) throws Exception {
        NotesStore store = new NotesStore(root);
        NoteItem folder = store.createFolder(null, "Pasta");
        NoteItem note = store.createNote(folder.getId());
        NoteItem retained = store.createNote(null);
        store.setEditorAppearanceOption(note.getId(), "lineWrap", true);
        store.setEditorAppearanceOption(retained.getId(), "lineNumbers", false);
        store.moveToTrash(folder.getId());
        switch (path) {
            case 0 -> store.deletePermanently(folder.getId());
            case 1 -> store.emptyTrash();
            case 2 -> store.emptyTrash(null);
            case 3 -> store.purgeExpiredTrash(Duration.ofHours(1), Instant.now().plus(Duration.ofHours(2)));
        }
        store.setEditorAppearanceOption(note.getId(), "lineWrap", true);
        NotesStore restarted = new NotesStore(root);
        assertTrue(restarted.loadEditorAppearance(note.getId()).getOptions().isEmpty());
        assertEquals(Map.of("lineNumbers", false), restarted.loadEditorAppearance(retained.getId()).getOptions());
        assertTrue(restarted.find(note.getId()).isEmpty());
    }

    @Test
    void recoversCorruptAppearanceFileWithoutAffectingNotes() throws Exception {
        NotesStore store = new NotesStore(root);
        NoteItem note = store.createNote(null);
        Files.writeString(root.resolve("editor-appearance.bin"), "invalid preferences");
        assertTrue(store.loadEditorAppearance(note.getId()).getOptions().isEmpty());
        try (var files = Files.list(root)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString().startsWith("editor-appearance.bin.corrupt-")));
        }
        store.setEditorAppearanceOption(note.getId(), "lineWrap", true);
        assertEquals(Map.of("lineWrap", true), new NotesStore(root).loadEditorAppearance(note.getId()).getOptions());
        assertEquals("", store.loadNote(note.getId()).content());
    }
}
