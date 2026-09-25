package dtm.ide.plugins.notes.model;

public enum NoteType {
    NOTE,
    FOLDER,
    WORD,
    SHEET;

    public boolean isDocument() { return this == WORD || this == SHEET; }

    public String extension() {
        return switch (this) {
            case NOTE -> ".note";
            case WORD -> ".docx";
            case SHEET -> ".xlsx";
            case FOLDER -> null;
        };
    }
}
