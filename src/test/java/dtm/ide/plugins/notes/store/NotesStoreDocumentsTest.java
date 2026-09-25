package dtm.ide.plugins.notes.store;

import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NoteType;
import dtm.ide.plugins.notes.model.NotesIndex;
import dtm.ide.plugins.notes.model.TitleMode;
import dtm.serialization.BinaryObjectSerializer;
import dtm.serialization.mapper.BinaryObjectMapper;
import dtm.stools.component.panels.editor.sheet.io.XlsxCodec;
import dtm.stools.component.panels.editor.sheet.model.CellValue;
import dtm.stools.component.panels.editor.sheet.model.SheetCell;
import dtm.stools.component.panels.editor.sheet.model.SheetWorkbook;
import dtm.stools.component.panels.editor.word.model.WordDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotesStoreDocumentsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void wordAndSheetNotesAreStoredAsDocxAndXlsxAndSurviveReload() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem folder = store.createFolder(null, "Relatorios");
        byte[] docx = word("Relatorio anual\nSegundo paragrafo");
        byte[] xlsx = sheet("Receita trimestral");

        NoteItem word = store.createDocument(folder.getId(), null, NoteType.WORD, "Relatorio", docx);
        NoteItem sheet = store.createDocument(null, "C:\\projects\\a", NoteType.SHEET, "  ", xlsx);

        assertEquals(NoteType.WORD, word.getType());
        assertEquals(TitleMode.MANUAL, word.getTitleMode());
        assertEquals(NotesStore.UNTITLED_SHEET, sheet.getTitle());
        assertTrue(Files.exists(temporaryDirectory.resolve("content").resolve(word.getId() + ".docx")));
        assertTrue(Files.exists(temporaryDirectory.resolve("content").resolve(sheet.getId() + ".xlsx")));

        NotesStore reloaded = new NotesStore(temporaryDirectory);
        assertArrayEquals(docx, reloaded.loadNote(word.getId()).data());
        assertArrayEquals(xlsx, reloaded.loadNote(sheet.getId()).data());
        assertEquals("Relatorio anual\nSegundo paragrafo",
                NoteDocuments.readWord(reloaded.loadNote(word.getId()).data()).document().text().strip());
    }

    @Test
    void savingADocumentKeepsItsManualTitleAndBumpsTheRevision() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem word = store.createDocument(null, null, NoteType.WORD, "Ata",
                NoteDocuments.emptyContent(NoteType.WORD));
        byte[] edited = word("Primeira linha que nao vira titulo");

        NotesStore.SaveResult saved = store.saveDocument(word.getId(), edited, word.getRevision());

        assertFalse(saved.hasConflict());
        assertEquals("Ata", saved.item().getTitle());
        assertEquals(word.getRevision() + 1, saved.item().getRevision());
        assertArrayEquals(edited, store.loadNote(word.getId()).data());
        assertThrows(IllegalArgumentException.class,
                () -> store.saveContent(word.getId(), "texto", saved.item().getRevision()));
    }

    @Test
    void searchLooksAtWordParagraphsAndSheetCells() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem word = store.createDocument(null, null, NoteType.WORD, "Documento", word("Clausula de rescisao"));
        NoteItem sheet = store.createDocument(null, null, NoteType.SHEET, "Planilha", sheet("Fornecedor Acme"));
        NoteItem text = store.createNote(null);
        store.saveContent(text.getId(), "texto qualquer", text.getRevision());

        assertEquals(List.of(word.getId()), ids(store.search("RESCISAO", false)));
        assertEquals(List.of(sheet.getId()), ids(store.searchVisible("acme", null)));

        NotesStore.SaveResult saved = store.saveDocument(sheet.getId(), sheet("Fornecedor Beta"), sheet.getRevision());
        assertFalse(saved.hasConflict());
        assertTrue(store.search("acme", false).isEmpty());
        assertEquals(List.of(sheet.getId()), ids(store.search("beta", false)));
    }

    @Test
    void staleDocumentRevisionPreservesLocalVersionAsCopyOfSameType() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        byte[] original = sheet("A");
        NoteItem sheet = store.createDocument(null, "C:\\projects\\a", NoteType.SHEET, "Custos", original);
        store.rename(sheet.getId(), "Custos 2026");
        byte[] local = sheet("versao local");

        NotesStore.SaveResult result = store.saveDocument(sheet.getId(), local, sheet.getRevision());

        assertTrue(result.hasConflict());
        NoteItem copy = result.conflictCopy();
        assertEquals(NoteType.SHEET, copy.getType());
        assertEquals("C:\\projects\\a", copy.getProjectId());
        assertTrue(copy.getTitle().startsWith("Custos 2026 (conflito"));
        assertArrayEquals(local, store.loadNote(copy.getId()).data());
        assertArrayEquals(original, store.loadNote(sheet.getId()).data());
    }

    @Test
    void trashAndPermanentDeletionHandleDocumentFiles() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem folder = store.createFolder(null, "Pasta");
        NoteItem word = store.createDocument(folder.getId(), null, NoteType.WORD, "W", word("w"));
        NoteItem sheet = store.createDocument(folder.getId(), null, NoteType.SHEET, "S", sheet("s"));
        Path content = temporaryDirectory.resolve("content");

        store.moveToTrash(folder.getId());
        assertTrue(store.find(word.getId()).orElseThrow().isDeleted());
        assertThrows(IllegalStateException.class,
                () -> store.saveDocument(word.getId(), word("x"), store.find(word.getId()).orElseThrow().getRevision()));
        store.restore(folder.getId());
        assertFalse(store.find(sheet.getId()).orElseThrow().isDeleted());

        store.moveToTrash(folder.getId());
        store.deletePermanently(folder.getId());

        assertFalse(Files.exists(content.resolve(word.getId() + ".docx")));
        assertFalse(Files.exists(content.resolve(sheet.getId() + ".xlsx")));
        assertTrue(store.list(true).isEmpty());
    }

    @Test
    void corruptIndexRecoversNotesOfEveryType() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem text = store.createNote(null);
        store.saveContent(text.getId(), "Texto recuperado", text.getRevision());
        NoteItem word = store.createDocument(null, null, NoteType.WORD, "W", word("Documento recuperado"));
        NoteItem sheet = store.createDocument(null, null, NoteType.SHEET, "S", NoteDocuments.emptyContent(NoteType.SHEET));
        Files.write(temporaryDirectory.resolve("index.bin"), new byte[]{0x01, 0x02, 0x03});

        NotesStore recovered = new NotesStore(temporaryDirectory);
        Map<String, NoteItem> items = recovered.list(false).stream()
                .collect(Collectors.toMap(NoteItem::getId, Function.identity()));

        assertEquals(3, items.size());
        assertEquals(NoteType.NOTE, items.get(text.getId()).getType());
        assertEquals("Texto recuperado", items.get(text.getId()).getTitle());
        assertEquals(NoteType.WORD, items.get(word.getId()).getType());
        assertEquals("Documento recuperado", items.get(word.getId()).getTitle());
        assertEquals(TitleMode.MANUAL, items.get(word.getId()).getTitleMode());
        assertEquals(NoteType.SHEET, items.get(sheet.getId()).getType());
        assertEquals(NotesStore.UNTITLED_SHEET, items.get(sheet.getId()).getTitle());
    }

    @Test
    void indexFromANewerVersionIsRejectedWithoutBeingTreatedAsCorrupt() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        store.createNote(null);
        BinaryObjectSerializer serializer = new BinaryObjectMapper();
        Path indexFile = temporaryDirectory.resolve("index.bin");
        NotesIndex index = serializer.readAsObject(Files.readAllBytes(indexFile), NotesIndex.class);
        index.setSchemaVersion(NotesStore.SCHEMA_VERSION + 1);
        byte[] future = serializer.encodeToByteArray(index);
        Files.write(indexFile, future);

        IncompatibleIndexException failure = assertThrows(IncompatibleIndexException.class,
                () -> new NotesStore(temporaryDirectory));

        assertEquals(NotesStore.SCHEMA_VERSION + 1, failure.getSchemaVersion());
        assertArrayEquals(future, Files.readAllBytes(indexFile));
        try (var files = Files.list(temporaryDirectory)) {
            assertTrue(files.noneMatch(path -> path.getFileName().toString().contains(".corrupt-")));
        }
    }

    @Test
    void textNotesFromTheVersionTwoIndexRemainReadable() throws Exception {
        NotesStore store = new NotesStore(temporaryDirectory);
        NoteItem legacy = store.createNote(null);
        store.saveContent(legacy.getId(), "Nota antiga", legacy.getRevision());
        BinaryObjectSerializer serializer = new BinaryObjectMapper();
        Path indexFile = temporaryDirectory.resolve("index.bin");
        NotesIndex index = serializer.readAsObject(Files.readAllBytes(indexFile), NotesIndex.class);
        index.setSchemaVersion(2);
        Files.write(indexFile, serializer.encodeToByteArray(index));

        NotesStore reloaded = new NotesStore(temporaryDirectory);
        NotesStore.LoadedNote loaded = reloaded.loadNote(legacy.getId());

        assertEquals(NoteType.NOTE, loaded.item().getType());
        assertEquals("Nota antiga", loaded.content());
        assertEquals(List.of(legacy.getId()), ids(reloaded.search("antiga", false)));
        assertTrue(Files.exists(temporaryDirectory.resolve("content").resolve(legacy.getId() + ".note")));
    }

    @Test
    void importValidationRejectsFilesTheCodecsCannotRead() {
        byte[] notAZip = "isto nao e um docx".getBytes(StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> NoteDocuments.validate(NoteType.WORD, notAZip));
        assertThrows(IOException.class, () -> NoteDocuments.validate(NoteType.SHEET, notAZip));
        assertThrows(IOException.class, () -> NoteDocuments.validate(NoteType.WORD, new byte[0]));
        assertEquals(NoteType.WORD, NoteDocuments.typeForFileName("Contrato.DOCX"));
        assertEquals(NoteType.SHEET, NoteDocuments.typeForFileName("dados.xlsx"));
        assertEquals(null, NoteDocuments.typeForFileName("macro.xlsm"));
    }

    static byte[] word(String text) throws IOException {
        return NoteDocuments.encodeWord(WordDocument.fromText(text), null);
    }

    static byte[] sheet(String firstCell) throws IOException {
        SheetWorkbook workbook = SheetWorkbook.create();
        workbook.sheet(0).cells().set(0, 0, SheetCell.of(CellValue.of(firstCell)));
        workbook.sheet(0).cells().set(1, 1, SheetCell.of(CellValue.of(42)));
        return new XlsxCodec().toBytes(workbook, null);
    }

    private static List<String> ids(List<NoteItem> items) {
        return items.stream().map(NoteItem::getId).toList();
    }
}
