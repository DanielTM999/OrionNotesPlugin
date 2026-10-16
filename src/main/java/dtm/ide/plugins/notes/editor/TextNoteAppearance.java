package dtm.ide.plugins.notes.editor;

import dtm.ide.plugins.notes.model.NoteEditorAppearance;
import dtm.stools.component.menu.popup.ActionPopupMenu;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.gutter.GutterRightClickListener;

import javax.swing.SwingUtilities;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Menu e preferencias de um editor de texto. Criar e encerrar na EDT. */
public final class TextNoteAppearance implements AutoCloseable {
    private final CodeEditor editor;
    private final BiFunction<String, String, String> texts;
    private final BiConsumer<String, Boolean> onChange;
    private final GutterRightClickListener listener;
    private ActionPopupMenu popup;
    private boolean closed;

    public TextNoteAppearance(CodeEditor editor, NoteEditorAppearance saved,
                              BiFunction<String, String, String> texts, BiConsumer<String, Boolean> onChange) {
        this.editor = editor;
        this.texts = texts;
        this.onChange = onChange;
        for (Option option : Option.values()) {
            Boolean value = saved.getOptions().get(option.key);
            if (value != null) option.write.accept(editor, value);
        }
        refresh();
        listener = event -> SwingUtilities.invokeLater(() -> {
            if (closed || !editor.getGutter().isShowing()) return;
            if (popup != null) popup.setVisible(false);
            popup = createPopupMenu();
            popup.show(editor.getGutter(), event.mouseEvent().getX(), event.mouseEvent().getY());
        });
        editor.getGutter().addGutterRightClickListener(listener);
    }

    ActionPopupMenu createPopupMenu() {
        ActionPopupMenu menu = ActionPopupMenu.create();
        menu.submenu(texts.apply("appearance", "Appearance"), appearance -> {
            for (Option option : Option.values()) {
                if (option == Option.FOLDING || option == Option.MINIMAP_PREVIEW_MIRROR || option == Option.ERROR_STRIPE) {
                    appearance.separator();
                }
                appearance.checkItem(texts.apply("appearance." + option.key, option.label),
                        option.read.test(editor), event -> {
                            if (closed) return;
                            boolean enabled = !option.read.test(editor);
                            option.write.accept(editor, enabled);
                            refresh();
                            onChange.accept(option.key, enabled);
                        });
            }
        });
        return menu;
    }

    private void refresh() {
        editor.revalidate();
        editor.repaint();
        editor.getGutter().revalidate();
        editor.getGutter().repaint();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        editor.getGutter().removeGutterRightClickListener(listener);
        if (popup != null) {
            popup.setVisible(false);
            popup = null;
        }
    }

    private static boolean breadcrumbsEnabled(CodeEditor editor) {
        Boolean enabled = editor.getUserProperty("appearance.breadcrumbs.enabled", Boolean.class);
        return enabled != null ? enabled
                : Boolean.parseBoolean(System.getProperty("orion.editor.breadcrumbs.enabled", "true"));
    }

    private enum Option {
        LINE_NUMBERS("lineNumbers", "Line numbers", e -> e.getGutter().isLineNumberEnabled(),
                (e, value) -> e.getGutter().enableLineNumber(value)),
        LINE_WRAP("lineWrap", "Wrap lines", CodeEditor::isLineWrapEnabled, CodeEditor::setLineWrapEnabled),
        INDENT_GUIDES("indentGuides", "Indent guides", CodeEditor::isShowIndentGuides, CodeEditor::setShowIndentGuides),
        CURRENT_LINE("currentLine", "Current line", CodeEditor::isHighlightCurrentLine, CodeEditor::setHighlightCurrentLine),
        BREADCRUMBS("breadcrumbs", "Breadcrumbs", TextNoteAppearance::breadcrumbsEnabled, (e, value) -> {
            e.putUserProperty("appearance.breadcrumbs.enabled", value);
            e.setBreadcrumbsEnabled(value);
        }),
        FOLDING("folding", "Folding", CodeEditor::isFoldingEnabled, CodeEditor::setFoldingEnabled),
        FOLD_CHEVRONS("foldChevronsOnHoverOnly", "Fold chevrons on hover only",
                CodeEditor::isFoldChevronVisibleOnHoverOnly, CodeEditor::setFoldChevronVisibleOnHoverOnly),
        FOLD_PREVIEW("foldPreviewOnHover", "Fold preview on hover",
                CodeEditor::isFoldPreviewOnHoverEnabled, CodeEditor::setFoldPreviewOnHoverEnabled),
        MINIMAP_PREVIEW_MIRROR("minimapPreviewMirror", "Minimap preview mirror",
                CodeEditor::isMinimapPreviewMirrorEnabled, CodeEditor::setMinimapPreviewMirrorEnabled),
        ERROR_STRIPE("errorStripe", "Error stripe", CodeEditor::isErrorStripeEnabled, CodeEditor::setErrorStripeEnabled),
        ERROR_STRIPE_STATUS("errorStripeStatus", "Error stripe status",
                CodeEditor::isErrorStripeStatusIndicatorEnabled, CodeEditor::setErrorStripeStatusIndicatorEnabled),
        INSPECTION_WIDGET("inspectionWidget", "Inspection widget",
                CodeEditor::isInspectionWidgetEnabled, CodeEditor::setInspectionWidgetEnabled);

        private final String key;
        private final String label;
        private final Predicate<CodeEditor> read;
        private final BiConsumer<CodeEditor, Boolean> write;

        Option(String key, String label, Predicate<CodeEditor> read, BiConsumer<CodeEditor, Boolean> write) {
            this.key = key;
            this.label = label;
            this.read = read;
            this.write = write;
        }
    }
}
