package io.zmbackup.local;

import io.zmbackup.core.port.ServerConfigArchiver;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class ServerConfigFileArchiver implements ServerConfigArchiver {

    private static final String MANIFEST_ENTRY_NAME = "MANIFEST.tsv";
    private static final String NO_PERMISSIONS_MARKER = "-";
    private static final Path FILESYSTEM_ROOT = Path.of("/");
    private static final boolean POSIX_SUPPORTED =
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

    private final List<Path> roots;

    public ServerConfigFileArchiver(List<Path> roots) {
        Objects.requireNonNull(roots, "roots must not be null");
        if (roots.isEmpty()) {
            throw new IllegalArgumentException("roots must not be empty");
        }
        List<Path> normalized = new ArrayList<>(roots.size());
        for (Path root : roots) {
            if (!root.isAbsolute()) {
                throw new IllegalArgumentException("Server config path must be absolute: " + root);
            }
            normalized.add(root.toAbsolutePath().normalize());
        }
        this.roots = List.copyOf(normalized);
    }

    @Override
    public void export(OutputStream destination) throws IOException {
        List<FileEntry> entries = collectFiles();
        try (ZipOutputStream zip = new ZipOutputStream(destination)) {
            zip.putNextEntry(new ZipEntry(MANIFEST_ENTRY_NAME));
            zip.write(manifestBytes(entries));
            zip.closeEntry();
            for (FileEntry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.zipName()));
                Files.copy(entry.path(), zip);
                zip.closeEntry();
            }
        }
    }

    @Override
    public void restore(InputStream source) throws IOException {
        Path spool = PosixFileHardening.createTempFile("zmbackup-serverconfig-", ".zip");
        try {
            Files.copy(source, spool, StandardCopyOption.REPLACE_EXISTING);
            applyArchive(spool);
        } finally {
            Files.deleteIfExists(spool);
        }
    }

    private void applyArchive(Path spoolFile) throws IOException {
        try (ZipFile zip = new ZipFile(spoolFile.toFile())) {
            ZipEntry manifestEntry = zip.getEntry(MANIFEST_ENTRY_NAME);
            if (manifestEntry == null) {
                throw new IOException("Server config archive is missing its manifest entry");
            }
            Map<String, String> permissionsByName = readManifest(zip.getInputStream(manifestEntry));

            List<TargetEntry> targets = new ArrayList<>();
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            while (zipEntries.hasMoreElements()) {
                ZipEntry entry = zipEntries.nextElement();
                if (!entry.getName().equals(MANIFEST_ENTRY_NAME)) {
                    targets.add(new TargetEntry(entry, resolveAndValidateTarget(entry.getName())));
                }
            }

            for (TargetEntry mapping : targets) {
                Path parent = mapping.target().getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (InputStream in = zip.getInputStream(mapping.entry())) {
                    Files.copy(in, mapping.target(), StandardCopyOption.REPLACE_EXISTING);
                }
                String permissions = permissionsByName.get(mapping.entry().getName());
                if (permissions != null && POSIX_SUPPORTED) {
                    Files.setPosixFilePermissions(mapping.target(), PosixFilePermissions.fromString(permissions));
                }
            }
        }
    }

    private Path resolveAndValidateTarget(String entryName) throws IOException {
        Path target = FILESYSTEM_ROOT.resolve(entryName).normalize();
        for (Path root : roots) {
            if (target.startsWith(root)) {
                return target;
            }
        }
        throw new IOException("Refusing to restore server config entry outside configured paths: " + target);
    }

    private static Map<String, String> readManifest(InputStream manifestStream) throws IOException {
        Map<String, String> permissions = new LinkedHashMap<>();
        String content = new String(manifestStream.readAllBytes(), StandardCharsets.UTF_8);
        for (String line : content.split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\t", 2);
            if (parts.length == 2 && !parts[1].equals(NO_PERMISSIONS_MARKER)) {
                permissions.put(parts[0], parts[1]);
            }
        }
        return permissions;
    }

    private static byte[] manifestBytes(List<FileEntry> entries) {
        StringBuilder manifest = new StringBuilder();
        for (FileEntry entry : entries) {
            manifest.append(entry.zipName())
                    .append('\t')
                    .append(entry.permissions() == null ? NO_PERMISSIONS_MARKER : entry.permissions())
                    .append('\n');
        }
        return manifest.toString().getBytes(StandardCharsets.UTF_8);
    }

    private List<FileEntry> collectFiles() throws IOException {
        Map<String, FileEntry> byName = new LinkedHashMap<>();
        for (Path root : roots) {
            if (!Files.exists(root)) {
                continue;
            }
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        try {
                            FileEntry entry = fileEntryFor(file);
                            byName.put(entry.zipName(), entry);
                        } catch (IOException e) {
                            return FileVisitResult.CONTINUE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return List.copyOf(byName.values());
    }

    private static FileEntry fileEntryFor(Path file) throws IOException {
        Path absolute = file.toAbsolutePath().normalize();
        String zipName = absolute.toString().substring(1);
        String permissions = POSIX_SUPPORTED
                ? PosixFilePermissions.toString(Files.getPosixFilePermissions(absolute))
                : null;
        return new FileEntry(absolute, zipName, permissions);
    }

    private record FileEntry(Path path, String zipName, String permissions) {}

    private record TargetEntry(ZipEntry entry, Path target) {}
}
