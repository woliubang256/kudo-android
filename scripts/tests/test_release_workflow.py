"""Exercise the actual release workflow's native toolchain setup contract."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")

    def step(self, name):
        marker = f"      - name: {name}\n"
        self.assertTrue(marker in self.workflow, f"Release must provide {name} before compilation")
        return self.workflow.split(marker, 1)[1].split("      - name:", 1)[0]

    def test_android_setup_installs_the_native_builder_requirements(self):
        builder = (ROOT / "scripts/build_doh.py").read_text(encoding="utf-8")
        ndk = re.search(r'add_argument\("--ndk", default="([^"]+)"\)', builder).group(1)
        setup = self.step("Set up Android SDK")
        packages = re.search(r"packages: '([^']+)'", setup).group(1).split()
        for package in (f"ndk;{ndk}", "cmake;3.22.1", "platforms;android-36", "build-tools;35.0.0"):
            self.assertIn(package, packages)
        self.assertLess(self.workflow.index("name: Set up Android SDK"), self.workflow.index("name: Unit tests and Android Lint"))

    def test_rust_setup_installs_the_android_target_and_stops_on_install_failure(self):
        step = self.step("Set up Rust")
        version = re.search(r"RUST_VERSION: '([0-9]+\.[0-9]+\.[0-9]+)'", step).group(1)
        self.assertIn("        run: |\n", step)
        shell = "\n".join(line[10:] for line in step.split("        run: |\n", 1)[1].splitlines())
        bash = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for the workflow fixture")
        stub = '''set -euo pipefail
rustup() { printf 'rustup %s\\n' "$*" >> calls.txt; [[ "$SCENARIO" != failure || "$1" != toolchain ]]; }
rustc() { printf 'rustc %s\\n' "$*" >> calls.txt; }
cargo() { printf 'cargo %s\\n' "$*" >> calls.txt; }
'''
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "setup.sh").write_text(stub + shell + "\n", encoding="utf-8", newline="\n")
            for scenario in ("success", "failure"):
                with self.subTest(scenario=scenario):
                    calls = root / "calls.txt"
                    calls.unlink(missing_ok=True)
                    result = subprocess.run([bash, "setup.sh"], cwd=root, capture_output=True,
                        env={**os.environ, "SCENARIO": scenario, "RUST_VERSION": version})
                    recorded = calls.read_text(encoding="utf-8").splitlines()
                    install = f"rustup toolchain install {version} --profile minimal --target aarch64-linux-android"
                    self.assertEqual(scenario == "success", result.returncode == 0, result.stderr)
                    self.assertEqual(install, recorded[0])
                    if scenario == "success":
                        self.assertEqual([install, f"rustup default {version}", "rustc --version", "cargo --version"], recorded)
                    else:
                        self.assertEqual([install], recorded)

    def test_native_tests_use_the_lockfile_before_android_checks(self):
        native = self.step("Test native DoH")
        self.assertIn("cargo test --locked --manifest-path native/doh/Cargo.toml --lib", native)
        self.assertLess(self.workflow.index("name: Set up Rust"), self.workflow.index("name: Test native DoH"))
        self.assertLess(self.workflow.index("name: Test native DoH"), self.workflow.index("name: Unit tests and Android Lint"))

    def cache_paths(self, step):
        match = re.search(r"          path: \|\n((?:            [^\n]+\n)+)", step)
        self.assertIsNotNone(match, "Cache paths must be an explicit allowlist")
        return [line.strip() for line in match.group(1).splitlines()]

    def test_dependency_caches_exclude_signing_and_only_save_complete_builds(self):
        restore = self.step("Restore dependency cache")
        save = self.step("Save dependency cache")
        allowed = ["~/.gradle/caches/modules-2", "~/.gradle/wrapper/dists", "~/.cargo/registry", "~/.cargo/git", "~/.npm/_cacache"]
        self.assertEqual(allowed, self.cache_paths(restore))
        self.assertEqual(allowed, self.cache_paths(save))
        self.assertRegex(restore, r"uses: actions/cache/restore@[0-9a-f]{40}")
        self.assertRegex(save, r"uses: actions/cache/save@[0-9a-f]{40}")
        self.assertIn("hashFiles(", restore)
        for source in ("native/doh/Cargo.lock", "**/*.gradle.kts", "gradle/wrapper/gradle-wrapper.properties", "package.json"):
            self.assertIn(source, restore)
        self.assertIn("restore-keys:", restore)
        self.assertIn("if: success()", save)
        self.assertIn("release-deps-v2-", restore)
        self.assertIn("release-deps-v1-", restore)
        self.assertIn("steps.dependency-cache.outcome == 'success'", save)
        self.assertIn("steps.dependency-cache.outputs.cache-hit != 'true'", save)
        self.assertIn("steps.dependency-cache.outputs.cache-primary-key", save)
        self.assertLess(self.workflow.index("name: Restore dependency cache"), self.workflow.index("name: Test release tooling"))
        self.assertLess(self.workflow.index("name: Remove temporary signing key"), self.workflow.index("name: Save dependency cache"))

    def test_native_toolchain_cache_is_versioned_and_saved_before_compilation(self):
        restore = self.step("Restore native toolchain cache")
        save = self.step("Save native toolchain cache")
        paths = self.cache_paths(restore)
        self.assertEqual(paths, self.cache_paths(save))
        self.assertEqual([
            "${{ steps.cache-paths.outputs.sdk }}/ndk/27.0.12077973",
            "${{ steps.cache-paths.outputs.sdk }}/cmake/3.22.1",
            "~/.rustup/toolchains/1.96.1-x86_64-unknown-linux-gnu",
        ], paths)
        for version in ("1.96.1", "27.0.12077973", "3.22.1", "arm64"):
            self.assertIn(version, re.search(r"          key: ([^\n]+)", restore).group(1))
        self.assertIn("steps.native-cache.outputs.cache-hit != 'true'", save)
        self.assertLess(self.workflow.index("name: Restore native toolchain cache"), self.workflow.index("name: Set up Android SDK"))
        self.assertLess(self.workflow.index("name: Set up Rust"), self.workflow.index("name: Save native toolchain cache"))
        self.assertLess(self.workflow.index("name: Save native toolchain cache"), self.workflow.index("name: Test native DoH"))

    def test_main_build_populates_caches_shared_with_future_tags(self):
        triggers = self.workflow.split("permissions:", 1)[0]
        self.assertRegex(triggers, r"branches:\s*\n\s*- main")
        self.assertRegex(triggers, r"tags:\s*\n\s*- 'v\*'")
        for name in ("Restore dependency cache", "Restore native toolchain cache"):
            key = re.search(r"          key: ([^\n]+)", self.step(name)).group(1)
            self.assertNotIn("github.ref", key)
            self.assertNotIn("github.sha", key)
        for name in ("Test native DoH", "Unit tests and Android Lint"):
            self.assertNotIn("        if:", self.step(name))

    def test_main_build_only_runs_when_dependency_inputs_or_release_workflow_change(self):
        triggers = self.workflow.split("permissions:", 1)[0]
        match = re.search(r"    paths:\n((?:      - [^\n]+\n)+)", triggers)
        self.assertIsNotNone(match, "Ordinary source or documentation pushes must not start the main cache build")
        paths = {line.strip()[2:].strip("'") for line in match.group(1).splitlines()}
        dependency_hash = re.search(r"hashFiles\(([^)]+)\)", self.step("Restore dependency cache")).group(1)
        inputs = set(re.findall(r"'([^']+)'", dependency_hash))
        self.assertEqual(inputs | {".github/workflows/release.yml"}, paths)

    def test_main_build_never_reads_release_keys_or_publishes(self):
        for name in ("Validate tag and calculate version", "Restore release keystore", "Build signed release",
                     "Verify signature and APK version, then create checksum", "Organize release notes from commits", "Upload verified release files"):
            with self.subTest(step=name):
                self.assertIn("        if: github.ref_type == 'tag'\n", self.step(name))
        publish = self.workflow.split("  publish:\n", 1)[1]
        self.assertIn("    if: github.ref_type == 'tag'\n", publish)

    def test_sentry_upload_only_receives_credentials_in_the_signed_tag_step(self):
        signed = self.step("Build signed release")
        self.assertIn("        if: github.ref_type == 'tag'\n", signed)
        for entry in ("SENTRY_AUTH_TOKEN: ${{ secrets.SENTRY_AUTH_TOKEN }}",
                      "SENTRY_ORG: ${{ vars.SENTRY_ORG }}", "SENTRY_PROJECT: ${{ vars.SENTRY_PROJECT }}"):
            self.assertIn(entry, signed)
            self.assertEqual(1, self.workflow.count(entry))
        self.assertIn('"-PsentryCiUpload=true"', signed)
        self.assertNotIn("sentryCiUpload", self.step("Unit tests and Android Lint"))

    def test_sentry_missing_configuration_stops_before_gradle_without_printing_token(self):
        signed = self.step("Build signed release")
        self.assertIn("        run: |\n", signed)
        shell = "\n".join(line[10:] for line in signed.split("        run: |\n", 1)[1].splitlines())
        bash = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for the workflow fixture")
        configured = {"SENTRY_AUTH_TOKEN": "fixture-secret-never-print", "SENTRY_ORG": "fixture-org", "SENTRY_PROJECT": "fixture-project"}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            (root / "build.sh").write_text("set -euo pipefail\n" + shell + "\n", encoding="utf-8", newline="\n")
            (root / "gradlew").write_text("printf '%s\\n' \"$@\" > gradle-calls.txt\n", encoding="ascii", newline="\n")
            scenarios = [(None, None)] + [(key, value) for key in configured for value in (None, "", "   ")]
            for missing, replacement in scenarios:
                with self.subTest(missing=missing, replacement=replacement):
                    calls = root / "gradle-calls.txt"
                    calls.unlink(missing_ok=True)
                    values = dict(configured)
                    if missing:
                        if replacement is None:
                            values.pop(missing)
                        else:
                            values[missing] = replacement
                    environment = {key: value for key, value in os.environ.items() if not key.startswith("SENTRY_")}
                    environment.update(values, VERSION_NAME="0.3.7", VERSION_CODE="3008")
                    result = subprocess.run([bash, "build.sh"], cwd=root, capture_output=True, text=True, env=environment)
                    self.assertNotIn(configured["SENTRY_AUTH_TOKEN"], result.stdout + result.stderr)
                    if missing:
                        self.assertNotEqual(0, result.returncode)
                        self.assertFalse(calls.exists(), "Missing configuration must not build or upload")
                        self.assertIn(missing, result.stdout + result.stderr)
                    else:
                        self.assertEqual(0, result.returncode, result.stderr)
                        arguments = calls.read_text(encoding="ascii").splitlines()
                        for argument in (":app:verifySentryBuild", ":app:assembleRelease", "-PsentryCiUpload=true", "-PversionName=0.3.7", "-PversionCode=3008"):
                            self.assertIn(argument, arguments)


if __name__ == "__main__":
    unittest.main()
