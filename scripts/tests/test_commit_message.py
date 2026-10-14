import unittest
from pathlib import Path
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from check_commit_message import validate
from check_commit_history import invalid_records, parse_records, read_records


class CommitMessageTest(unittest.TestCase):
    def test_accepts_supported_types_and_scopes(self):
        for subject in ("feat: 添加楼层跳转", "fix(ui): 修复吸顶渐显", "docs: 更新说明", "ci!: 调整工作流"):
            with self.subTest(subject=subject):
                self.assertTrue(validate(subject)[0])

    def test_rejects_untyped_or_unknown_subjects(self):
        for subject in ("修复楼层跳转", "update: change", "feat:", ""):
            with self.subTest(subject=subject):
                self.assertFalse(validate(subject)[0])

    def test_allows_merge_subjects_and_ignores_comment_lines(self):
        self.assertTrue(validate("# comment\nfix: 修复问题\n")[0])
        self.assertTrue(validate("Merge branch 'main'\n")[0])

    def test_history_parser_keeps_subjects_with_non_ascii_text(self):
        records = parse_records("a1\x00feat: 修复中文标题\na2\x00fix(ui): 修复界面\n")
        self.assertEqual([("a1", "feat: 修复中文标题"), ("a2", "fix(ui): 修复界面")], records)

    def test_history_validator_reports_only_unprefixed_subjects(self):
        records = [("good", "docs: 更新说明"), ("bad", "修复旧问题"), ("merge", "Merge branch 'main'")]
        self.assertEqual([("bad", "修复旧问题")], invalid_records(records))

    def test_workflow_checks_main_push_range_and_does_not_trigger_on_tags(self):
        bash = str(Path(r"C:\Program Files\Git\bin\bash.exe")) if os.name == "nt" else shutil.which("bash")
        if not bash or not Path(bash).is_file():
            self.skipTest("Bash is required for the workflow shell fixture")
        workflow = (Path(__file__).resolve().parents[2] / ".github/workflows/commit-messages.yml").read_text(encoding="utf-8")
        triggers = workflow.split("permissions:", 1)[0]
        self.assertIn("    branches:\n      - main", triggers)
        self.assertNotIn("    tags:", triggers)
        self.assertIn("group: commit-messages-${{ github.ref }}", workflow)
        self.assertIn("cancel-in-progress: true", workflow)
        block = workflow.split("        run: |\n", 1)[1]
        shell = "\n".join(line[10:] for line in block.splitlines())
        self.assertNotIn('REF_TYPE" == "tag"', shell)
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.run(["git", *args], cwd=root, check=True, capture_output=True,
                    encoding="utf-8", env={**os.environ, "GIT_CONFIG_NOSYSTEM": "1"}).stdout.strip()
            git("init")
            git("config", "user.name", "Fixture")
            git("config", "user.email", "fixture@example.test")
            git("config", "core.hooksPath", ".git/no-hooks")
            git("commit", "--allow-empty", "-m", "旧版未规范提交")
            legacy = git("rev-parse", "HEAD")
            git("tag", "-a", "v0.1.0", "-m", "legacy")
            git("commit", "--allow-empty", "-m", "fix: 修复当前功能")
            good = git("rev-parse", "HEAD")
            git("commit", "--allow-empty", "-m", "新增但没有前缀")
            invalid = git("rev-parse", "HEAD")
            git("commit", "--allow-empty", "-m", "doc: 记录发布")
            latest = git("rev-parse", "HEAD")
            (root / "validate.sh").write_text('set -euo pipefail\npython3() { printf "%s\\n" "$2" > revision.txt; }\n' + shell,
                encoding="utf-8", newline="\n")
            def selected(sha, ref_type="branch", before=legacy):
                subprocess.run([bash, "validate.sh"], cwd=root, check=True, capture_output=True,
                    env={**os.environ, "EVENT_NAME": "push", "REF_TYPE": ref_type, "BEFORE": before, "SHA": sha, "BASE_REF": ""})
                return read_records((root / "revision.txt").read_text().strip(), root)
            self.assertEqual([(good, "fix: 修复当前功能")], selected(good))
            self.assertEqual([(invalid, "新增但没有前缀")], invalid_records(selected(latest)))
            self.assertEqual([(invalid, "新增但没有前缀")], invalid_records(selected(latest, "branch", good)))


if __name__ == "__main__":
    unittest.main()
