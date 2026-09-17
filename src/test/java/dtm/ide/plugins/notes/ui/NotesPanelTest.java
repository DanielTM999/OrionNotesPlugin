package dtm.ide.plugins.notes.ui;

import dtm.ide.plugins.notes.store.NotesStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NotesPanelTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void creationWithoutSelectionUsesTheConfiguredDefaultArea() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        AtomicReference<NotesPanel.CreationTarget> created = new AtomicReference<>();
        NotesPanel[] panel = new NotesPanel[1];
        SwingUtilities.invokeAndWait(() -> panel[0] = new NotesPanel(
                store, new ActionsStub("project-a", created), (key, fallback) -> fallback,
                "project-a", "Project A"));

        SwingUtilities.invokeAndWait(panel[0]::createNote);

        assertEquals(new NotesPanel.CreationTarget(null, "project-a"), created.get());
    }

    private static final class ActionsStub implements NotesPanel.Actions {
        private final String defaultProjectId;
        private final AtomicReference<NotesPanel.CreationTarget> created;

        private ActionsStub(String defaultProjectId, AtomicReference<NotesPanel.CreationTarget> created) {
            this.defaultProjectId = defaultProjectId;
            this.created = created;
        }

        @Override
        public void createNote(String parentId, String projectId) {
            created.set(new NotesPanel.CreationTarget(parentId, projectId));
        }

        @Override public void requestCreateFolder(String parentId, String projectId) { }
        @Override public void openNote(String id) { }
        @Override public void requestRename(String id, String currentTitle) { }
        @Override public boolean move(String id, String parentId, String projectId, int order) { return true; }
        @Override public void moveToTrash(String id) { }
        @Override public void restore(String id) { }
        @Override public void requestDeletePermanently(String id, String title) { }
        @Override public void requestEmptyTrash() { }
        @Override public void requestEmptyTrashArea(String projectId, String label) { }
        @Override public void onTreeStateChanged(Set<String> expandedIds, String selectedItemId) { }
        @Override public String defaultProjectId() { return defaultProjectId; }
    }
}
