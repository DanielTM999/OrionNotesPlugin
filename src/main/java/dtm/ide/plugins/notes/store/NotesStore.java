package dtm.ide.plugins.notes.store;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import dtm.ide.plugins.notes.model.NoteItem;
import dtm.ide.plugins.notes.model.NoteType;
import dtm.ide.plugins.notes.model.NotesIndex;
import dtm.ide.plugins.notes.model.NotesSessionState;
import dtm.ide.plugins.notes.model.NotesSessions;
import dtm.ide.plugins.notes.model.TitleMode;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class NotesStore {
    public static final int SCHEMA_VERSION = 1;
    public static final String UNTITLED = "Nota sem titulo";

    private static final DateTimeFormatter CONFLICT_TIME = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH-mm")
            .withZone(ZoneId.systemDefault());
    private static final Map<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();

    private final Path root;
    private final Path contentDirectory;
    private final Path temporaryDirectory;
    private final Path indexFile;
    private final Path sessionFile;
    private final Path lockFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final ReentrantLock jvmLock;

    private NotesIndex index;

    public NotesStore(Path root) throws IOException {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.contentDirectory = this.root.resolve("content");
        this.temporaryDirectory = this.root.resolve("tmp");
        this.indexFile = this.root.resolve("index.json");
        this.sessionFile = this.root.resolve("session.json");
        this.lockFile = this.root.resolve("store.lock");
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.root, ignored -> new ReentrantLock());
        this.index = new NotesIndex();
        Files.createDirectories(contentDirectory);
        Files.createDirectories(temporaryDirectory);
        cleanupTemporaryFiles();
        this.index = loadIndexRecovering();
    }

    public Path getRoot() {
        return root;
    }

    public List<NoteItem> list(boolean deleted) {
        return withJvmLock(() -> {
            refreshIndexForRead();
            return index.getItems().stream()
                    .filter(item -> item.isDeleted() == deleted)
                    .sorted(itemComparator())
                    .map(NoteItem::copy)
                    .toList();
        });
    }

    public Optional<NoteItem> find(String id) {
        if (id == null) return Optional.empty();
        return withJvmLock(() -> {
            refreshIndexForRead();
            return findInternal(id).map(NoteItem::copy);
        });
    }

    public NoteItem createNote(String parentId) throws IOException {
        return mutate(() -> {
            validateParent(parentId);
            NoteItem item = newItem(NoteType.NOTE, parentId, UNTITLED);
            index.getItems().add(item);
            writeContent(item.getId(), "");
            return item.copy();
        });
    }

    public NoteItem createFolder(String parentId, String title) throws IOException {
        return mutate(() -> {
            validateParent(parentId);
            NoteItem item = newItem(NoteType.FOLDER, parentId, normalizeManualTitle(title, "Nova pasta"));
            item.setTitleMode(TitleMode.MANUAL);
            index.getItems().add(item);
            return item.copy();
        });
    }

    public NoteItem rename(String id, String title) throws IOException {
        return mutate(() -> {
            NoteItem item = requireItem(id);
            item.setTitle(normalizeManualTitle(title, item.isFolder() ? "Nova pasta" : UNTITLED));
            item.setTitleMode(TitleMode.MANUAL);
            item.setUpdatedAt(Instant.now().toString());
            item.setRevision(item.getRevision() + 1);
            return item.copy();
        });
    }

    public NoteItem move(String id, String parentId, int requestedOrder) throws IOException {
        return mutate(() -> {
            NoteItem item = requireActiveItem(id);
            validateParent(parentId);
            if (item.isFolder() && parentId != null && isDescendant(parentId, id)) {
                throw new IllegalArgumentException("Uma pasta nao pode ser movida para dentro dela mesma");
            }
            String oldParentId = item.getParentId();
            item.setParentId(parentId);
            List<NoteItem> destination = index.getItems().stream()
                    .filter(candidate -> !candidate.isDeleted()
                            && !Objects.equals(candidate.getId(), id)
                            && Objects.equals(parentId, candidate.getParentId()))
                    .sorted(itemComparator()).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            int insertion = requestedOrder < 0 ? destination.size() : Math.min(requestedOrder, destination.size());
            destination.add(insertion, item);
            for (int i = 0; i < destination.size(); i++) destination.get(i).setOrder(i);
            item.setUpdatedAt(Instant.now().toString());
            item.setRevision(item.getRevision() + 1);
            if (!Objects.equals(oldParentId, parentId)) normalizeSiblingOrder(oldParentId);
            return item.copy();
        });
    }

    public void moveToTrash(String id) throws IOException {
        mutate(() -> {
            NoteItem rootItem = requireActiveItem(id);
            rootItem.setOriginalParentId(rootItem.getParentId());
            String deletedAt = Instant.now().toString();
            for (NoteItem item : subtree(id)) {
                item.setDeletedAt(deletedAt);
                item.setUpdatedAt(deletedAt);
                item.setRevision(item.getRevision() + 1);
            }
            return null;
        });
    }

    public void restore(String id) throws IOException {
        mutate(() -> {
            NoteItem rootItem = requireItem(id);
            String destination = rootItem.getOriginalParentId();
            if (destination != null && findInternal(destination).filter(item -> !item.isDeleted() && item.isFolder()).isEmpty()) {
                destination = null;
            }
            rootItem.setParentId(destination);
            rootItem.setOriginalParentId(null);
            for (NoteItem item : subtree(id)) {
                item.setDeletedAt(null);
                item.setUpdatedAt(Instant.now().toString());
                item.setRevision(item.getRevision() + 1);
            }
            return null;
        });
    }

    public void deletePermanently(String id) throws IOException {
        mutate(() -> {
            List<NoteItem> removed = subtree(id);
            if (removed.stream().anyMatch(item -> !item.isDeleted())) {
                throw new IllegalStateException("Somente itens da lixeira podem ser excluidos definitivamente");
            }
            for (NoteItem item : removed) {
                if (item.isNote()) Files.deleteIfExists(contentPath(item.getId()));
            }
            Set<String> removedIds = removed.stream().map(NoteItem::getId).collect(java.util.stream.Collectors.toSet());
            index.getItems().removeIf(item -> removedIds.contains(item.getId()));
            return null;
        });
    }

    public void emptyTrash() throws IOException {
        mutate(() -> {
            List<NoteItem> removed = index.getItems().stream().filter(NoteItem::isDeleted).toList();
            for (NoteItem item : removed) {
                if (item.isNote()) Files.deleteIfExists(contentPath(item.getId()));
            }
            index.getItems().removeIf(NoteItem::isDeleted);
            return null;
        });
    }

    public LoadedNote loadNote(String id) throws IOException {
        return withFileLock(() -> {
            index = readIndex(indexFile);
            NoteItem item = findInternal(id).map(NoteItem::copy)
                    .orElseThrow(() -> new IllegalArgumentException("Nota nao encontrada: " + id));
            if (!item.isNote()) throw new IllegalArgumentException("O item nao e uma nota: " + id);
            String content = Files.exists(contentPath(id))
                    ? Files.readString(contentPath(id), StandardCharsets.UTF_8) : "";
            return new LoadedNote(item, content);
        });
    }

    public SaveResult saveContent(String id, String content, long expectedRevision) throws IOException {
        return withFileLock(() -> {
            index = readIndex(indexFile);
            NoteItem current = requireActiveItem(id);
            if (!current.isNote()) throw new IllegalArgumentException("O item nao e uma nota: " + id);
            String safeContent = content == null ? "" : content;
            if (current.getRevision() != expectedRevision) {
                NoteItem conflict = newItem(NoteType.NOTE, current.getParentId(), conflictTitle(current.getTitle()));
                conflict.setTitleMode(TitleMode.MANUAL);
                index.getItems().add(conflict);
                writeContent(conflict.getId(), safeContent);
                writeIndex();
                return SaveResult.conflict(current.copy(), conflict.copy());
            }

            writeContent(current.getId(), safeContent);
            if (current.getTitleMode() == TitleMode.AUTO) {
                current.setTitle(deriveTitle(safeContent));
            }
            current.setRevision(current.getRevision() + 1);
            current.setUpdatedAt(Instant.now().toString());
            writeIndex();
            return SaveResult.saved(current.copy());
        });
    }

    public List<NoteItem> search(String query, boolean includeDeleted) {
        String term = normalizeSearch(query);
        if (term.isBlank()) return list(includeDeleted);
        List<NoteItem> candidates = list(includeDeleted);
        List<NoteItem> matches = new ArrayList<>();
        for (NoteItem item : candidates) {
            if (!item.isNote()) continue;
            String title = normalizeSearch(item.getTitle());
            if (title.contains(term)) {
                matches.add(item);
                continue;
            }
            try {
                String content = Files.exists(contentPath(item.getId()))
                        ? Files.readString(contentPath(item.getId()), StandardCharsets.UTF_8) : "";
                if (normalizeSearch(content).contains(term)) matches.add(item);
            } catch (IOException ignored) {
            }
        }
        return List.copyOf(matches);
    }

    public NotesSessionState loadSession(String context) {
        return withJvmLock(() -> {
            NotesSessions sessions = readSessionsRecovering();
            NotesSessionState state = sessions.getContexts().get(contextKey(context));
            return state == null ? new NotesSessionState() : state.copy();
        });
    }

    public void saveSession(String context, NotesSessionState state) throws IOException {
        withFileLock(() -> {
            NotesSessions sessions = readSessionsRecovering();
            sessions.getContexts().put(contextKey(context), state == null ? new NotesSessionState() : state.copy());
            writeJsonAtomic(sessionFile, sessions);
            return null;
        });
    }

    public String breadcrumb(NoteItem item) {
        if (item == null) return "";
        Deque<String> parts = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        String parentId = item.getParentId();
        while (parentId != null && visited.add(parentId)) {
            Optional<NoteItem> parent = find(parentId);
            if (parent.isEmpty()) break;
            parts.addFirst(parent.get().getTitle());
            parentId = parent.get().getParentId();
        }
        return String.join(" / ", parts);
    }

    public static String deriveTitle(String content) {
        if (content != null) {
            for (String line : content.split("\\R", -1)) {
                String normalized = line.strip().replaceAll("\\s+", " ");
                if (!normalized.isBlank()) {
                    int end = Math.min(80, normalized.length());
                    return normalized.substring(0, end);
                }
            }
        }
        return UNTITLED;
    }

    private NoteItem newItem(NoteType type, String parentId, String title) {
        String now = Instant.now().toString();
        NoteItem item = new NoteItem();
        item.setId(UUID.randomUUID().toString());
        item.setType(type);
        item.setParentId(parentId);
        item.setTitle(title);
        item.setTitleMode(type == NoteType.NOTE ? TitleMode.AUTO : TitleMode.MANUAL);
        item.setOrder(nextOrder(parentId));
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        item.setRevision(0);
        return item;
    }

    private int nextOrder(String parentId) {
        return index.getItems().stream()
                .filter(item -> !item.isDeleted() && Objects.equals(parentId, item.getParentId()))
                .mapToInt(NoteItem::getOrder)
                .max().orElse(-1) + 1;
    }

    private void normalizeSiblingOrder(String parentId) {
        List<NoteItem> siblings = index.getItems().stream()
                .filter(item -> !item.isDeleted() && Objects.equals(parentId, item.getParentId()))
                .sorted(itemComparator()).toList();
        for (int i = 0; i < siblings.size(); i++) siblings.get(i).setOrder(i);
    }

    private Comparator<NoteItem> itemComparator() {
        return Comparator.comparing((NoteItem item) -> item.isFolder() ? 0 : 1)
                .thenComparingInt(NoteItem::getOrder)
                .thenComparing(NoteItem::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
    }

    private List<NoteItem> subtree(String id) {
        List<NoteItem> result = new ArrayList<>();
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        pending.add(id);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) continue;
            findInternal(current).ifPresent(result::add);
            index.getItems().stream()
                    .filter(item -> Objects.equals(current, item.getParentId()))
                    .map(NoteItem::getId).forEach(pending::addLast);
        }
        return result;
    }

    private boolean isDescendant(String candidateId, String ancestorId) {
        Set<String> visited = new HashSet<>();
        String current = candidateId;
        while (current != null && visited.add(current)) {
            if (Objects.equals(current, ancestorId)) return true;
            current = findInternal(current).map(NoteItem::getParentId).orElse(null);
        }
        return false;
    }

    private void validateParent(String parentId) {
        if (parentId == null) return;
        NoteItem parent = requireActiveItem(parentId);
        if (!parent.isFolder()) throw new IllegalArgumentException("O destino nao e uma pasta");
    }

    private NoteItem requireItem(String id) {
        return findInternal(id).orElseThrow(() -> new IllegalArgumentException("Item nao encontrado: " + id));
    }

    private NoteItem requireActiveItem(String id) {
        NoteItem item = requireItem(id);
        if (item.isDeleted()) throw new IllegalStateException("Item esta na lixeira: " + id);
        return item;
    }

    private Optional<NoteItem> findInternal(String id) {
        return index.getItems().stream().filter(item -> Objects.equals(id, item.getId())).findFirst();
    }

    private <T> T mutate(IoSupplier<T> mutation) throws IOException {
        return withFileLock(() -> {
            index = Files.exists(indexFile) ? readIndex(indexFile) : new NotesIndex();
            T result = mutation.get();
            writeIndex();
            return result;
        });
    }

    private void writeIndex() throws IOException {
        index.setSchemaVersion(SCHEMA_VERSION);
        writeJsonAtomic(indexFile, index);
    }

    private void writeContent(String id, String content) throws IOException {
        writeTextAtomic(contentPath(id), content == null ? "" : content);
    }

    private Path contentPath(String id) {
        return contentDirectory.resolve(id + ".note");
    }

    private NotesIndex loadIndexRecovering() throws IOException {
        if (!Files.exists(indexFile)) {
            NotesIndex fresh = new NotesIndex();
            writeJsonAtomic(indexFile, fresh);
            return fresh;
        }
        try {
            return readIndex(indexFile);
        } catch (IOException | JsonParseException failure) {
            preserveCorrupt(indexFile);
            NotesIndex rebuilt = rebuildIndexFromContent();
            writeJsonAtomic(indexFile, rebuilt);
            return rebuilt;
        }
    }

    private NotesIndex readIndex(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            NotesIndex loaded = gson.fromJson(reader, NotesIndex.class);
            if (loaded == null) throw new JsonParseException("Indice vazio");
            if (loaded.getSchemaVersion() > SCHEMA_VERSION) {
                throw new IOException("Versao de indice nao suportada: " + loaded.getSchemaVersion());
            }
            loaded.getItems();
            return loaded;
        }
    }

    private void refreshIndexForRead() {
        if (!Files.exists(indexFile)) return;
        try {
            index = readIndex(indexFile);
        } catch (IOException | JsonParseException ignored) {
        }
    }

    private NotesIndex rebuildIndexFromContent() throws IOException {
        NotesIndex rebuilt = new NotesIndex();
        try (Stream<Path> files = Files.list(contentDirectory)) {
            files.filter(path -> path.getFileName().toString().endsWith(".note")).sorted().forEach(path -> {
                try {
                    String fileName = path.getFileName().toString();
                    String id = fileName.substring(0, fileName.length() - ".note".length());
                    try {
                        UUID.fromString(id);
                    } catch (IllegalArgumentException invalid) {
                        id = UUID.randomUUID().toString();
                        Files.move(path, contentPath(id), StandardCopyOption.REPLACE_EXISTING);
                    }
                    String content = Files.readString(path, StandardCharsets.UTF_8);
                    NoteItem item = newItem(NoteType.NOTE, null, deriveTitle(content));
                    item.setId(id);
                    rebuilt.getItems().add(item);
                } catch (IOException ignored) {
                }
            });
        }
        return rebuilt;
    }

    private NotesSessions readSessionsRecovering() {
        if (!Files.exists(sessionFile)) return new NotesSessions();
        try (Reader reader = Files.newBufferedReader(sessionFile, StandardCharsets.UTF_8)) {
            NotesSessions sessions = gson.fromJson(reader, NotesSessions.class);
            if (sessions == null || sessions.getSchemaVersion() > SCHEMA_VERSION) return new NotesSessions();
            sessions.getContexts();
            return sessions;
        } catch (IOException | JsonParseException failure) {
            try { preserveCorrupt(sessionFile); } catch (IOException ignored) { }
            return new NotesSessions();
        }
    }

    private void preserveCorrupt(Path path) throws IOException {
        if (!Files.exists(path)) return;
        String suffix = ".corrupt-" + System.currentTimeMillis();
        Files.move(path, path.resolveSibling(path.getFileName() + suffix), StandardCopyOption.REPLACE_EXISTING);
    }

    private void cleanupTemporaryFiles() throws IOException {
        try (Stream<Path> files = Files.list(temporaryDirectory)) {
            for (Path path : files.toList()) Files.deleteIfExists(path);
        }
    }

    private void writeJsonAtomic(Path target, Object value) throws IOException {
        Path temporary = temporaryDirectory.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            gson.toJson(value, writer);
        }
        moveAtomic(temporary, target);
    }

    private void writeTextAtomic(Path target, String value) throws IOException {
        Path temporary = temporaryDirectory.resolve(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        Files.writeString(temporary, value, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        moveAtomic(temporary, target);
    }

    private void moveAtomic(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(source);
        }
    }

    private <T> T withFileLock(IoSupplier<T> action) throws IOException {
        jvmLock.lock();
        try {
            Files.createDirectories(root);
            try (FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } finally {
            jvmLock.unlock();
        }
    }

    private <T> T withJvmLock(Supplier<T> action) {
        jvmLock.lock();
        try {
            return action.get();
        } finally {
            jvmLock.unlock();
        }
    }

    private static String normalizeManualTitle(String title, String fallback) {
        if (title == null || title.isBlank()) return fallback;
        String normalized = title.strip().replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(80, normalized.length()));
    }

    private static String conflictTitle(String title) {
        String base = title == null || title.isBlank() ? UNTITLED : title;
        return normalizeManualTitle(base + " (conflito " + CONFLICT_TIME.format(Instant.now()) + ")", UNTITLED);
    }

    private static String normalizeSearch(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).strip();
    }

    private static String contextKey(String context) {
        return context == null || context.isBlank() ? "no-project" : context;
    }

    @FunctionalInterface
    private interface IoSupplier<T> {
        T get() throws IOException;
    }

    public record LoadedNote(NoteItem item, String content) {
    }

    public record SaveResult(NoteItem item, NoteItem conflictCopy) {
        public static SaveResult saved(NoteItem item) { return new SaveResult(item, null); }
        public static SaveResult conflict(NoteItem item, NoteItem conflict) { return new SaveResult(item, conflict); }
        public boolean hasConflict() { return conflictCopy != null; }
    }
}
