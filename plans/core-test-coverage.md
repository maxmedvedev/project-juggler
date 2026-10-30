# Cover the core module with tests

## Context

`core` has tests for sync, VM options generation, ids, paths, hashing and copying, but many classes have none:
`IdeInstallation`, model serialization, `IdeConfigRepository` happy paths, `IdeRegistry`, `IdeScopeManager`/`KoinInit`,
`FileLockUtils`, most of `ProjectManager`, `ProjectLauncher.launch`, `IntelliJLauncher.launch`, `ShutdownWaiter`,
`DirectoryManager`'s base-path resolution and first-open copy, `ProjectCleaner.cleanProject`, `Platform`, and all three
locators. `docs/core-spec.md` is the contract the tests check.

Decisions (with the user):

- **Cover only what no gap plan covers.** The gap plans in `plans/core-gap-*.md` already add tests for
  `ProjectLockUtils` (09), `IdeConfigRepository` corruption handling and `RecentProjectsIndex` (10), `PortAllocator` and
  JDWP-dependent port allocation (05), `launchMain` (06), vmoptions update mode and hash refresh (04),
  `ProcessLauncherImpl` (13), `GitWorktreeManager` and SHA-256 heads (11), copy semantics (12), main-instance
  detection (14), signal/lock cleanup and stop-request fields (01, 02), restart via launch (03). This plan writes no
  test that asserts behavior a gap plan changes. Concretely, do not assert: whether a debug port is allocated on
  launch (05), the vmoptions content after a relaunch (04), `isProjectOpen` results (09; mock it instead), what
  `IDEA_VM_OPTIONS` `launchMain` passes (06), corrupt-file handling (10), what `cleanup`/`cleanupStaleSignals` do with
  the lock file (01), what a forced config sync does to `.lock` (12), and `isRunningInMainInstance` (14).
- Gaps 7 (Linux locator layout) and 8 (Toolbox `ch-0` only, display-name fallback) have no plan and stay documented.
  The locator tests pin today's behavior there; name those tests so a later fix knows they encode the gap (e.g.
  `"linux config dir is looked up under .local/share (known gap 7)"`).
- **Kover, report only.** Add the Kover Gradle plugin to `core` for `:core:koverHtmlReport` / `:core:koverLog`; no
  `koverVerify` threshold.
- **Locators get internal seams** so tests build fake IDE trees in temp dirs for all three platforms.
- **Drop the migration code.** `MigrationManager` and `LegacyGlobalConfig` (v1 → v2) are never called; delete them
  instead of testing them.

## Target behavior

No user-visible change. Afterwards `./gradlew :core:test` covers every core class except `WindowFocuser` (untested by
decision, see `plans/core-gap-15-test-cleanup.md`) and the parts the gap plans own; `./gradlew :core:koverHtmlReport`
writes `core/build/reports/kover/html/index.html`.

## Implementation

### Drop migration (session 1)

- Delete `core/src/main/kotlin/com/projectjuggler/config/MigrationManager.kt` and `.../config/LegacyGlobalConfig.kt`.
- `plugin/src/main/kotlin/com/projectjuggler/plugin/services/IdeInstallationService.kt`: remove the
  `MigrationManager` import, the `init { // todo implement? ... }` block and `doMigrationFromV1toV2IfNeeded()`.
- Grep for leftovers (`Migration`, `LegacyGlobalConfig`, `v1.bak`) across `core`, `plugin`, `sync-helper`, docs.

### Kover (session 1)

- Root `build.gradle.kts` `plugins {}`: `id("org.jetbrains.kotlinx.kover") version "<v>" apply false`, matching how the
  Kotlin plugins are declared there. Use the newest 0.9.x from https://plugins.gradle.org/plugin/org.jetbrains.kotlinx.kover
  that works with Gradle 9.2.1 (`gradle/wrapper/gradle-wrapper.properties`); if it fails to apply, try the next newer
  release and note the version in the commit body.
