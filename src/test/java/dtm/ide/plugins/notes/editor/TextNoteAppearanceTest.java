package dtm.ide.plugins.notes.editor;

import dtm.ide.plugins.notes.model.NoteEditorAppearance;
import dtm.stools.component.panels.editor.code.CodeEditor;
import dtm.stools.component.panels.editor.code.listeners.DocumentEditListener;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Dimension;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TextNoteAppearanceTest {
    @Test
    void menuReflectsLiveStateAndWrapNeverEditsText() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeEditor editor = new CodeEditor();
            String text = "Uma linha longa com palavras e acentos: ação. ".repeat(60) + "\nSegunda linha";
            editor.setText(text);
            editor.setLineWrapEnabled(false);
            AtomicInteger edits = new AtomicInteger();
            editor.addDocumentEditListener(new DocumentEditListener() {
                @Override public void onTextChanged() { edits.incrementAndGet(); }
            });
            Map<String, Boolean> changes = new LinkedHashMap<>();
            try (TextNoteAppearance appearance = new TextNoteAppearance(editor, new NoteEditorAppearance(),
                    (key, fallback) -> fallback, changes::put)) {
                JMenu menu = (JMenu) appearance.createPopupMenu().getComponent(0);
                assertEquals("Appearance", menu.getText());
                JCheckBoxMenuItem wrap = item(menu, "Wrap lines");
                assertFalse(wrap.isSelected());
                wrap.doClick(0);
                assertTrue(editor.isLineWrapEnabled());
                assertTrue(wrap.isSelected());
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER, editor.getScrollPane().getHorizontalScrollBarPolicy());
                assertEquals(Map.of("lineWrap", true), changes);

                // A consulta ao estado real acompanha alteracoes externas ao menu.
                editor.getGutter().enableLineNumber(false);
                JMenu rebuilt = (JMenu) appearance.createPopupMenu().getComponent(0);
                assertFalse(item(rebuilt, "Line numbers").isSelected());
                assertTrue(item(rebuilt, "Wrap lines").isSelected());
                item(rebuilt, "Wrap lines").doClick(0);
                assertFalse(editor.isLineWrapEnabled());
                assertEquals(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED,
                        editor.getScrollPane().getHorizontalScrollBarPolicy());
                assertEquals(text, editor.getText());
                assertEquals(0, edits.get(), "aparencia nao deve disparar autosave de conteudo");
            }
        });
    }

    @Test
    void restoresOverridesPreservesDefaultsAndDisablesActionsAfterClose() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeEditor editor = new CodeEditor();
            editor.setShowIndentGuides(false);
            NoteEditorAppearance saved = new NoteEditorAppearance();
            saved.getOptions().putAll(Map.of("lineWrap", true, "lineNumbers", false, "unknownFutureOption", true));
            AtomicInteger changes = new AtomicInteger();
            TextNoteAppearance appearance = new TextNoteAppearance(editor, saved,
                    (key, fallback) -> fallback, (key, value) -> changes.incrementAndGet());
            assertTrue(editor.isLineWrapEnabled());
            assertFalse(editor.getGutter().isLineNumberEnabled());
            assertFalse(editor.isShowIndentGuides());
            assertEquals(0, changes.get(), "restaurar nao deve regravar preferencias");
            JMenu menu = (JMenu) appearance.createPopupMenu().getComponent(0);
            appearance.close();
            appearance.close();
            item(menu, "Wrap lines").doClick(0);
            assertTrue(editor.isLineWrapEnabled());
            assertEquals(0, changes.get());
        });
    }

    @Test
    void wrappingReflowsWhenTheAvailableWidthChanges() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            CodeEditor editor = new CodeEditor();
            editor.setText("palavras compridas para testar quebra visual ".repeat(50));
            try (TextNoteAppearance appearance = new TextNoteAppearance(editor, new NoteEditorAppearance(),
                    (key, fallback) -> fallback, (key, value) -> { })) {
                item((JMenu) appearance.createPopupMenu().getComponent(0), "Wrap lines").doClick(0);
                var textArea = editor.getTextArea();
                editor.getScrollPane().getViewport().setExtentSize(new Dimension(260, 600));
                int narrow = textArea.wrapRowCount(0);
                editor.getScrollPane().getViewport().setExtentSize(new Dimension(760, 600));
                int wide = textArea.wrapRowCount(0);
                assertTrue(narrow > wide, "aumentar largura deve reduzir linhas visuais: " + narrow + " -> " + wide);
                assertTrue(wide > 1);
                assertEquals(1, textArea.getBuffer().lineCount());
            }
        });
    }

    private static JCheckBoxMenuItem item(JMenu menu, String text) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JCheckBoxMenuItem item && text.equals(item.getText())) return item;
        }
        throw new AssertionError("Item ausente: " + text);
    }
}
