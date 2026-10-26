package classfixer;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

final class ClassFile {
    private static final int MAX_SIZE = 16 * 1024 * 1024;
    private final String name;

    ClassFile(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (input.readInt() != 0xcafebabe) {
                throw new IOException("invalid class magic");
            }
            input.skipNBytes(4);
            int count = input.readUnsignedShort();
            String[] strings = new String[count];
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

    static byte[] read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(MAX_SIZE + 1);
        if (bytes.length > MAX_SIZE) {
            throw new IOException("class exceeds 16 mib parsing limit");
        }
        return bytes;
    }
}
