package classfixer;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
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
        if (args.length == 2 && args[0].equals("--args-file")) {
            try {
                Path arguments = Path.of(args[1]);
                if (Files.size(arguments) > 1024 * 1024) {
                    throw new IOException("argument file exceeds 1 mib limit");
                }
                args = Files.readAllLines(arguments).toArray(String[]::new);
            } catch (IOException | IllegalArgumentException exception) {
                System.err.println("arguments -> failed: " + exception.getMessage());
                System.exit(1);
            }
        }
        if (args.length == 1 && args[0].equals("--help")) {
            System.out.println("usage: java -jar ClassFixer.jar [options] input.jar|input.class [output]");
            System.out.println("repairs class entries hidden by trailing slashes; existing outputs are not replaced");
            System.out.println("--recover-names -> recover paths from class headers, including disguised resource entries");
            System.out.println("--dry-run -> validate the archive and preview renames without writing output");
            System.out.println("--strip-debug -> remove source and local-variable debug attributes");
            System.out.println("--strip-generics -> remove generic signature metadata");
            System.out.println("--trim-trailing -> remove data following the complete class structure");
            System.out.println("--normalize-paths -> normalize archive separators and reject traversal paths");
            System.out.println("--unsigned -> remove jar signing files and manifest digests in the output");

            System.out.println("--args-file file.txt -> read one argument per line as utf-8");
            return;
        }
        List<String> paths = new ArrayList<>();
        boolean recoverNames = false;
        boolean dryRun = false;
        boolean trimTrailing = false;
        boolean normalizePaths = false;
        boolean unsigned = false;
        Set<String> removedAttributes = new HashSet<>();
        boolean positional = false;
        for (String argument : args) {
            if (!positional && argument.equals("--")) {
                positional = true;
            } else if (!positional && argument.equals("--recover-names")) {
                recoverNames = true;
            } else if (!positional && argument.equals("--dry-run")) {
                dryRun = true;
            } else if (!positional && argument.equals("--strip-debug")) {
                removedAttributes.addAll(ClassFile.DEBUG);
            } else if (!positional && argument.equals("--strip-generics")) {
                removedAttributes.add("Signature");
            } else if (!positional && argument.equals("--trim-trailing")) {
                trimTrailing = true;
            } else if (!positional && argument.equals("--normalize-paths")) {
                normalizePaths = true;
            } else if (!positional && argument.equals("--unsigned")) {
                unsigned = true;
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
            boolean standalone;
            try (InputStream data = Files.newInputStream(input)) {
                standalone = Arrays.equals(data.readNBytes(4), CLASS_MAGIC)
                        || input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".class");
            }
            Path output = (paths.size() == 2 ? Path.of(paths.get(1)) : defaultOutput(input, standalone)).toAbsolutePath().normalize();
            int repaired;
            if (standalone) {
                if (normalizePaths || unsigned) {
                    throw new IllegalArgumentException("archive-only options require jar input");
                }
                fixClass(input, output, dryRun, removedAttributes, trimTrailing);
                repaired = 0;
            } else {
                repaired = fix(input, output, recoverNames, dryRun, removedAttributes, trimTrailing, normalizePaths, unsigned);
            }
            System.out.println("repaired entries -> " + repaired);
            System.out.println("output -> " + (dryRun ? "not written (dry-run)" : output));
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            System.err.println("repair -> failed: " + exception.getMessage());
            System.exit(1);
        }
    }

    private static Path defaultOutput(Path input, boolean standalone) {
        String name = input.getFileName().toString();
        String extension = standalone ? ".class" : ".jar";
        if (name.toLowerCase(Locale.ROOT).endsWith(extension)) {
            name = name.substring(0, name.length() - extension.length());
        }
        return input.resolveSibling(name + "-fixed" + extension);
    }

    private static void fixClass(Path input, Path output, boolean dryRun, Set<String> removedAttributes, boolean trimTrailing) throws IOException {
        if (!dryRun && (input.equals(output) || Files.exists(output, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("output already exists or matches input");
        }
        byte[] original;
        try (InputStream data = Files.newInputStream(input)) {
            original = ClassFile.read(data);
        }
        ClassFile classFile = new ClassFile(original);
        byte[] transformed = removedAttributes.isEmpty() && !trimTrailing
                ? original : classFile.rewrite(removedAttributes, trimTrailing);
        if (!dryRun) {
            Path temporary = Files.createTempFile(output.getParent(), ".classfixer-", ".tmp");
            try {
                Files.write(temporary, transformed);
                Files.move(temporary, output);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        System.out.println("declared class -> " + printable(classFile.name()));
        System.out.println("checked entries -> 1");
        System.out.println("rewritten classes -> " + (Arrays.equals(original, transformed) ? 0 : 1));
    }

    static int fix(Path input, Path output, boolean recoverNames, boolean dryRun, Set<String> removedAttributes, boolean trimTrailing, boolean normalizePaths, boolean unsigned) throws IOException {
        if (!dryRun && (input.equals(output) || Files.exists(output, LinkOption.NOFOLLOW_LINKS))) {
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
                String name = repairedName(archive, entry, recoverNames, normalizePaths);
                if (!uniqueNames.add(name)) {
                    throw new IOException("duplicate output entry: " + name);
                }
                if (!name.equals(entry.getName())) {
                    repaired++;
                }
                signed |= isSignature(name);
                entries.add(entry);
                names.add(name);
            }
            if (signed && repaired > 0 && !unsigned) {
                throw new IOException("repair would invalidate jar signatures");
            }
            Path temporary = dryRun ? null : Files.createTempFile(output.getParent(), ".classfixer-", ".tmp");
            int rewritten = 0;
            int removedSignatures = 0;
            try {
                try (ZipOutputStream destination = new ZipOutputStream(
                        dryRun ? OutputStream.nullOutputStream() : Files.newOutputStream(temporary))) {
                    if (archive.getComment() != null) {
                        destination.setComment(archive.getComment());
                    }
                    long total = 0;
                    for (int index = 0; index < entries.size(); index++) {
                        ZipEntry source = entries.get(index);
                        ZipEntry target = new ZipEntry(names.get(index));
                        if (unsigned && isSignature(target.getName())) {
                            total += copyEntry(archive, source, OutputStream.nullOutputStream(), MAX_TOTAL_SIZE - total);
                            removedSignatures++;
                            continue;
                        }
                        if (dryRun && !source.getName().equals(target.getName())) {
                            System.out.println("rename -> " + printable(source.getName()) + " -> " + printable(target.getName()));
                        }
                        byte[] transformed = null;
                        int originalLength = 0;
                        boolean manifest = unsigned && target.getName().equalsIgnoreCase("META-INF/MANIFEST.MF");
                        if (manifest || ((!removedAttributes.isEmpty() || trimTrailing) && isClass(archive, source))) {
                            byte[] original;
                            try (InputStream data = archive.getInputStream(source)) {
                                if (manifest) {
                                    original = data.readNBytes(16 * 1024 * 1024 + 1);
                                    if (original.length > 16 * 1024 * 1024) {
                                        throw new IOException("manifest exceeds 16 mib parsing limit");
                                    }
                                } else {
                                    original = ClassFile.read(data);
                                }
                            }
                            CRC32 originalCrc = new CRC32();
                            originalCrc.update(original);
                            if (original.length != source.getSize() || originalCrc.getValue() != source.getCrc()) {
                                throw new IOException("invalid entry size or crc: " + source.getName());
                            }
                            originalLength = original.length;
                            transformed = manifest ? unsignedManifest(original)
                                    : new ClassFile(original).rewrite(removedAttributes, trimTrailing);
                            if (!Arrays.equals(original, transformed)) {
                                if (signed && !unsigned) {
                                    throw new IOException("repair would invalidate jar signatures");
                                }
                                if (!manifest) {
                                    rewritten++;
                                }
                            }
                        }
                        target.setMethod(source.getMethod());
                        if (source.getTime() >= 0) {
                            target.setTime(source.getTime());
                        }
                        target.setComment(source.getComment());
                        if (source.getMethod() == ZipEntry.STORED) {
                            if (transformed == null) {
                                target.setSize(source.getSize());
                                target.setCrc(source.getCrc());
                            } else {
                                CRC32 transformedCrc = new CRC32();
                                transformedCrc.update(transformed);
                                target.setSize(transformed.length);
                                target.setCrc(transformedCrc.getValue());
                            }
                        }
                        destination.putNextEntry(target);
                        if (transformed != null) {
                            if (originalLength > MAX_TOTAL_SIZE - total) {
                                throw new IOException("expanded archive exceeds limit");
                            }
                            total += originalLength;
                            destination.write(transformed);
                            destination.closeEntry();
                            continue;
                        }
                        total += copyEntry(archive, source, destination, MAX_TOTAL_SIZE - total);
                        destination.closeEntry();
                    }
                }
                if (!dryRun) {
                    Files.move(temporary, output);
                }
            } finally {
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            }
            System.out.println("checked entries -> " + entries.size());
            System.out.println("rewritten classes -> " + rewritten);
            System.out.println("removed signing files -> " + removedSignatures);
            return repaired;
        }
    }

    private static String printable(String value) {
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isISOControl(character)) {
                result.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
            } else {
                result.append(character);
            }
        }
        return result.toString();
    }

    private static long copyEntry(ZipFile archive, ZipEntry source, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[8192];
        long count = 0;
        CRC32 crc = new CRC32();
        try (InputStream data = archive.getInputStream(source)) {
            int length;
            while ((length = data.read(buffer)) != -1) {
                if (length > MAX_ENTRY_SIZE - count || length > remaining - count) {
                    throw new IOException("expanded archive exceeds limit");
                }
                count += length;
                crc.update(buffer, 0, length);
                output.write(buffer, 0, length);
            }
        }
        if (count != source.getSize() || crc.getValue() != source.getCrc()) {
            throw new IOException("invalid entry size or crc: " + printable(source.getName()));
        }
        return count;
    }

    private static byte[] unsignedManifest(byte[] bytes) throws IOException {
        Manifest manifest = new Manifest(new ByteArrayInputStream(bytes));
        boolean changed = removeDigests(manifest.getMainAttributes());
        for (Attributes attributes : manifest.getEntries().values()) {
            changed |= removeDigests(attributes);
        }
        if (!changed) {
            return bytes;
        }
        manifest.getEntries().entrySet().removeIf(entry -> entry.getValue().isEmpty());
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        manifest.write(result);
        return result.toByteArray();
    }

    private static boolean removeDigests(Attributes attributes) {
        return attributes.entrySet().removeIf(entry -> {
            String name = entry.getKey().toString().toUpperCase(Locale.ROOT);
            return name.endsWith("-DIGEST") || name.contains("-DIGEST-")
                    || name.equals("SIGNATURE-VERSION") || name.equals("MAGIC");
        });
    }

    private static boolean isClass(ZipFile archive, ZipEntry entry) throws IOException {
        try (InputStream data = archive.getInputStream(entry)) {
            return Arrays.equals(data.readNBytes(4), CLASS_MAGIC);
        }
    }

    private static String repairedName(ZipFile archive, ZipEntry entry, boolean recoverNames, boolean normalizePaths) throws IOException {
        String name = normalizePaths ? normalizePath(entry.getName()) : entry.getName();
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

    private static String normalizePath(String name) throws IOException {
        String slashes = name.replace('\\', '/');
        if (slashes.startsWith("/") || slashes.indexOf(':') >= 0 || slashes.indexOf('\0') >= 0) {
            throw new IOException("unsafe archive path: " + printable(name));
        }
        List<String> segments = new ArrayList<>();
        for (String segment : slashes.split("/")) {
            if (segment.equals("..")) {
                throw new IOException("unsafe archive path: " + printable(name));
            }
            if (!segment.isEmpty() && !segment.equals(".")) {
                segments.add(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IOException("empty normalized archive path");
        }
        return String.join("/", segments) + (slashes.endsWith("/") ? "/" : "");
    }
}