- `core/build.gradle.kts` `plugins {}`: `id("org.jetbrains.kotlinx.kover")`. No extra configuration.
- Run `./gradlew :core:koverLog` once and write the baseline line coverage into this plan under "Coverage log" below.

### Locator seams (session 4)

Package `com.projectjuggler.locators`, all new declarations `internal`:

- New `locators/LocatorEnvironment.kt`:
  ```kotlin
  internal data class LocatorEnvironment(
      val platform: Platform,
      val userHome: Path,
      val fsRoot: Path,                  // replaces absolute "/Applications", "/opt", "/snap"
      val getenv: (String) -> String?,   // ProgramFiles, LOCALAPPDATA, APPDATA
  ) {
      companion object {
          fun current() = LocatorEnvironment(
              Platform.current(), Paths.get(System.getProperty("user.home")),
              Paths.get("/"), System::getenv,
          )
      }
  }
  ```
- `IntelliJLocator`: `findAllInstallations()` becomes `findAllInstallations(LocatorEnvironment.current())`; the new
  `internal fun findAllInstallations(env: LocatorEnvironment)` holds today's body. `prepareScanConfig(env)` builds
  paths from `env.userHome`, `env.fsRoot.resolve("Applications")`, `env.fsRoot.resolve("opt")`, and on Windows from
  `env.getenv("ProgramFiles") ?: "C:\\Program Files"` / `env.getenv("LOCALAPPDATA") ?: ...` as today.
  `scanLinuxSnapInstallations` takes `env.fsRoot.resolve("snap")`. `findInstallation(path)` gets the same
  `internal` overload with `env`. The private `extractDisplayName(ideDir, platform)` becomes `internal`. Public
  signatures stay unchanged (the plugin calls `findInstallation`, `findAllInstallations`, `extractDisplayName`).
- `ConfigLocator.findDefaultConfigDirectory()` → delegates to `internal fun findDefaultConfigDirectory(env)`; the
  three `get*Candidates` use `env.userHome` and `env.getenv("APPDATA")`.
- `PluginLocator.findDefaultPluginsDirectory()` → `internal fun findDefaultPluginsDirectory(env)` calling the
  `ConfigLocator` overload.
- `platform/Platform.kt`: extract `internal fun fromOsName(osName: String): Platform` from `current()`.

Keep the scanning logic otherwise unchanged; this is a seam, not a behavior change.

## Tests

Kotest `StringSpec` + MockK in `core/src/test/kotlin/com/projectjuggler/<package>/`. Temp dirs via
`com.projectjuggler.test.createTempDir`; `KoinTestExtension()` in every spec that calls `getInstance(...)`;
repositories as `IdeConfigRepository(tempDir, IdeInstallation("/test/ide", "Test IDE"))`. Mock a Koin singleton as
`LaunchProjectTest` does (`loadKoinModules(module { single<ProcessLauncher> { mock } })`). For `object`s use
`mockkObject` and `unmockkObject` in `afterTest`. Where a gap plan creates the same test file, the session that comes
second adds its cases to the existing file.

### Session 2: model, persistence, DI, `ProjectManager`

- New `config/IdeInstallationTest.kt`: `id` is the first 16 hex of `HashUtils.calculateStringHash(executablePath)`;
  `directoryName` for `"IntelliJ IDEA 2025.1 (Toolbox)"` is `"IntelliJ-IDEA-2025.1-Toolbox-<id.take(8)>"`; same path
  with different display names → same `id`; JSON round trip contains only `executablePath` and `displayName`.
- New `config/ModelSerializationTest.kt` (use a `Json { prettyPrint = true; encodeDefaults = false }` like the
  repository): `ProjectPath` serializes as a bare string; `ProjectMetadata` omits `openCount` when 0 and `debugPort`
  when null (§3.2); `IdeConfig.default(inst)` encodes only `installation`; decoding `{"installation": {...}}` gives
  all §3.1 defaults (`maxRecentProjects == 10`, flags false, paths null).
