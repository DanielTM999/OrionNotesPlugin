package dtm.ide.plugins.notes;

import dtm.ide.api.annotations.PluginReference;
import dtm.ide.api.extension.IdeWindowAdapter;
import dtm.ide.api.extension.NotificationContext;
import dtm.ide.api.extension.Resource;
import dtm.ide.api.extension.event.KeyboardEvent;
import dtm.ide.api.extension.menu.IdeMenuBarBuilder;
import dtm.ide.api.extension.screen.ManagedCenterTabHandle;
import dtm.ide.api.extension.screen.ManagedCenterTabListener;
import dtm.ide.api.extension.screen.ManagedCenterTabRequest;
import dtm.ide.api.extension.settings.PluginSettingsPage;
import dtm.ide.api.plugin.PluginScope;
import dtm.ide.plugins.notes.editor.DocumentNoteEditor;
import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NoteType;
import dtm.ide.plugins.notes.model.NotesSessionState;
import dtm.ide.plugins.notes.settings.NotesSettings;
import dtm.ide.plugins.notes.settings.NotesSettingsPage;
import dtm.ide.plugins.notes.store.NoteDocuments;
import dtm.ide.plugins.notes.store.NotesStore;
import dtm.ide.plugins.notes.ui.NotesIcons;
import dtm.ide.plugins.notes.ui.NotesPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.component.inputfields.osfilepicker.DeFilter;
import dtm.stools.component.inputfields.osfilepicker.OsFilePicker;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.listeners.DocumentEditListener;
import dtm.stools.component.popup.ModernDialog;

