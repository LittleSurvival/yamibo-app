# 给 AI 的 App 版本更新操作文档

> 当你（AI）收到这份文档时，按下面的流程帮用户完成一次「版本更新 + 提交推送」。
> 执行前先通读全文；遇到需要用户决定的地方用提问确认，不要擅自假设。

## 一、总览：你要做的事

1. 读取当前版本号并告知用户。
2. 询问用户要改成什么版本号。
3. 收集并整理本次更新内容（changelog），自动填入对应文件。
4. 修改版本号相关文件。
5. 生成 `stable.json` 并校验。
6. 提交并推送到当前分支。

## 二、版本号定义位置（只在这一处）

读取 `composeApp/build.gradle.kts`：

```kotlin
val yamiboAppVersionCode = 7       // 数字，只能递增，客户端用它比较新旧
val yamiboAppVersionName = "0.0.6" // 显示给用户的版本名
```

## 三、执行流程（逐步）

### 第 1 步：报告当前版本

读取上面的 `yamiboAppVersionCode` 和 `yamiboAppVersionName`，告诉用户：

> 当前版本是 versionCode=7，versionName=0.0.6。

### 第 2 步：询问新版本号

向用户确认两个值，并给出建议：

- 新的 `versionName`（例如 `0.0.7`）
- 新的 `versionCode`（建议 = 当前值 + 1，即 `8`）

等用户确认后再继续。

### 第 3 步：收集更新内容并整理成 changelog

询问用户本次更新的内容（可一句话描述，也可分点粘贴；或让用户说「根据 git log 自动生成」，此时用
`git log` 从当前分支未发布的提交中提取要点）。

把内容整理成下面的 **简体中文** 格式，写入新文件
`update/changelogs/{新 versionCode}.changelog`：

```markdown
# stable-{新 versionName}

新增 :
- ...
修复 :
- ...
更改 :
- ...
其他 :
- ...

将来预计的更新 :
- ...
```

规则：

- 标题固定为 `# stable-{versionName}`。
- 没有内容的分类直接省略，不要留空项。
- 保持简体中文，与项目现有 changelog 风格一致。

### 第 4 步：修改文件

共改 3 个文件、新建 1 个：

1. `composeApp/build.gradle.kts`
   - `yamiboAppVersionCode` → 新 code
   - `yamiboAppVersionName` → 新 name

2. `update/manifest.json`
   - `versionCode` → 新 code
   - `versionName` → 新 name
   - `releaseNotes` → 本次更新的一句话简介
   - 必须保持：`isReady: false`、`assets: []`
   - 不得出现 `releaseUrl` 字段

3. 新建 `update/changelogs/{新 versionCode}.changelog`（第 3 步整理的内容）

4. `update/stable.json` —— **不要手改**，由下一步命令自动从 `manifest.json` 生成。

### 第 5 步：生成 stable.json 并校验

```powershell
.\gradlew syncStableManifest validateUpdateManifest --console=plain
```

必须 `BUILD SUCCESSFUL`。若失败，按报错修正，常见原因见「四、校验规则」。

### 第 6 步：提交并推送

1. 先跑身份校验（仓库规范，必须通过才能 commit）：

```
python .github/scripts/validate_git_name.py
```

2. 提交（只提交本次版本相关改动）：

```
git add composeApp/build.gradle.kts update/manifest.json update/stable.json update/changelogs/{新 versionCode}.changelog
git commit -m "release: 版本更新到 {versionName} (versionCode {versionCode})"
```

3. 推送：

```
git push origin HEAD
```

### 第 7 步：完成后告知用户

报告：

- 改动后的版本号（versionCode / versionName）
- 改了哪些文件
- 校验结果（BUILD SUCCESSFUL / FAILED）

并提醒：**发布 APK 需用户手动触发** —— 到 GitHub Actions 页面选择 `Release Android APK` →
`Run workflow` → 选对应分支。AI 不自动触发发布（除非用户额外提供了可用的触发凭据）。

## 四、校验规则（来自 `ValidateUpdateManifestTask`）

`validateUpdateManifest` 会失败，除非同时满足：

1. `composeApp/build.gradle.kts` 的 versionCode == `update/manifest.json` 的 versionCode。
2. `composeApp/build.gradle.kts` 的 versionName == `update/manifest.json` 的 versionName。
3. `update/manifest.json` 与 `update/stable.json` 内容一致（由 `syncStableManifest` 保证）。
4. `update/manifest.json` 的 `isReady == false`。
5. `update/manifest.json` 不含 `releaseUrl` 字段。
6. `update/manifest.json` 的 `assets == []`。
7. `update/changelogs/{versionCode}.changelog` 存在且非空。

## 五、注意事项

- 只改上述与版本相关的文件，不要顺手动其它无关代码。
- `versionCode` 只能递增，不能小于或等于上一次发布的值。
- 每次 commit 前必须运行 `python .github/scripts/validate_git_name.py`，且只在它通过后 commit。
- 若同一 `versionCode` 的 tag / Release 已存在（例如上次发布失败残留），先提示用户处理或改用更大的 versionCode。