- `config/IdeConfigRepositoryTest.kt` (new, or extend if gap 10 created it): `load()` without a file returns
  `IdeConfig.default`; `save` then `load` round trip and `save` creates `baseDir`; `update` applies the transform;
  `saveProjectMetadata` writes `projects/<id>/metadata.json`; `loadProjectMetadata(path)` and `(id)` return it, null
  when missing; `loadAllProjects()` returns all saved, skips a `projects/<x>/` dir without `metadata.json`, empty
  when `projects/` is absent; `deleteProjectMetadata` removes only `metadata.json` (a sibling `config/` dir stays),
  no-op when missing; `IdeConfigRepository.create(inst, dir)` has `baseDir == dir.resolve(inst.directoryName)`;
  `loadFromDir` returns null without `config.json` and the saved installation with one.
- New `config/IdeRegistryTest.kt` (`IdeRegistry(tempBase)`, constructor is `internal`): `getRegisteredInstallations`
  is empty without `v2/`, lists installations saved via `getRepository(inst).save(IdeConfig.default(inst))`, ignores
  a `v2/<dir>` without `config.json`; `getRepository` creates nothing on disk; `getAllInstallations` merges
  `mockkObject(IntelliJLocator)`-stubbed detected installations with registered ones, deduplicated by
  `executablePath` (detected entry wins); `getDefaultBaseDir()` honors `System.setProperty(PROJECT_JUGGLER_BASE_DIR,
  ...)` and falls back to `user.home/.project-juggler` (save and restore the property).
- New `di/IdeScopeManagerTest.kt` (`KoinTestExtension()`): two repositories with the same installation get the same
  `ProjectManager` instance; different installations get different ones; after `closeScope(inst)` a new instance is
  created; `closeAllScopes` likewise.
- New `di/KoinInitTest.kt` (no `KoinTestExtension`; call `KoinInit.stop()` in `afterTest`): `init()` twice does not
  throw and `GlobalContext.get().get<IdeRegistry>()` works; `init(extraModule)` on a running Koin makes the extra
  binding resolvable; `stop()` leaves `GlobalContext.getOrNull()` null.
- New `util/FileLockUtilsTest.kt`: creates missing parent dirs and the file, returns the block's value, and the lock is
  released afterwards (a second `withFileLock` on the same file in the same thread succeeds).
- `core/ProjectManagerTest.kt` (extend): `registerOrUpdate` sets `openCount` 1 then 2 and preserves a saved
  `debugPort`; `listAll` sorts by `lastOpened` descending (save metadata with fixed timestamps); `ensureDebugPort`
  returns an existing port without saving and, when missing, saves the allocated port into metadata; `remove` deletes
  metadata only (project root dir still exists); `get` returns null for an unknown path.

Context estimate ~18%.

### Session 3: launch, shutdown wait, directories, cleaning

- New `process/ProjectLauncherLaunchTest.kt` (`KoinTestExtension()`; build `ProjectLauncher(repo, projectManager =
  ..., intellijLauncher = mockk(relaxed = true), ...)` through the default-argument constructor, keeping real
  `ProjectManager`/`RecentProjectsIndex`, a `mockk<BaseVMOptionsTracker>()`):
  - main project (`MainProjectService.setMainProject`): calls `intellijLauncher.launchMain(path.path, any())`, never
    `launch`, writes no metadata and no `recent.json`;
  - isolated: `registerOrUpdate` result is passed to `intellijLauncher.launch`, `getRecent(10).first().path` is the
    project, `openCount == 1`;
  - tracker `hasChanged()` true → `updateHash()` called once; false → not called;
  - `onFailure` passed through to both launcher calls (capture the lambda, assert same instance).
