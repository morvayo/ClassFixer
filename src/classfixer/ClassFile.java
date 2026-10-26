package classfixer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

final class ClassFile {
    private static final int MAX_SIZE = 16 * 1024 * 1024;
    static final Set<String> DEBUG = Set.of("SourceFile", "SourceDebugExtension",
            "LineNumberTable", "LocalVariableTable", "LocalVariableTypeTable");
    private final String name;
    private final byte[] bytes;
    private final String[] strings;
    private final int bodyOffset;

    ClassFile(byte[] bytes) throws IOException {
        this.bytes = bytes;
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0xcafebabe) {
                throw new IOException("invalid class magic");
            }
            input.skipNBytes(4);
            int count = input.readUnsignedShort();
            strings = new String[count];
            int[] classes = new int[count];
            for (int index = 1; index < count; index++) {
                int tag = input.readUnsignedByte();
                switch (tag) {
                    case 1 -> strings[index] = input.readUTF();
                    case 7 -> classes[index] = input.readUnsignedShort();
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> input.skipNBytes(4);
                    case 5, 6 -> {
                        input.skipNBytes(8);
                        if (++index >= count) {
                            throw new IOException("invalid wide constant pool entry");
                        }
                    }
                    case 8, 16, 19, 20 -> input.skipNBytes(2);
                    case 15 -> input.skipNBytes(3);
                    default -> throw new IOException("unsupported constant pool tag: " + tag);
                }
            }
            bodyOffset = bytes.length - input.available();
            input.readUnsignedShort();
            int self = input.readUnsignedShort();
            if (self <= 0 || self >= count || classes[self] <= 0 || classes[self] >= count) {
                throw new IOException("invalid this_class reference");
            }
            name = strings[classes[self]];
            if (name == null || name.isEmpty()) {
                throw new IOException("invalid class name reference");
            }
            for (String segment : name.split("/", -1)) {
                if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                    throw new IOException("unsafe class name");
                }
                for (int index = 0; index < segment.length(); index++) {
                    char character = segment.charAt(index);
                    if (character < 32 || ".;[\\:".indexOf(character) >= 0) {
                        throw new IOException("unsafe class name");
                    }
                }
            }
        }
    }

    String name() {
        return name;
    }

    byte[] rewrite(Set<String> removedAttributes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes, bodyOffset, bytes.length - bodyOffset));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(bytes.length);
        DataOutputStream output = new DataOutputStream(buffer);
        output.write(bytes, 0, bodyOffset);
        copy(input, output, 6);
        int interfaces = input.readUnsignedShort();
        output.writeShort(interfaces);
        copy(input, output, interfaces * 2);
        members(input, output, false, removedAttributes);
        members(input, output, true, removedAttributes);
        attributes(input, output, false, removedAttributes);
        if (input.available() != 0) {
            throw new IOException("trailing bytes after class attributes");
        }
        return buffer.toByteArray();
    }

    private void members(DataInputStream input, DataOutputStream output, boolean methods, Set<String> removedAttributes) throws IOException {
        int count = input.readUnsignedShort();
        output.writeShort(count);
        for (int index = 0; index < count; index++) {
            copy(input, output, 6);
            attributes(input, output, methods, removedAttributes);
        }
    }

    private void attributes(DataInputStream input, DataOutputStream output, boolean methods, Set<String> removedAttributes) throws IOException {
        int count = input.readUnsignedShort();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream retained = new DataOutputStream(buffer);
        int kept = 0;
        for (int index = 0; index < count; index++) {
            int reference = input.readUnsignedShort();
            if (reference <= 0 || reference >= strings.length || strings[reference] == null) {
                throw new IOException("invalid attribute name reference");
            }
            long length = Integer.toUnsignedLong(input.readInt());
            if (length > input.available()) {
                throw new IOException("truncated class attribute");
            }
            byte[] payload = input.readNBytes((int) length);
            String attribute = strings[reference];
            if (removedAttributes.contains(attribute)) {
                continue;
            }
            if (methods && attribute.equals("Code")) {
                payload = rewriteCode(payload, removedAttributes);
            }
            retained.writeShort(reference);
            retained.writeInt(payload.length);
            retained.write(payload);
            kept++;
        }
        output.writeShort(kept);
        buffer.writeTo(output);
    }

    private byte[] rewriteCode(byte[] payload, Set<String> removedAttributes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload));
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(payload.length);
        DataOutputStream output = new DataOutputStream(buffer);
        copy(input, output, 4);
        long length = Integer.toUnsignedLong(input.readInt());
        if (length == 0 || length > 65535) {
            throw new IOException("invalid method code length");
        }
        output.writeInt((int) length);
        copy(input, output, (int) length);
        int exceptions = input.readUnsignedShort();
        output.writeShort(exceptions);
        copy(input, output, exceptions * 8);
        attributes(input, output, false, removedAttributes);
        if (input.available() != 0) {
            throw new IOException("trailing bytes in code attribute");
        }
        return buffer.toByteArray();
    }

    private static void copy(DataInputStream input, DataOutputStream output, int length) throws IOException {
        if (length > input.available()) {
            throw new IOException("truncated class structure");
        }
        output.write(input.readNBytes(length));
    }

    static byte[] read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_SIZE + 1);
        if (bytes.length > MAX_SIZE) {
            throw new IOException("class exceeds 16 mib parsing limit");
        }
        return bytes;
    }
}
