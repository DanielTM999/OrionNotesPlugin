package dtm.ide.plugins.notes.model;

import java.util.LinkedHashMap;
import java.util.Map;

public final class NotesSessions {
    private int schemaVersion = 1;
    private Map<String, NotesSessionState> contexts = new LinkedHashMap<>();

    public int getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
    public Map<String, NotesSessionState> getContexts() {
        if (contexts == null) contexts = new LinkedHashMap<>();
        return contexts;
    }
    public void setContexts(Map<String, NotesSessionState> contexts) { this.contexts = contexts; }
}