- New `process/IntelliJLauncherTest.kt` (or extend if gap 06 created it), mocked `ProcessLauncher` capturing all four
  arguments: `launch(project)` starts `Paths.get(installation.executablePath)` with `listOf(project.path.pathString)`
  and env `IDEA_VM_OPTIONS == <projRoot>/idea.vmoptions`; the file exists; `config/system/logs/plugins` exist.
  Don't assert the debug port or vmoptions content (gaps 04, 05).
- New `core/ShutdownWaiterTest.kt` (`mockkObject(ProjectLockUtils)` stubbing `isProjectOpen`): returns `Success`
  immediately when not open (no progress callback); `true andThen false` → `Success` after ~1 s; always true with
  `timeoutSeconds = 2` → `Timeout` and `onProgress` received `1` (keep timeouts ≤ 2 s).
- `core/DirectoryManagerTest.kt` (extend): `getBaseConfigPath`/`getBasePluginsPath` return the configured path, else
  `ConfigLocator`/`PluginLocator` result (`mockkObject`), else null; `ensureProjectDirectories` with a configured base
  config containing `.lock`, `recentProjects.xml`, `recentProjectDirectories.xml`, `options/recentProjects.xml`,
  `plugins/x.jar`, `options/other.xml` copies only `options/other.xml`; copies base plugins on first open and not when
  `plugins/` is non-empty; no base configured and locators mocked to null → directories created, nothing copied;
  `syncConfigFromBase`/`syncPluginsFromBase` throw `IllegalStateException` when no base is found.
- `core/ProjectCleanerTest.kt` (extend): `cleanProject` removes the id from `recent.json` and deletes `<projRoot>`,
  leaving other projects intact.

Context estimate ~18%.

### Session 4: locators and platform

- New `platform/PlatformTest.kt`: `fromOsName` for `"Mac OS X"`, `"Darwin"`, `"Windows 11"`, `"Linux"`, `"AIX"`,
  `"FreeBSD"` (throws `UnsupportedOperationException`).
- Replace the smoke tests in `platform/IntelliJLocatorTest.kt`, `ConfigLocatorTest.kt`, `PluginLocatorTest.kt` (they
  run against the real machine) with tests on `LocatorEnvironment(platform, home = temp/home, fsRoot = temp/root,
  getenv = mapOf(...)::get)`. Keep one smoke case per locator that calls the public function and only asserts it does
  not throw. A helper in the spec creates an executable file (`createFile` + `toFile().setExecutable(true)`).
  - `IntelliJLocatorTest`: macOS `root/Applications/IntelliJ IDEA.app/Contents/MacOS/idea` and
    `home/Applications/...` found with display name `"IntelliJ IDEA"`; `.app` not containing `IntelliJ` ignored;
    non-executable binary ignored; Linux `root/opt/intellij-idea-ultimate/bin/idea.sh` → `"IntelliJ IDEA Ultimate"`,
    `root/opt/idea` → `"IntelliJ IDEA"`, `root/opt/idea-IU-251` ignored (the filter needs `intellij` or exactly
    `idea`), direct executable `home/.local/bin/idea` → `"IntelliJ IDEA (User)"`, Snap ultimate/community;
    Windows via `ProgramFiles`/`LOCALAPPDATA` env → `bin/idea64.exe`; Toolbox `apps/IDEA-U/ch-0/251.1/` per platform →
    `"IntelliJ IDEA Ultimate 251.1 (Toolbox)"`; a Toolbox `ch-1` layout is not found (known gap 8); duplicates by
    executable path are collapsed; `findInstallation` returns the scanned match, else a synthesized installation named
    after the executable file (known gap 8).
  - `ConfigLocatorTest`: picks the lexicographically greatest `IntelliJIdea*` dir under the platform base
    (`IntelliJIdea2025.1` over `IntelliJIdea2024.3`; note `IntelliJIdea2025.10` < `IntelliJIdea2025.9` is the
    known gap 7 sort, pin it), ignores `IdeaIC*` (known gap 7), Linux base is `home/.local/share/JetBrains` (known
    gap 7), Windows uses `APPDATA`; null when the base is missing.
  - `PluginLocatorTest`: `plugins/` child of the chosen config dir, null when absent or a file.

