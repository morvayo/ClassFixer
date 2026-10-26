import argparse
import os
import pathlib
import shutil
import subprocess
import tempfile


ROOT = pathlib.Path(__file__).resolve().parent


def main():
    parser = argparse.ArgumentParser(description="build the Java 17 command-line application")
    parser.add_argument("--test", action="store_true", help="run integration tests after building")
    options = parser.parse_args()
    for command in ("javac", "java", "jar"):
        if shutil.which(command) is None:
            parser.exit(1, f"missing tool -> {command}; install JDK 17 or newer\n")
    build = ROOT / "build"
    build.mkdir(exist_ok=True)
    try:
        with tempfile.TemporaryDirectory(dir=build) as temporary:
            staging = pathlib.Path(temporary)
            classes = staging / "classes"
            classes.mkdir()
            sources = sorted((ROOT / "src").rglob("*.java"))
            subprocess.run(
                ["javac", "--release", "17", "-encoding", "UTF-8", "-d", str(classes), *map(str, sources)],
                check=True, cwd=ROOT,
            )
            artifact = staging / "ClassFixer.jar"
            subprocess.run(
                ["jar", "--create", "--date=2000-01-01T00:00:00Z", "--file", str(artifact),
                 "--main-class", "classfixer.Main", "-C", str(classes), "."], check=True, cwd=ROOT,
            )
            os.replace(artifact, build / "ClassFixer.jar")
        print("build -> ok", flush=True)
        if options.test:
            subprocess.run(
                [os.sys.executable, "-m", "unittest", "discover", "-s", "tests", "-v"], check=True, cwd=ROOT,
            )
    except (OSError, subprocess.CalledProcessError) as error:
        parser.exit(1, f"build -> failed: {error}\n")


if __name__ == "__main__":
    main()
