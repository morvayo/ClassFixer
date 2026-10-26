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
            System.out.println("usage: java -jar ClassFixer.jar [--recover-names] input.jar [output.jar]");
            System.out.println("repairs class entries hidden by trailing slashes; existing outputs are not replaced");
            System.out.println("--recover-names -> recover paths from class headers, including disguised resource entries");
            return;
        }
        List<String> paths = new ArrayList<>();
        boolean recoverNames = false;
        boolean positional = false;
        for (String argument : args) {
            if (!positional && argument.equals("--")) {
                positional = true;
            } else if (!positional && argument.equals("--recover-names")) {
                recoverNames = true;
            } else if (!positional && argument.startsWith("--")) {
                System.err.println("unknown option: " + argument);
                System.exit(2);
            } else {
                paths.add(argument);
            }
        }
        if (paths.isEmpty() || paths.size() > 2) {
            System.err.println("usage: java -jar ClassFixer.jar [--recover-names] input.jar [output.jar]");
            System.exit(2);
        }
        try {
            Path input = Path.of(paths.get(0)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(input)) {
                throw new IOException("input is not a regular file");
            }
            Path output = (paths.size() == 2 ? Path.of(paths.get(1)) : defaultOutput(input)).toAbsolutePath().normalize();
            int repaired = fix(input, output, recoverNames);
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

    static int fix(Path input, Path output, boolean recoverNames) throws IOException {
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
                String name = repairedName(archive, entry, recoverNames);
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

    private static String repairedName(ZipFile archive, ZipEntry entry, boolean recoverNames) throws IOException {
        String name = entry.getName();
        int end = name.length();
        while (end > 0 && name.charAt(end - 1) == '/') {
            end--;
        }
        String candidate = name.substring(0, end);
        if (!recoverNames && (end == name.length() || !candidate.endsWith(".class"))) {
            return name;
        }
        try (InputStream data = archive.getInputStream(entry)) {
            if (!Arrays.equals(data.readNBytes(4), CLASS_MAGIC)) {
                return name;
            }
        }
        if (!recoverNames) {
            return candidate;
        }
        try (InputStream data = archive.getInputStream(entry)) {
            String recovered = new ClassFile(ClassFile.read(data)).name() + ".class";
            if (name.startsWith("META-INF/versions/")) {
                int separator = name.indexOf('/', 18);
                if (separator < 0) {
                    throw new IOException("invalid multi-release class path: " + name);
                }
                String version = name.substring(18, separator);
                if (!version.matches("[1-9][0-9]*") || version.length() > 9 || Long.parseLong(version) < 9) {
                    throw new IOException("invalid multi-release version: " + version);
                }
                return name.substring(0, separator + 1) + recovered;
            }
            return recovered;
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
