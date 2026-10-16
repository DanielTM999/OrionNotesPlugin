package dtm.ide.plugins.notes.model;

import java.util.LinkedHashMap;
import java.util.Map;

/** Somente opcoes alteradas pelo usuario; as demais herdam o padrao do editor da IDE. */
public final class NoteEditorAppearance {
    private Map<String, Boolean> options = new LinkedHashMap<>();

    public Map<String, Boolean> getOptions() {
        if (options == null) options = new LinkedHashMap<>();
        return options;
    }

    public void setOptions(Map<String, Boolean> options) { this.options = options; }

    public NoteEditorAppearance copy() {
        NoteEditorAppearance copy = new NoteEditorAppearance();
        copy.options.putAll(getOptions());
        return copy;
    }
}
