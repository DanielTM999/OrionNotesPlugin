package dtm.ide.plugins.notes.ui;

import dtm.stools.theme.ThemeIcon;
import dtm.stools.utils.ImageUtils;

import javax.swing.Icon;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class NotesIcons {
    public static final String NOTES = "notes";
    public static final String NOTE = "note";
    public static final String NOTE_ADD = "note-add";
    public static final String FOLDER = "folder";
    public static final String FOLDER_ADD = "folder-add";
    public static final String SEARCH = "search";
    public static final String TRASH = "trash";

    private static final String PATH_PATTERN = "/img/icons/%s.svg";
    private static final Map<String, Icon> CACHE = new ConcurrentHashMap<>();

    private NotesIcons() {
        throw new IllegalStateException("utility class");
    }

    public static Icon of(String name, int size) {
        return CACHE.computeIfAbsent(name + "@" + size, key -> ImageUtils
                .getIconByResource(NotesIcons.class, PATH_PATTERN.formatted(name))
                .map(icon -> ImageUtils.resizeIcon(icon, size, size))
                .map(ThemeIcon::new)
                .orElse(null));
    }
}
