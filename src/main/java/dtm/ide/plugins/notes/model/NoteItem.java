package dtm.ide.plugins.notes.model;

import java.util.Objects;

public final class NoteItem {
    private String id;
    private NoteType type;
    private String projectId;
    private String parentId;
    private String title;
    private TitleMode titleMode;
    private int order;
    private String createdAt;
    private String updatedAt;
    private String deletedAt;
    private String originalParentId;
    private long revision;

    public NoteItem() {
    }

    public NoteItem copy() {
        NoteItem copy = new NoteItem();
        copy.id = id;
        copy.type = type;
        copy.projectId = projectId;
        copy.parentId = parentId;
        copy.title = title;
        copy.titleMode = titleMode;
        copy.order = order;
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        copy.deletedAt = deletedAt;
        copy.originalParentId = originalParentId;
        copy.revision = revision;
        return copy;
    }

    public boolean isDeleted() { return deletedAt != null && !deletedAt.isBlank(); }
    public boolean isNote() { return type == NoteType.NOTE; }
    public boolean isFolder() { return type == NoteType.FOLDER; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public NoteType getType() { return type; }
    public void setType(NoteType type) { this.type = type; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getParentId() { return parentId; }
    public void setParentId(String parentId) { this.parentId = parentId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public TitleMode getTitleMode() { return titleMode; }
    public void setTitleMode(TitleMode titleMode) { this.titleMode = titleMode; }
    public int getOrder() { return order; }
    public void setOrder(int order) { this.order = order; }
    public String getCreatedAt() { return createdAt; }
    public void setCreatedAt(String createdAt) { this.createdAt = createdAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }
    public String getDeletedAt() { return deletedAt; }
    public void setDeletedAt(String deletedAt) { this.deletedAt = deletedAt; }
    public String getOriginalParentId() { return originalParentId; }
    public void setOriginalParentId(String originalParentId) { this.originalParentId = originalParentId; }
    public long getRevision() { return revision; }
    public void setRevision(long revision) { this.revision = revision; }

    @Override
    public boolean equals(Object other) {
        return other instanceof NoteItem item && Objects.equals(id, item.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
