package dtm.ide.plugins.notes.store;

import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NotesIndex;
import dtm.ide.plugins.notes.model.NotesSessionState;
import dtm.ide.plugins.notes.model.TitleMode;
import dtm.serialization.BinaryObjectSerializer;
import dtm.serialization.mapper.BinaryObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
    void permanentlyDeletesTrashWhenRetentionExpires() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem folder = store.createFolder(null, "Expirada");
        NoteItem note = store.createNote(folder.getId());
        store.saveContent(note.getId(), "conteudo", note.getRevision());
        store.moveToTrash(folder.getId());
        Instant deletedAt = Instant.parse(store.find(folder.getId()).orElseThrow().getDeletedAt());

        assertEquals(0, store.purgeExpiredTrash(Duration.ofHours(1), deletedAt.plusSeconds(3599)));
        assertTrue(store.find(note.getId()).isPresent());

        assertEquals(2, store.purgeExpiredTrash(Duration.ofHours(1), deletedAt.plusSeconds(3600)));
        assertTrue(store.find(folder.getId()).isEmpty());
        assertTrue(store.find(note.getId()).isEmpty());
        assertFalse(Files.exists(temporaryDirectory.resolve("content").resolve(note.getId() + ".note")));
    }

    @Test
    void rejectsNonPositiveTrashRetention() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);

        assertThrows(IllegalArgumentException.class,
                () -> store.purgeExpiredTrash(Duration.ZERO, Instant.now()));
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
    void showsGlobalAndCurrentProjectWhileHidingOtherProjects() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem global = store.createNote(null, null);
        NoteItem projectA = store.createNote(null, "C:\\projects\\a");
        NoteItem projectB = store.createNote(null, "C:\\projects\\b");
        store.saveContent(projectA.getId(), "termo projeto a", projectA.getRevision());
        store.saveContent(projectB.getId(), "termo projeto b", projectB.getRevision());

        assertEquals(Set.of(global.getId(), projectA.getId()), store.listVisible("C:\\projects\\a").stream()
                .map(NoteItem::getId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(List.of(projectA.getId()), store.searchVisible("projeto a", "C:\\projects\\a").stream()
                .map(NoteItem::getId).toList());
        assertTrue(store.searchVisible("projeto b", "C:\\projects\\a").isEmpty());
    }

    @Test
    void movingFolderBetweenAreasTransfersTheWholeSubtree() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem folder = store.createFolder(null, null, "Global");
        NoteItem child = store.createNote(folder.getId(), null);
        NoteItem firstProjectItem = store.createNote(null, "C:\\projects\\a");

        NoteItem moved = store.move(folder.getId(), null, "C:\\projects\\a", 0);

        assertEquals("C:\\projects\\a", moved.getProjectId());
        assertEquals("C:\\projects\\a", store.find(child.getId()).orElseThrow().getProjectId());
        assertEquals(0, moved.getOrder());
        assertEquals(1, store.find(firstProjectItem.getId()).orElseThrow().getOrder());
    }

    @Test
    void trashContainsEveryProjectAndRestoreKeepsTheOrigin() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem global = store.createNote(null, null);
        NoteItem projectA = store.createNote(null, "C:\\projects\\a");
        NoteItem projectB = store.createNote(null, "C:\\projects\\b");
        store.moveToTrash(global.getId());
        store.moveToTrash(projectA.getId());
        store.moveToTrash(projectB.getId());

        assertEquals(Set.of(global.getId(), projectA.getId(), projectB.getId()), store.listTrash().stream()
                .map(NoteItem::getId).collect(java.util.stream.Collectors.toSet()));

        store.restore(projectB.getId());
        assertEquals("C:\\projects\\b", store.find(projectB.getId()).orElseThrow().getProjectId());
        assertFalse(store.listVisible("C:\\projects\\a").stream()
                .anyMatch(item -> item.getId().equals(projectB.getId())));
    }

    @Test
    void upgradesSchemaOneItemsAsGlobalOnTheNextWrite() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem legacy = store.createNote(null);
        BinaryObjectSerializer serializer = new BinaryObjectMapper();
        Path indexFile = temporaryDirectory.resolve("index.bin");
        NotesIndex index = serializer.readAsObject(Files.readAllBytes(indexFile), NotesIndex.class);
        index.setSchemaVersion(1);
        Files.write(indexFile, serializer.encodeToByteArray(index));

        NotesStore reloaded = new NotesStore(temporaryDirectory);
        assertEquals(legacy.getId(), reloaded.listVisible("C:\\projects\\a").getFirst().getId());
        assertEquals(null, reloaded.find(legacy.getId()).orElseThrow().getProjectId());

        reloaded.createNote(null, "C:\\projects\\a");
        NotesIndex upgraded = serializer.readAsObject(Files.readAllBytes(indexFile), NotesIndex.class);
        assertEquals(NotesStore.SCHEMA_VERSION, upgraded.getSchemaVersion());
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
    void storesIndexAndSessionAsBinaryFilesWithoutJson() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        store.createNote(null);
        store.saveSession("project-a", new NotesSessionState());

        assertTrue(Files.exists(temporaryDirectory.resolve("index.bin")));
        assertTrue(Files.exists(temporaryDirectory.resolve("session.bin")));
        assertFalse(Files.exists(temporaryDirectory.resolve("index.json")));
        assertFalse(Files.exists(temporaryDirectory.resolve("session.json")));
    }

    @Test
    void corruptIndexIsPreservedAndContentsAreRecovered() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem note = store.createNote(null);
        store.saveContent(note.getId(), "Recuperada", note.getRevision());
        Files.write(temporaryDirectory.resolve("index.bin"), new byte[]{0x01, 0x02, 0x03});

        NotesStore recovered = new NotesStore(temporaryDirectory);

        assertEquals(1, recovered.list(false).size());
        assertEquals("Recuperada", recovered.list(false).getFirst().getTitle());
        try (var files = Files.list(temporaryDirectory)) {
            assertNotNull(files.filter(path -> path.getFileName().toString().startsWith("index.bin.corrupt-")).findFirst().orElse(null));
        }
    }

    @Test
    void corruptSessionIsPreservedAndReplacedByEmptyState() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NotesSessionState state = new NotesSessionState();
        state.setOpenNoteIds(List.of("a"));
        store.saveSession("project-a", state);
        Files.write(temporaryDirectory.resolve("session.bin"), new byte[]{0x01, 0x02, 0x03});

        assertTrue(store.loadSession("project-a").getOpenNoteIds().isEmpty());
        try (var files = Files.list(temporaryDirectory)) {
            assertNotNull(files.filter(path -> path.getFileName().toString().startsWith("session.bin.corrupt-"))
                    .findFirst().orElse(null));
        }
    }
}
