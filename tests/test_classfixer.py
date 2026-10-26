import pathlib
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

    def test_argument_file_missing_and_oversized(self):
        arguments = self.folder / "arguments.txt"
        self.assertEqual(self.run_cli("--args-file", arguments).returncode, 1)
        arguments.write_bytes(b"a" * (1024 * 1024 + 1))
        self.assertIn("exceeds 1 mib", self.run_cli("--args-file", arguments).stderr)


if __name__ == "__main__":
    unittest.main()
