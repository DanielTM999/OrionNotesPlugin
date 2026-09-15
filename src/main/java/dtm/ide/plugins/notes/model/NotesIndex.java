package dtm.ide.plugins.notes.model;

import java.util.ArrayList;
import java.util.List;

public final class NotesIndex {
    private int schemaVersion = 2;
    private List<NoteItem> items = new ArrayList<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public List<NoteItem> getItems() {
        if (items == null) items = new ArrayList<>();
        return items;
    }
    public void setItems(List<NoteItem> items) { this.items = items; }
}
