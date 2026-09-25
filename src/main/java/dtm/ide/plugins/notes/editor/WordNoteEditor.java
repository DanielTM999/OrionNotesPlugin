package dtm.ide.plugins.notes.editor;

import dtm.ide.plugins.notes.model.NoteType;
import dtm.stools.component.panels.editor.word.WordEditor;
import dtm.stools.component.panels.editor.word.api.ProviderRegistration;
import dtm.stools.component.panels.editor.word.api.WordSession;
import dtm.stools.component.panels.editor.word.io.DocxCodec;
import dtm.stools.component.panels.editor.word.io.WordImportResult;
import dtm.stools.component.panels.editor.word.model.WordDocument;
import dtm.stools.component.panels.editor.word.provider.WordConfirmationProvider;
import dtm.stools.component.panels.editor.word.provider.WordConfirmationRequest;
import dtm.stools.component.panels.editor.word.provider.WordFileDialogProvider;
import dtm.stools.component.panels.editor.word.provider.WordFileDialogRequest;
import dtm.stools.component.panels.editor.word.render.WordObjectRegistry;

import javax.swing.Action;
import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class WordNoteEditor extends DocumentNoteEditor<WordImportResult> {
    private final WordEditor editor;
    private final ProviderRegistration sessionListener;
    /** Pacote DOCX de origem: sem ele a gravacao descartaria partes protegidas do arquivo importado. */
    private WordImportResult origin;
    /** Documento que corresponde ao conteudo gravado no armazenamento das notas. */
    private WordDocument persisted;
    private boolean loading;

    WordNoteEditor() {
        this(new WordEditor());
    }

    private WordNoteEditor(WordEditor editor) {
        super(editor);
        this.editor = editor;
        this.persisted = editor.getDocument();
        WordFileDialogProvider nativeFiles = editor.getFileDialogProvider();
        WordConfirmationProvider nativeConfirmation = editor.getConfirmationProvider();
        editor.setFileDialogProvider(new NoteFileDialogs(nativeFiles));
        editor.setConfirmationProvider(new NoteConfirmations(nativeConfirmation));
        editor.getCanvas().bind("word.save", KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK),
                () -> commands().save());
        // "Novo" substituiria o conteudo da nota; novas notas sao criadas pelo painel.
        Action newDocument = editor.getCommands().get("word.new");
        if (newDocument != null) newDocument.setEnabled(false);
        sessionListener = editor.getSession().addListener(this::sessionChanged);
    }

    @Override
    public NoteType type() { return NoteType.WORD; }

    WordEditor wordEditor() { return editor; }

    @Override
    public void show(WordImportResult decoded) {
        loading = true;
        try {
            origin = decoded;
            editor.setDocument(decoded == null ? WordDocument.empty() : decoded.document());
            persisted = editor.getDocument();
            boolean readOnly = decoded != null && !decoded.isEditable();
            editor.setReadOnly(readOnly);
            List<String> messages = new java.util.ArrayList<>();
            if (decoded != null) {
                messages.addAll(decoded.blockingReasons());
                decoded.diagnostics().stream().filter(message -> !messages.contains(message)).forEach(messages::add);
            }
            showMessages(readOnly, messages);
        } finally {
            loading = false;
        }
    }

    @Override
    public boolean isDirty() {
        return !editor.isClosed() && !Objects.equals(editor.getDocument(), persisted);
    }

    @Override
    public Snapshot snapshot(boolean commitEdits, boolean includeClean) {
        if (editor.isClosed()) return null;
        WordDocument document = editor.getDocument();
        if (!includeClean && Objects.equals(document, persisted)) return null;
        WordImportResult source = origin;
        WordObjectRegistry objects = editor.getObjectRegistry();
        return new Snapshot() {
            @Override
            public byte[] encode() throws IOException {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                new DocxCodec().write(document, source, out, objects);
                return out.toByteArray();
            }

            @Override
            public void markSaved() {
                persisted = document;
                if (!editor.isClosed()) editor.getSession().markSaved(document);
            }
        };
    }

    @Override
    public void focusEditor() {
        if (!editor.isClosed()) editor.getCanvas().requestFocusInWindow();
    }

    @Override
    protected void closeEditor() {
        sessionListener.close();
        editor.close();
    }

    private void sessionChanged(WordSession.Event event) {
        if (loading || event.change() != WordSession.Change.DOCUMENT || !isDirty()) return;
        // Comandos como comparar ou mala direta recarregam a sessao e a consideram salva;
        // o conteudo gravado continua sendo a referencia.
        if (!editor.getSession().isDirty()) editor.getSession().markSaved(persisted);
        fireChanged();
    }

    private final class NoteFileDialogs implements WordFileDialogProvider {
        private final WordFileDialogProvider delegate;

        private NoteFileDialogs(WordFileDialogProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String id() { return "orion-notes.word.files"; }

        @Override
        public Optional<Path> choose(WordFileDialogRequest request) {
            if (request.mode() == WordFileDialogRequest.Mode.OPEN
                    && calledFrom(WordEditor.class, Set.of("chooseOpen"))) {
                commands().importFile();
                return Optional.empty();
            }
            if (request.mode() == WordFileDialogRequest.Mode.SAVE) {
                if (calledFrom(WordEditor.class, Set.of("chooseSave"))) commands().save();
                else commands().exportCopy();
                return Optional.empty();
            }
            return delegate.choose(request);
        }
    }

    private final class NoteConfirmations implements WordConfirmationProvider {
        private final WordConfirmationProvider delegate;

        private NoteConfirmations(WordConfirmationProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public String id() { return "orion-notes.word.confirmations"; }

        @Override
        public boolean confirm(WordConfirmationRequest request) {
            // "Abrir" importa uma nova nota: nada da nota atual e descartado.
            if (request.kind() == WordConfirmationRequest.Kind.DISCARD_CHANGES
                    && calledFrom(WordEditor.class, Set.of("chooseOpen"))) {
                return true;
            }
            return delegate.confirm(request);
        }
    }
}