import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@PluginReference(
        id = "orion-notes.window",
        name = "Orion Notes",
        description = "Notas globais e por projeto com pastas, busca, lixeira e autosave",
        version = "1.0.0"
)
public class OrionNotesWindowPlugin extends IdeWindowAdapter implements NotesPanel.Actions {
    private static final int MAX_TAB_TITLE_LENGTH = 36;
    private static final int DOCUMENT_SAVE_DELAY_MS = 800;
    private static final long MAX_IMPORT_BYTES = 64L * 1024 * 1024;

    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Orion-Notes-IO");
        thread.setDaemon(true);
        return thread;
    });
    /** Sessoes de editor existentes: texto enquanto a aba esta aberta; Word/Planilha ate o unload. */
    private final Map<String, EditorSession> editors = new ConcurrentHashMap<>();
    /** IDs com aba aberta, na ordem em que foram abertas. */
    private final List<String> openOrder = java.util.Collections.synchronizedList(new ArrayList<>());
    private final Object sessionLock = new Object();

    private volatile NotesStore store;
    private volatile NotesSettings settings;
    private volatile NotesPanel panel;
    private volatile String panelKey;
    private volatile String sessionContext = "no-project";
    private volatile String currentProjectId;
    private volatile String currentProjectLabel;
    private volatile String activeNoteId;
    private volatile Set<String> expandedFolderIds = Set.of();
    private volatile String selectedItemId;
    private volatile ScheduledFuture<?> sessionSaveFuture;
    private volatile ScheduledFuture<?> trashCleanupFuture;
    private volatile boolean shuttingDown;

    @Override
    public void onLoad() {
        try {
            Resource resource = getResource();
            if (resource == null || resource.getSharedResourcePath() == null) {
                throw new IOException("Resource compartilhado da IDE indisponivel");
            }
            Path notesRoot = resource.getSharedResourcePath().resolve("orion-notes");
            store = new NotesStore(notesRoot);
            settings = new NotesSettings(notesRoot);
            Path openedProject;
            try {
                openedProject = getCurrentOpenedProject().orElse(null);
            } catch (RuntimeException unavailableProject) {
                openedProject = null;
            }
            currentProjectId = projectId(openedProject);
            currentProjectLabel = projectLabel(openedProject);
            sessionContext = sessionContext(currentProjectId);
            panel = createPanelOnEdt();
            panelKey = registerToolPanel(
                    DockRegion.RIGHT,
                    text("tree.notes", "Notes"),
                    NotesIcons.of(NotesIcons.NOTES, 18),
                    panel,
                    new Dimension(300, 0)
            );
            restoreSession();
            trashCleanupFuture = ioExecutor.scheduleWithFixedDelay(
                    this::purgeExpiredTrash, 0, 1, TimeUnit.MINUTES);
            if (hasNewNoteArgument(getApplicationArgs())) createNote(null, defaultProjectId());
        } catch (Exception failure) {
            notifyFailure("Nao foi possivel iniciar o bloco de notas", failure);
        }
    }

    @Override
    public void onUnload() {
        shuttingDown = true;
        ScheduledFuture<?> pendingSessionSave = sessionSaveFuture;
        if (pendingSessionSave != null) pendingSessionSave.cancel(false);
        ScheduledFuture<?> pendingTrashCleanup = trashCleanupFuture;
        if (pendingTrashCleanup != null) pendingTrashCleanup.cancel(false);
        for (EditorSession editor : List.copyOf(editors.values())) {
            ScheduledFuture<?> pending = editor.pendingSave;
            if (pending != null) pending.cancel(false);
        }
        List<PendingDocumentSave> documentSaves = onEdt(this::captureDocumentsForUnload);

        // Conclui as gravacoes ja enfileiradas antes das finais, para que usem a revisao mais recente.
        ioExecutor.shutdown();
        try {
            ioExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        for (EditorSession editor : List.copyOf(editors.values())) {
            if (!editor.isDocument()) {
                saveEditor(editor, false);
                editor.editor.removeDocumentEditListener(editor.listener);
            }
        }
        for (PendingDocumentSave pending : documentSaves) writeDocument(pending.session(), pending.snapshot(), false);
        saveSessionNow();
        onEdt(() -> {
            for (EditorSession editor : List.copyOf(editors.values())) {
                if (editor.isDocument() && editor.closed.compareAndSet(false, true)) editor.document.close();
            }
            return null;
        });
        editors.clear();
        openOrder.clear();
        ioExecutor.shutdownNow();
    }

    @Override
    public void onProjectOpen(Path projectPath) {
        String nextProjectId = projectId(projectPath);
        String nextProjectLabel = projectLabel(projectPath);
        String previousProjectId = currentProjectId;
        String previousSessionContext = sessionContext;
        if (java.util.Objects.equals(previousProjectId, nextProjectId)) {
            currentProjectLabel = nextProjectLabel;
            NotesPanel currentPanel = panel;
            if (currentPanel != null) SwingUtilities.invokeLater(
                    () -> currentPanel.setCurrentProject(nextProjectId, nextProjectLabel));
            return;
        }
        ioExecutor.execute(() -> {
            saveSessionNow(previousSessionContext);
            currentProjectId = nextProjectId;
            currentProjectLabel = nextProjectLabel;
            sessionContext = sessionContext(nextProjectId);
            SwingUtilities.invokeLater(() -> {
                closeEditorsOutsideProject(nextProjectId);
                NotesPanel currentPanel = panel;
                if (currentPanel != null) currentPanel.setCurrentProject(nextProjectId, nextProjectLabel);
                restoreSession();
            });
        });
    }

    @Override
    public void contributeMenuBar(IdeMenuBarBuilder menu) {
        menu.into("tools", tools -> tools.submenu("tools:notes", text("menu.notes", "Notes"), notes -> {
            notes.item("tools:notes:open", text("menu.openNotes", "Open notes"), event -> openPanel());
            notes.item("tools:notes:new", text("button.newNote", "New note"), event -> createNewNoteFromUi());
            notes.item("tools:notes:newWord", text("button.newWord", "Word document"),
                    event -> createNewNoteFromUi(NoteType.WORD));
            notes.item("tools:notes:newSheet", text("button.newSheet", "Spreadsheet"),
                    event -> createNewNoteFromUi(NoteType.SHEET));
            notes.item("tools:notes:newFolder", text("button.newFolder", "New folder"), event -> createNewFolderFromUi());
            notes.item("tools:notes:import", text("menu.import", "Import DOCX/XLSX..."), event -> importFromUi());
        }));
    }

    @Override
    public List<PluginSettingsPage> getSettingsPages() {
        return List.of(new NotesSettingsPage(ensureSettings(), this::text, this::onSettingsChanged));
    }

    @Override
    public void onKeyboardEvent(KeyboardEvent event) {
        if (event == null || !event.isPressed() || event.keyCode() != KeyEvent.VK_N) return;
        int required = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK;
        int blocking = InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;
        if ((event.modifiersEx() & required) == required && (event.modifiersEx() & blocking) == 0) {
            SwingUtilities.invokeLater(this::createNewNoteFromUi);
        }
    }

    @Override
    public void createNote(String parentId, String projectId) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                NoteItem note = current.createNote(parentId, projectId);
                SwingUtilities.invokeLater(() -> {
                    refreshPanel();
                    openNote(note.getId());
                });
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel criar a nota", failure);
            }
        });
    }

    @Override
    public void requestCreateDocument(String parentId, String projectId, NoteType type) {
        if (type == null || !type.isDocument()) {
            createNote(parentId, projectId);
            return;
        }
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestCreateDocument(parentId, projectId, type));
            return;
        }
        JTextField input = new JTextField(NotesStore.defaultTitle(type));
        input.selectAll();
        String title = createModernInputDialogBuilder()
                .title(type == NoteType.WORD
                        ? text("button.newWord", "Word document") : text("button.newSheet", "Spreadsheet"))
                .message(text("dialog.documentName", "Name:"))
                .input(input)
                .confirmText(text("button.create", "Create"))
                .cancelText(text("button.cancel", "Cancel"))
                .onValidate(context -> validateName(context.value()))
                .show();
        if (title == null || title.isBlank()) return;
        createDocument(parentId, projectId, type, title.strip());
    }

    private void createDocument(String parentId, String projectId, NoteType type, String title) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                NoteItem note = current.createDocument(parentId, projectId, type, title,
                        NoteDocuments.emptyContent(type));
                SwingUtilities.invokeLater(() -> {
                    refreshPanel();
                    openNote(note.getId());
                });
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel criar a nota", failure);
            }
        });
    }

    private void createFolder(String parentId, String projectId, String title) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                current.createFolder(parentId, projectId, title);
                SwingUtilities.invokeLater(this::refreshPanel);
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel criar a pasta", failure);
            }
        });
    }

    @Override
    public void openNote(String id) {
        if (id == null) return;
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> openNote(id));
            return;
        }
        EditorSession existing = editors.get(id);
        if (existing != null && existing.hasOpenTab()) {
            existing.handle.select();
            return;
        }
        NotesStore current = store;
        if (current == null || shuttingDown) return;
        ioExecutor.execute(() -> {
            try {
                NotesStore.LoadedNote loaded = current.loadNote(id);
                NoteItem item = loaded.item();
                if (item.isDeleted() || !isVisibleInProject(item, currentProjectId)) return;
                if (!item.isDocument()) {
                    SwingUtilities.invokeLater(() -> openLoadedNote(loaded));
                    return;
                }
                EditorSession retained = editors.get(id);
                boolean reload = retained == null || retained.stale || retained.revision != item.getRevision();
                Object decoded = reload ? DocumentNoteEditor.decode(item.getType(), loaded.data()) : null;
                SwingUtilities.invokeLater(() -> openLoadedDocument(item, reload, decoded));
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel abrir a nota", failure);
            }
        });
    }

    @Override
    public void requestCreateFolder(String parentId, String projectId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestCreateFolder(parentId, projectId));
            return;
        }
        JTextField input = new JTextField();
        String title = createModernInputDialogBuilder()
                .title(text("button.newFolder", "New folder"))
                .message(text("dialog.folderName", "Folder name:"))
                .input(input)
                .confirmText(text("button.create", "Create"))
                .cancelText(text("button.cancel", "Cancel"))
                .onValidate(context -> validateName(context.value()))
                .show();
        if (title != null && !title.isBlank()) {
            createFolder(parentId, projectId, title.strip());
        }
    }

    @Override
    public void requestRename(String id, String currentTitle) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestRename(id, currentTitle));
            return;
        }
        JTextField input = new JTextField(currentTitle == null ? "" : currentTitle);
        input.selectAll();
        String title = createModernInputDialogBuilder()
                .title(text("menu.rename", "Rename"))
                .message(text("dialog.newTitle", "New title:"))
                .input(input)
                .confirmText(text("button.rename", "Rename"))
                .cancelText(text("button.cancel", "Cancel"))
                .onValidate(context -> validateName(context.value()))
                .show();
        if (title != null && !title.isBlank() && !title.strip().equals(currentTitle)) {
            rename(id, title.strip());
        }
    }

    private void validateName(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(text("dialog.nameRequired", "Enter a name"));
        }
    }

    private void rename(String id, String title) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                Map<String, Long> before = editorRevisions(current);
                NoteItem renamed = current.rename(id, title);
                syncMetadataRevisions(current, before);
                SwingUtilities.invokeLater(() -> applyMetadataUpdate(renamed));
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel renomear o item", failure);
            }
        });
    }

    @Override
    public boolean move(String id, String parentId, String projectId, int order) {
        NotesStore current = store;
        if (current == null) return false;
        try {
            Map<String, Long> before = editorRevisions(current);
            NoteItem moved = current.move(id, parentId, projectId, order);
            syncMetadataRevisions(current, before);
            applyMetadataUpdate(moved);
            return true;
        } catch (Exception failure) {
            notifyFailure("Nao foi possivel mover o item", failure);
            return false;
        }
    }

    @Override
    public void moveToTrash(String id) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                Map<String, Long> before = editorRevisions(current);
                current.moveToTrash(id);
                syncMetadataRevisions(current, before);
                SwingUtilities.invokeLater(() -> {
                    closeDeletedEditors();
                    refreshPanel();
                    scheduleSessionSave();
                });
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel mover o item para a lixeira", failure);
            }
        });
    }

    @Override
    public void restore(String id) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                Map<String, Long> before = editorRevisions(current);
                current.restore(id);
                syncMetadataRevisions(current, before);
                SwingUtilities.invokeLater(this::refreshPanel);
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel restaurar o item", failure);
            }
        });
    }

    @Override
    public void requestDeletePermanently(String id, String title) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestDeletePermanently(id, title));
            return;
        }
        java.awt.Color danger = UiTokens.danger();
        int option = createModernDialogBuilder()
                .type(ModernDialog.Type.QUESTION)
                .accentColor(danger)
                .title(text("dialog.delete.title", "Delete permanently"))
                .message(text("dialog.delete.message", "Permanently delete") + " '" + title + "'?")
                .option(text("button.delete", "Delete"), 0, danger, UiTokens.onColor(danger))
                .option(text("button.cancel", "Cancel"), 1)
                .show();
        if (option == 0) deletePermanently(id);
    }

    private void deletePermanently(String id) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                current.deletePermanently(id);
                SwingUtilities.invokeLater(this::refreshPanel);
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel excluir o item", failure);
            }
        });
    }

    @Override
    public void requestEmptyTrash() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::requestEmptyTrash);
            return;
        }
        java.awt.Color danger = UiTokens.danger();
        int option = createModernDialogBuilder()
                .type(ModernDialog.Type.QUESTION)
                .accentColor(danger)
                .title(text("menu.emptyTrash", "Empty trash"))
                .message(text("dialog.emptyTrash.message",
                        "Permanently delete every item in the trash, including other projects?"))
                .option(text("menu.emptyTrash", "Empty trash"), 0, danger, UiTokens.onColor(danger))
                .option(text("button.cancel", "Cancel"), 1)
                .show();
        if (option == 0) emptyTrash();
    }

    private void emptyTrash() {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                current.emptyTrash();
                SwingUtilities.invokeLater(this::refreshPanel);
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel esvaziar a lixeira", failure);
            }
        });
    }

    @Override
    public void requestEmptyTrashArea(String projectId, String label) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestEmptyTrashArea(projectId, label));
            return;
        }
        java.awt.Color danger = UiTokens.danger();
        int option = createModernDialogBuilder()
                .type(ModernDialog.Type.QUESTION)
                .accentColor(danger)
                .title(text("menu.emptyTrashArea", "Empty trash for this project"))
                .message(text("dialog.emptyTrashArea.message",
                        "Permanently delete every trashed item of") + " '" + label + "'?")
                .option(text("menu.emptyTrash", "Empty trash"), 0, danger, UiTokens.onColor(danger))
                .option(text("button.cancel", "Cancel"), 1)
                .show();
        if (option == 0) emptyTrashArea(projectId);
    }

    private void emptyTrashArea(String projectId) {
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                current.emptyTrash(projectId);
                SwingUtilities.invokeLater(this::refreshPanel);
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel esvaziar a lixeira do projeto", failure);
            }
        });
    }

    @Override
    public void requestImport(String parentId, String projectId) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestImport(parentId, projectId));
            return;
        }
        if (store == null || shuttingDown) return;
        File selected = OsFilePicker.openFile(
                text("dialog.import.title", "Import document"),
                DeFilter.of(text("dialog.import.filter", "Word and Excel documents"), "docx", "xlsx"),
                DeFilter.of(text("button.newWord", "Word document"), "docx"),
                DeFilter.of(text("button.newSheet", "Spreadsheet"), "xlsx"));
        if (selected == null) return;
        Path source = selected.toPath();
        ioExecutor.execute(() -> importDocument(source, parentId, projectId));
    }

    private void importDocument(Path source, String parentId, String projectId) {
        NotesStore current = store;
        if (current == null) return;
        try {
            NoteType type = NoteDocuments.typeForFileName(source.getFileName().toString());
            if (type == null) throw new IOException("Formato nao suportado; use DOCX ou XLSX");
            if (Files.size(source) > MAX_IMPORT_BYTES) throw new IOException("O arquivo excede 64 MB");
            byte[] content = Files.readAllBytes(source);
            NoteDocuments.validate(type, content);
            String destinationParent = parentId != null && current.find(parentId)
                    .filter(parent -> parent.isFolder() && !parent.isDeleted()).isPresent() ? parentId : null;
            NoteItem imported = current.createDocument(destinationParent, projectId, type,
                    baseName(source), content);
            SwingUtilities.invokeLater(() -> {
                refreshPanel();
                openNote(imported.getId());
            });
        } catch (Exception failure) {
            notifyFailure("Nao foi possivel importar o arquivo", failure);
        }
    }

    @Override
    public void requestExport(String id) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> requestExport(id));
            return;
        }
        NotesStore current = store;
        if (current == null || shuttingDown) return;
        EditorSession session = editors.get(id);
        NoteItem item = session != null ? session.item : current.find(id).orElse(null);
        if (item == null || !item.isDocument()) return;
        // A copia reflete o que esta no editor, inclusive alteracoes ainda nao gravadas.
        DocumentNoteEditor.Snapshot snapshot = session != null && session.isDocument() && !session.document.isClosed()
                ? session.document.snapshot(true, true) : null;
        String extension = item.getType().extension();
        File selected = OsFilePicker.saveFile(
                text("dialog.export.title", "Export copy"),
                safeFileName(item.getTitle()) + extension,
                DeFilter.of(item.getType() == NoteType.WORD
                        ? text("button.newWord", "Word document") : text("button.newSheet", "Spreadsheet"),
                        extension.substring(1)));
        if (selected == null) return;
        Path target = withExtension(selected.toPath(), extension);
        ioExecutor.execute(() -> {
            try {
                byte[] content = snapshot != null ? snapshot.encode() : current.loadNote(id).data();
                if (content.length == 0) content = NoteDocuments.emptyContent(item.getType());
                writeExternalFile(target, content);
                SwingUtilities.invokeLater(() -> createNotification(new NotificationContext(
                        "Orion Notes", text("notification.exported", "Copy exported to") + " " + target)));
            } catch (Exception failure) {
                notifyFailure("Nao foi possivel exportar a nota", failure);
            }
        });
    }

    private Map<String, Long> editorRevisions(NotesStore current) {
        Map<String, Long> revisions = new java.util.HashMap<>();
        for (String id : editors.keySet()) {
            current.find(id).ifPresent(item -> revisions.put(id, item.getRevision()));
        }
        return revisions;
    }

    /**
     * Renomear, mover, excluir e restaurar incrementam a revisao dos itens afetados sem mudar o conteudo.
     * Sessoes que estavam em dia acompanham a nova revisao, para que o proximo salvamento nao gere conflito.
     */
    private void syncMetadataRevisions(NotesStore current, Map<String, Long> before) {
        for (EditorSession editor : List.copyOf(editors.values())) {
            Long previous = before.get(editor.noteId);
            if (previous == null || editor.revision != previous) continue;
            current.find(editor.noteId).ifPresent(item -> {
                editor.revision = item.getRevision();
                editor.item = item;
            });
        }
    }

    private void onSettingsChanged() {
        if (!shuttingDown) ioExecutor.execute(this::purgeExpiredTrash);
    }

    private void purgeExpiredTrash() {
        NotesStore current = store;
        if (current == null) return;
        long retentionHours = ensureSettings().getTrashRetentionHours();
        if (retentionHours == NotesSettings.KEEP_TRASH_FOREVER) return;
        try {
            int removed = current.purgeExpiredTrash(Duration.ofHours(retentionHours), Instant.now());
            if (removed > 0) SwingUtilities.invokeLater(this::refreshPanel);
        } catch (Exception failure) {
            if (!shuttingDown) notifyFailure("Nao foi possivel limpar itens expirados da lixeira", failure);
        }
    }

    @Override
    public void onTreeStateChanged(Set<String> expandedIds, String selectedItemId) {
        this.expandedFolderIds = expandedIds == null ? Set.of() : Set.copyOf(expandedIds);
        this.selectedItemId = selectedItemId;
        scheduleSessionSave();
    }

    private void openLoadedNote(NotesStore.LoadedNote loaded) {
        if (shuttingDown || loaded == null) return;
        String noteId = loaded.item().getId();
        EditorSession existing = editors.get(noteId);
        if (existing != null && existing.hasOpenTab()) {
            existing.handle.select();
            return;
        }

        String content = loaded.content();
        CodeEditor editor = requestEmbeddedCodeEditor(noteId + ".txt", content);
        if (editor == null) {
            notifyFailure("Editor de codigo indisponivel", null);
            return;
        }
        editor.setFocusBorderEnabled(false);
        EditorSession session = EditorSession.text(noteId, editor, loaded.item(), content);
        DocumentEditListener listener = new DocumentEditListener() {
            @Override
            public void onTextChanged() {
                if (session.suppressEvents || session.closed.get()) return;
                session.pendingText = editor.getText();
                session.dirty = true;
                scheduleEditorSave(session);
            }
        };
        session.listener = listener;
        editor.addDocumentEditListener(listener);
        editors.put(noteId, session);
        if (!showTab(session)) {
            editors.remove(noteId, session);
            editor.removeDocumentEditListener(listener);
        }
    }

    private void openLoadedDocument(NoteItem item, boolean reload, Object decoded) {
        if (shuttingDown) return;
        String noteId = item.getId();
        EditorSession session = editors.get(noteId);
        if (session != null && session.hasOpenTab()) {
            session.handle.select();
            return;
        }
        if (session == null) {
            if (!reload) return;
            DocumentNoteEditor<?> document;
            try {
                document = DocumentNoteEditor.create(item.getType());
            } catch (RuntimeException failure) {
                notifyFailure("Nao foi possivel abrir a nota", failure);
                return;
            }
            session = EditorSession.document(noteId, document, item);
            configureDocumentSession(session);
            editors.put(noteId, session);
        }
        // Reabrir reutiliza a instancia existente; o conteudo gravado so substitui o local se nao houver
        // alteracoes pendentes (ou se elas ja foram preservadas numa copia de conflito).
        if (reload && (session.stale || !session.document.isDirty())) {
            try {
                session.document.showDecoded(decoded);
            } catch (RuntimeException failure) {
                notifyFailure("Nao foi possivel abrir a nota", failure);
                return;
            }
            session.item = item;
            session.revision = item.getRevision();
            session.stale = false;
            session.skipCloseSave = false;
        }
        showTab(session);
    }

    private void configureDocumentSession(EditorSession session) {
        Timer timer = new Timer(DOCUMENT_SAVE_DELAY_MS, event -> saveDocumentAsync(session, false));
        timer.setRepeats(false);
        session.saveTimer = timer;
        session.document.setChangeListener(() -> {
            if (!shuttingDown && !session.stale && !session.skipCloseSave) timer.restart();
        });
        session.document.setFileCommands(new DocumentNoteEditor.FileCommands() {
            @Override
            public void save() {
                saveNow(session);
            }

            @Override
            public void importFile() {
                NoteItem item = session.item;
                requestImport(item.getParentId(), item.getProjectId());
            }

            @Override
            public void exportCopy() {
                requestExport(session.noteId);
            }
        });
    }

    /** Abre a aba da sessao; a instancia do editor e reaproveitada quando ja existe. Deve rodar na EDT. */
    private boolean showTab(EditorSession session) {
        String noteId = session.noteId;
        Object token = new Object();
        session.tabToken = token;
        ManagedCenterTabRequest request = new ManagedCenterTabRequest(
                "orion-notes:note:" + noteId,
                tabTitle(session.item.getTitle()),
                session.component(),
                true,
                session.isDocument() ? NotesIcons.forType(session.type, 16) : null,
                new ManagedCenterTabListener() {
                    @Override
                    public void onSelected() {
                        if (session.tabToken != token) return;
                        activeNoteId = noteId;
                        selectedItemId = noteId;
                        scheduleSessionSave();
                    }

                    @Override
                    public void onClosed() {
                        if (SwingUtilities.isEventDispatchThread()) onTabClosed(session, token);
                        else SwingUtilities.invokeLater(() -> onTabClosed(session, token));
                    }

                    @Override
                    public boolean onSaveRequested() {
                        if (session.tabToken != token) return false;
                        if (SwingUtilities.isEventDispatchThread()) saveNow(session);
                        else SwingUtilities.invokeLater(() -> saveNow(session));
                        return true;
                    }
                }
        );
        ManagedCenterTabHandle handle = openManagedCenterTab(request);
        if (handle == null) {
            session.tabToken = null;
            notifyFailure("Nao foi possivel abrir a aba da nota", null);
            return false;
        }
        session.handle = handle;
        session.tabOpen = true;
        synchronized (openOrder) {
            openOrder.remove(noteId);
            openOrder.add(noteId);
        }
        activeNoteId = noteId;
        selectedItemId = noteId;
        refreshPanel();
        if (panel != null) panel.restoreTreeState(expandedFolderIds, noteId);
        if (session.isDocument()) session.document.focusEditor();
        else session.editor.getTextArea().requestFocusInWindow();
        scheduleSessionSave();
        return true;
    }

    private void onTabClosed(EditorSession session, Object token) {
        if (session.tabToken != token) return;
        session.tabToken = null;
        session.tabOpen = false;
        session.handle = null;
        synchronized (openOrder) {
            openOrder.remove(session.noteId);
        }
        if (session.isDocument()) {
            // A instancia continua viva para ser reaproveitada; close() so ocorre no unload.
            if (!shuttingDown) saveDocumentAsync(session, true);
        } else {
            closeTextSession(session);
        }
        if (!shuttingDown) scheduleSessionSave();
    }

    private void saveNow(EditorSession session) {
        if (shuttingDown || session.closed.get()) return;
        if (session.isDocument()) {
            saveDocumentAsync(session, true);
            return;
        }
        ScheduledFuture<?> pending = session.pendingSave;
        if (pending != null) pending.cancel(false);
        ioExecutor.execute(() -> saveEditor(session, true));
    }

    private void scheduleEditorSave(EditorSession session) {
        ScheduledFuture<?> previous = session.pendingSave;
        if (previous != null) previous.cancel(false);
        session.pendingSave = ioExecutor.schedule(() -> saveEditor(session, true), 600, TimeUnit.MILLISECONDS);
    }

    private void saveEditor(EditorSession session, boolean updateUi) {
        if (session == null || session.skipCloseSave || !session.dirty) return;
        String text = session.pendingText == null ? "" : session.pendingText;
        try {
            if (isMissingOrTrashed(session.noteId)) return;
            NotesStore.SaveResult result = store.saveContent(session.noteId, text, session.revision);
            if (result.hasConflict()) {
                session.skipCloseSave = true;
                if (updateUi && !shuttingDown) SwingUtilities.invokeLater(() -> handleConflict(session, result.conflictCopy()));
                return;
            }
            session.revision = result.item().getRevision();
            session.item = result.item();
            if (java.util.Objects.equals(session.pendingText, text)) session.dirty = false;
            if (updateUi && !shuttingDown) {
                SwingUtilities.invokeLater(() -> {
                    if (session.handle != null) session.handle.updateTitle(tabTitle(result.item().getTitle()));
                    refreshPanel();
                });
            }
        } catch (Exception failure) {
            if (!shuttingDown) notifyFailure("Nao foi possivel salvar a nota", failure);
        }
    }

    /** Captura o documento na EDT e grava a versao capturada na thread de IO. */
    private void saveDocumentAsync(EditorSession session, boolean commitEdits) {
        if (shuttingDown || !session.isDocument() || session.closed.get()) return;
        if (session.saveTimer != null) session.saveTimer.stop();
        if (session.stale || session.skipCloseSave) return;
        DocumentNoteEditor.Snapshot snapshot = session.document.snapshot(commitEdits, false);
        if (snapshot == null) return;
        ioExecutor.execute(() -> writeDocument(session, snapshot, true));
    }

    private void writeDocument(EditorSession session, DocumentNoteEditor.Snapshot snapshot, boolean updateUi) {
        NotesStore current = store;
        if (current == null || session.stale || session.skipCloseSave) return;
        try {
            if (isMissingOrTrashed(session.noteId)) return;
            byte[] content = snapshot.encode();
            NotesStore.SaveResult result = current.saveDocument(session.noteId, content, session.revision);
            if (result.hasConflict()) {
                session.stale = true;
                if (updateUi && !shuttingDown) {
                    SwingUtilities.invokeLater(() -> handleConflict(session, result.conflictCopy()));
                }
                return;
            }
            session.revision = result.item().getRevision();
            session.item = result.item();
            if (updateUi && !shuttingDown) {
                SwingUtilities.invokeLater(() -> {
                    // So agora a versao capturada e marcada como salva; edicoes posteriores seguem pendentes.
                    snapshot.markSaved();
                    if (session.document.isDirty() && session.saveTimer != null && !shuttingDown) {
                        session.saveTimer.restart();
                    }
                    if (session.handle != null) session.handle.updateTitle(tabTitle(result.item().getTitle()));
                    refreshPanel();
                });
            }
        } catch (Exception failure) {
            if (!shuttingDown) notifyFailure("Nao foi possivel salvar a nota", failure);
        }
    }

    private boolean isMissingOrTrashed(String noteId) {
        NotesStore current = store;
        return current == null || current.find(noteId).map(NoteItem::isDeleted).orElse(true);
    }

    private List<PendingDocumentSave> captureDocumentsForUnload() {
        List<PendingDocumentSave> saves = new ArrayList<>();
        for (EditorSession session : List.copyOf(editors.values())) {
            if (!session.isDocument() || session.closed.get()) continue;
            if (session.saveTimer != null) session.saveTimer.stop();
            if (session.stale || session.skipCloseSave) continue;
            try {
                DocumentNoteEditor.Snapshot snapshot = session.document.snapshot(true, false);
                if (snapshot != null) saves.add(new PendingDocumentSave(session, snapshot));
            } catch (RuntimeException failure) {
                // Um documento com falha nao impede o salvamento dos demais.
            }
        }
        return saves;
    }

    private void handleConflict(EditorSession session, NoteItem conflict) {
        if (session.handle != null) session.handle.close();
        refreshPanel();
        createNotification(new NotificationContext(
                "Conflito de nota",
                "A edicao local foi preservada em '" + conflict.getTitle() + "'."
        ));
        openNote(conflict.getId());
    }

    private void closeTextSession(EditorSession session) {
        if (session == null || !session.closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> pending = session.pendingSave;
        if (pending != null) pending.cancel(false);
        session.editor.removeDocumentEditListener(session.listener);
        editors.remove(session.noteId, session);
        if (!session.skipCloseSave && !shuttingDown) ioExecutor.execute(() -> saveEditor(session, false));
    }

    private void closeDeletedEditors() {
        for (EditorSession editor : List.copyOf(editors.values())) {
            if (editor.hasOpenTab() && store.find(editor.noteId).map(NoteItem::isDeleted).orElse(true)) {
                editor.handle.close();
            }
        }
    }

    private void closeEditorsOutsideProject(String projectId) {
        for (EditorSession editor : List.copyOf(editors.values())) {
            String editorProjectId = editor.item.getProjectId();
            if (editorProjectId != null && !java.util.Objects.equals(editorProjectId, projectId)
                    && editor.hasOpenTab()) {
                editor.handle.close();
            }
        }
    }

    static boolean isVisibleInProject(NoteItem item, String projectId) {
        return item != null && (item.getProjectId() == null
                || java.util.Objects.equals(item.getProjectId(), projectId));
    }

    private void applyMetadataUpdate(NoteItem item) {
        EditorSession editor = editors.get(item.getId());
        if (editor != null && editor.handle != null) editor.handle.updateTitle(tabTitle(item.getTitle()));
        refreshPanel();
    }

    private void restoreSession() {
        NotesStore current = store;
        NotesPanel currentPanel = panel;
        if (current == null || currentPanel == null || shuttingDown) return;
        NotesSessionState state = current.loadSession(sessionContext);
        expandedFolderIds = Set.copyOf(state.getExpandedFolderIds());
        selectedItemId = state.getSelectedItemId();
        String restoredActiveNoteId = state.getActiveNoteId();
        SwingUtilities.invokeLater(() -> currentPanel.restoreTreeState(expandedFolderIds, selectedItemId));
        if (!ensureSettings().isRestoreOpenNotes()) {
            activeNoteId = null;
            return;
        }
        activeNoteId = restoredActiveNoteId;
        for (String id : state.getOpenNoteIds()) {
            if (id != null && !id.isBlank()) openNote(id);
        }
        // openNote passa pela EDT antes de enfileirar a leitura; a selecao final entra depois dessas leituras.
        SwingUtilities.invokeLater(() -> {
            if (shuttingDown) return;
            ioExecutor.execute(() -> SwingUtilities.invokeLater(() -> {
                EditorSession active = restoredActiveNoteId == null || restoredActiveNoteId.isBlank()
                        ? null : editors.get(restoredActiveNoteId);
                if (active != null && active.hasOpenTab()) active.handle.select();
            }));
        });
    }

    static String tabTitle(String title) {
        String safeTitle = title == null || title.isBlank() ? NotesStore.UNTITLED : title.strip();
        int codePointCount = safeTitle.codePointCount(0, safeTitle.length());
        if (codePointCount <= MAX_TAB_TITLE_LENGTH) return safeTitle;
        int end = safeTitle.offsetByCodePoints(0, MAX_TAB_TITLE_LENGTH - 1);
        return safeTitle.substring(0, end).stripTrailing() + "…";
    }

    static boolean hasNewNoteArgument(List<String> applicationArgs) {
        if (applicationArgs == null) return false;
        return applicationArgs.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::strip)
                .anyMatch(argument -> argument.equalsIgnoreCase("notepad") || argument.equals("-n"));
    }

    static String safeFileName(String title) {
        String name = title == null ? "" : title.strip().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        while (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        return name.isBlank() ? "nota" : name;
    }

    static Path withExtension(Path path, String extension) {
        String name = path.getFileName().toString();
        return name.toLowerCase(java.util.Locale.ROOT).endsWith(extension) ? path : path.resolveSibling(name + extension);
    }

    private static String baseName(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    private static void writeExternalFile(Path target, byte[] content) throws IOException {
        Path directory = target.toAbsolutePath().getParent();
        if (directory == null || !Files.isDirectory(directory)) throw new IOException("Pasta de destino inexistente");
        Path temporary = Files.createTempFile(directory, ".orion-notes-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private synchronized NotesSettings ensureSettings() {
        NotesSettings current = settings;
        if (current != null) return current;
        Path settingsRoot = null;
        try {
            Resource resource = getResource();
            if (resource != null && resource.getSharedResourcePath() != null) {
                settingsRoot = resource.getSharedResourcePath().resolve("orion-notes");
            }
        } catch (Exception ignored) {
        }
        current = new NotesSettings(settingsRoot);
        settings = current;
        return current;
    }

    private void scheduleSessionSave() {
        if (shuttingDown || store == null) return;
        synchronized (sessionLock) {
            if (sessionSaveFuture != null) sessionSaveFuture.cancel(false);
            sessionSaveFuture = ioExecutor.schedule(() -> saveSessionNow(), 300, TimeUnit.MILLISECONDS);
        }
    }

    private void saveSessionNow() {
        saveSessionNow(sessionContext);
    }

    private void saveSessionNow(String context) {
        NotesStore current = store;
        if (current == null) return;
        NotesSessionState state = new NotesSessionState();
        synchronized (openOrder) {
            state.setOpenNoteIds(openOrder.stream().filter(id -> {
                EditorSession editor = editors.get(id);
                return editor != null && editor.tabOpen;
            }).toList());
        }
        state.setActiveNoteId(activeNoteId);
        state.setExpandedFolderIds(new LinkedHashSet<>(expandedFolderIds));
        state.setSelectedItemId(selectedItemId);
        try {
            current.saveSession(context, state);
        } catch (IOException failure) {
            if (!shuttingDown) notifyFailure("Nao foi possivel salvar a sessao das notas", failure);
        }
    }

    private void refreshPanel() {
        NotesPanel current = panel;
        if (current == null) return;
        if (SwingUtilities.isEventDispatchThread()) current.refresh();
        else SwingUtilities.invokeLater(current::refresh);
    }

    private void openPanel() {
        String key = panelKey;
        if (key != null) requestOpenToolPanel(key);
    }

    private void createNewNoteFromUi() {
        createNewNoteFromUi(NoteType.NOTE);
    }

    private void createNewNoteFromUi(NoteType type) {
        openPanel();
        NotesPanel current = panel;
        if (current != null) current.createNote(type);
        else if (type == NoteType.NOTE) createNote(null, defaultProjectId());
        else requestCreateDocument(null, defaultProjectId(), type);
    }

    private void createNewFolderFromUi() {
        openPanel();
        NotesPanel current = panel;
        if (current != null) current.createFolderFromUi();
    }

    private void importFromUi() {
        openPanel();
        NotesPanel current = panel;
        if (current != null) current.importFromUi();
        else requestImport(null, defaultProjectId());
    }

    private NotesPanel createPanelOnEdt() throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return new NotesPanel(store, this, this::text, currentProjectId, currentProjectLabel);
        }
        java.util.concurrent.atomic.AtomicReference<NotesPanel> created = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                created.set(new NotesPanel(store, this, this::text, currentProjectId, currentProjectLabel));
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() instanceof Exception exception) throw exception;
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return created.get();
    }

    private static <T> T onEdt(Supplier<T> action) {
        if (SwingUtilities.isEventDispatchThread()) return action.get();
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<RuntimeException> failure = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    result.set(action.get());
                } catch (RuntimeException error) {
                    failure.set(error);
                }
            });
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new IllegalStateException(error.getCause());
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private String text(String key, String fallback) {
        return getText(key, fallback);
    }

    @Override
    public String defaultProjectId() {
        return ensureSettings().getDefaultArea() == NotesSettings.DefaultArea.PROJECT
                ? currentProjectId : null;
    }

    static String projectId(Path projectPath) {
        return projectPath == null ? null : projectPath.toAbsolutePath().normalize().toString();
    }

    static String projectLabel(Path projectPath) {
        if (projectPath == null) return null;
        Path normalized = projectPath.toAbsolutePath().normalize();
        Path fileName = normalized.getFileName();
        return fileName == null ? normalized.toString() : fileName.toString();
    }

    private static String sessionContext(String projectId) {
        return projectId == null || projectId.isBlank() ? "no-project" : projectId;
    }

    private void notifyFailure(String message, Throwable failure) {
        String detail = failure == null || failure.getMessage() == null || failure.getMessage().isBlank()
                ? message : message + ": " + failure.getMessage();
        SwingUtilities.invokeLater(() -> createNotification(new NotificationContext("Orion Notes", detail)));
    }

    private record PendingDocumentSave(EditorSession session, DocumentNoteEditor.Snapshot snapshot) {
    }

    private static final class EditorSession {
        private final String noteId;
        private final NoteType type;
        /** Editor de texto da IDE; nulo para Word e Planilha. */
        private final CodeEditor editor;
        /** Editor Word/Planilha; mantido sem aba e encerrado apenas no unload. */
        private final DocumentNoteEditor<?> document;
        /** Texto: sessao descartada. Documentos: close() ja chamado. */
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile NoteItem item;
        private volatile String pendingText;
        private volatile long revision;
        private volatile DocumentEditListener listener;
        private volatile ManagedCenterTabHandle handle;
        private volatile Object tabToken;
        private volatile boolean tabOpen;
        private volatile ScheduledFuture<?> pendingSave;
        private Timer saveTimer;
        private volatile boolean suppressEvents;
        private volatile boolean skipCloseSave;
        private volatile boolean dirty;
        /** Documento cuja edicao local foi preservada numa copia de conflito; recarregar ao reabrir. */
        private volatile boolean stale;

        private EditorSession(String noteId, NoteType type, CodeEditor editor, DocumentNoteEditor<?> document,
                              NoteItem item, String text) {
            this.noteId = noteId;
            this.type = type;
            this.editor = editor;
            this.document = document;
            this.item = item;
            this.pendingText = text;
            this.revision = item.getRevision();
        }

        static EditorSession text(String noteId, CodeEditor editor, NoteItem item, String text) {
            return new EditorSession(noteId, NoteType.NOTE, editor, null, item, text);
        }

        static EditorSession document(String noteId, DocumentNoteEditor<?> document, NoteItem item) {
            return new EditorSession(noteId, document.type(), null, document, item, null);
        }

        boolean isDocument() { return document != null; }

        boolean hasOpenTab() {
            ManagedCenterTabHandle current = handle;
            return tabOpen && current != null && current.isOpen();
        }

        JComponent component() { return isDocument() ? document.component() : editor; }
    }
}
