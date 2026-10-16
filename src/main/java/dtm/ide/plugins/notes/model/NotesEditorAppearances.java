package dtm.ide.plugins.notes.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Preferencias visuais independentes do indice e das revisoes de conteudo. */
public final class NotesEditorAppearances {
    private int schemaVersion = 1;
    private Map<String, NoteEditorAppearance> notes = new LinkedHashMap<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Map<String, NoteEditorAppearance> getNotes() {
        if (notes == null) notes = new LinkedHashMap<>();
        return notes;
    }
    public void setNotes(Map<String, NoteEditorAppearance> notes) { this.notes = notes; }
}
