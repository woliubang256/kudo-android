"""Extract App changes and publish a versioned, AI-prepared Chinese Release body."""

import argparse
import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
import re
import subprocess

from release_version import parse_tag


GROUPS = ("新增", "修复", "调整")
ALLOWED_TYPES = {"feat": "新增", "fix": "修复", "perf": "调整"}
IGNORED_TYPES = {"build", "chore", "ci", "doc", "docs", "refactor", "style", "test"}
CONVENTIONAL_TYPES = r"feat|fix|perf|refactor|style|build|ci|doc|docs|chore|test"


@dataclass(frozen=True)
class ReleaseCommit:
    subject: str
    paths: tuple[str, ...] = ()
    sha: str = ""


def git(*arguments: str, cwd: Path | None = None) -> str:
    result = subprocess.run(["git", *arguments], cwd=cwd, capture_output=True, encoding="utf-8", check=False)
    if result.returncode:
        raise ValueError("无法读取发布提交，请确认Tag存在并已获取完整Git历史。")
    return result.stdout.strip()


def previous_tag(tag: str, cwd: Path | None = None, published: str | None = None, *, from_head: bool = False) -> str | None:
    current = parse_tag(tag)
    target = "HEAD" if from_head else f"refs/tags/{tag}"
    git("rev-parse", "--verify", f"{target}^{{commit}}", cwd=cwd)
    merged = git("tag", "--merged", target, cwd=cwd).splitlines()
    if published is not None:
        if not published:
            return None
        if parse_tag(published).code >= current.code or published not in merged:
            raise ValueError("已发布基线必须是版本较小的祖先Tag。")
        return published
    candidates = []
    for candidate in merged:
        try:
            version = parse_tag(candidate)
        except ValueError:
            continue
        if version.code < current.code:
            candidates.append((version.code, candidate))
    return max(candidates)[1] if candidates else None


def classify(subject: str) -> tuple[str, str]:
    subject = " ".join(subject.split())
    conventional = re.match(rf"^({CONVENTIONAL_TYPES})(?:\([^)]*\))?!?:\s*(.+)$", subject, re.I)
    if conventional:
        kind, title = conventional.groups()
        return ALLOWED_TYPES.get(kind.lower(), ""), title.strip()
    if re.match(r"^(修复|解决|纠正|修正)", subject):
        return "修复", subject
    if re.match(r"^(新增|添加|支持|实现|接入)", subject):
        return "新增", subject
    if re.match(r"^(调整|优化|改进|完善|精简|收紧|上移|移除|更换|统一)", subject):
        return "调整", subject
    return "调整", subject


def is_app_path(path: str) -> bool:
    normalized = path.replace("\\", "/")
    # native/doh is compiled into the APK's jniLibs, so its sources and Cargo manifests ship at runtime.
    return normalized.startswith(("app/src/main/", "native/doh/")) and "/licenses/" not in normalized


def is_app_commit(commit: ReleaseCommit) -> bool:
    subject = " ".join(commit.subject.split())
    conventional = re.match(rf"^({CONVENTIONAL_TYPES})(?:\([^)]*\))?!?:\s*.+$", subject, re.I)
    if conventional and conventional.group(1).lower() in IGNORED_TYPES:
        return False
    return any(is_app_path(path) for path in commit.paths)


def selected_changes(subjects: list[str | ReleaseCommit]) -> list[tuple[str, str, ReleaseCommit]]:
    changes = []
    for item in subjects:
        commit = item if isinstance(item, ReleaseCommit) else ReleaseCommit(item)
        subject = commit.subject
        if not subject.strip() or subject.startswith(("Merge ", "合并分支")):
            continue
        if not is_app_commit(commit):
            continue
        group, title = classify(subject)
        if group:
            changes.append((group, title, commit))
    return changes


