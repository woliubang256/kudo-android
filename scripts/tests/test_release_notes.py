from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import hashlib
import os
import shutil
import json

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from release_notes import generate_notes, previous_tag, render_notes


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "gc.auto", "0")
        self.git("config", "maintenance.auto", "false")
        self.git("config", "user.name", "Fixture")
        self.git("config", "user.email", "fixture@example.invalid")

    def tearDown(self):
        self.temporary.cleanup()

    def test_fixture_disables_background_git_maintenance_before_cleanup(self):
        self.assertEqual("0", self.git("config", "--local", "--get", "gc.auto").strip())
        self.assertEqual("false", self.git("config", "--local", "--get", "maintenance.auto").strip())

    def test_published_pages_select_latest_stable_across_pages_excluding_current(self):
        from release_notes import published_baseline
        release = lambda tag, date, **flags: {"tag_name": tag, "published_at": date, "draft": False, "prerelease": False, **flags}
        pages = [[release("v0.2.0", "2026-10-12"), release("v0.1.9", "2026-10-11", draft=True)],
                 [release("v0.1.8", "2026-10-10", prerelease=True), release("v0.1.5", "2026-10-09"), release("v0.1.4", "2026-10-08")]]
        self.assertEqual("v0.1.5", published_baseline(pages, "v0.2.0"))
        self.assertEqual("", published_baseline([[]], "v0.2.0"))
        for invalid in ({"message": "API failure"}, ["bad page"], [[None]]):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                published_baseline(invalid, "v0.2.0")

    def test_cli_consumes_published_pages_for_the_reviewed_app_notes(self):
        self.commit("feat: 已公开功能")
        self.git("tag", "v0.1.5")
        self.commit("fix: 新功能修复")
        self.git("tag", "v0.2.0")
        pages = self.root / "pages.json"
        pages.write_text(json.dumps([[{"tag_name": "v0.1.5", "published_at": "2026-10-09", "draft": False, "prerelease": False}]]), encoding="utf-8")
        output = self.root / "notes.md"
        result = subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "release_notes.py"),
            "--tag", "v0.2.0", "--published-releases", str(pages), "--output", str(output)], cwd=self.root, capture_output=True)
        self.assertEqual(0, result.returncode, result.stderr)
        notes = output.read_text(encoding="utf-8")
        self.assertIn("新功能修复", notes)
        self.assertNotIn("已公开功能", notes)
        self.assertIn("/compare/v0.1.5...v0.2.0", notes)

    def git(self, *args):
        return subprocess.run(["git", *args], cwd=self.root, capture_output=True, check=True, encoding="utf-8").stdout

    def commit(self, subject, paths=("app/src/main/java/com/kudo/android/Feature.kt",)):
        for path in paths:
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(subject, encoding="utf-8")
            self.git("add", path)
        self.git("-c", "commit.gpgsign=false", "commit", "--allow-empty", "-m", subject)

    def notes_cli(self, *arguments):
        return subprocess.run([sys.executable, str(Path(__file__).resolve().parents[1] / "release_notes.py"),
            "--repository", "owner/repo", *arguments], cwd=self.root, capture_output=True, encoding="utf-8",
            env={**os.environ, "PYTHONIOENCODING": "utf-8"})

    def prepare_summary(self):
        self.commit("feat: 已发布功能")
        self.git("tag", "v0.1.0")
        self.commit("feat: 增加话题新回复订阅")
        self.commit("fix: 修复新回复加载时的位置重置")
        self.commit("feat: 显示正在回复的用户头像")
        self.commit("fix: 保持回复按钮宽度")
        prepared = self.root / "release-notes/v0.1.1.md"
        result = self.notes_cli("--tag", "v0.1.1", "--previous-tag", "v0.1.0", "--prepare", "--output", str(prepared))
        self.assertEqual(0, result.returncode, result.stderr)
        template = prepared.read_text(encoding="utf-8")
        header = template.splitlines()[0]
        summary = "# 酷DO 0.1.1\n\n## 新增\n\n- 话题详情自动加载新回复，保留阅读位置。\n- 回复按钮显示正在回复的用户头像，保持按钮宽度。\n\n[完整变更](https://github.com/owner/repo/compare/v0.1.0...v0.1.1)\n"
        prepared.write_text(header + "\n" + summary, encoding="utf-8")
        return prepared, template, summary

    def test_prepared_summary_merges_related_commits_and_keeps_raw_candidates(self):
        prepared, template, summary = self.prepare_summary()
        self.assertIn("增加话题新回复订阅", template)
        self.assertIn("修复新回复加载时的位置重置", template)
        self.assertIn("保持回复按钮宽度", template)
        self.git("add", str(prepared.relative_to(self.root)))
        self.git("-c", "commit.gpgsign=false", "commit", "-m", "doc: 整理发布说明")
        self.commit("ci: 调整工作流", (".github/workflows/fixture.yml",))
        self.git("tag", "v0.1.1")
        output, raw = self.root / "notes.md", self.root / "raw.md"
        result = self.notes_cli("--tag", "v0.1.1", "--previous-tag", "v0.1.0", "--prepared-notes-dir", "release-notes",
            "--raw-output", str(raw), "--output", str(output))
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(summary, output.read_text(encoding="utf-8"))
        candidates = raw.read_text(encoding="utf-8")
        self.assertIn("修复新回复加载时的位置重置", candidates)
        self.assertNotIn("调整工作流", candidates)
        self.assertNotIn("整理发布说明", candidates)
        self.assertNotIn("kudo-release-notes", output.read_text(encoding="utf-8"))

    def test_prepared_summary_detects_later_app_changes_and_changed_baselines(self):
        self.prepare_summary()
        self.commit("test: 增加验收用例", ("app/src/test/Fixture.kt",))
        output = self.root / "notes.md"
        arguments = ("--tag", "v0.1.1", "--from-head", "--prepared-notes-dir", "release-notes", "--output", str(output))
        result = self.notes_cli(*arguments, "--previous-tag", "v0.1.0")
        self.assertEqual(0, result.returncode, result.stderr)
        output.unlink()
        result = self.notes_cli(*arguments, "--previous-tag", "")
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())
        self.commit("fix: 补齐新回复的失败处理")
        result = self.notes_cli(*arguments, "--previous-tag", "v0.1.0")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("过期", result.stderr)
        self.assertFalse(output.exists())

    def test_missing_prepared_notes_fall_back_but_invalid_existing_notes_fail(self):
        self.commit("feat: 新功能")
        self.git("tag", "v0.1.0")
        output = self.root / "notes.md"
        arguments = ("--tag", "v0.1.0", "--prepared-notes-dir", "release-notes", "--output", str(output))
        result = self.notes_cli(*arguments)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("新功能", output.read_text(encoding="utf-8"))
        output.unlink()
        prepared = self.root / "release-notes/v0.1.0.md"
        prepared.parent.mkdir()
        prepared.write_text("## 新增\n- 过期且没有来源的说明\n", encoding="utf-8")
        result = self.notes_cli(*arguments)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

    def test_prepared_notes_reject_invalid_sections_duplicates_and_wrong_release_links(self):
        prepared, _, summary = self.prepare_summary()
        header = prepared.read_text(encoding="utf-8").splitlines()[0]
        malformed = (
            summary.replace("## 新增", "## 工具链"),
            summary.replace("# 酷DO 0.1.1", "# 酷DO 0.1.2"),
            summary.replace("compare/v0.1.0...v0.1.1", "compare/v0.0.9...v0.1.1"),
            summary.replace("- 回复按钮显示正在回复的用户头像，保持按钮宽度。", "- 话题详情自动加载新回复，保留阅读位置。"),
            summary.replace("## 新增", "## 修复\n\n## 新增"),
            summary.replace("## 新增", "## 新增\n\n不是更新列表的段落"),
        )
        output = self.root / "notes.md"
        for body in malformed:
            with self.subTest(body=body):
                prepared.write_text(header + "\n" + body, encoding="utf-8")
                result = self.notes_cli("--tag", "v0.1.1", "--previous-tag", "v0.1.0", "--from-head",
                    "--prepared-notes-dir", "release-notes", "--output", str(output))
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(output.exists())

    def test_prepare_does_not_overwrite_a_reviewed_file_and_raw_output_must_be_separate(self):
        prepared, _, _ = self.prepare_summary()
        before = prepared.read_bytes()
        result = self.notes_cli("--tag", "v0.1.1", "--previous-tag", "v0.1.0", "--prepare", "--output", str(prepared))
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(before, prepared.read_bytes())
        output = self.root / "notes.md"
        result = self.notes_cli("--tag", "v0.1.1", "--previous-tag", "v0.1.0", "--from-head",
            "--raw-output", str(output), "--output", str(output))
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())
        for outputs in (("--output", str(prepared)), ("--raw-output", str(prepared), "--output", str(output))):
            result = self.notes_cli("--tag", "v0.1.1", "--previous-tag", "v0.1.0", "--from-head",
                "--prepared-notes-dir", "release-notes", *outputs)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual(before, prepared.read_bytes())

    def test_preparing_a_first_release_without_app_changes_cannot_invent_changes(self):
        self.commit("ci: 构建流程", (".github/workflows/fixture.yml",))
        prepared = self.root / "release-notes/v0.1.0.md"
        result = self.notes_cli("--tag", "v0.1.0", "--previous-tag", "", "--prepare", "--output", str(prepared))
        self.assertEqual(0, result.returncode, result.stderr)
        template = prepared.read_text(encoding="utf-8")
        self.assertIn('"previous_tag": null', template)
        self.assertIn("本版本暂无 App 功能变更。", template)
        output = self.root / "notes.md"
        arguments = ("--tag", "v0.1.0", "--previous-tag", "", "--from-head", "--prepared-notes-dir", "release-notes", "--output", str(output))
        result = self.notes_cli(*arguments)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("kudo-release-notes", output.read_text(encoding="utf-8"))
        output.unlink()
        prepared.write_text(template.replace("本版本暂无 App 功能变更。", "## 新增\n\n- 不存在的功能"), encoding="utf-8")
        result = self.notes_cli(*arguments)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse(output.exists())

    def test_first_release_includes_history_and_groups_deduplicated_subjects(self):
        self.commit("支持原生帖子")
        self.commit("fix(ui): 修复头像角标")
        self.commit("调整设置间距")
        self.commit("docs: 更新说明", ("README.md",))
        self.commit("支持原生帖子")
        self.git("tag", "v0.1.0")
        notes = generate_notes("v0.1.0", "owner/repo", self.root)
        for heading in ("新增", "修复", "调整"):
            self.assertIn(f"## {heading}", notes)
        self.assertEqual(1, notes.count("- 支持原生帖子"))
        self.assertIn("- 修复头像角标", notes)
        self.assertNotIn("fix(ui):", notes)
        self.assertNotIn("更新说明", notes)
        self.assertNotIn("维护", notes)
        self.assertIn("/commits/v0.1.0", notes)

    def test_incremental_range_chooses_numeric_ancestor_and_ignores_other_branches(self):
        self.commit("旧版本内容")
        self.git("tag", "v0.8.0")
        self.git("tag", "not-a-release")
        self.git("checkout", "-b", "other")
        self.commit("其他分支")
        self.git("tag", "v0.9.0")
        self.git("checkout", "main")
        self.commit("旧的小版本")
        self.git("tag", "v0.8.9")
        self.commit("feat!: 新版本能力")
        self.git("tag", "v1.0.0")
        self.assertEqual("v0.8.9", previous_tag("v1.0.0", self.root))
        notes = generate_notes("v1.0.0", "owner/repo", self.root)
        self.assertIn("新版本能力", notes)
        self.assertNotIn("旧版本内容", notes)
        self.assertNotIn("其他分支", notes)
        self.assertIn("/compare/v0.8.9...v1.0.0", notes)

    def test_merged_subjects_empty_changes_and_invalid_parameters(self):
        notes = render_notes("v1.0.0", ["Merge branch 'feature'", "", "  "], "owner/repo", "v0.9.0")
        self.assertIn("本版本暂无 App 功能变更", notes)
        for repository in ("../unsafe", "https://github.com/a/b", "a/b\n# injected"):
            with self.assertRaises(ValueError):
                render_notes("v1.0.0", [], repository, None)
        with self.assertRaises(ValueError):
            generate_notes("v1.0.0;unsafe", "owner/repo", self.root)
        with self.assertRaises(ValueError):
            generate_notes("v1.0.0", "owner/repo", self.root)

    def test_published_baseline_keeps_changes_from_failed_tags_and_rejects_invalid_ranges(self):
        self.commit("feat: 已发布功能")
        self.git("tag", "v0.1.0")
        self.commit("fix: 首次失败构建的修复")
        self.git("tag", "v0.1.1")
        self.commit("feat: 第二次失败构建的新功能")
        self.git("tag", "v0.1.2")
        self.commit("test: 修复发布检查", ("scripts/fixture.py",))
        self.git("tag", "v0.1.3")
        notes = generate_notes("v0.1.3", "owner/repo", self.root, previous="v0.1.0")
        self.assertIn("首次失败构建的修复", notes)
        self.assertIn("第二次失败构建的新功能", notes)
        self.assertNotIn("已发布功能", notes)
        self.assertNotIn("修复发布检查", notes)
        self.assertIn("/compare/v0.1.0...v0.1.3", notes)
        first = generate_notes("v0.1.3", "owner/repo", self.root, previous="")
        self.assertIn("已发布功能", first)
        self.assertIn("/commits/v0.1.3", first)
        self.git("checkout", "--orphan", "other")
        self.commit("feat: 无共同祖先的版本")
        self.git("tag", "v0.0.9")
        for previous in ("v0.1.3", "v0.1.4", "v0.0.9", "v0.1.0;unsafe"):
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                generate_notes("v0.1.3", "owner/repo", self.root, previous=previous)

    def test_merge_commit_is_excluded_but_feature_is_kept(self):
        self.commit("初始提交")
        self.git("tag", "v0.1.0")
        self.git("checkout", "-b", "feature")
        self.commit("新增检查更新")
        self.git("checkout", "main")
        self.git("-c", "commit.gpgsign=false", "merge", "--no-ff", "feature", "-m", "合并功能")
        self.git("tag", "v0.2.0")
        notes = generate_notes("v0.2.0", "owner/repo", self.root)
        self.assertIn("新增检查更新", notes)
        self.assertNotIn("合并功能", notes)

    def test_only_app_changes_and_allowed_types_are_kept(self):
        for subject, paths in (
            ("feat: 新增运行功能", ("app/src/main/java/com/kudo/android/Feature.kt",)),
            ("fix: 修复运行问题", ("app/src/main/res/values/strings.xml",)),
            ("perf: 优化渲染", ("app/src/main/java/com/kudo/android/Feature.kt",)),
            ("docs: 更新说明", ("app/src/main/java/com/kudo/android/Feature.kt",)),
            ("doc: 更新说明", ("app/src/main/java/com/kudo/android/Feature.kt",)),
            ("feat: 补测试", ("app/src/test/java/com/kudo/android/FeatureTest.kt",)),
            ("fix: 更新许可证", ("app/src/main/assets/licenses/libavif.txt",)),
            ("ci: 调整工作流", (".github/workflows/release.yml",)),
            ("支持旧版功能", ("app/src/main/java/com/kudo/android/Legacy.kt",)),
            ("保留旧版功能", ("app/src/main/java/com/kudo/android/Legacy.kt",)),
            ("构建旧版包", ("app/build.gradle.kts",)),
        ):
            self.commit(subject, paths)
        self.git("tag", "v1.0.0")
        notes = generate_notes("v1.0.0", "owner/repo", self.root)
        self.assertIn("- 新增运行功能", notes)
        self.assertIn("- 修复运行问题", notes)
        self.assertIn("- 优化渲染", notes)
        self.assertIn("- 支持旧版功能", notes)
        self.assertIn("- 保留旧版功能", notes)
        self.assertNotIn("补测试", notes)
        self.assertNotIn("更新许可证", notes)
        self.assertNotIn("调整工作流", notes)
        self.assertNotIn("更新说明", notes)
        self.assertNotIn("构建旧版包", notes)
        self.assertNotIn("## 维护", notes)

    def test_bundled_native_runtime_changes_are_kept(self):
        for subject, paths in (
            ("feat: 新增原生偏好", ("native/doh/src/edge_selection.rs",)),
            ("fix: 修复原生依赖", ("native/doh/Cargo.toml", "native/doh/Cargo.lock")),
            ("fix: 更新原生许可证", ("native/doh/licenses/rustls.txt",)),
            ("test: 补原生测试", ("native/doh/src/edge_pool.rs",)),
            ("feat: 新增别处原生", ("native/other/src/lib.rs",)),
        ):
            self.commit(subject, paths)
        self.git("tag", "v1.0.0")
        notes = generate_notes("v1.0.0", "owner/repo", self.root)
        self.assertIn("- 新增原生偏好", notes)
        self.assertIn("- 修复原生依赖", notes)
        self.assertNotIn("更新原生许可证", notes)
        self.assertNotIn("补原生测试", notes)
        self.assertNotIn("新增别处原生", notes)

    def test_actual_workflow_writes_notes_for_new_and_retried_drafts_before_publication(self):
        bash = str(Path(r"C:\Program Files\Git\bin\bash.exe")) if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for the workflow shell fixture")
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text(encoding="utf-8")
        block = workflow.split("      - name: Publish GitHub Release\n", 1)[1].split("        run: |\n", 1)[1]
        shell = "\n".join(line[10:] for line in block.splitlines())
        stub = '''set -euo pipefail
gh() {
  printf '%s\\n' "$*" >> calls.txt
  case "$1 $2" in
    'release view')
      case "$SCENARIO" in
        new|upload-failure) return 1 ;;
        published) printf 'false\\n' ;;
        *) printf 'true\\n' ;;
      esac ;;
    'release create') [[ "$*" == *'--notes-file release-notes.md'* ]] && [[ -s release-notes.md ]] ;;
    'release upload') [[ "$SCENARIO" != 'upload-failure' ]] ;;
    'release edit')
      if [[ "$*" == *'--notes-file'* ]]; then [[ -s release-notes.md ]]; fi ;;
  esac
}
'''
        (self.root / "publish.sh").write_text(stub + shell + "\n", encoding="utf-8", newline="\n")
        (self.root / "release-notes.md").write_text("## 新增\n\n- 检查更新\n", encoding="utf-8")
        apk = self.root / "fixture.apk"
        apk.write_bytes(b"verified APK fixture")
        for scenario in ("new", "draft", "published", "upload-failure", "checksum-failure"):
            with self.subTest(scenario=scenario):
                calls = self.root / "calls.txt"
                calls.unlink(missing_ok=True)
                checksum = "0" * 64 if scenario == "checksum-failure" else hashlib.sha256(apk.read_bytes()).hexdigest()
                (self.root / "fixture.apk.sha256").write_text(f"{checksum}  fixture.apk\n", encoding="ascii", newline="\n")
                result = subprocess.run([bash, "publish.sh"], cwd=self.root, capture_output=True,
                    env={**os.environ, "SCENARIO": scenario, "APK_NAME": "fixture.apk", "RELEASE_TAG": "v1.2.3", "VERSION_NAME": "1.2.3"})
                recorded = calls.read_text(encoding="utf-8") if calls.exists() else ""
                self.assertEqual(scenario in ("new", "draft"), result.returncode == 0, result.stderr)
                self.assertNotIn("--generate-notes", recorded)
                if scenario in ("new", "draft"):
                    self.assertIn("release edit v1.2.3 --notes-file release-notes.md", recorded)
                    self.assertTrue(recorded.index("--notes-file") < recorded.index("release upload"))
                    self.assertTrue(recorded.index("release upload") < recorded.index("--draft=false"))
                else:
                    self.assertNotIn("--draft=false", recorded)
                if scenario == "published":
                    self.assertNotIn("--notes-file", recorded)

    def test_workflow_passes_published_baseline_and_stops_when_query_fails(self):
        bash = str(Path(r"C:\Program Files\Git\bin\bash.exe")) if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for the workflow shell fixture")
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/release.yml").read_text(encoding="utf-8")
        block = workflow.split("      - name: Organize release notes from commits\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("        run: |\n", block)
        shell = "\n".join(line[10:] for line in block.split("        run: |\n", 1)[1].splitlines())
        stub = '''set -euo pipefail
gh() {
  if [[ "$*" == *'--slurp'* && "$*" == *'--jq'* ]]; then
    printf '%s\\n' 'the `--slurp` option is not supported with `--jq` or `--template`' >&2
    return 1
  fi
  [[ "$SCENARIO" != error ]] || return 1
  printf '%s' "$PUBLISHED_PAGES"
}
python3() { printf '%s\\n' "$@" > arguments.txt; }
'''
        (self.root / "notes.sh").write_text(stub + shell, encoding="utf-8", newline="\n")
        pages = [[{"tag_name": "v0.1.0", "draft": False, "prerelease": False, "published_at": "2026-10-01T00:00:00Z"}]]
        for scenario, published in (("published", pages), ("first", [[]]), ("error", [[]])):
            with self.subTest(scenario=scenario):
                arguments = self.root / "arguments.txt"
                arguments.unlink(missing_ok=True)
                result = subprocess.run([bash, "notes.sh"], cwd=self.root, capture_output=True,
                    env={**os.environ, "SCENARIO": scenario, "PUBLISHED_PAGES": json.dumps(published),
                        "RELEASE_TAG": "v0.1.3", "RELEASE_REPOSITORY": "owner/repo", "RUNNER_TEMP": str(self.root)})
                self.assertEqual(scenario != "error", result.returncode == 0, result.stderr)
                if scenario == "error":
                    self.assertFalse(arguments.exists())
                else:
                    values = arguments.read_text().splitlines()
                    self.assertNotIn("--previous-tag", values)
                    self.assertEqual("release-notes", values[values.index("--prepared-notes-dir") + 1])
                    raw_file = Path(values[values.index("--raw-output") + 1])
                    self.assertEqual(self.root / "kudo-release-dist/release-notes.raw.md", raw_file)
                    published_file = Path(values[values.index("--published-releases") + 1])
                    self.assertEqual(self.root / "published-releases.json", published_file)
                    self.assertEqual(published, json.loads(published_file.read_text(encoding="utf-8")))

    def test_actual_workflow_uses_prepared_notes_retains_raw_and_rejects_invalid_sources(self):
        bash = r"C:\Program Files\Git\bin\bash.exe" if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for the workflow shell fixture")
        project = Path(__file__).resolve().parents[2]
        workflow = (project / ".github/workflows/release.yml").read_text(encoding="utf-8")
        block = workflow.split("      - name: Organize release notes from commits\n", 1)[1].split("      - name:", 1)[0]
        shell = "\n".join(line[10:] for line in block.split("        run: |\n", 1)[1].splitlines())
        stub = '''set -euo pipefail
gh() { printf '%s' "$PUBLISHED_PAGES"; }
python3() { "$PYTHON_EXECUTABLE" "$@"; }
'''
        (self.root / "notes.sh").write_text(stub + shell, encoding="utf-8", newline="\n")
        (self.root / "scripts").mkdir()
        for filename in ("release_notes.py", "release_version.py"):
            shutil.copyfile(project / "scripts" / filename, self.root / "scripts" / filename)
        prepared, template, summary = self.prepare_summary()
        reviewed = prepared.read_text(encoding="utf-8")
        self.git("add", "release-notes/v0.1.1.md")
        self.git("-c", "commit.gpgsign=false", "commit", "-m", "doc: 整理发布说明")
        self.git("tag", "v0.1.1")
        output = self.root / "kudo-release-dist/release-notes.md"
        raw = output.with_name("release-notes.raw.md")
        pages = [[{"tag_name": "v0.1.0", "draft": False, "prerelease": False, "published_at": "2026-10-01T00:00:00Z"}]]
        for scenario in ("prepared", "missing", "invalid", "stale"):
            with self.subTest(scenario=scenario):
                output.unlink(missing_ok=True)
                raw.unlink(missing_ok=True)
                prepared.write_text(reviewed, encoding="utf-8")
                if scenario == "missing":
                    prepared.unlink()
                elif scenario == "invalid":
                    prepared.write_text(reviewed.replace("## 新增", "## 工具链"), encoding="utf-8")
                elif scenario == "stale":
                    self.commit("fix: 补齐新回复的失败处理")
                    self.git("tag", "-f", "v0.1.1")
                result = subprocess.run([bash, "notes.sh"], cwd=self.root, capture_output=True, encoding="utf-8",
                    env={**os.environ, "PUBLISHED_PAGES": json.dumps(pages), "PYTHON_EXECUTABLE": Path(sys.executable).as_posix(),
                        "PYTHONIOENCODING": "utf-8", "RELEASE_TAG": "v0.1.1", "RELEASE_REPOSITORY": "owner/repo",
                        "RUNNER_TEMP": self.root.as_posix()})
                self.assertEqual(scenario in ("prepared", "missing"), result.returncode == 0, result.stderr)
                self.assertIn("修复新回复加载时的位置重置", raw.read_text(encoding="utf-8"))
                if scenario == "prepared":
                    self.assertEqual(summary, output.read_text(encoding="utf-8"))
                elif scenario == "missing":
                    self.assertEqual(template.partition("\n")[2], output.read_text(encoding="utf-8"))
                else:
                    self.assertFalse(output.exists())
                    self.assertIn("过期" if scenario == "stale" else "只允许", result.stderr)
        upload = workflow.split("      - name: Upload verified release files\n", 1)[1].split("      - name:", 1)[0]
        self.assertIn("kudo-release-dist/release-notes.raw.md", upload)


if __name__ == "__main__":
    unittest.main()
