#!/usr/bin/env python3
"""Prepare Version Update 的核心脚本。

由 .github/workflows/prepare-release.yml 调用；也可以本地模拟：
  python .github/scripts/prepare-version-update.py

读取的环境变量（workflow 通过 INPUT_* 传入）：
  INPUT_VERSION_NAME          新 versionName，必填，格式 x.y.z
  INPUT_VERSION_CODE          versionCode，留空 = 当前 + 1
  INPUT_CHANGELOG_MODE        auto | auto+extra | manual（默认 auto）
  INPUT_CHANGELOG_EXTRA       补充/手写的更新内容（多行）
  INPUT_RELEASE_NOTES         manifest 一句话简介（留空自动取第一条要点）
  INPUT_TARGET_BRANCH         提交目标分支（留空 = 当前分支；必须与当前分支一致）
  INPUT_ALLOW_MAIN_PUSH       true/false（默认 false；main 需要显式授权）
  INPUT_DRY_RUN               true/false（默认 false；只预览不写文件）
  INPUT_TRIGGER_RELEASE_AFTER true/false（仅用于 workflow 后续步骤，脚本只透传输出）

仓库安全红线：
  - main 默认禁止直推，必须显式 ALLOW_MAIN_PUSH=true
  - 只修改版本相关文件，不碰 openspec/、build/ 生成物
"""

import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE_FILE = ROOT / "composeApp" / "build.gradle.kts"
MANIFEST_FILE = ROOT / "update" / "manifest.json"
CHANGELOG_DIR = ROOT / "update" / "changelogs"

VERSION_NAME_PATTERN = re.compile(r"^\d+\.\d+\.\d+$")
SECTION_ORDER = ["新增", "修复", "更改", "其他"]


def fail(message: str) -> None:
    print(f"::error::{message}", file=sys.stderr)
    sys.exit(1)


def git(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["git", *args],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=check,
    )


def env(name: str, default: str = "") -> str:
    return os.environ.get(name, default).strip()


def env_bool(name: str) -> bool:
    return env(name, "false").lower() == "true"


def current_branch() -> str:
    result = git("branch", "--show-current")
    if result.returncode != 0:
        fail("无法确定当前分支：git branch --show-current 失败")
    branch = result.stdout.strip()
    if not branch:
        fail("当前不在任何分支上（可能是 detached HEAD），无法继续")
    return branch


def read_versions() -> tuple[int, str]:
    text = GRADLE_FILE.read_text(encoding="utf-8")
    code_match = re.search(r"val\s+yamiboAppVersionCode\s*=\s*(\d+)", text)
    name_match = re.search(r'val\s+yamiboAppVersionName\s*=\s*"([^"]+)"', text)
    if not code_match or not name_match:
        fail(f"无法在 {GRADLE_FILE} 中解析 yamiboAppVersionCode/Name")
    return int(code_match.group(1)), name_match.group(1)


def read_manifest() -> dict:
    return json.loads(MANIFEST_FILE.read_text(encoding="utf-8"))


