package dtm.ide.plugins.notes.ui;

import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.store.NotesStore;
import dtm.stools.configs.UiTokens;
import dtm.stools.component.tree.TreeDropContext;
import dtm.stools.component.tree.TreeNode;
import dtm.stools.component.tree.TreeView;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class NotesPanel extends JPanel {
    private final NotesStore store;
    private final Actions actions;
    private final TextResolver texts;
    private final TreeView<Entry> tree = new TreeView<>();
    private final JTextField searchField = new JTextField();
    private final Timer searchTimer;
    private final Map<String, TreeNode<Entry>> itemNodes = new HashMap<>();

    public NotesPanel(NotesStore store, Actions actions, TextResolver texts) {
        super(new BorderLayout());
        this.store = Objects.requireNonNull(store, "store");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.texts = Objects.requireNonNull(texts, "texts");
        this.searchTimer = new Timer(250, event -> refresh());
        this.searchTimer.setRepeats(false);
        setBackground(UiTokens.background());
        add(buildHeader(), BorderLayout.NORTH);
        configureTree();
        JScrollPane scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(tree.getBackground());
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new BorderLayout(0, UiTokens.space(2)));
        header.setBackground(UiTokens.surfaceAlt());
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTokens.border()),
                new EmptyBorder(UiTokens.space(2), UiTokens.space(3), UiTokens.space(2), UiTokens.space(3))
        ));

        JPanel bar = new JPanel(new BorderLayout(UiTokens.space(2), 0));
        bar.setOpaque(false);

        JLabel title = new JLabel(text("tree.notes", "Notes").toUpperCase(),
                NotesIcons.of(NotesIcons.NOTES, 18), JLabel.LEFT);
        title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
        title.setForeground(UiTokens.foreground());
        title.setIconTextGap(UiTokens.space(2));
        bar.add(title, BorderLayout.WEST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, UiTokens.space(1), 0));
        buttons.setOpaque(false);
        buttons.add(toolButton(
                NotesIcons.of(NotesIcons.NOTE_ADD, 17),
                text("tooltip.newNote", "New note (Ctrl+Alt+N)"),
                this::createNote
        ));
        buttons.add(toolButton(
                NotesIcons.of(NotesIcons.FOLDER_ADD, 17),
                text("tooltip.newFolder", "New folder"),
                this::createFolderFromUi
        ));
        bar.add(buttons, BorderLayout.EAST);
        header.add(bar, BorderLayout.NORTH);

        searchField.putClientProperty("JTextField.placeholderText", text("search.placeholder", "Search notes"));
        searchField.putClientProperty("JTextField.leadingIcon", NotesIcons.of(NotesIcons.SEARCH, 15));
        searchField.putClientProperty("JTextField.showClearButton", true);
        searchField.putClientProperty("FlatLaf.style", "arc: 8");
        searchField.setPreferredSize(new Dimension(0, 32));
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { searchTimer.restart(); }
            @Override public void removeUpdate(DocumentEvent event) { searchTimer.restart(); }
            @Override public void changedUpdate(DocumentEvent event) { searchTimer.restart(); }
        });
        header.add(searchField, BorderLayout.SOUTH);
        return header;
    }

    private JButton toolButton(javax.swing.Icon icon, String tooltip, Runnable action) {
        JButton button = new JButton(icon);
        button.setToolTipText(tooltip);
        button.setPreferredSize(new Dimension(28, 28));
        button.setMinimumSize(new Dimension(28, 28));
        button.setMaximumSize(new Dimension(28, 28));
        button.setForeground(UiTokens.muted());
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setOpaque(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        button.putClientProperty("JButton.buttonType", "toolBarButton");
        button.addActionListener(event -> action.run());
        return button;
    }

    private void configureTree() {
        tree.setRootVisible(false);
        tree.setRowHeight(25);
        tree.setBorder(new EmptyBorder(UiTokens.space(1), UiTokens.space(1), UiTokens.space(2), UiTokens.space(1)));
        tree.setDragAndDropEnabled(true);
        tree.setDropPolicy(this::handleDrop);
        tree.setPopupMenuProvider(context -> popupFor(context.node()));
        tree.addTreeSelectionListener(event -> actions.onTreeStateChanged(
                expandedFolderIds(), selectedItemId()));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(event)) return;
                TreeNode<Entry> node = tree.getNodeAtMouse(event);
                if (node != null && node.getData().kind() == EntryKind.ITEM && node.getData().item().isNote()
                        && !node.getData().item().isDeleted()) {
                    actions.openNote(node.getData().item().getId());
                }
            }
        });
    }

    public void refresh() {
        Set<String> expansion = tree.getRootNode() == null ? Set.of() : tree.snapshotExpansion();
        String selected = selectedItemId();
        itemNodes.clear();

        TreeNode<Entry> root = node(new Entry(EntryKind.ROOT, null, text("tree.notes", "Notes")), "notes-root");
        root.setAlwaysParent(true);
        root.setDropTarget(true);
        String query = searchField.getText() == null ? "" : searchField.getText().strip();
        if (query.isBlank()) {
            appendHierarchy(root, null, store.list(false), new HashSet<>());
            TreeNode<Entry> trash = node(new Entry(EntryKind.TRASH, null, text("tree.trash", "Trash")), "notes-trash");
            trash.setAlwaysParent(true);
            trash.setIcon(NotesIcons.of(NotesIcons.TRASH, 16));
            appendTrash(trash, store.list(true));
            root.addChild(trash);
        } else {
            for (NoteItem item : store.search(query, false)) {
                String breadcrumb = store.breadcrumb(item);
                String label = breadcrumb.isBlank() ? item.getTitle() : item.getTitle() + " - " + breadcrumb;
                root.addChild(itemNode(item, label));
            }
        }
        tree.setRoot(root);
        tree.restoreExpansion(expansion);
        if (selected != null && itemNodes.containsKey(selected)) tree.selectNode(itemNodes.get(selected));
    }

    public void restoreTreeState(Set<String> expandedIds, String selectedId) {
        tree.restoreExpansion(expandedIds == null ? Set.of() : expandedIds);
        if (selectedId != null && itemNodes.containsKey(selectedId)) tree.selectNode(itemNodes.get(selectedId));
    }

    public Set<String> expandedFolderIds() {
        Set<String> result = new LinkedHashSet<>();
        for (String id : tree.snapshotExpansion()) {
            if (itemNodes.containsKey(id) && itemNodes.get(id).getData().item().isFolder()) result.add(id);
        }
        return result;
    }

    public String selectedItemId() {
        TreeNode<Entry> selected = tree.getSelectedNode();
        if (selected == null || selected.getData() == null || selected.getData().item() == null) return null;
        return selected.getData().item().getId();
    }

    public String selectedFolderId() {
        TreeNode<Entry> selected = tree.getSelectedNode();
        if (selected == null || selected.getData() == null) return null;
        Entry entry = selected.getData();
        if (entry.kind() != EntryKind.ITEM || entry.item().isDeleted()) return null;
        return entry.item().isFolder() ? entry.item().getId() : entry.item().getParentId();
    }

    public void createNote() {
        actions.createNote(selectedFolderId());
    }

    public void createFolderFromUi() {
        actions.requestCreateFolder(selectedFolderId());
    }

    private void appendHierarchy(TreeNode<Entry> parent, String parentId, List<NoteItem> items, Set<String> visited) {
        for (NoteItem item : items) {
            if (!Objects.equals(parentId, item.getParentId()) || !visited.add(item.getId())) continue;
            TreeNode<Entry> child = itemNode(item, item.getTitle());
            parent.addChild(child);
            if (item.isFolder()) appendHierarchy(child, item.getId(), items, visited);
        }
        if (parentId == null) {
            for (NoteItem item : items) {
                if (!visited.contains(item.getId())) {
                    TreeNode<Entry> child = itemNode(item, item.getTitle());
                    parent.addChild(child);
                    visited.add(item.getId());
                    if (item.isFolder()) appendHierarchy(child, item.getId(), items, visited);
                }
            }
        }
    }

    private void appendTrash(TreeNode<Entry> trash, List<NoteItem> items) {
        Set<String> deletedIds = new HashSet<>();
        for (NoteItem item : items) deletedIds.add(item.getId());
        Set<String> visited = new HashSet<>();
        for (NoteItem item : items) {
            if (item.getParentId() != null && deletedIds.contains(item.getParentId())) continue;
            TreeNode<Entry> child = itemNode(item, item.getTitle());
            trash.addChild(child);
            visited.add(item.getId());
            if (item.isFolder()) appendHierarchy(child, item.getId(), items, visited);
        }
    }

    private TreeNode<Entry> itemNode(NoteItem item, String label) {
        TreeNode<Entry> node = node(new Entry(EntryKind.ITEM, item, label), item.getId());
        node.setIcon(NotesIcons.of(item.isFolder() ? NotesIcons.FOLDER : NotesIcons.NOTE, 16));
        node.setDraggable(!item.isDeleted());
        node.setDropTarget(item.isFolder() && !item.isDeleted());
        node.setAlwaysParent(item.isFolder());
        itemNodes.put(item.getId(), node);
        return node;
    }

    private TreeNode<Entry> node(Entry entry, String id) {
        TreeNode<Entry> node = new TreeNode<>(entry, entry.label());
        node.setId(id);
        return node;
    }

    private boolean handleDrop(TreeDropContext<Entry> context) {
        if (context == null || context.draggedNodes().isEmpty()) return false;
        TreeNode<Entry> target = context.targetNode();
        String parentId = null;
        if (target != null && target.getData() != null) {
            Entry targetEntry = target.getData();
            if (targetEntry.kind() == EntryKind.TRASH) return false;
            if (targetEntry.kind() == EntryKind.ITEM) {
                if (!targetEntry.item().isFolder() || targetEntry.item().isDeleted()) return false;
                parentId = targetEntry.item().getId();
            }
        }
        int order = Math.max(0, context.childIndex());
        for (TreeNode<Entry> dragged : new ArrayList<>(context.draggedNodes())) {
            Entry entry = dragged.getData();
            if (entry == null || entry.kind() != EntryKind.ITEM || entry.item().isDeleted()) return false;
            if (!actions.move(entry.item().getId(), parentId, order++)) return false;
        }
        SwingUtilities.invokeLater(this::refresh);
        return true;
    }

    private JPopupMenu popupFor(TreeNode<Entry> node) {
        JPopupMenu menu = new JPopupMenu();
        if (node == null || node.getData() == null) return menu;
        Entry entry = node.getData();
        if (entry.kind() == EntryKind.ROOT) {
            add(menu, text("button.newNote", "New note"), event -> createNote());
            add(menu, text("button.newFolder", "New folder"), event -> createFolderFromUi());
            return menu;
        }
        if (entry.kind() == EntryKind.TRASH) {
            add(menu, text("menu.emptyTrash", "Empty trash"), event -> actions.requestEmptyTrash());
            return menu;
        }
        NoteItem item = entry.item();
        if (item.isDeleted()) {
            add(menu, text("menu.restore", "Restore"), event -> actions.restore(item.getId()));
            add(menu, text("menu.deletePermanently", "Delete permanently"),
                    event -> actions.requestDeletePermanently(item.getId(), item.getTitle()));
            return menu;
        }
        if (item.isNote()) add(menu, text("menu.open", "Open"), event -> actions.openNote(item.getId()));
        if (item.isFolder()) {
            add(menu, text("button.newNote", "New note"), event -> actions.createNote(item.getId()));
            add(menu, text("button.newFolder", "New folder"), event -> actions.requestCreateFolder(item.getId()));
        }
        add(menu, text("menu.rename", "Rename"), event -> actions.requestRename(item.getId(), item.getTitle()));
        add(menu, text("menu.moveToTrash", "Move to trash"), event -> actions.moveToTrash(item.getId()));
        return menu;
    }

    private String text(String key, String fallback) {
        return texts.resolve(key, fallback);
    }

    private void add(JPopupMenu menu, String label, java.awt.event.ActionListener listener) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(listener);
        menu.add(item);
    }

    public interface Actions {
        void createNote(String parentId);
        void requestCreateFolder(String parentId);
        void openNote(String id);
        void requestRename(String id, String currentTitle);
        boolean move(String id, String parentId, int order);
        void moveToTrash(String id);
        void restore(String id);
        void requestDeletePermanently(String id, String title);
        void requestEmptyTrash();
        void onTreeStateChanged(Set<String> expandedIds, String selectedItemId);
    }

    @FunctionalInterface
    public interface TextResolver {
        String resolve(String key, String fallback);
    }

    private enum EntryKind {
        ROOT,
        ITEM,
        TRASH
    }

    private record Entry(EntryKind kind, NoteItem item, String label) {
    }
}
