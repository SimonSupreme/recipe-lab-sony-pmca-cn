# Recipe Lab — agent rules

A PlayMemories (PMCA) camera app: 96 film-look recipes (77 built-in + 19 custom generated from recipes.json)
written straight into the camera's settings store. Native lib (ndk-build, NDK r16b) + Java, no Gradle.
Upstream targets the Sony A6000; this CN fork is used on a **Sony A7R II (installed via PMCA-RE)**.

Read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) and [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) before changing anything.
The rules below are the ones that break things when ignored.

## 本 fork 的工作规则（Simon 定，2026-09）

These rules were given by the fork's owner during development; they apply to every change in this repo.

### 安全底线（需求排序：安全 > 可靠 > 不卡 > 不影响相机原有功能）

- 装上 app 后，**相机原生功能不能变差**。
- 任何新增代码失败时的最坏结果必须是**降级为当前版本的行为**（丢功能、给提示），绝不允许崩溃循环，
  更不允许影响相机本身。唯一的持久写入路径是设置存储，且**不得扩大写入面**（不新增槽位 ID、不新增
  native 调用、`jni/` 不动）。

### 版本与兼容红线（不许「顺手升级」）

- 相机运行时是内嵌 **Android 2.3.7（API 10）**：`minSdkVersion` / `targetSdkVersion` 保持 10，不改。
- 工具链原样不动：NDK r16b / GCC 4.9 / armeabi / build-tools 30.0.3 / 仅 v1 签名（相机不认 v2/v3）。
- `versionCode` / `versionName` 只归 semantic-release；**不引入任何第三方库**。
- 新代码只用与现有代码同代的 Java API 与语法：相机 `java.*` 是 Java 6 子集，`--release 8` 编译通过
  ≠ 运行时可用。无 lambda、无 diamond、无 try-with-resources。

### 界面与提示规则

- 界面一律中文；改了用户可见字符串必须**同步 test/ 的期望**（1.3.1-cn 曾因漏改导致 59 个测试全红）。
- **flashSuggest 永远只是拍摄建议提示**：不写死、不锁定、不控制闪光灯硬件——本 app 没有也不得有
  闪光灯写入路径；用户始终可以手动开关闪光灯。
- 提示不遮挡拍摄取景：pill / hidden（拍摄态）**零新增元素**；full panel 内优先复用现有行；
  tip 文案 ≤ 30 字。

### 既定产品方向（勿反复）

- 自定义配方与内置表**同表编译**：仓库根的 `recipes.json` 是人类编辑真源（带名字字段、防呆），
  `tools/gen-recipes.py` 生成 Recipes.java 的「自定义」区块，一致性测试逐字段把关。**没有运行时导入**
  （冷启动 IO 偶发失败曾致分组消失，2026-10-06 移除该路径）；改配方 = 改 JSON → 跑脚本 → 测试 → 重刷。
  JPG/RAW 切换在**应用时覆盖 dro**，不批量改写。Phase 2 机上编辑到来时再启用 prefs 用户层与
  install() 机制（已休眠保留并有测试）。

### 流程

- **commit 前必须把改动摆给 Simon 过目，确认后才提交**；push / 开 PR 一律等他明确指示。
- 相机侧操作（PMCA-RE 装机、OpenMemories-Tweak、试机验证）由 Claude 直接驱动，Simon 只做物理动作
  （插线、按键、断电重启）。
- 实机验证按仓库规则执行：写入后必须**断电重启确认仍在**。A7R II 验证过的是运行 + 中文渲染；
  A7M2 上的写入尚无记录，首次上机走试机协议。

## Branching

- **There is one long-lived branch: `main`.** Work branches are cut from it and squash-merged back.
  `main` is not "releases only" — it carries unreleased work between releases, and a release is a
  tag on it, not a branch of its own. Do not commit to it directly; go through a PR.
- Branch off `main`: `feat/<issue>-<slug>` or `feat/<slug>`, likewise `fix/`, and also
  `docs/ refactor/ chore/ build/ ci/ perf/ test/`.
- A GitHub issue is not required. If one exists, put its number in the branch name; otherwise drop the
  number and use `<type>/<slug>`. Never open an issue just to have one, and never invent a number.