def render_notes(tag: str, subjects: list[str | ReleaseCommit], repository: str, previous: str | None) -> str:
    version = parse_tag(tag)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]*/[A-Za-z0-9_][A-Za-z0-9_.-]*", repository):
        raise ValueError("仓库需使用owner/repository格式。")
    groups: dict[str, list[str]] = {name: [] for name in GROUPS}
    seen = set()
    for group, title, _ in selected_changes(subjects):
        key = title.rstrip("。.").casefold()
        if key and key not in seen:
            groups[group].append(title)
            seen.add(key)
    lines = [f"# 酷DO {version.name}", ""]
    for group, titles in groups.items():
        if titles:
            lines.extend([f"## {group}", "", *(f"- {title}" for title in titles), ""])
    if not seen:
        lines.extend(["本版本暂无 App 功能变更。", ""])
    base = f"https://github.com/{repository}"
    link = f"{base}/compare/{previous}...{tag}" if previous else f"{base}/commits/{tag}"
    lines.extend([f"[完整变更]({link})", ""])
    return "\n".join(lines)


def release_source(tag: str, cwd: Path | None, previous: str | None, *, from_head: bool = False) -> tuple[str | None, list[ReleaseCommit]]:
    previous = previous_tag(tag, cwd, published=previous, from_head=from_head)
    target = "HEAD" if from_head else f"refs/tags/{tag}"
    revision = f"refs/tags/{previous}..{target}" if previous else target
    records = git("log", "--no-merges", "--reverse", "--format=%H%x00%s", revision, cwd=cwd).splitlines()
    commits = []
    for record in records:
        commit_hash, subject = record.split("\x00", 1)
        paths = git("diff-tree", "--root", "--no-commit-id", "--name-only", "-r", commit_hash, cwd=cwd).splitlines()
        commits.append(ReleaseCommit(subject, tuple(paths), commit_hash))
    return previous, commits


def generate_notes(tag: str, repository: str, cwd: Path | None = None, previous: str | None = None) -> str:
    previous, commits = release_source(tag, cwd, previous)
    return render_notes(tag, commits, repository, previous)


