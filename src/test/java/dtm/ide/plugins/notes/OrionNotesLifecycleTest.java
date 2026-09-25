package dtm.ide.plugins.notes;

import dtm.ide.api.extension.NotificationContext;
import dtm.ide.api.extension.Resource;
import dtm.ide.api.extension.screen.ManagedCenterTabHandle;
import dtm.ide.api.extension.screen.ManagedCenterTabRequest;
import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NoteType;
import dtm.ide.plugins.notes.settings.NotesSettings;
import dtm.ide.plugins.notes.store.NoteDocuments;
import dtm.ide.plugins.notes.store.NotesStore;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.sheet.SheetEditor;
import dtm.stools.component.panels.editor.word.WordEditor;
import dtm.stools.component.panels.editor.word.model.WordDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class OrionNotesLifecycleTest {

    @TempDir
    Path shared;

    @Test
    void documentEditorsSurviveClosedTabsAndAreClosedOnlyOnUnload() throws Exception {
        Path projectA = shared.resolve("project-a");
        Path projectB = shared.resolve("project-b");
        NotesStore seed = new NotesStore(shared.resolve("orion-notes"));
        NoteItem word = seed.createDocument(null, null, NoteType.WORD, "Ata",
                NoteDocuments.emptyContent(NoteType.WORD));
        NoteItem sheet = seed.createDocument(null, OrionNotesWindowPlugin.projectId(projectA), NoteType.SHEET,
                "Custos", NoteDocuments.emptyContent(NoteType.SHEET));
        HostedPlugin plugin = new HostedPlugin(shared, projectA);
        plugin.onLoad();

        plugin.openNote(word.getId());
        FakeTab wordTab = plugin.awaitTab(word.getId(), 1);
        WordEditor wordEditor = find(wordTab.request.component(), WordEditor.class);
        onEdt(() -> { wordEditor.insertText("Decisoes da reuniao "); return null; });
        waitUntil(() -> seed.search("decisoes", false).stream().anyMatch(item -> item.getId().equals(word.getId())));
        waitUntil(() -> onEdt(() -> !wordEditor.isDirty()));
        assertEquals("Ata", seed.find(word.getId()).orElseThrow().getTitle());

        // Fechar a aba conserva a instancia e a retira da sessao de abas abertas.
        onEdt(() -> { wordTab.close(); return null; });
        assertFalse(wordEditor.isClosed());
        String sessionContext = OrionNotesWindowPlugin.projectId(projectA);
        waitUntil(() -> !seed.loadSession(sessionContext).getOpenNoteIds().contains(word.getId()));

        plugin.openNote(word.getId());
        FakeTab reopened = plugin.awaitTab(word.getId(), 2);
        assertSame(wordTab.request.component(), reopened.request.component());
        assertFalse(wordEditor.isClosed());
        assertTrue(onEdt(wordEditor::getText).contains("Decisoes da reuniao"));

        plugin.openNote(sheet.getId());
        FakeTab sheetTab = plugin.awaitTab(sheet.getId(), 1);
        SheetEditor sheetEditor = find(sheetTab.request.component(), SheetEditor.class);
        onEdt(() -> { sheetEditor.input("A1", "Aluguel"); return null; });

        // Trocar de projeto fecha a aba da planilha do projeto anterior sem encerrar o editor.
        plugin.onProjectOpen(projectB);
        waitUntil(() -> !sheetTab.open);
        assertFalse(sheetEditor.isClosed());
        assertTrue(reopened.open, "a nota global continua aberta");
        waitUntil(() -> seed.search("aluguel", false).stream().anyMatch(item -> item.getId().equals(sheet.getId())));

        onEdt(() -> { wordEditor.insertText("Pendente "); return null; });
        plugin.onUnload();

        assertTrue(wordEditor.isClosed());
        assertTrue(sheetEditor.isClosed(), "instancias sem aba tambem sao encerradas no unload");
        NotesStore reloaded = new NotesStore(shared.resolve("orion-notes"));
        String saved = NoteDocuments.readWord(reloaded.loadNote(word.getId()).data()).document().text();
        assertTrue(saved.contains("Decisoes da reuniao") && saved.contains("Pendente"), saved);
        assertEquals(List.of(), plugin.notifications);
    }

    @Test
    void restartRestoresTabsOfEveryTypeWithNewEditorInstances() throws Exception {
        Path root = shared.resolve("orion-notes");
        NotesSettings settings = new NotesSettings(root);
        settings.setRestoreOpenNotes(true);
        settings.save();
        NotesStore seed = new NotesStore(root);
        NoteItem text = seed.createNote(null);
        seed.saveContent(text.getId(), "Nota de texto", text.getRevision());
        NoteItem word = seed.createDocument(null, null, NoteType.WORD, "Documento",
                NoteDocuments.encodeWord(WordDocument.fromText("Conteudo do documento"), null));
        NoteItem sheet = seed.createDocument(null, null, NoteType.SHEET, "Planilha",
                NoteDocuments.emptyContent(NoteType.SHEET));

        HostedPlugin first = new HostedPlugin(shared, null);
        first.onLoad();
        first.openNote(text.getId());
        first.awaitTab(text.getId(), 1);
        first.openNote(word.getId());
        FakeTab firstWord = first.awaitTab(word.getId(), 1);
        first.openNote(sheet.getId());
        FakeTab firstSheet = first.awaitTab(sheet.getId(), 1);
        SheetEditor firstSheetEditor = find(firstSheet.request.component(), SheetEditor.class);
        onEdt(() -> { firstSheetEditor.input("B2", "Salvo no unload"); return null; });
        waitUntil(() -> seed.loadSession("no-project").getOpenNoteIds().size() == 3);
        first.onUnload();
        assertTrue(firstSheetEditor.isClosed());

        HostedPlugin second = new HostedPlugin(shared, null);
        second.onLoad();
        FakeTab restoredText = second.awaitTab(text.getId(), 1);
        FakeTab restoredWord = second.awaitTab(word.getId(), 1);
        FakeTab restoredSheet = second.awaitTab(sheet.getId(), 1);

        assertEquals("Nota de texto", onEdt(() -> ((CodeEditor) restoredText.request.component()).getText()));
        assertNotSame(firstWord.request.component(), restoredWord.request.component());
        WordEditor restoredWordEditor = find(restoredWord.request.component(), WordEditor.class);
        assertFalse(restoredWordEditor.isClosed());
        assertTrue(onEdt(restoredWordEditor::getText).contains("Conteudo do documento"));
        SheetEditor restoredSheetEditor = find(restoredSheet.request.component(), SheetEditor.class);
        assertEquals("Salvo no unload", onEdt(() -> restoredSheetEditor.getText("B2")));

        second.onUnload();
        assertTrue(restoredWordEditor.isClosed());
        assertEquals(List.of(), first.notifications);
        assertEquals(List.of(), second.notifications);
    }

    @Test
    void externalChangeKeepsTheLocalVersionAsACopyAndReloadsTheNoteOnReopen() throws Exception {
        NotesStore seed = new NotesStore(shared.resolve("orion-notes"));
        NoteItem word = seed.createDocument(null, null, NoteType.WORD, "Contrato",
                NoteDocuments.encodeWord(WordDocument.fromText("Original"), null));
        HostedPlugin plugin = new HostedPlugin(shared, null);
        plugin.onLoad();
        plugin.openNote(word.getId());
        FakeTab wordTab = plugin.awaitTab(word.getId(), 1);
        WordEditor wordEditor = find(wordTab.request.component(), WordEditor.class);

        // Outra janela da IDE grava a mesma nota.
        seed.saveDocument(word.getId(), NoteDocuments.encodeWord(WordDocument.fromText("Externa"), null),
                word.getRevision());
        onEdt(() -> { wordEditor.insertText("Local "); return null; });

        waitUntil(() -> !wordTab.open);
        NoteItem copy = seed.list(false).stream()
                .filter(item -> !item.getId().equals(word.getId())).findFirst().orElseThrow();
        assertEquals(NoteType.WORD, copy.getType());
        assertTrue(copy.getTitle().startsWith("Contrato (conflito"));
        assertTrue(NoteDocuments.readWord(seed.loadNote(copy.getId()).data()).document().text().contains("Local"));
        plugin.awaitTab(copy.getId(), 1);
        assertFalse(wordEditor.isClosed());

        plugin.openNote(word.getId());
        FakeTab reopened = plugin.awaitTab(word.getId(), 2);
        assertSame(wordTab.request.component(), reopened.request.component());
        assertEquals("Externa", onEdt(wordEditor::getText).strip());
        assertFalse(onEdt(wordEditor::isDirty));

        plugin.onUnload();
        assertTrue(wordEditor.isClosed());
        assertEquals(1, plugin.notifications.size(), String.valueOf(plugin.notifications));
        assertTrue(plugin.notifications.getFirst().startsWith("Conflito de nota"));
    }

    @Test
    void pendingEditsSurviveTrashRestoreAndRenameWithoutConflictCopies() throws Exception {
        NotesStore seed = new NotesStore(shared.resolve("orion-notes"));
        NoteItem folder = seed.createFolder(null, "Financeiro");
        NoteItem sheet = seed.createDocument(folder.getId(), null, NoteType.SHEET, "Orcamento",
                NoteDocuments.emptyContent(NoteType.SHEET));
        HostedPlugin plugin = new HostedPlugin(shared, null);
        plugin.onLoad();
        plugin.openNote(sheet.getId());
        FakeTab sheetTab = plugin.awaitTab(sheet.getId(), 1);
        SheetEditor sheetEditor = find(sheetTab.request.component(), SheetEditor.class);

        // A edicao ainda nao foi gravada quando a pasta vai para a lixeira; a instancia sem aba a conserva.
        onEdt(() -> { sheetEditor.input("A1", "Marketing"); return null; });
        plugin.moveToTrash(folder.getId());
        waitUntil(() -> !sheetTab.open);
        plugin.restore(folder.getId());
        waitUntil(() -> !seed.find(sheet.getId()).orElseThrow().isDeleted());
        plugin.renameForTest(sheet.getId(), "Orcamento 2027");
        waitUntil(() -> seed.find(sheet.getId()).orElseThrow().getTitle().equals("Orcamento 2027"));

        plugin.openNote(sheet.getId());
        FakeTab reopened = plugin.awaitTab(sheet.getId(), 2);
        assertSame(sheetTab.request.component(), reopened.request.component());
        assertEquals("Marketing", onEdt(() -> sheetEditor.getText("A1")));
        onEdt(() -> { sheetEditor.input("A2", "Vendas"); return null; });
        waitUntil(() -> seed.search("vendas", false).stream().anyMatch(item -> item.getId().equals(sheet.getId())));

        plugin.onUnload();
        assertEquals(2, seed.list(false).size(), "nenhuma copia de conflito");
        assertTrue(seed.search("marketing", false).stream().anyMatch(item -> item.getId().equals(sheet.getId())));
        assertEquals(List.of(), plugin.notifications);
    }

    private static <T extends Component> T find(Component root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof Container container) {
            for (Component child : container.getComponents()) {
                T found = find(child, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void waitUntil(ThrowingCondition condition) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < deadline) {
            SwingUtilities.invokeAndWait(() -> { });
            if (condition.test()) return;
            Thread.sleep(50);
        }
        fail("Condicao nao atendida a tempo");
    }

    private static <T> T onEdt(Callable<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        return result.get();
    }

    @FunctionalInterface
    private interface ThrowingCondition {
        boolean test() throws Exception;
    }

    /** Plugin com a IDE simulada: abas em memoria, recursos numa pasta temporaria e editor de texto real. */
    private static final class HostedPlugin extends OrionNotesWindowPlugin {
        private final Path sharedPath;
        private final Path project;
        private final List<FakeTab> tabs = new CopyOnWriteArrayList<>();
        private final List<String> notifications = new CopyOnWriteArrayList<>();

        private HostedPlugin(Path sharedPath, Path project) {
            this.sharedPath = sharedPath;
            this.project = project;
        }

        /** Renomeia pelo mesmo fluxo do dialogo, sem exibi-lo. */
        void renameForTest(String id, String title) throws Exception {
            java.lang.reflect.Method rename = OrionNotesWindowPlugin.class.getDeclaredMethod("rename", String.class, String.class);
            rename.setAccessible(true);
            rename.invoke(this, id, title);
        }

        FakeTab awaitTab(String noteId, int count) throws Exception {
            AtomicReference<FakeTab> found = new AtomicReference<>();
            BooleanSupplier opened = () -> {
                List<FakeTab> matching = tabs.stream()
                        .filter(tab -> tab.request.key().equals("orion-notes:note:" + noteId)).toList();
                if (matching.size() < count) return false;
                found.set(matching.get(count - 1));
                return true;
            };
            waitUntil(opened::getAsBoolean);
            return found.get();
        }

        @Override
        public Resource getResource() {
            return new SharedResource(sharedPath);
        }

        @Override
        public String getText(String key, String fallback) {
            return fallback;
        }

        @Override
        public Optional<Path> getCurrentOpenedProject() {
            return Optional.ofNullable(project);
        }

        @Override
        public List<String> getApplicationArgs() {
            return List.of();
        }

        @Override
        public String registerToolPanel(DockRegion region, String title, Icon icon, JComponent component,
                                        Dimension size) {
            return "orion-notes-panel";
        }

        @Override
        public void requestOpenToolPanel(String key) {
        }

        @Override
        public ManagedCenterTabHandle openManagedCenterTab(ManagedCenterTabRequest request) {
            FakeTab tab = new FakeTab(request);
            tabs.add(tab);
            return tab;
        }

        @Override
        public void createNotification(NotificationContext context) {
            notifications.add(context.getTitle() + ": " + context.getMessage());
        }

        @Override
        public CodeEditor requestEmbeddedCodeEditor(String fileName, String content) {
            CodeEditor editor = new CodeEditor();
            editor.setText(content);
            return editor;
        }
    }

    private static final class FakeTab implements ManagedCenterTabHandle {
        private final ManagedCenterTabRequest request;
        private volatile boolean open = true;

        private FakeTab(ManagedCenterTabRequest request) {
            this.request = request;
        }

        @Override public String key() { return request.key(); }
        @Override public boolean isOpen() { return open; }
        @Override public void select() { request.listener().onSelected(); }
        @Override public void updateTitle(String title) { }

        @Override
        public boolean close() {
            if (!open) return false;
            open = false;
            request.listener().onClosed();
            return true;
        }
    }

    private record SharedResource(Path shared) implements Resource {
        @Override public Path getSharedResourcePath() { return shared; }
        @Override public Path getResourcePath() { return null; }
        @Override public Path getResourcePath(String name) { return null; }
        @Override public Path getResourcePath(Path path) { return null; }
        @Override public URL getResource(String name) { return null; }
        @Override public List<URL> getResources(Collection<String> names) { return List.of(); }
        @Override public InputStream getResourceAsStream(String name) { return null; }
        @Override public List<InputStream> getResourcesAsStreams(Collection<String> names) { return List.of(); }
        @Override public URL getSharedResource(String name) { return null; }
        @Override public List<URL> getSharedResources(Collection<String> names) { return List.of(); }
        @Override public InputStream getSharedResourceAsStream(String name) { return null; }
        @Override public List<InputStream> getSharedResourcesAsStreams(Collection<String> names) { return List.of(); }
    }
}
