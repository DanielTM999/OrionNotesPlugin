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
import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NotesSessionState;
import dtm.ide.plugins.notes.settings.NotesSettings;
import dtm.ide.plugins.notes.settings.NotesSettingsPage;
import dtm.ide.plugins.notes.store.NotesStore;
import dtm.ide.plugins.notes.ui.NotesIcons;
import dtm.ide.plugins.notes.ui.NotesPanel;
import dtm.stools.configs.UiTokens;
import dtm.stools.component.panels.dock.DockRegion;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.listeners.DocumentEditListener;
import dtm.stools.component.popup.ModernDialog;

import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
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

@PluginReference(
        id = "orion-notes.window",
        name = "Orion Notes",
        description = "Notas globais e por projeto com pastas, busca, lixeira e autosave",
        version = "1.0.0"
)
public class OrionNotesWindowPlugin extends IdeWindowAdapter implements NotesPanel.Actions {
    private static final int MAX_TAB_TITLE_LENGTH = 36;

    private final ScheduledExecutorService ioExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "Orion-Notes-IO");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, EditorSession> editors = new ConcurrentHashMap<>();
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
            saveEditor(editor, false);
            editor.editor.removeDocumentEditListener(editor.listener);
        }
        saveSessionNow();
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
            notes.item("tools:notes:newFolder", text("button.newFolder", "New folder"), event -> createNewFolderFromUi());
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
        EditorSession existing = editors.get(id);
        if (existing != null && existing.handle != null && existing.handle.isOpen()) {
            existing.handle.select();
            return;
        }
        NotesStore current = store;
        if (current == null) return;
        ioExecutor.execute(() -> {
            try {
                NotesStore.LoadedNote loaded = current.loadNote(id);
                if (loaded.item().isDeleted() || !isVisibleInProject(loaded.item(), currentProjectId)) return;
                SwingUtilities.invokeLater(() -> openLoadedNote(loaded));
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
                NoteItem renamed = current.rename(id, title);
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
            NoteItem moved = current.move(id, parentId, projectId, order);
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
                current.moveToTrash(id);
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
                current.restore(id);
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
        if (existing != null && existing.handle != null && existing.handle.isOpen()) {
            existing.handle.select();
            return;
        }

        CodeEditor editor = requestEmbeddedCodeEditor(noteId + ".txt", loaded.content());
        if (editor == null) {
            notifyFailure("Editor de codigo indisponivel", null);
            return;
        }
        editor.setFocusBorderEnabled(false);
        EditorSession session = new EditorSession(noteId, editor, loaded.item(), loaded.content());
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
        synchronized (openOrder) {
            openOrder.remove(noteId);
            openOrder.add(noteId);
        }

        ManagedCenterTabRequest request = new ManagedCenterTabRequest(
                "orion-notes:note:" + noteId,
                tabTitle(loaded.item().getTitle()),
                editor,
                true,
                null,
                new ManagedCenterTabListener() {
                    @Override
                    public void onSelected() {
                        activeNoteId = noteId;
                        selectedItemId = noteId;
                        scheduleSessionSave();
                    }

                    @Override
                    public void onClosed() {
                        closeEditorSession(session);
                    }
                }
        );
        session.handle = openManagedCenterTab(request);
        if (session.handle == null) {
            editors.remove(noteId, session);
            editor.removeDocumentEditListener(listener);
            notifyFailure("Nao foi possivel abrir a aba da nota", null);
            return;
        }
        activeNoteId = noteId;
        selectedItemId = noteId;
        refreshPanel();
        if (panel != null) panel.restoreTreeState(expandedFolderIds, noteId);
        editor.getTextArea().requestFocusInWindow();
        scheduleSessionSave();
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

    private void handleConflict(EditorSession session, NoteItem conflict) {
        if (session.handle != null) session.handle.close();
        refreshPanel();
        createNotification(new NotificationContext(
                "Conflito de nota",
                "A edicao local foi preservada em '" + conflict.getTitle() + "'."
        ));
        openNote(conflict.getId());
    }

    private void closeEditorSession(EditorSession session) {
        if (session == null || !session.closed.compareAndSet(false, true)) return;
        ScheduledFuture<?> pending = session.pendingSave;
        if (pending != null) pending.cancel(false);
        session.editor.removeDocumentEditListener(session.listener);
        editors.remove(session.noteId, session);
        synchronized (openOrder) {
            openOrder.remove(session.noteId);
        }
        if (!session.skipCloseSave && !shuttingDown) ioExecutor.execute(() -> saveEditor(session, false));
        if (!shuttingDown) scheduleSessionSave();
    }

    private void closeDeletedEditors() {
        for (EditorSession editor : List.copyOf(editors.values())) {
            if (store.find(editor.noteId).map(NoteItem::isDeleted).orElse(true) && editor.handle != null) {
                editor.handle.close();
            }
        }
    }

    private void closeEditorsOutsideProject(String projectId) {
        for (EditorSession editor : List.copyOf(editors.values())) {
            String editorProjectId = editor.item.getProjectId();
            if (editorProjectId != null && !java.util.Objects.equals(editorProjectId, projectId)
                    && editor.handle != null) {
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
        if (editor != null) {
            editor.item = item;
            editor.revision = item.getRevision();
            if (editor.handle != null) editor.handle.updateTitle(tabTitle(item.getTitle()));
        }
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
        ioExecutor.execute(() -> SwingUtilities.invokeLater(() -> {
            EditorSession active = restoredActiveNoteId == null || restoredActiveNoteId.isBlank()
                    ? null : editors.get(restoredActiveNoteId);
            if (active != null && active.handle != null) active.handle.select();
        }));
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
            state.setOpenNoteIds(openOrder.stream().filter(editors::containsKey).toList());
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
        openPanel();
        NotesPanel current = panel;
        if (current != null) current.createNote();
        else createNote(null, defaultProjectId());
    }

    private void createNewFolderFromUi() {
        openPanel();
        NotesPanel current = panel;
        if (current != null) current.createFolderFromUi();
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

    private static final class EditorSession {
        private final String noteId;
        private final CodeEditor editor;
        private final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        private volatile NoteItem item;
        private volatile String pendingText;
        private volatile long revision;
        private volatile DocumentEditListener listener;
        private volatile ManagedCenterTabHandle handle;
        private volatile ScheduledFuture<?> pendingSave;
        private volatile boolean suppressEvents;
        private volatile boolean skipCloseSave;
        private volatile boolean dirty;

        private EditorSession(String noteId, CodeEditor editor, NoteItem item, String text) {
            this.noteId = noteId;
            this.editor = editor;
            this.item = item;
            this.pendingText = text;
            this.revision = item.getRevision();
        }
    }
}
