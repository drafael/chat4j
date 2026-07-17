package com.github.drafael.chat4j.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;

/**
 * Stable, application-scoped authority for direct children of the cache directory.
 * Cache storage is deliberately best-effort: an unsafe or unavailable root is a cache miss.
 */
@Slf4j
public final class CacheRootHandle {

    private static final ConcurrentMap<Path, CacheRootHandle> HANDLES = new ConcurrentHashMap<>();

    private final Path root;
    private final Object lock = new Object();
    private Object establishedRootFileKey;

    private CacheRootHandle(Path root) {
        this.root = root;
    }

    public static CacheRootHandle of(@NonNull Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        CacheRootHandle existing = HANDLES.get(normalized);
        if (existing != null) {
            return existing;
        }
        Path canonical = canonicalPath(normalized);
        CacheRootHandle handle = HANDLES.computeIfAbsent(canonical, CacheRootHandle::new);
        HANDLES.putIfAbsent(normalized, handle);
        return handle;
    }

    public static CacheRootHandle from(@NonNull StoragePaths storagePaths) {
        return of(storagePaths.cacheDirectory());
    }

    public Path path() {
        return root;
    }

    public Object lock() {
        return lock;
    }

    public Optional<Path> availableRoot() {
        synchronized (lock) {
            try {
                if (establishedRootFileKey != null) {
                    return hasEstablishedIdentity(readAttributes(root))
                            ? Optional.of(root)
                            : Optional.empty();
                }
                if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(root);
                }
                BasicFileAttributes attributes = readAttributes(root);
                if (!isDirectory(attributes) || attributes.fileKey() == null) {
                    log.warn("Cache root is unavailable or unsafe");
                    return Optional.empty();
                }
                establishedRootFileKey = attributes.fileKey();
                return Optional.of(root);
            } catch (IOException | SecurityException e) {
                log.warn("Cache root is unavailable: {}", ExceptionUtils.getMessage(e));
                return Optional.empty();
            }
        }
    }

    public Optional<Path> directChild(String basename) {
        if (!isBasename(basename)) {
            return Optional.empty();
        }
        return availableRoot().flatMap(ignored -> {
            Path child = root.resolve(basename).normalize();
            return child.getParent().equals(root) ? Optional.of(child) : Optional.empty();
        });
    }

    public boolean isSafeRegularFile(Path file) {
        return directChild(file == null || file.getFileName() == null ? "" : file.getFileName().toString())
                .filter(expected -> expected.equals(file.toAbsolutePath().normalize()))
                .filter(CacheRootHandle::isRegularFile)
                .isPresent();
    }

    public static boolean isBasename(String value) {
        if (value == null
                || value.isBlank()
                || ".".equals(value)
                || "..".equals(value)
                || value.indexOf('\u0000') >= 0
                || value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0) {
            return false;
        }
        try {
            Path path = Path.of(value);
            return path.getNameCount() == 1 && !path.isAbsolute();
        } catch (InvalidPathException e) {
            return false;
        }
    }

    /** Canonicalizes existing ancestors without following the final path entry. */
    public static Path canonicalPath(@NonNull Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent == null) {
            return absolute;
        }
        try {
            Deque<Path> missingSegments = new ArrayDeque<>();
            Path existingAncestor = parent;
            while (existingAncestor != null && Files.notExists(existingAncestor, LinkOption.NOFOLLOW_LINKS)) {
                missingSegments.push(existingAncestor.getFileName());
                existingAncestor = existingAncestor.getParent();
            }
            if (existingAncestor == null) {
                return absolute;
            }
            Path canonicalParent = existingAncestor.toRealPath();
            while (!missingSegments.isEmpty()) {
                canonicalParent = canonicalParent.resolve(missingSegments.pop());
            }
            return canonicalParent.resolve(absolute.getFileName()).normalize();
        } catch (IOException | SecurityException e) {
            return absolute;
        }
    }

    private boolean hasEstablishedIdentity(BasicFileAttributes attributes) {
        boolean matches = isDirectory(attributes) && establishedRootFileKey.equals(attributes.fileKey());
        if (!matches) {
            log.warn("Cache root identity changed; treating it as unavailable");
        }
        return matches;
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isDirectory(BasicFileAttributes attributes) {
        return attributes.isDirectory() && !attributes.isSymbolicLink();
    }

    private static boolean isRegularFile(Path path) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.isRegularFile() && !attributes.isSymbolicLink();
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
