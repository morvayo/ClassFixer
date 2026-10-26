package classfixer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public final class Main {
    private static final byte[] CLASS_MAGIC = {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe};
    private static final long MAX_ENTRY_SIZE = 256L * 1024 * 1024;
    private static final long MAX_TOTAL_SIZE = 1024L * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("usage: java -jar ClassFixer.jar input.jar [output.jar]");
            System.out.println("repairs class entries hidden by trailing slashes; existing outputs are not replaced");
            return;
        }
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: java -jar ClassFixer.jar input.jar [output.jar]");
            System.exit(2);
        }
        try {
            Path input = Path.of(args[0]).toAbsolutePath().normalize();
            if (!Files.isRegularFile(input)) {
                throw new IOException("input is not a regular file");
            }
            Path output = (args.length == 2 ? Path.of(args[1]) : defaultOutput(input)).toAbsolutePath().normalize();
            int repaired = fix(input, output);
            System.out.println("repaired entries -> " + repaired);
            System.out.println("output -> " + output);
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            System.err.println("repair -> failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Path defaultOutput(Path input) {
        String name = input.getFileName().toString();
        if (name.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }
        return input.resolveSibling(name + "-fixed.jar");
    }

    static int fix(Path input, Path output) throws IOException {
        if (input.equals(output) || Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("output already exists or matches input");
        }
        try (ZipFile archive = new ZipFile(input.toFile())) {
            List<ZipEntry> entries = new ArrayList<>();
            List<String> names = new ArrayList<>();
            Set<String> uniqueNames = new HashSet<>();
            boolean signed = false;
            int repaired = 0;
            long declaredTotal = 0;
            var iterator = archive.entries();
            while (iterator.hasMoreElements()) {
                ZipEntry entry = iterator.nextElement();
                if (entries.size() >= MAX_ENTRIES) {
                    throw new IOException("archive entry count exceeds limit");
                }
                long size = entry.getSize();
                if (size < 0 || size > MAX_ENTRY_SIZE || size > MAX_TOTAL_SIZE - declaredTotal) {
                    throw new IOException("archive size exceeds limit");
                }
                declaredTotal += size;
                String name = repairedName(archive, entry);
                if (!uniqueNames.add(name)) {
                    throw new IOException("duplicate output entry: " + name);
                }
                if (!name.equals(entry.getName())) {
                    repaired++;
                }
                signed |= isSignature(entry.getName());
                entries.add(entry);
                names.add(name);
            }
            if (signed && repaired > 0) {
                throw new IOException("repair would invalidate jar signatures");
            }
            Path temporary = Files.createTempFile(output.getParent(), ".classfixer-", ".tmp");
            try {
                try (ZipOutputStream destination = new ZipOutputStream(Files.newOutputStream(temporary))) {
                    if (archive.getComment() != null) {
                        destination.setComment(archive.getComment());
                    }
                    byte[] buffer = new byte[8192];
                    long total = 0;
                    for (int index = 0; index < entries.size(); index++) {
                        ZipEntry source = entries.get(index);
                        ZipEntry target = new ZipEntry(names.get(index));
                        target.setMethod(source.getMethod());
                        if (source.getTime() >= 0) {
                            target.setTime(source.getTime());
                        }
                        target.setComment(source.getComment());
                        if (source.getMethod() == ZipEntry.STORED) {
                            target.setSize(source.getSize());
                            target.setCrc(source.getCrc());
                        }
                        destination.putNextEntry(target);
                        long count = 0;
                        CRC32 crc = new CRC32();
                        try (InputStream data = archive.getInputStream(source)) {
                            int length;
                            while ((length = data.read(buffer)) != -1) {
                                if (length > MAX_ENTRY_SIZE - count || length > MAX_TOTAL_SIZE - total) {
                                    throw new IOException("expanded archive exceeds limit");
                                }
                                count += length;
                                total += length;
                                crc.update(buffer, 0, length);
                                destination.write(buffer, 0, length);
                            }
                        }
                        if (count != source.getSize() || crc.getValue() != source.getCrc()) {
                            throw new IOException("invalid entry size or crc: " + source.getName());
                        }
                        destination.closeEntry();
                    }
                }
                Files.move(temporary, output);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return repaired;
        }
    }

    private static String repairedName(ZipFile archive, ZipEntry entry) throws IOException {
        String name = entry.getName();
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '/') {
            end--;
        }
        String candidate = name.substring(0, end);
        if (end == name.length() || !candidate.endsWith(".class")) {
            return name;
        }
        try (InputStream data = archive.getInputStream(entry)) {
            return Arrays.equals(data.readNBytes(4), CLASS_MAGIC) ? candidate : name;
        }
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        if (!upper.startsWith("META-INF/")) {
            return false;
        }
        String file = upper.substring(9);
        return !file.contains("/") && (file.endsWith(".SF") || file.endsWith(".RSA")
                || file.endsWith(".DSA") || file.endsWith(".EC") || file.startsWith("SIG-"));
    }
}
