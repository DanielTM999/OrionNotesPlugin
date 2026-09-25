package dtm.ide.plugins.notes.editor;

import dtm.ide.plugins.notes.model.NoteType;
import dtm.stools.component.panels.editor.sheet.SheetEditor;
import dtm.stools.component.panels.editor.sheet.api.ProviderRegistration;
import dtm.stools.component.panels.editor.sheet.api.SheetSessionEvent;
import dtm.stools.component.panels.editor.sheet.controller.FileController;
import dtm.stools.component.panels.editor.sheet.controller.SheetAction;
import dtm.stools.component.panels.editor.sheet.io.SheetImportResult;
import dtm.stools.component.panels.editor.sheet.model.SheetWorkbook;
import dtm.stools.component.panels.editor.sheet.provider.SheetConfirmationProvider;
import dtm.stools.component.panels.editor.sheet.provider.SheetConfirmationRequest;
import dtm.stools.component.panels.editor.sheet.provider.SheetFileDialogProvider;
import dtm.stools.component.panels.editor.sheet.provider.SheetFileDialogRequest;
import dtm.stools.component.panels.editor.sheet.ui.popup.DefaultConfirmationProvider;
import dtm.stools.component.panels.editor.sheet.ui.popup.DefaultFileDialogProvider;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class SheetNoteEditor extends DocumentNoteEditor<SheetImportResult> {
    private final SheetEditor editor;
    private final ProviderRegistration sessionListener;
    private final ProviderRegistration fileDialogs;
    private final ProviderRegistration confirmations;
    private boolean loading;

    SheetNoteEditor() {
        this(new SheetEditor());
    }

    private SheetNoteEditor(SheetEditor editor) {
        super(editor);
        this.editor = editor;
        fileDialogs = editor.addProvider(new NoteFileDialogs());
        confirmations = editor.addProvider(new NoteConfirmations());
        bind("sheet.file.save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), () -> commands().save());
        bind("sheet.file.saveAs", KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0), () -> commands().exportCopy());
        bind("sheet.file.open", KeyStroke.getKeyStroke(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK), () -> commands().importFile());
        // "Novo" substituiria o conteudo da nota; novas notas sao criadas pelo painel.
        if (editor.getCommands().get("sheet.file.new") instanceof SheetAction newWorkbook) {
            newWorkbook.when(() -> false);
            editor.commandRegistry().refresh();
        }
        sessionListener = editor.addSessionListener(this::sessionChanged);
    }

    @Override
    public NoteType type() { return NoteType.SHEET; }

    SheetEditor sheetEditor() { return editor; }

    @Override
    public void show(SheetImportResult decoded) {
        loading = true;
        try {
            editor.load(decoded == null ? SheetWorkbook.create() : decoded.workbook());
            boolean readOnly = decoded != null && !decoded.editable();
            editor.setReadOnly(readOnly);
            List<String> diagnostics = new ArrayList<>();
            if (decoded != null) {
                diagnostics.addAll(decoded.workbook().properties().diagnostics());
                for (String diagnostic : decoded.diagnostics()) {
                    if (!diagnostics.contains(diagnostic)) diagnostics.add(diagnostic);
                }
            }
            editor.showDiagnostics(diagnostics);
            showMessages(readOnly, List.of());
            editor.refreshAll();
        } finally {
            loading = false;
        }
    }

    @Override
    public boolean isDirty() {
        return !editor.isClosed() && editor.isDirty();
    }

    @Override
    public Snapshot snapshot(boolean commitEdits, boolean includeClean) {
        if (editor.isClosed()) return null;
        if (commitEdits) editor.commitEditingIfActive();
        if (!includeClean && !editor.isDirty()) return null;
        long revision = editor.getSession().getRevision();
        byte[] content;
        IOException failure = null;
        try {
            // O motor de calculo nao e seguro fora da EDT; a planilha e codificada aqui, como no SwingTools.
            content = editor.getServices().xlsx().toBytes(editor.getWorkbook(), editor.getEngine());
        } catch (IOException encodingFailure) {
            content = null;
            failure = encodingFailure;
        }
        byte[] encoded = content;
        IOException error = failure;
        return new Snapshot() {
            @Override
            public byte[] encode() throws IOException {
                if (error != null) throw error;
                return encoded;
            }

            @Override
            public void markSaved() {
                if (!editor.isClosed() && editor.getSession().getRevision() == revision) {
                    editor.getSession().markSaved();
                }
            }
        };
    }

    @Override
    public void focusEditor() {
        if (!editor.isClosed()) editor.focusGrid();
    }

    @Override
    protected void closeEditor() {
        sessionListener.close();
        fileDialogs.close();
        confirmations.close();
        editor.close();
    }

    private void bind(String id, KeyStroke key, Runnable action) {
        editor.getCanvas().bind(id, key, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                try {
                    action.run();
                } catch (RuntimeException failure) {
                    editor.reportError(failure);
                }
            }
        });
    }

    private void sessionChanged(SheetSessionEvent event) {
        if (loading) return;
        // Uma pasta carregada por um comando do editor e considerada salva pelo SwingTools,
        // mas ainda nao corresponde ao conteudo gravado da nota.
        if (event.kind() == SheetSessionEvent.Kind.LOAD) editor.getSession().markDirty();
        if ((event.kind() == SheetSessionEvent.Kind.CONTENT || event.kind() == SheetSessionEvent.Kind.STATE)
                && editor.isDirty()) {
            fireChanged();
        }
    }

    private final class NoteFileDialogs implements SheetFileDialogProvider {
        private final SheetFileDialogProvider delegate = new DefaultFileDialogProvider();

        @Override
        public String id() { return "orion-notes.sheet.files"; }

        @Override
        public Optional<Path> choose(SheetFileDialogRequest request) {
            if (request.mode() == SheetFileDialogRequest.Mode.OPEN
                    && calledFrom(FileController.class, Set.of("openDialog"))) {
                commands().importFile();
                return Optional.empty();
            }
            if (request.mode() == SheetFileDialogRequest.Mode.SAVE) {
                if (calledFrom(FileController.class, Set.of("save", "saveNow"))) commands().save();
                else commands().exportCopy();
                return Optional.empty();
            }
            return delegate.choose(request);
        }
    }

    private static final class NoteConfirmations implements SheetConfirmationProvider {
        /** Indice de "Nao Salvar" na pergunta de FileController.confirmDiscard. */
        private static final int CONTINUE_WITHOUT_SAVING = 1;
        private final SheetConfirmationProvider delegate = new DefaultConfirmationProvider();

        @Override
        public String id() { return "orion-notes.sheet.confirmations"; }

        @Override
        public int confirm(SheetConfirmationRequest request) {
            // "Abrir" importa uma nova nota e o salvamento automatico preserva a atual: nada e descartado.
            if (calledFrom(FileController.class, Set.of("openDialog"))
                    && calledFrom(FileController.class, Set.of("confirmDiscard"))) {
                return CONTINUE_WITHOUT_SAVING;
            }
            return delegate.confirm(request);
        }
    }
}
