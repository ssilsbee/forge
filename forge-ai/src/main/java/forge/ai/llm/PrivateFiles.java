package forge.ai.llm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;

/** Owner-only local files; no credentials or private hands belong in source control or logs. */
public final class PrivateFiles {
    private PrivateFiles() { }

    public static void protect(Path path) throws IOException {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(
                    Files.isDirectory(path) ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (acl == null) {
            throw new IOException("This filesystem cannot protect private LLM files.");
        }
        acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW)
                .setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
    }

    public static void write(Path destination, String content) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        boolean existed = Files.exists(parent);
        Files.createDirectories(parent);
        if (!existed) {
            protect(parent);
        }
        Path temporary = Files.createTempFile(parent, ".llm-", ".tmp");
        try {
            protect(temporary);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Private LLM storage requires atomic file replacement.", e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
