package dtm.ide.plugins.notes.editor;

import dtm.ide.plugins.notes.model.NoteType;
import dtm.ide.plugins.notes.store.NoteDocuments;
import dtm.stools.configs.UiTokens;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Adaptador entre uma nota Word/Planilha e a instancia do editor do SwingTools que a exibe.
 * Todos os metodos, exceto {@link #decode(byte[])} e {@link Snapshot#encode()}, devem ser chamados na EDT.
 * A instancia sobrevive ao fechamento da aba; {@link #close()} so e chamado quando o plugin e descarregado.
 */
public abstract sealed class DocumentNoteEditor<D> permits WordNoteEditor, SheetNoteEditor {
    private final JPanel root = new JPanel(new BorderLayout());
    private final JLabel banner = new JLabel();
    private Runnable changeListener = () -> { };
    private FileCommands fileCommands = FileCommands.NONE;
    private boolean closed;

    protected DocumentNoteEditor(JComponent editor) {
        banner.setVisible(false);
        banner.setForeground(UiTokens.warning());
        banner.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()),
                BorderFactory.createEmptyBorder(UiTokens.space(1), UiTokens.space(3), UiTokens.space(1), UiTokens.space(3))));
        root.add(banner, BorderLayout.NORTH);
        root.add(editor, BorderLayout.CENTER);
    }

    public static DocumentNoteEditor<?> create(NoteType type) {
        return switch (Objects.requireNonNull(type, "type")) {
            case WORD -> new WordNoteEditor();
            case SHEET -> new SheetNoteEditor();
            default -> throw new IllegalArgumentException("Tipo sem editor de documento: " + type);
        };
    }

    /** Le o conteudo armazenado com o codec do tipo; pode rodar fora da EDT, antes de o editor existir. */
    public static Object decode(NoteType type, byte[] content) throws IOException {
        return switch (Objects.requireNonNull(type, "type")) {
            case WORD -> NoteDocuments.readWord(content);
            case SHEET -> NoteDocuments.readSheet(content);
            default -> throw new IllegalArgumentException("Tipo sem editor de documento: " + type);
        };
    }

    public abstract NoteType type();

    /** Substitui o conteudo exibido pelo documento lido do armazenamento, que passa a ser a versao salva. */
    public abstract void show(D decoded);

    /** Aplica o resultado de {@link #decode(NoteType, byte[])} sem conhecer seu tipo concreto. */
    @SuppressWarnings("unchecked")
    public final void showDecoded(Object decoded) {
        show((D) decoded);
    }

    public abstract boolean isDirty();

    /**
     * Captura uma versao estavel do documento. Retorna {@code null} quando nao ha nada a gravar,
     * a menos que {@code includeClean} seja verdadeiro (exportacao). {@code commitEdits} confirma
     * uma edicao de celula em andamento; o salvamento automatico nao a interrompe.
     */
    public abstract Snapshot snapshot(boolean commitEdits, boolean includeClean);

    public abstract void focusEditor();

    protected abstract void closeEditor();

    public final JComponent component() { return root; }

    public final void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public final void setFileCommands(FileCommands commands) {
        fileCommands = commands == null ? FileCommands.NONE : commands;
    }

    public final boolean isClosed() { return closed; }

    /** Remove listeners e encerra o editor do SwingTools; chamadas repetidas sao ignoradas. */
    public final void close() {
        if (closed) return;
        closed = true;
        changeListener = () -> { };
        fileCommands = FileCommands.NONE;
        closeEditor();
    }

    protected final void fireChanged() {
        if (!closed) changeListener.run();
    }

    protected final FileCommands commands() { return fileCommands; }

    protected final void showMessages(boolean readOnly, List<String> diagnostics) {
        List<String> lines = new java.util.ArrayList<>();
        if (readOnly) lines.add("Somente leitura: o arquivo possui conteudo que o editor nao consegue alterar com seguranca.");
        if (diagnostics != null) diagnostics.stream().filter(Objects::nonNull).limit(4).forEach(lines::add);
        if (diagnostics != null && diagnostics.size() > 4) lines.add("… +" + (diagnostics.size() - 4));
        if (lines.isEmpty()) {
            banner.setVisible(false);
        } else {
            banner.setText("<html>" + String.join("<br>", lines.stream().map(DocumentNoteEditor::escape).toList())
                    + "</html>");
            banner.setVisible(true);
        }
        root.revalidate();
        root.repaint();
    }

    /**
     * Os comandos de arquivo dos editores sao privados; identificamos a origem de um pedido de dialogo
     * pelo metodo do SwingTools que o disparou.
     */
    protected static boolean calledFrom(Class<?> owner, Set<String> methods) {
        String name = owner.getName();
        return StackWalker.getInstance().walk(frames -> frames.anyMatch(
                frame -> frame.getClassName().equals(name) && methods.contains(frame.getMethodName())));
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Versao imutavel capturada na EDT; {@link #encode()} pode rodar em outra thread. */
    public interface Snapshot {
        byte[] encode() throws IOException;

        /** Marca o editor como salvo nesta versao; alteracoes posteriores continuam pendentes. Chamado na EDT. */
        void markSaved();
    }

    /** Destino dos comandos de arquivo do editor (Ctrl+S, Abrir, Salvar como). */
    public interface FileCommands {
        FileCommands NONE = new FileCommands() {
            @Override public void save() { }
            @Override public void importFile() { }
            @Override public void exportCopy() { }
        };

        void save();
        void importFile();
        void exportCopy();
    }
}
