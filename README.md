# ClassFixer

Repair JAR entries that hide class files behind trailing slashes, such as `Example.class/`. Class bytes and resource contents are preserved. This tool does not decrypt strings or undo control-flow obfuscation.

Requires JDK 17 or newer and Python 3.9 or newer to build. The generated JAR only needs Java 17 or newer.

```sh
python build.py --test
java -jar build/ClassFixer.jar input.jar
java -jar build/ClassFixer.jar input.jar output.jar
```

The default output is `input-fixed.jar`. Existing output files are never replaced. Name collisions, CRC failures and oversized archives are rejected. Renaming entries in signed JARs is rejected because it would invalidate their signatures.

`java -jar build/ClassFixer.jar --args-file arguments.txt` reads UTF-8 arguments with one argument per line, without quotes. Paths are relative to the current working directory. This also supports paths that the Windows Java 17 launcher cannot pass directly through the active system code page. The argument-file path itself must be representable by that launcher.

Archive contents are streamed with limits of 256 MiB per entry, 1 GiB total and 100,000 entries. Entry contents, compression methods, timestamps and comments are retained; ZIP extra fields and filesystem permission bits are not retained.

The implementation is independent of [apkreader/ClassFixer](https://github.com/apkreader/ClassFixer), which inspired the archive-entry repair use case.