Context estimate ~20%.

## Docs

- `docs/core-spec.md`: delete §3.4 and the `config.json.v1.bak, recent.json.v1.bak` line in the §3 layout block; in
  §10 mention that locators resolve roots through `LocatorEnvironment` (one sentence). Leave "Known gaps" as is;
  this plan closes none of them.
- `docs/plugin-spec.md` §2: delete the "v1 to v2 migration is written ... but not called." bullet.
- `CLAUDE.md`: delete the `LegacyGlobalConfig` + `MigrationManager` paragraph; add `./gradlew :core:koverHtmlReport  #
  Core coverage report (core/build/reports/kover/html)` to Build Commands; in Testing add one line: locators are
  tested through `LocatorEnvironment` with temp dirs, not the real machine.

## Sessions

Sessions run in order; 2, 3 and 4 depend only on session 1 (Kover) and can run in any order after it. Each on
Opus 5.5 at low effort, ending with its own commit (rules in Finishing) that marks its entry **Done**.

1. **Done.** **Drop migration, add Kover.** Implements "Drop migration" and "Kover", plus the migration parts of Docs (core-spec
   §3/§3.4, plugin-spec §2, CLAUDE.md paragraph and Kover command). Reads `IdeInstallationService.kt`, root and
   `core/build.gradle.kts`. Verify with steps 1–4. Record the baseline in "Coverage log". ~8%.
2. **Done.** **Model, persistence, DI, ProjectManager tests** (Tests → Session 2). Reads `config/*.kt`, `di/*.kt`,
   `util/FileLockUtils.kt`, `core/ProjectManager.kt`, `ProjectManagerTest.kt`, `LaunchProjectTest.kt` (mocking
   pattern), `plans/core-gap-10-persistence-robustness.md` Tests section (to avoid duplicating its cases). ~18%.
3. **Done.** **Launch, shutdown, directories, cleaning tests** (Tests → Session 3). Reads `process/ProjectLauncher.kt` (lines
   20–66), `process/IntelliJLauncher.kt`, `core/ShutdownWaiter.kt`, `core/DirectoryManager.kt`,
   `core/ProjectCleaner.kt`, `DirectoryManagerTest.kt`, `ProjectCleanerTest.kt`, `LaunchProjectTest.kt`. ~18%.
4. **Locator seams and tests** ("Locator seams", Tests → Session 4, the §10 and CLAUDE.md Testing lines of Docs).
   Reads `locators/*.kt`, `platform/Platform.kt`, the three locator tests, and greps `plugin/` for locator call sites
   to confirm the public signatures still compile (`./gradlew :plugin:compileKotlin`). Does the full Finishing. ~20%.

## Coverage log

Line coverage of `core` from `./gradlew :core:koverLog`, filled in by each session.

- Baseline (session 1, Kover 0.9.9): 60.39%
- Session 2: 63.93%
- Session 3: 65.61%

## Verification

1. `./gradlew :core:test`
2. `./gradlew :plugin:compileKotlin :sync-helper:build` (session 1 and 4: public API or plugin code changed)
3. `./gradlew :core:koverLog` and record the number in "Coverage log"
4. `./gradlew build`

No manual check: nothing here changes behavior a user can see. Locator seams are covered by the smoke cases plus the
plugin compiling; optionally run `./gradlew :plugin:runIde` and confirm the Recent Projects popup still lists IDE
installations.

## Finishing

- Last session: apply the remaining Docs items, remove the "Coverage log" (put the final number in the commit body),
  and delete `plans/core-test-coverage.md` in the same commit.
- Every session: check `git status` / `git diff`, stage only this work's files by path (never `git add -A`/`-a`),
  and commit like recent commits: lowercase imperative subject (`drop the unused v1 migration code and add kover to
  core`, `cover core persistence and DI with tests`, ...), a body with what and why, and
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. List changes left out of the commit in the reply.