def remote_tag_exists(version_code: int) -> bool:
    result = subprocess.run(
        ["git", "ls-remote", "--tags", "origin", f"refs/tags/{version_code}"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        fail(f"无法查询远端 tag {version_code}：{result.stderr.strip() or 'git ls-remote 失败'}")
    return bool(result.stdout.strip())


def working_tree_clean() -> bool:
    result = git("status", "--porcelain")
    return result.stdout.strip() == ""


def resolve_target_branch() -> str:
    configured = env("INPUT_TARGET_BRANCH")
    branch = configured or env("GITHUB_REF_NAME") or current_branch()
    if not branch:
        fail("无法确定目标分支，请填写 target_branch")
    return branch


def classify(subject: str) -> str:
    match = re.match(r"^([A-Za-z]+)(\([^)]*\))?!?:\s*(.*)$", subject)
    if match:
        kind = match.group(1).lower()
        text = match.group(3).strip()
    else:
        kind = ""
        text = subject.strip()
    if not text:
        text = subject.strip()
    section = {
        "feat": "新增",
        "feature": "新增",
        "fix": "修复",
        "revert": "修复",
        "perf": "更改",
        "refactor": "更改",
    }.get(kind, "其他")
    if kind in ("release", "version"):
        return None
    return section, text


def collect_changelog() -> dict[str, list[str]]:
    """从未发布的提交中整理 changelog。

    基准 = 当前分支最近一条 `release: 版本更新到 ...` 提交；
    找不到时回退为最近 40 条非 merge 提交。
    """
    base_result = subprocess.run(
        ["git", "log", "-1", "--format=%H", "--grep=^release: 版本更新到", "HEAD"],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    base = base_result.stdout.strip()
    if base:
        commits = git("log", f"{base}..HEAD", "--no-merges", "--format=%s").stdout
    else:
        commits = git("log", "-n", "40", "--no-merges", "--format=%s", "HEAD").stdout

    sections: dict[str, list[str]] = {}
    for line in commits.splitlines():
        subject = line.strip()
        if not subject:
            continue
        classified = classify(subject)
        if classified is None:
            continue
        section, text = classified
        sections.setdefault(section, [])
        if text not in sections[section]:
            sections[section].append(text)
    return sections


def render_changelog(version_name: str, sections: dict[str, list[str]]) -> str:
    lines = [f"# stable-{version_name}", ""]
    for section in SECTION_ORDER:
        items = sections.get(section)
        if not items:
            continue
        lines.append(f"{section} :")
        lines.extend(f"- {item}" for item in items)
        lines.append("")
    return "\n".join(lines).rstrip() + "\n"


def first_bullet(sections: dict[str, list[str]]) -> str:
    for section in SECTION_ORDER:
        items = sections.get(section)
        if items:
            return items[0]
    return ""


def build_changelog(version_name: str, mode: str, extra: str) -> tuple[str, str]:
    extra = extra.strip()
    if mode == "manual":
        if not extra:
            fail("changelog_mode=manual 时必须填写 changelog_extra")
        body = f"# stable-{version_name}\n\n{extra.strip()}\n"
        default_notes = extra.splitlines()[0].lstrip("- ").strip()
        return body, default_notes

    sections = collect_changelog()
    if extra:
        for line in extra.splitlines():
            item = line.strip().lstrip("- ").strip()
            if item:
                sections.setdefault("其他", [])
                if item not in sections["其他"]:
                    sections["其他"].append(item)

    if not any(sections.values()) and mode == "auto":
        fail(
            "auto 模式没有找到未发布的提交；请先提交代码，"
            "或改用 changelog_mode=auto+extra / manual 并填写 changelog_extra"
        )
    body = render_changelog(version_name, sections)
    return body, first_bullet(sections)


def already_prepared(version_code: int, version_name: str) -> bool:
    try:
        current_code, current_name = read_versions()
        manifest = read_manifest()
        changelog = CHANGELOG_DIR / f"{version_code}.changelog"
        return (
            current_code == version_code
            and current_name == version_name
            and manifest.get("versionCode") == version_code
            and manifest.get("versionName") == version_name
            and changelog.exists()
            and changelog.read_text(encoding="utf-8").strip() != ""
        )
    except Exception:
        return False


def write_github_output(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as out:
            out.write(f"{key}={value}\n")


def write_github_env(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_ENV")
    if path:
        with open(path, "a", encoding="utf-8") as out:
            out.write(f"{key}={value}\n")


def main() -> None:
    version_name = env("INPUT_VERSION_NAME")
    version_code_raw = env("INPUT_VERSION_CODE")
    mode = env("INPUT_CHANGELOG_MODE", "auto")
    extra = env("INPUT_CHANGELOG_EXTRA")
    release_notes = env("INPUT_RELEASE_NOTES")
    dry_run = env_bool("INPUT_DRY_RUN")
    allow_main_push = env_bool("INPUT_ALLOW_MAIN_PUSH")

    if mode not in ("auto", "auto+extra", "manual"):
        fail(f"changelog_mode 只能是 auto / auto+extra / manual，当前为 {mode}")
    if not VERSION_NAME_PATTERN.match(version_name):
        fail(f"versionName 格式必须是 x.y.z（例如 0.2.6），当前为 {version_name}")

    current_code, current_name = read_versions()
    try:
        version_code = int(version_code_raw) if version_code_raw else current_code + 1
    except ValueError:
        fail(f"versionCode 必须是整数，当前输入 {version_code_raw}")

    if already_prepared(version_code, version_name):
        target_branch = resolve_target_branch()
        print(
            f"已准备过：{version_name} (versionCode {version_code}) 的版本文件与 changelog 均已就绪，"
            "无需重复准备，可直接运行 Release Android APK。"
        )
        write_github_output("version_code", str(version_code))
        write_github_output("version_name", version_name)
        write_github_output("target_branch", target_branch)
        write_github_output("already_prepared", "true")
        return

    if version_code <= current_code:
        fail(f"versionCode 必须大于当前值 {current_code}，当前输入 {version_code}")

    target_branch = resolve_target_branch()
    actual_branch = current_branch()
    if target_branch != actual_branch:
        fail(
            f"target_branch({target_branch}) 必须与 workflow 当前分支({actual_branch}) 一致；"
            "如需其他分支，请先在该分支上运行本 workflow"
        )
    if target_branch == "main" and not allow_main_push:
        fail("main 分支默认禁止直推；如确需直接准备并推送 main，请显式勾选 allow_main_push")

    changelog_file = CHANGELOG_DIR / f"{version_code}.changelog"
    if changelog_file.exists():
        fail(f"{changelog_file} 已存在但版本文件尚未更新到该版本；请人工确认后处理，脚本不会覆盖")

    if remote_tag_exists(version_code):
        fail(f"远端 tag {version_code} 已存在；请换更大的 versionCode")

    if not dry_run and not working_tree_clean():
        fail("工作区不干净；workflow 检出的仓库应为干净状态，请检查是否有并发运行")

    manifest = read_manifest()
    if manifest.get("isReady") is not False:
        fail("源 manifest 的 isReady 必须为 false")
    if manifest.get("assets"):
        fail("源 manifest 的 assets 必须为空")
    if "releaseUrl" in manifest:
        fail("源 manifest 不得包含 releaseUrl")

    changelog_body, default_notes = build_changelog(version_name, mode, extra)
    final_release_notes = (release_notes or default_notes).strip()
    if not final_release_notes:
        fail("releaseNotes 为空；请填写 INPUT_RELEASE_NOTES 或提供更新内容")

    gradle_text = GRADLE_FILE.read_text(encoding="utf-8")
    new_gradle_text = re.sub(
        r"(val\s+yamiboAppVersionCode\s*=\s*)\d+",
        rf"\g<1>{version_code}",
        gradle_text,
        count=1,
    )
    new_gradle_text = re.sub(
        r'(val\s+yamiboAppVersionName\s*=\s*)"[^"]*"',
        rf'\g<1>"{version_name}"',
        new_gradle_text,
        count=1,
    )
    if new_gradle_text == gradle_text:
        fail("未能定位并替换 gradle 版本号（文件格式可能已变化）")

    new_manifest = dict(manifest)
    new_manifest["versionName"] = version_name
    new_manifest["versionCode"] = version_code
    new_manifest["releaseNotes"] = final_release_notes
    manifest_text = json.dumps(new_manifest, ensure_ascii=False, indent=2) + "\n"

    print("=" * 72)
    if dry_run:
        print("[DRY RUN] 只预览：不写文件、不提交、不推送")
    else:
        print("准备写入版本文件")
    print(f"当前版本: {current_name} (versionCode {current_code})")
    print(f"目标版本: {version_name} (versionCode {version_code})")
    print(f"当前分支: {target_branch}")
    print(f"changelog 模式: {mode}")
    print(f"将提交信息: release: 版本更新到 {version_name} (versionCode {version_code})")
    print(f"releaseNotes: {final_release_notes}")
    print("-" * 72)
    print("changelog 预览:")
    print(changelog_body)
    print("-" * 72)
    print("将修改的文件:")
    print(f"  - {GRADLE_FILE.relative_to(ROOT)}")
    print(f"  - {MANIFEST_FILE.relative_to(ROOT)}")
    print(f"  - 新建 {changelog_file.relative_to(ROOT)}")
    print(f"  - update/stable.json 由后续 gradle syncStableManifest 生成（脚本不手改）")

    write_github_output("version_code", str(version_code))
    write_github_output("version_name", version_name)
    write_github_output("target_branch", target_branch)
    write_github_output("already_prepared", "false")

    if dry_run:
        print("\n[DRY RUN] 完成，未产生任何改动。")
        return

    GRADLE_FILE.write_text(new_gradle_text, encoding="utf-8")
    MANIFEST_FILE.write_text(manifest_text, encoding="utf-8")
    CHANGELOG_DIR.mkdir(parents=True, exist_ok=True)
    changelog_file.write_text(changelog_body, encoding="utf-8")
    write_github_env("VERSION_CODE", str(version_code))
    write_github_env("VERSION_NAME", version_name)
    write_github_env("TARGET_BRANCH", target_branch)
    print("\n版本文件已写入；后续步骤将执行 gradle 校验并提交推送。")


if __name__ == "__main__":
    main()