def source_metadata(tag: str, previous: str | None, commits: list[ReleaseCommit]) -> dict[str, str | None]:
    changes = [{"sha": commit.sha, "subject": commit.subject, "paths": sorted(path for path in commit.paths if is_app_path(path))}
               for _, _, commit in selected_changes(commits)]
    source = json.dumps(changes, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return {"tag": tag, "previous_tag": previous, "source_sha256": hashlib.sha256(source).hexdigest()}


def prepare_notes(tag: str, previous: str | None, commits: list[ReleaseCommit], raw: str) -> str:
    metadata = json.dumps(source_metadata(tag, previous, commits), ensure_ascii=False, sort_keys=True)
    return f"<!-- kudo-release-notes: {metadata} -->\n{raw}"


def use_prepared_notes(path: Path, tag: str, previous: str | None, commits: list[ReleaseCommit], raw: str) -> str:
    if not path.exists():
        return raw
    header, separator, body = path.read_text(encoding="utf-8").partition("\n")
    match = re.fullmatch(r"<!-- kudo-release-notes: (.+) -->", header)
    if not separator or not match:
        raise ValueError("已整理日志缺少来源元数据，请通过 --prepare 重新提取后整理。")
    try:
        metadata = json.loads(match.group(1))
    except json.JSONDecodeError as error:
        raise ValueError("已整理日志的来源元数据不是有效JSON。") from error
    if metadata != source_metadata(tag, previous, commits):
        raise ValueError("已整理日志已过期：目标版本、发布基线或App提交发生变化，请重新提取并整理。")
    body = body.strip() + "\n"
    lines, original = body.splitlines(), raw.splitlines()
    if not lines or lines[0] != original[0] or lines[-1] != original[-1]:
        raise ValueError("已整理日志的版本标题或完整变更链接不匹配。")
    if not selected_changes(commits):
        if body != raw:
            raise ValueError("没有App变化时不能增加更新条目。")
        return body
    headings, titles = set(), set()
    current, count = None, 0
    for line in lines[1:-1]:
        if not line.strip():
            continue
        if line in (f"## {group}" for group in GROUPS):
            if line in headings or (current is not None and count == 0):
                raise ValueError("已整理日志不能包含重复或空的分类。")
            headings.add(line)
            current, count = line, 0
        elif current is not None and line.startswith("- ") and line[2:].strip():
            title = line[2:].strip().rstrip("。.").casefold()
            if title in titles:
                raise ValueError("已整理日志仍含重复条目，请合并同一功能。")
            titles.add(title)
            count += 1
        else:
            raise ValueError("已整理日志只允许新增、修复、调整分类及非空的单行列表。")
    if not titles or count == 0:
        raise ValueError("已整理日志不能遗漏全部App变化或留下空分类。")
    return body


def published_baseline(pages: object, tag: str) -> str:
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ValueError("GitHub发布列表必须是分页JSON数组。")
    candidates = []
    for page in pages:
        for release in page:
            if not isinstance(release, dict) or not isinstance(release.get("draft"), bool) or not isinstance(release.get("prerelease"), bool):
                raise ValueError("GitHub发布记录格式不正确。")
            if release["draft"] or release["prerelease"] or release.get("tag_name") == tag:
                continue
            if not isinstance(release.get("tag_name"), str) or not isinstance(release.get("published_at"), str):
                raise ValueError("GitHub已公开版本缺少Tag或发布时间。")
            candidates.append(release)
    return max(candidates, key=lambda release: release["published_at"])["tag_name"] if candidates else ""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository", default="woliubang256/kudo-android")
    baseline = parser.add_mutually_exclusive_group()
    baseline.add_argument("--previous-tag", help="Previous published release tag; an empty value includes all history")
    baseline.add_argument("--published-releases", type=Path, help="JSON page array returned by gh api --paginate --slurp")
    parser.add_argument("--prepare", action="store_true", help="Create a source-stamped draft from HEAD before tagging, for AI editing")
    parser.add_argument("--from-head", action="store_true", help="Preview the next release using HEAD before creating its tag")
    parser.add_argument("--prepared-notes-dir", type=Path, help="Prefer <directory>/<tag>.md when its App source is current")
    parser.add_argument("--raw-output", type=Path, help="Also retain the original App commit notes for review")
    parser.add_argument("--output", type=Path, required=True)
    arguments = parser.parse_args()
    try:
        previous = arguments.previous_tag
        if arguments.published_releases is not None:
            previous = published_baseline(json.loads(arguments.published_releases.read_text(encoding="utf-8")), arguments.tag)
        previous, commits = release_source(arguments.tag, None, previous, from_head=arguments.prepare or arguments.from_head)
        raw = render_notes(arguments.tag, commits, arguments.repository, previous)
        if arguments.prepare and arguments.output.exists():
            raise ValueError("准备日志的输出文件已存在；请另选草稿路径，避免覆盖已整理内容。")
        prepared_path = arguments.prepared_notes_dir / f"{arguments.tag}.md" if arguments.prepared_notes_dir is not None else None
        if prepared_path is not None and any(path is not None and path.resolve() == prepared_path.resolve()
                                             for path in (arguments.output, arguments.raw_output)):
            raise ValueError("最终日志和原始候选不能覆盖仓库中的已整理文件。")
        if arguments.raw_output is not None:
            if arguments.raw_output.resolve() == arguments.output.resolve():
                raise ValueError("原始候选和最终日志必须使用不同的输出路径。")
            arguments.raw_output.parent.mkdir(parents=True, exist_ok=True)
            arguments.raw_output.write_text(raw, encoding="utf-8", newline="\n")
        body = raw
        if arguments.prepare:
            body = prepare_notes(arguments.tag, previous, commits, raw)
        elif prepared_path is not None:
            body = use_prepared_notes(prepared_path, arguments.tag, previous, commits, raw)
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(body, encoding="utf-8", newline="\n")
    except (ValueError, OSError) as error:
        parser.exit(1, f"{error}\n")


if __name__ == "__main__":
    main()
