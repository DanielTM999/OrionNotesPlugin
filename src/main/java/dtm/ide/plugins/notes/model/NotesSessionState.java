package dtm.ide.plugins.notes.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class NotesSessionState {
    private List<String> openNoteIds = new ArrayList<>();
    private String activeNoteId;
    private Set<String> expandedFolderIds = new LinkedHashSet<>();
    private String selectedItemId;

    public NotesSessionState copy() {
        NotesSessionState copy = new NotesSessionState();
        copy.openNoteIds = new ArrayList<>(getOpenNoteIds());
        copy.activeNoteId = activeNoteId;
        copy.expandedFolderIds = new LinkedHashSet<>(getExpandedFolderIds());
        copy.selectedItemId = selectedItemId;
        return copy;
    }

    public List<String> getOpenNoteIds() {
        if (openNoteIds == null) openNoteIds = new ArrayList<>();
        return openNoteIds;
    }
    public void setOpenNoteIds(List<String> openNoteIds) { this.openNoteIds = openNoteIds; }
    public String getActiveNoteId() { return activeNoteId; }
    public void setActiveNoteId(String activeNoteId) { this.activeNoteId = activeNoteId; }
    public Set<String> getExpandedFolderIds() {
        if (expandedFolderIds == null) expandedFolderIds = new LinkedHashSet<>();
        return expandedFolderIds;
    }
    public void setExpandedFolderIds(Set<String> expandedFolderIds) { this.expandedFolderIds = expandedFolderIds; }
    public String getSelectedItemId() { return selectedItemId; }
    public void setSelectedItemId(String selectedItemId) { this.selectedItemId = selectedItemId; }
}