- **Never open a PR unless asked.** Finish the change, commit, and stop there — pushing the branch
  and opening the PR is the user's call. `/commit-and-pr` is that ask; so is "open a PR".
- **Never merge a PR yourself.** Open it and leave the merge to a human.
- **Rebase onto `main` before asking for a merge.** A PR whose base has moved on should have its
  checks run against the tip. `git fetch origin && git rebase origin/main`, then
  `git push --force-with-lease`.
- There is no `release/*` branch and no `hotfix/*` branch. A hotfix is an ordinary `fix/` branch
  PR'd into `main`, released by running **create-release** when it lands.
- Only semantic-release writes to `main` without a PR: the tag, and the `chore(release): X.Y.Z
  [skip ci]` manifest bump. That is why `main` carries no required status checks — a release commit
  is created with `[skip ci]`, so no check could ever pass for it.

## Commits

```
type(scope): subject

Closes #123
```

- **A commit references its issue when there is one**, in a footer: `Closes #N` when it finishes the
  issue, `Refs #N` when it is one step of several. The number comes from the branch name. No issue → no
  footer; do not guess a number.

- Types: `feat fix docs refactor perf test build ci chore revert`.
- Scopes: `ui input browser recipes tools build ci docs deps release`. Optional.
- **Do not write the issue number in the subject.** It goes in the `Closes #N` footer and the branch name;
  GitHub appends the PR number to the subject itself on squash merge.
- Subject ≤ 72 chars, imperative, no trailing period.
- **The PR title decides the release.** A squash merge leaves only the title, so it is what semantic-release
  reads: `fix:` → patch, `feat:` → minor, `!` → major, `chore:`/`docs:` → no release at all.

## Versions

- `AndroidManifest.xml` `android:versionName` is the **only** version in the tree, and holds the *next target
  release* (`X.Y.Z`, never a `-dev` suffix).
- **Never hand-edit a version, and never pick one.** semantic-release derives it from the commits and calls
  `tools/bump-version.sh` itself.
- Never add a version string to `README.md`, `MainActivity.java` or anywhere else —
  `tools/check-version.sh` fails the build if one reappears.
- `versionCode = MAJOR*10_000_000 + MINOR*100_000 + PATCH*1_000 + P`, `P=999` for a release, `P=N` for a
  dev build. A build derives this itself; it never rewrites the checked-in manifest.

## Never commit

- APKs (`dist/` no longer holds one — releases carry the binaries)
- keystores, or anything decoded from `ANDROID_KEYSTORE_B64`
- `out/`, `jni/platform/errno.h.updater_only`

## Building

- Windows: `build.cmd`. Linux/WSL/CI: `./build.sh` (needs `ANDROID_NDK` pointing at **r16b** — later NDKs
  cannot build this target).
- Keep `build.cmd` and `build.sh` in step. A change to one needs the same change in the other.
- The `errno.h` park must stay reversible (`build.sh` does it from an `EXIT` trap). A build that leaves the
  submodule dirty is a bug.
- `./tools/test.sh` runs the unit tests: the `test` CI job, also run by `dev-build` and `create-release`; JDK 17 only, no SDK. Logic that needs no camera
  goes in `Params.java`, `Recipes.java` or `Favourites.java` **with a test**, never into `MainActivity`. All three are
  compiled there **without** `android.jar`, so an `android.*` import in any of them breaks the job.
- **New key bindings go on keys every body has** (wheel, four-way, centre, MENU, shutter, TRASH) — a hold of the centre
  button is the escape hatch. Fn, AEL, C1 and DISP are missing on several supported bodies (issue #18).

## What CI cannot check

Nothing about recipes, settings-store IDs, live preview or key handling can be validated by a build. The unit
tests prove which bytes and parameters the app *sends*, not what the camera *does* with them. Those changes
need a real A6000, exercised **and power-cycled** — a look that vanishes after a power cycle was never stored.
Say so plainly rather than implying a green build or a green `test` means the change works.

## Releasing

Run the **create-release** workflow (`gh workflow run create-release.yml`, or `-f dry_run=true` to preview).
It releases whatever is on `main` at that moment, so releasing is a decision about timing, not about
merging anything. Full runbook in [docs/RELEASING.md](docs/RELEASING.md).
