package dtm.ide.plugins.notes.store;

import dtm.ide.plugins.notes.model.NoteType;
import dtm.stools.component.panels.editor.sheet.io.SheetImportResult;
import dtm.stools.component.panels.editor.sheet.io.XlsxCodec;
import dtm.stools.component.panels.editor.sheet.model.SheetWorkbook;
import dtm.stools.component.panels.editor.sheet.model.SheetWorksheet;
import dtm.stools.component.panels.editor.word.io.DocxCodec;
import dtm.stools.component.panels.editor.word.io.WordImportResult;
import dtm.stools.component.panels.editor.word.model.WordDocument;
import dtm.stools.component.panels.editor.word.model.WordParagraph;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Conversao entre o conteudo armazenado das notas e os modelos do SwingTools.
 * Um arquivo vazio representa um documento vazio, o que mantem legiveis os arquivos
 * recuperados sem cabecalho DOCX/XLSX.
 */
public final class NoteDocuments {
    private NoteDocuments() {
        throw new IllegalStateException("utility class");
    }

    public static byte[] emptyContent(NoteType type) throws IOException {
        Objects.requireNonNull(type, "type");
        return switch (type) {
            case NOTE -> new byte[0];
            case WORD -> encodeWord(WordDocument.empty(), null);
            case SHEET -> new XlsxCodec().toBytes(SheetWorkbook.create(), null);
            case FOLDER -> throw new IllegalArgumentException("Pastas nao possuem conteudo");
        };
    }

    public static WordImportResult readWord(byte[] content) throws IOException {
        if (content == null || content.length == 0) return null;
        return new DocxCodec().read(new ByteArrayInputStream(content));
    }

    public static SheetImportResult readSheet(byte[] content) throws IOException {
        if (content == null || content.length == 0) return null;
        return new XlsxCodec().read(content);
    }

    public static byte[] encodeWord(WordDocument document, WordImportResult origin) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new DocxCodec().write(document, origin, out);
        return out.toByteArray();
    }

    /** Le o conteudo pelos codecs do SwingTools e falha se ele nao for um documento valido do tipo. */
    public static void validate(NoteType type, byte[] content) throws IOException {
        if (content == null || content.length == 0) throw new IOException("Arquivo vazio");
        switch (type) {
            case WORD -> readWord(content);
            case SHEET -> readSheet(content);
            default -> throw new IllegalArgumentException("Tipo nao importavel: " + type);
        }
    }

    public static NoteType typeForFileName(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".docx")) return NoteType.WORD;
        if (name.endsWith(".xlsx")) return NoteType.SHEET;
        return null;
    }

    /** Texto pesquisavel: paragrafos no Word e valores exibidos das celulas na planilha. */
    public static String searchableText(NoteType type, byte[] content) throws IOException {
        if (content == null || content.length == 0 || type == null) return "";
        return switch (type) {
            case NOTE -> new String(content, StandardCharsets.UTF_8);
            case WORD -> wordText(readWord(content).document());
            case SHEET -> sheetText(readSheet(content).workbook());
            case FOLDER -> "";
        };
    }

    public static String wordText(WordDocument document) {
        StringBuilder text = new StringBuilder();
        for (WordParagraph paragraph : document.paragraphs()) {
            String value = paragraph.text();
            if (value != null && !value.isBlank()) text.append(value).append('\n');
        }
        return text.toString();
    }

    public static String sheetText(SheetWorkbook workbook) {
        StringBuilder text = new StringBuilder();
        for (SheetWorksheet sheet : workbook.sheets()) {
            sheet.cells().forEach((row, column, cell) -> {
                if (cell == null || cell.value() == null || cell.value().isEmpty()) return;
                String value = cell.value().display();
                if (value != null && !value.isBlank()) text.append(value).append('\n');
            });
        }
        return text.toString();
    }
}
