# ClassFixer

Repair JAR entries that hide class files behind trailing slashes, such as `Example.class/`. Default repair preserves class bytes and resource contents. Optional modes recover declared class paths and remove debug metadata. This tool does not decrypt strings or undo control-flow obfuscation.

Requires JDK 17 or newer and Python 3.9 or newer to build. The generated JAR only needs Java 17 or newer.

```sh
python build.py --test
java -jar build/ClassFixer.jar input.jar
java -jar build/ClassFixer.jar input.jar output.jar
java -jar build/ClassFixer.jar --recover-names input.jar
java -jar build/ClassFixer.jar --dry-run --recover-names input.jar
java -jar build/ClassFixer.jar --strip-debug --recover-names input.jar
java -jar build/ClassFixer.jar --trim-trailing damaged.class Example.class
```

The default output is `input-fixed.jar`. Existing output files are never replaced. Name collisions, CRC failures and oversized archives are rejected. Renaming entries in signed JARs is rejected because it would invalidate their signatures.

Standalone `.class` files are supported with the same metadata and trailing-data repair flags. Their default output ends in `-fixed.class`; an explicit output filename can be used when loading the repaired class on the JVM. Standalone name recovery reports the declared name without creating package directories. Archive-only flags `--unsigned` and `--normalize-paths` are rejected for standalone inputs.

`--recover-names` reads `this_class` from class-file headers and restores the declared package and class name, even when an entry has a different extension. Version prefixes in multi-release JARs are retained. Unsafe class names and malformed constant pools are rejected. Parsing is limited to 16 MiB per class; this checks headers, not JVM bytecode validity.

`--dry-run` prints planned renames and validates the complete archive, including CRCs, without creating an output or temporary file. It can inspect a repair even if the chosen output path already exists. Signature checks and size limits still apply.

`--strip-debug` removes `SourceFile`, `SourceDebugExtension`, `LineNumberTable`, `LocalVariableTable` and `LocalVariableTypeTable` attributes, including those nested in method `Code` attributes. This can remove malformed debug metadata that trips decompilers. It preserves instructions, exception tables, stack maps, annotations and unknown attributes. Debugging source locations and local variable names are lost. Structural truncation is rejected rather than guessed. Signed JARs are rejected if class bytes would change.

`--strip-generics` removes `Signature` attributes from classes, fields and methods. It can repair malformed generic metadata that breaks reflection or decompilers, but removes generic type information exposed through reflection. Runtime descriptors and instructions are retained. This flag does not remove JAR signing files.

`--trim-trailing` removes bytes after the final class attribute, which can cause JVM `Extra bytes at the end of class file` errors. The complete class structure must be readable; truncated fields, methods or attributes still fail. Existing metadata is retained unless a stripping flag is also supplied.

`--normalize-paths` converts backslashes to slashes and removes redundant separators and `.` segments from class and resource paths. Absolute paths, parent traversal, NUL and colon-containing names are rejected. Collisions after normalization fail before output is written. Normalization is opt-in because a literal backslash can be a valid resource-name character.

`--unsigned` explicitly creates an unsigned output so signed input can be repaired. Root signing files under `META-INF` and signing digests in the manifest are removed. Other manifest attributes, service resources and the input archive are retained. Removed entries still undergo CRC and size validation. The output no longer carries the original publisher's signature; re-sign it separately if required.

`--json` emits one JSON success report on stdout with entry counts, class rewrites, removed signing files and rename mappings. Dry-run reports use a null output path. Operational failures emit one JSON error on stderr and exit 1, with no partial success report. Usage errors exit 2 with plain-text diagnostics. JSON strings are ASCII-escaped so reports retain Unicode paths across console code pages.

`java -jar build/ClassFixer.jar --args-file arguments.txt` reads UTF-8 arguments with one argument per line, without quotes. Paths are relative to the current working directory. This also supports paths that the Windows Java 17 launcher cannot pass directly through the active system code page. The argument-file path itself must be representable by that launcher.

Archive contents are streamed with limits of 256 MiB per entry, 1 GiB total and 100,000 entries. Entry contents, compression methods, timestamps and comments are retained; ZIP extra fields and filesystem permission bits are not retained.

The implementation is independent of [apkreader/ClassFixer](https://github.com/apkreader/ClassFixer), which inspired the archive-entry repair use case.
