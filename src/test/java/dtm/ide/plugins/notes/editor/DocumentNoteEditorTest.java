package dtm.ide.plugins.notes.editor;

import dtm.ide.plugins.notes.model.NoteType;
import dtm.ide.plugins.notes.store.NoteDocuments;
import dtm.stools.component.panels.editor.sheet.SheetEditor;
import dtm.stools.component.panels.editor.word.WordEditor;
import dtm.stools.component.panels.editor.word.model.WordDocument;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentNoteEditorTest {

    @Test
    void wordSnapshotIsMarkedSavedOnlyForTheCapturedVersion() throws Exception {
        WordNoteEditor note = onEdt(WordNoteEditor::new);
        try {
            byte[] stored = NoteDocuments.encodeWord(WordDocument.fromText("Inicio"), null);
            Object decoded = DocumentNoteEditor.decode(NoteType.WORD, stored);
            List<String> changes = new ArrayList<>();
            onEdt(() -> {
                note.setChangeListener(() -> changes.add("changed"));
                note.showDecoded(decoded);
                assertFalse(note.isDirty());
                assertNull(note.snapshot(false, false));
                note.wordEditor().insertText("Primeira ");
                assertTrue(note.isDirty());
                return null;
            });
            assertFalse(changes.isEmpty());

            DocumentNoteEditor.Snapshot first = onEdt(() -> note.snapshot(false, false));
            assertNotNull(first);
            onEdt(() -> {
                note.wordEditor().insertText("segunda ");
                first.markSaved();
                assertTrue(note.isDirty(), "edicoes posteriores continuam pendentes");
                assertTrue(note.wordEditor().isDirty());
                return null;
            });

            DocumentNoteEditor.Snapshot second = onEdt(() -> note.snapshot(false, false));
            byte[] encoded = second.encode();
            onEdt(() -> {
                second.markSaved();
                assertFalse(note.isDirty());
                assertFalse(note.wordEditor().isDirty());
                return null;
            });
            String text = NoteDocuments.readWord(encoded).document().text();
            assertTrue(text.contains("Primeira segunda Inicio") || text.contains("Primeira segunda"), text);
        } finally {
            onEdt(() -> { note.close(); return null; });
        }
    }

    @Test
    void wordFileCommandsAreForwardedAndNewIsDisabled() throws Exception {
        WordNoteEditor note = onEdt(WordNoteEditor::new);
        try {
            List<String> calls = new ArrayList<>();
            onEdt(() -> {
                note.show(null);
                note.setFileCommands(recorder(calls));
                WordEditor editor = note.wordEditor();
                editor.insertText("pendente");
                perform(editor, "word.save");
                perform(editor, "word.saveAs");
                perform(editor, "word.open");
                assertFalse(editor.getCommands().get("word.new").isEnabled());
                assertFalse(editor.getCurrentFile().isPresent(), "a nota nao passa a apontar para outro arquivo");
                return null;
            });
            assertEquals(List.of("save", "export", "import"), calls);
        } finally {
            onEdt(() -> { note.close(); return null; });
        }
    }

    @Test
    void documentReplacedByAnEditorCommandStillCountsAsUnsaved() throws Exception {
        WordNoteEditor note = onEdt(WordNoteEditor::new);
        try {
            onEdt(() -> {
                note.show(null);
                note.wordEditor().setDocument(WordDocument.fromText("Resultado da mala direta"));
                assertTrue(note.isDirty());
                assertTrue(note.wordEditor().isDirty());
                assertNotNull(note.snapshot(false, false));
                return null;
            });
        } finally {
            onEdt(() -> { note.close(); return null; });
        }
    }

    @Test
    void unsupportedDocxOpensReadOnlyWithDiagnostics() throws Exception {
        // Um pacote assinado nao pode ser editado sem invalidar a assinatura.
        byte[] docx = docxWith("<w:p><w:r><w:t>Antes</w:t></w:r></w:p>"
                + "<w:sdt><w:sdtContent><w:p><w:r><w:t>Controle</w:t></w:r></w:p></w:sdtContent></w:sdt>",
                "_xmlsignatures/sig1.xml");
        var imported = NoteDocuments.readWord(docx);
        assertFalse(imported.isEditable());
        assertFalse(imported.diagnostics().isEmpty());
        WordNoteEditor note = onEdt(WordNoteEditor::new);
        try {
            onEdt(() -> {
                note.show(imported);
                assertTrue(note.wordEditor().isReadOnly());
                javax.swing.JLabel banner = (javax.swing.JLabel) note.component().getComponent(0);
                assertTrue(banner.isVisible(), "aviso de somente leitura visivel");
                assertTrue(banner.getText().contains("Assinatura digital"), banner.getText());
                assertFalse(note.isDirty());
                return null;
            });
        } finally {
            onEdt(() -> { note.close(); return null; });
        }
    }

    @Test
    void sheetSnapshotEncodesXlsxAndForwardsFileCommands() throws Exception {
        SheetNoteEditor note = onEdt(SheetNoteEditor::new);
        // Os dialogos da planilha so sao exibidos com o editor visivel.
        JFrame frame = onEdt(() -> {
            JFrame window = new JFrame();
            window.add(note.component());
            window.setSize(900, 600);
            window.setVisible(true);
            return window;
        });
        try {
            List<String> calls = new ArrayList<>();
            AtomicReference<DocumentNoteEditor.Snapshot> snapshot = new AtomicReference<>();
            onEdt(() -> {
                note.show(null);
                note.setFileCommands(recorder(calls));
                SheetEditor editor = note.sheetEditor();
                assertFalse(note.isDirty());
                editor.input("A1", "Fornecedor");
                editor.input("B1", "12");
                assertTrue(note.isDirty());
                snapshot.set(note.snapshot(false, false));
                editor.execute("sheet.file.save");
                editor.execute("sheet.file.saveAs");
                editor.execute("sheet.file.open");
                assertFalse(editor.execute("sheet.file.new"));
                assertNull(editor.getFile());
                return null;
            });
            assertEquals(List.of("save", "export", "import"), calls);

            byte[] encoded = snapshot.get().encode();
            assertTrue(NoteDocuments.searchableText(NoteType.SHEET, encoded).contains("Fornecedor"));
            onEdt(() -> {
                snapshot.get().markSaved();
                assertFalse(note.isDirty());
                note.sheetEditor().input("A2", "depois");
                assertTrue(note.isDirty());
                return null;
            });
        } finally {
            onEdt(() -> {
                note.close();
                frame.dispose();
                return null;
            });
        }
    }

    @Test
    void closeShutsTheEditorDownOnceAndStopsNotifications() throws Exception {
        WordNoteEditor word = onEdt(WordNoteEditor::new);
        SheetNoteEditor sheet = onEdt(SheetNoteEditor::new);
        List<String> changes = new ArrayList<>();
        onEdt(() -> {
            word.show(null);
            sheet.show(null);
            word.setChangeListener(() -> changes.add("word"));
            word.close();
            word.close();
            sheet.close();
            sheet.close();
            assertTrue(word.isClosed());
            assertTrue(word.wordEditor().isClosed());
            assertTrue(sheet.sheetEditor().isClosed());
            assertNull(word.snapshot(true, true));
            assertNull(sheet.snapshot(true, true));
            return null;
        });
        assertTrue(changes.isEmpty());
    }

    private static DocumentNoteEditor.FileCommands recorder(List<String> calls) {
        return new DocumentNoteEditor.FileCommands() {
            @Override public void save() { calls.add("save"); }
            @Override public void importFile() { calls.add("import"); }
            @Override public void exportCopy() { calls.add("export"); }
        };
    }

    private static void perform(WordEditor editor, String command) {
        editor.getCommands().get(command).actionPerformed(new ActionEvent(editor, ActionEvent.ACTION_PERFORMED, command));
    }

    private static byte[] docxWith(String body, String... extraParts) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            entry(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "</Types>");
            entry(zip, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "</Relationships>");
            entry(zip, "word/document.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "<w:sectPr/></w:body></w:document>");
            for (String part : extraParts) entry(zip, part, "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Signature/>");
        }
        return bytes.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String content) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    static <T> T onEdt(Callable<T> action) throws Exception {
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
}
