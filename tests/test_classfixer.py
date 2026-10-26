import pathlib
import random
import struct
import subprocess
import tempfile
import unittest
import zipfile


ROOT = pathlib.Path(__file__).resolve().parents[1]
JAR = ROOT / "build" / "ClassFixer.jar"


class ClassFixerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not JAR.is_file():
            raise RuntimeError("build/ClassFixer.jar is missing; run python build.py first")
        cls.workspace = tempfile.TemporaryDirectory(dir=ROOT / "build")
        cls.fixture = pathlib.Path(cls.workspace.name)
        source = cls.fixture / "Example.java"
        source.write_text(
            'public class Example { public static void main(String[] args) { '
            'System.out.println("fixture -> ok"); } }', encoding="utf-8"
        )
        subprocess.run(["javac", "--release", "17", str(source)], check=True, capture_output=True)
        cls.bytecode = (cls.fixture / "Example.class").read_bytes()
        packaged = cls.fixture / "Packaged.java"
        packaged.write_text(
            'package demo; public class Packaged { '
            'static final long LONG = 123456789012345L; static final double DOUBLE = 1.234; '
            'public static void main(String[] args) { '
            'java.util.List<String> values = java.util.List.of("fixture"); '
            'if (values.isEmpty()) { throw new IllegalArgumentException(); } '
            'Runnable action = () -> System.out.println("packaged " + LONG + DOUBLE); action.run(); } }',
            encoding="utf-8",
        )
        subprocess.run(
            ["javac", "--release", "17", "-g", "-d", str(cls.fixture), str(packaged)],
            check=True, capture_output=True,
        )
        cls.packaged = (cls.fixture / "demo" / "Packaged.class").read_bytes()
        generic = cls.fixture / "GenericFixture.java"
        generic.write_text(
            'public class GenericFixture<T> { public java.util.List<T> values; '
            'public T value(T input) { return input; } '
            'public static void main(String[] args) { '
            'System.out.println("parameters -> " + GenericFixture.class.getTypeParameters().length); } }',
            encoding="utf-8",
        )
        subprocess.run(["javac", "--release", "17", "-g", str(generic)], check=True, capture_output=True)
        cls.generic = (cls.fixture / "GenericFixture.class").read_bytes()

    @classmethod
    def tearDownClass(cls):
        cls.workspace.cleanup()

    def setUp(self):
        self.workspace = tempfile.TemporaryDirectory(dir=ROOT / "build")
        self.addCleanup(self.workspace.cleanup)
        self.folder = pathlib.Path(self.workspace.name)
        self.input = self.folder / "input.jar"
        self.output = self.folder / "output.jar"

    def write_archive(self, entries, compression=zipfile.ZIP_DEFLATED):
        with zipfile.ZipFile(self.input, "w", compression=compression) as archive:
            for name, payload in entries:
                archive.writestr(name, payload)

    def run_cli(self, *args):
        return subprocess.run(
            ["java", "-jar", str(JAR), *map(str, args)],
            capture_output=True, text=True, timeout=20
        )

    def fix(self):
        return self.run_cli(self.input, self.output)

    def assert_rejected(self, result, message):
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertIn(message, result.stderr)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.folder.glob(".classfixer-*")), [])

    def test_repaired_class_runs_on_jvm(self):
        self.write_archive([("Example.class/", self.bytecode), ("asset.txt", b"asset")])
        original = self.input.read_bytes()
        result = self.fix()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("repaired entries -> 1", result.stdout)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), ["Example.class", "asset.txt"])
            self.assertEqual(archive.read("Example.class"), self.bytecode)
            self.assertEqual(archive.read("asset.txt"), b"asset")
        execution = subprocess.run(
            ["java", "-cp", str(self.output), "Example"], capture_output=True, text=True, timeout=20
        )
        self.assertEqual(execution.returncode, 0, execution.stderr)
        self.assertEqual(execution.stdout.strip(), "fixture -> ok")
        self.assertEqual(self.input.read_bytes(), original)

    def test_multiple_trailing_slashes(self):
        self.write_archive([("package/Example.class///", self.bytecode)])
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), ["package/Example.class"])

    def test_normal_entries_unchanged(self):
        entries = [("Example.class", self.bytecode), ("folder/", b""), ("folder/data.bin", bytes(range(256)))]
        self.write_archive(entries)
        result = self.fix()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("repaired entries -> 0", result.stdout)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual([(name, archive.read(name)) for name in archive.namelist()], entries)

    def test_class_named_directories_without_bytecode_preserved(self):
        entries = [("empty.class/", b""), ("short.class/", b"\xca\xfe"), ("text.class/", b"text")]
        self.write_archive(entries)
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual([(name, archive.read(name)) for name in archive.namelist()], entries)

    def test_name_collision_rejected(self):
        self.write_archive([("Example.class/", self.bytecode), ("Example.class", self.bytecode)])
        self.assert_rejected(self.fix(), "duplicate output entry")

    def test_collision_between_repaired_names_rejected(self):
        self.write_archive([("Example.class/", self.bytecode), ("Example.class//", self.bytecode)])
        self.assert_rejected(self.fix(), "duplicate output entry")

    def test_existing_output_preserved(self):
        self.write_archive([("Example.class/", self.bytecode)])
        self.output.write_bytes(b"existing output")
        result = self.fix()
        self.assertEqual(result.returncode, 1)
        self.assertEqual(self.output.read_bytes(), b"existing output")

    def test_input_cannot_be_output(self):
        self.write_archive([("Example.class/", self.bytecode)])
        original = self.input.read_bytes()
        self.assertEqual(self.run_cli(self.input, self.input).returncode, 1)
        self.assertEqual(self.input.read_bytes(), original)

    def test_signed_repair_rejected(self):
        for signature in ["META-INF/TEST.SF", "META-INF/TEST.RSA", "meta-inf/test.ec", "META-INF/SIG-TEST"]:
            with self.subTest(signature=signature):
                self.write_archive([("Example.class/", self.bytecode), (signature, b"signature")])
                self.assert_rejected(self.fix(), "invalidate jar signatures")

    def test_signature_files_preserved_when_no_repair(self):
        self.write_archive([("Example.class", self.bytecode), ("META-INF/TEST.SF", b"signature")])
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.read("META-INF/TEST.SF"), b"signature")

    def test_metadata_preserved(self):
        info = zipfile.ZipInfo("Example.class/", (2024, 6, 8, 12, 30, 14))
        info.comment = b"entry comment"
        info.compress_type = zipfile.ZIP_STORED
        with zipfile.ZipFile(self.input, "w") as archive:
            archive.comment = b"archive comment"
            archive.writestr(info, self.bytecode)
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            repaired = archive.getinfo("Example.class")
            self.assertEqual(repaired.date_time, info.date_time)
            self.assertEqual(repaired.comment, info.comment)
            self.assertEqual(repaired.compress_type, zipfile.ZIP_STORED)
            self.assertEqual(archive.comment, b"archive comment")

    def test_default_output(self):
        self.write_archive([("Example.class/", self.bytecode)])
        result = self.run_cli(self.input)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.folder / "input-fixed.jar").exists())

    def test_spaces_and_unicode_paths(self):
        self.write_archive([("Example.class/", self.bytecode)])
        self.input = self.input.rename(self.folder / "sample \u00f6 file.jar")
        self.output = self.folder / "repaired \u015f file.jar"
        arguments = self.folder / "arguments.txt"
        arguments.write_text(str(self.input) + "\n" + str(self.output) + "\n", encoding="utf-8")
        result = self.run_cli("--args-file", arguments)
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.read("Example.class"), self.bytecode)

    def test_empty_archive(self):
        self.write_archive([])
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), [])

    def test_invalid_archive_rejected(self):
        self.input.write_bytes(b"not a zip")
        self.assert_rejected(self.fix(), "repair -> failed")

    def test_bad_crc_rejected_without_partial_output(self):
        payload = b"unique stored content"
        self.write_archive([("asset.txt", payload)], compression=zipfile.ZIP_STORED)
        self.input.write_bytes(self.input.read_bytes().replace(payload, b"X" + payload[1:], 1))
        self.assert_rejected(self.fix(), "invalid entry size or crc")

    def test_declared_oversized_entry_rejected(self):
        self.write_archive([("asset.txt", b"data")], compression=zipfile.ZIP_STORED)
        data = bytearray(self.input.read_bytes())
        central = data.index(b"PK\x01\x02")
        struct.pack_into("<I", data, central + 24, 256 * 1024 * 1024 + 1)
        self.input.write_bytes(data)
        self.assert_rejected(self.fix(), "archive size exceeds limit")

    def test_missing_input_and_missing_output_parent(self):
        self.assert_rejected(self.fix(), "input is not a regular file")
        self.write_archive([])
        self.output = self.folder / "missing" / "output.jar"
        self.assert_rejected(self.fix(), "repair -> failed")

    def test_cli_usage(self):
        self.assertEqual(self.run_cli().returncode, 2)
        self.assertEqual(self.run_cli("a", "b", "c").returncode, 2)
        result = self.run_cli("--help")
        self.assertEqual(result.returncode, 0)
        self.assertIn("usage:", result.stdout)
        self.assertEqual(result.stderr, "")

    def test_recover_declared_package_from_disguised_entry(self):
        self.write_archive([("hidden/data.bin", self.packaged), ("asset.txt", b"asset")])
        result = self.run_cli("--recover-names", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), ["demo/Packaged.class", "asset.txt"])
            self.assertEqual(archive.read("demo/Packaged.class"), self.packaged)
        execution = subprocess.run(
            ["java", "-cp", str(self.output), "demo.Packaged"], capture_output=True, text=True, timeout=20
        )
        self.assertEqual(execution.returncode, 0, execution.stderr)
        self.assertIn("packaged", execution.stdout)

    def test_recover_multi_release_paths(self):
        self.write_archive([
            ("wrong.class", self.bytecode),
            ("META-INF/versions/17/wrong.class/", self.bytecode),
        ])
        result = self.run_cli("--recover-names", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), ["Example.class", "META-INF/versions/17/Example.class"])

    def test_recover_rejects_invalid_multi_release_version(self):
        self.write_archive([("META-INF/versions/8/wrong.class", self.bytecode)])
        self.assert_rejected(self.run_cli("--recover-names", self.input, self.output), "multi-release version")

    def test_recovery_detects_collisions(self):
        self.write_archive([("first.bin", self.bytecode), ("second.bin", self.bytecode)])
        self.assert_rejected(self.run_cli("--recover-names", self.input, self.output), "duplicate output entry")

    def test_recovery_rejects_unsafe_class_names(self):
        invalid = self.bytecode.replace(b"Example", b"Exa.ple")
        self.write_archive([("wrong.class", invalid)])
        self.assert_rejected(self.run_cli("--recover-names", self.input, self.output), "unsafe class name")

    def test_recovery_rejects_invalid_and_truncated_constant_pools(self):
        invalid = bytearray(self.bytecode)
        invalid[10] = 2
        for payload in (self.bytecode[:4], self.bytecode[:20], bytes(invalid)):
            with self.subTest(size=len(payload)):
                self.write_archive([("wrong.class", payload)])
                self.assert_rejected(self.run_cli("--recover-names", self.input, self.output), "repair -> failed")

    def test_recovery_is_explicit(self):
        self.write_archive([("wrong.class", self.bytecode)])
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.namelist(), ["wrong.class"])

    def test_unknown_options_rejected(self):
        self.assertEqual(self.run_cli("--unknown", self.input).returncode, 2)

    def test_dry_run_previews_without_writing(self):
        self.write_archive([("Example.class/", self.bytecode)])
        original = self.input.read_bytes()
        result = self.run_cli("--dry-run", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("rename -> Example.class/ -> Example.class", result.stdout)
        self.assertIn("not written (dry-run)", result.stdout)
        self.assertEqual(self.input.read_bytes(), original)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.folder.glob(".classfixer-*")), [])

    def test_dry_run_with_existing_output(self):
        self.write_archive([])
        self.output.write_bytes(b"existing")
        result = self.run_cli("--dry-run", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(self.output.read_bytes(), b"existing")

    def test_dry_run_validates_crc(self):
        payload = b"unique stored content"
        self.write_archive([("asset.txt", payload)], compression=zipfile.ZIP_STORED)
        self.input.write_bytes(self.input.read_bytes().replace(payload, b"X" + payload[1:], 1))
        self.assert_rejected(self.run_cli("--dry-run", self.input, self.output), "invalid entry size or crc")

    def test_dry_run_combines_with_recovery(self):
        self.write_archive([("wrong.bin", self.packaged)])
        result = self.run_cli("--dry-run", "--recover-names", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("rename -> wrong.bin -> demo/Packaged.class", result.stdout)
        self.assertFalse(self.output.exists())

    def test_dry_run_escapes_control_characters(self):
        self.write_archive([("line\nbreak.class/", self.bytecode)])
        result = self.run_cli("--dry-run", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("line\\u000abreak.class/", result.stdout)
        self.assertNotIn("line\nbreak", result.stdout)

    def test_argument_file_missing_and_oversized(self):
        arguments = self.folder / "arguments.txt"
        self.assertEqual(self.run_cli("--args-file", arguments).returncode, 1)
        arguments.write_bytes(b"a" * (1024 * 1024 + 1))
        self.assertIn("exceeds 1 mib", self.run_cli("--args-file", arguments).stderr)

    def test_debug_stripping_preserves_executable_bytecode(self):
        for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            with self.subTest(compression=compression):
                target = self.folder / f"stripped-{compression}.jar"
                self.write_archive([("demo/Packaged.class", self.packaged)], compression=compression)
                result = self.run_cli("--strip-debug", self.input, target)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("rewritten classes -> 1", result.stdout)
                inspection = subprocess.run(
                    ["javap", "-v", "-classpath", str(target), "demo.Packaged"],
                    capture_output=True, text=True, timeout=20,
                )
                self.assertEqual(inspection.returncode, 0, inspection.stderr)
                for attribute in ("SourceFile:", "LineNumberTable:", "LocalVariableTable:", "LocalVariableTypeTable:"):
                    self.assertNotIn(attribute, inspection.stdout)
                self.assertIn("StackMapTable:", inspection.stdout)
                execution = subprocess.run(
                    ["java", "-verify", "-cp", str(target), "demo.Packaged"],
                    capture_output=True, text=True, timeout=20,
                )
                self.assertEqual(execution.returncode, 0, execution.stderr)
                self.assertIn("packaged", execution.stdout)

    def test_debug_stripping_repairs_invalid_source_reference(self):
        self.assertEqual(struct.unpack(">I", self.bytecode[-6:-2])[0], 2)
        damaged = self.bytecode[:-2] + b"\x00\x00"
        self.write_archive([("Example.class", damaged)])
        before = subprocess.run(
            ["java", "-cp", str(self.input), "Example"], capture_output=True, text=True, timeout=20
        )
        self.assertNotEqual(before.returncode, 0)
        result = self.run_cli("--strip-debug", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        after = subprocess.run(
            ["java", "-verify", "-cp", str(self.output), "Example"],
            capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(after.returncode, 0, after.stderr)
        self.assertEqual(after.stdout.strip(), "fixture -> ok")

    def test_debug_stripping_rejects_signed_byte_changes(self):
        self.write_archive([("Example.class", self.bytecode), ("META-INF/TEST.SF", b"signature")])
        self.assert_rejected(self.run_cli("--strip-debug", self.input, self.output), "invalidate jar signatures")

    def test_debug_stripping_rejects_truncated_class(self):
        self.write_archive([("Example.class", self.bytecode[:-1])])
        self.assert_rejected(self.run_cli("--strip-debug", self.input, self.output), "truncated class attribute")

    def test_debug_stripping_is_idempotent(self):
        self.write_archive([("Example.class/", self.bytecode)])
        first = self.run_cli("--strip-debug", "--recover-names", self.input, self.output)
        self.assertEqual(first.returncode, 0, first.stderr)
        second_output = self.folder / "second.jar"
        second = self.run_cli("--strip-debug", self.output, second_output)
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertIn("rewritten classes -> 0", second.stdout)
        with zipfile.ZipFile(self.output) as first_zip, zipfile.ZipFile(second_output) as second_zip:
            self.assertEqual(first_zip.read("Example.class"), second_zip.read("Example.class"))

    def test_dry_run_debug_stripping(self):
        self.write_archive([("Example.class", self.bytecode)])
        result = self.run_cli("--dry-run", "--strip-debug", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("rewritten classes -> 1", result.stdout)
        self.assertFalse(self.output.exists())

    def test_class_mutations_fail_without_partial_output_or_stack_traces(self):
        generator = random.Random(1947)
        for index in range(40):
            payload = bytearray(self.packaged)
            if index % 2:
                payload = payload[:generator.randrange(4, len(payload))]
            else:
                position = generator.randrange(4, len(payload))
                payload[position] ^= 0xff
            self.write_archive([("wrong.class", payload)])
            result = self.run_cli("--dry-run", "--strip-debug", "--recover-names", self.input, self.output)
            with self.subTest(index=index):
                self.assertIn(result.returncode, (0, 1), result.stderr)
                self.assertNotIn("Exception in thread", result.stderr)
                self.assertFalse(self.output.exists())
                self.assertEqual(list(self.folder.glob(".classfixer-*")), [])

    def test_generic_stripping_repairs_reflection(self):
        signature = b"<T:Ljava/lang/Object;>Ljava/lang/Object;"
        self.assertIn(signature, self.generic)
        damaged = self.generic.replace(signature, b"!" + signature[1:])
        self.write_archive([("GenericFixture.class", damaged)])
        before = subprocess.run(
            ["java", "-cp", str(self.input), "GenericFixture"], capture_output=True, text=True, timeout=20
        )
        self.assertNotEqual(before.returncode, 0)
        self.assertIn("GenericSignatureFormatError", before.stderr)
        result = self.run_cli("--strip-generics", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        after = subprocess.run(
            ["java", "-verify", "-cp", str(self.output), "GenericFixture"],
            capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(after.returncode, 0, after.stderr)
        self.assertIn("parameters -> 0", after.stdout)
        inspection = subprocess.run(
            ["javap", "-v", "-classpath", str(self.output), "GenericFixture"],
            capture_output=True, text=True, timeout=20,
        )
        self.assertNotIn("Signature:", inspection.stdout)
        self.assertIn("LineNumberTable:", inspection.stdout)

    def test_generic_stripping_combines_with_debug_stripping(self):
        self.write_archive([("GenericFixture.class/", self.generic)])
        result = self.run_cli("--strip-debug", "--strip-generics", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        inspection = subprocess.run(
            ["javap", "-v", "-classpath", str(self.output), "GenericFixture"],
            capture_output=True, text=True, timeout=20,
        )
        self.assertNotIn("Signature:", inspection.stdout)
        self.assertNotIn("LineNumberTable:", inspection.stdout)

    def test_trailing_data_repair_restores_jvm_loading(self):
        self.write_archive([("Example.class", self.bytecode + b"trailing junk")])
        before = subprocess.run(
            ["java", "-cp", str(self.input), "Example"], capture_output=True, text=True, timeout=20
        )
        self.assertNotEqual(before.returncode, 0)
        self.assertIn("Extra bytes", before.stderr)
        result = self.run_cli("--trim-trailing", self.input, self.output)
        self.assertEqual(result.returncode, 0, result.stderr)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.read("Example.class"), self.bytecode)
        after = subprocess.run(
            ["java", "-verify", "-cp", str(self.output), "Example"],
            capture_output=True, text=True, timeout=20,
        )
        self.assertEqual(after.returncode, 0, after.stderr)

    def test_trailing_data_preserved_by_default(self):
        payload = self.bytecode + b"trailing junk"
        self.write_archive([("Example.class", payload)])
        self.assertEqual(self.fix().returncode, 0)
        with zipfile.ZipFile(self.output) as archive:
            self.assertEqual(archive.read("Example.class"), payload)

    def test_trimming_rejects_truncated_class(self):
        self.write_archive([("Example.class", self.bytecode[:-1])])
        self.assert_rejected(self.run_cli("--trim-trailing", self.input, self.output), "truncated class attribute")


if __name__ == "__main__":
    unittest.main()
