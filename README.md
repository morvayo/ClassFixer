# ClassFixer

Repair JAR entries that hide class files behind trailing slashes, such as `Example.class/`. Class bytes and resource contents are preserved. This tool does not decrypt strings or undo control-flow obfuscation.

Requires JDK 17 or newer and Python 3.9 or newer to build. The generated JAR only needs Java 17 or newer.

```sh
python build.py --test
java -jar build/ClassFixer.jar input.jar
java -jar build/ClassFixer.jar input.jar output.jar
java -jar build/ClassFixer.jar --recover-names input.jar
java -jar build/ClassFixer.jar --dry-run --recover-names input.jar
java -jar build/ClassFixer.jar --strip-debug --recover-names input.jar
```

The default output is `input-fixed.jar`. Existing output files are never replaced. Name collisions, CRC failures and oversized archives are rejected. Renaming entries in signed JARs is rejected because it would invalidate their signatures.

`--recover-names` reads `this_class` from class-file headers and restores the declared package and class name, even when an entry has a different extension. Version prefixes in multi-release JARs are retained. Unsafe class names and malformed constant pools are rejected. Parsing is limited to 16 MiB per class; this checks headers, not JVM bytecode validity.

`--dry-run` prints planned renames and validates the complete archive, including CRCs, without creating an output or temporary file. It can inspect a repair even if the chosen output path already exists. Signature checks and size limits still apply.

`--strip-debug` removes `SourceFile`, `SourceDebugExtension`, `LineNumberTable`, `LocalVariableTable` and `LocalVariableTypeTable` attributes, including those nested in method `Code` attributes. This can remove malformed debug metadata that trips decompilers. It preserves instructions, exception tables, stack maps, annotations and unknown attributes. Debugging source locations and local variable names are lost. Structural truncation is rejected rather than guessed. Signed JARs are rejected if class bytes would change.

`java -jar build/ClassFixer.jar --args-file arguments.txt` reads UTF-8 arguments with one argument per line, without quotes. Paths are relative to the current working directory. This also supports paths that the Windows Java 17 launcher cannot pass directly through the active system code page. The argument-file path itself must be representable by that launcher.

Archive contents are streamed with limits of 256 MiB per entry, 1 GiB total and 100,000 entries. Entry contents, compression methods, timestamps and comments are retained; ZIP extra fields and filesystem permission bits are not retained.

The implementation is independent of [apkreader/ClassFixer](https://github.com/apkreader/ClassFixer), which inspired the archive-entry repair use case.
