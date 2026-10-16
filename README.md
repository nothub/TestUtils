# TestUtils

Paper plugin. Fake players for headless plugin tests.

Write JUnit 6 tests with the [test library](#test-library) and run them with `./gradlew test`.
The library boots a headless Paper server with the plugin under test and TestUtils installed,
drives it over RCON through fake players, and stops it after the last test.

A fake player is a real `ServerPlayer`, joined through vanilla's own login
path with an in-memory connection. Other plugins see it in
`getOnlinePlayers()` and receive the same events a real client would fire.
No Minecraft client or account needed, so the whole test runs from the
server console.

---

### Commands

Alias `/tu`, permission `testutils.use` (op).

| Command                             | Effect                                                                                             |
|-------------------------------------|----------------------------------------------------------------------------------------------------|
| `/tu spawn <name> [x y z]`          | Join a fake player, at world spawn by default.                                                     |
| `/tu remove <name>`                 | Disconnect it.                                                                                     |
| `/tu as <name> <command...>`        | Run a command as the fake player.                                                                  |
| `/tu open <name> <x> <y> <z>`       | Open the container at that block. Fires `InventoryOpenEvent`.                                      |
| `/tu close <name>`                  | Close its open inventory view. Fires `InventoryCloseEvent`.                                        |
| `/tu use <name> <x> <y> <z> [face]` | Right-click the block with the main-hand item. Fires `PlayerInteractEvent`. Face defaults to `up`. |
| `/tu inventory <name>`              | Report what it carries: every non-empty slot and the main-hand item.                               |
| `/tu block <x> <y> <z>`             | Report the block's type and state properties. Needs no fake player.                                |
| `/tu container <x> <y> <z>`         | Report the slots of the container block there. Needs no fake player.                               |

Vanilla commands cover the rest, since the fake player is a real player:
`tp`, `give`, `clear`, `item replace entity <name> weapon.mainhand with ...`,
`data get entity <name> Inventory`. The `inventory`, `block` and `container` queries answer in JSON, so a test
never has to parse the text those commands print.

### Replies

Every `tu` command answers with one line of JSON. Success carries `"ok": true`
plus the fields below. Anything the caller can fix answers `{"ok":false,"error":"..."}`:
an unknown action, missing or non-numeric arguments, coordinates outside the world border or height
limits, an unknown face, a name that is taken or unknown, a block that is no container.
None of these log an error. An error reply holds exactly `ok` and `error`.

| Command     | Reply                                                                                                                                                                                                                                                                                                                                                                                                                                                              |
|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `spawn`     | `{"ok":true,"name":"bot","x":0,"y":64,"z":0}` (block coordinates)                                                                                                                                                                                                                                                                                                                                                                                                  |
| `remove`    | `{"ok":true,"name":"bot"}`                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `as`        | `{"ok":true,"name":"bot","command":"help","success":true}`, `success` is false for an unknown command or one the bot may not run                                                                                                                                                                                                                                                                                                                                   |
| `open`      | `{"ok":true,"name":"bot","block":"minecraft:chest"}`                                                                                                                                                                                                                                                                                                                                                                                                               |
| `close`     | `{"ok":true,"name":"bot"}`                                                                                                                                                                                                                                                                                                                                                                                                                                         |
| `use`       | `{"ok":true,"name":"bot","item":"minecraft:iron_hoe","pos":[0,64,2],"result":"Success"}`, `item` is the main-hand item before the click, `result` the vanilla interaction result (`Success`, `Pass`, `Fail`, ...)                                                                                                                                                                                                                                                  |
| `inventory` | `{"ok":true,"name":"bot","main_hand":{"id":"minecraft:iron_hoe","count":1,"damage":7,"item_model":"minecraft:iron_hoe"},"items":[{"slot":0,"id":"minecraft:iron_hoe","count":1,"damage":7,"item_model":"minecraft:iron_hoe"}]}`, `main_hand` is `null` for an empty hand, empty slots are left out, `damage` is 0 for an item that can't be damaged. Slots are numbered like Bukkit's `PlayerInventory`: 0-8 hotbar, 9-35 bag, 36-39 boots to helmet, 40 off hand. |
| `block`     | `{"ok":true,"pos":[0,64,0],"type":"minecraft:oak_stairs","state":{"facing":"east","half":"top","shape":"straight","waterlogged":"false"}}`, property values are vanilla's names as strings, `state` is `{}` for a block without properties                                                                                                                                                                                                                         |
| `container` | `{"ok":true,"pos":[0,64,0],"block":"minecraft:chest","size":27,"items":[{"slot":4,"id":"minecraft:dirt","count":3,"damage":0,"item_model":"minecraft:dirt"}]}`, `items` holds the non-empty slots like `inventory` does, a block that is no container answers `no container at x y z`.                                                                                                                                                                             |

A fake player is not op. `as` runs commands under the permissions it has.

### Test library

`TestServerExtension` starts one Paper server per test run and injects it as a `TestServer`:

```java
@ExtendWith(TestServerExtension.class)
class SpawnTest {
    @Test
    void spawns(TestServer server) {
        server.command("forceload add 0 0");
        server.command("setblock 0 64 0 stone");
        JsonObject reply = server.tu("spawn bot 0 65 0");   // parsed reply, ok:false is returned, not thrown
        assertTrue(reply.get("ok").getAsBoolean());
        assertEquals(new CheckResult(true, 1), server.check("if block 0 64 0 stone"));
    }
}
```

- `tu(args)` runs a `tu` command and parses the reply.
- `check(condition)` runs `execute <condition>` (`if ...` or `unless ...`) and returns a `CheckResult(passed, count)`.
  A plain pass counts 1 and a plain fail 0. Vanilla's `Test passed. Count: N` and
  `Test failed. Count: N` carry their count. Any other reply (typo, unknown block,
  unloaded position) throws with the reply text.
- `block(pos)` returns a `Block(type, state)` and `container(pos)` a `Container(block, size, slots)`, both typed
  from the JSON replies. `container` throws for a block that is no container. Neither loads anything from text.
- `command(line)` runs any console command and returns everything it wrote back.
- `Waiting.waitUntil(timeout, query, holds)` re-runs the query (any `Supplier`, such as
  `() -> server.check("if ...")`) about every 50 ms until `holds` accepts its value, and returns that value.
  After the timeout it fails with the last value. Use it instead of a fixed sleep.
- `Waiting.holdsFor(window, query, holds)` re-runs the query for the whole window and fails
  as soon as a value breaks `holds`, with that value. It returns only after the window has
  passed, so a control case can show that something did not happen.

- `server.area()` / an injected `Area` parameter: one area per test invocation (each parameterized case too).
  It is a force-loaded chunk with a stone floor, spaced far from every other area. `area.at(dx, dy, dz)`
  gives a `Pos`, where `dy` 0 is the floor and 1 the first air block; a `Pos` prints as `x y z` for commands.
  `area.bot()` and `area.bot(pos)` spawn a `Bot` with a unique name from a run-wide counter. The area
  removes its bots when the test ends. An `Area` can't be injected into `@BeforeAll`.
- `Bot` has `hold(item)`, `run(command)`, `use(pos)`, `open(pos)`, `close()`, `inventory()`, `remove()`. A call the server
  refuses throws, so tests never check a reply. `run` returns the command's success instead.
  `inventory()` returns an `Inventory(Map<Integer, Item> slots, Item mainHand)` of `Item(id, count, damage, itemModel)`;
  `mainHand` is null for an empty hand.

- After every test, and after the last test of a class, the extension has the server `say` a marker and reads
  the log up to it. An entry that belongs to the plugin under test fails that test, with the entry in the message.
  An entry belongs to it when it is an ERROR logged as `[PluginName] ...`, an ERROR in which the server
  blames the plugin ("Could not pass event X to PluginName v1"), or any entry with a stack frame in the
  package of the `main` class from the jar's `plugin.yml`. Paper's and other plugins' errors are ignored. Errors
  logged while the server boots fail the first test.
- A server that exits or never gets ready fails the start with the exit code or the timeout and the last 40
  log lines in the message. A corrupt Paper jar fails the launcher's patch pass, and the message carries its output.
- When the server stops, its log is copied to `build/testutils/server.log` (relative to the test JVM's
  working directory, which is the project directory under Gradle).
- Next to the log it writes `build/testutils/timing.txt`: `boot_seconds`, `teardown_seconds`, their sum
  `total_seconds`, and `paper_cache=cold|warm`. Boot counts from patching Paper to the world being set up.
  Cold means the shared Paper repo (below) was empty when the run began. The same line goes to stdout.
- The test server also turns Paper's update checker off. Offline it logs three ERROR lines at boot, and it
  is a network call a test has no use for.

The test server runs a flat world: bedrock at y=-64, dirt up to y=62, grass at y=63, air from y=64.
It has no fluids, structures or animals. Random ticks, mob spawning and the day cycle are off, the
time is fixed at 6000 and the difficulty is peaceful. The library reads each setting back before
the first test and fails the run if the server did not take it.

The library compiles against JUnit and Gson without shipping them: declare both in the consuming build.

### Consumer setup

Every `v*` release carries three assets under one version:

| Asset                                | Contents                                                          |
|--------------------------------------|-------------------------------------------------------------------|
| `testutils-<version>+<mc>-paper.jar` | the Paper plugin                                                  |
| `testutils-library-<version>.jar`    | the test library                                                  |
| `testutils-<version>.properties`     | the Paper pin: `minecraft_version`, `paper_build`, `paper_sha256` |

The repo is public, so Gradle fetches them straight from the release download URL through an Ivy
repository, with no credentials and no package registry. The project pins the TestUtils version and
reads `minecraft_version` from its own `gradle.properties`. The Paper build and its hash come from the pin asset:

```groovy
repositories {
    mavenCentral()
    // Gradle sends the `+` in the plugin jar's name as is, and GitHub serves it.
    ivy {
        url = 'https://github.com/nothub/TestUtils/releases/download'
        patternLayout { artifact 'v[revision]/[module]-[revision](+[classifier]).[ext]' }
        metadataSources { artifact() }
        content { includeGroup 'lol.hub' }
    }
}

configurations {
    testutilsPlugin
    testutilsPin
}

def testutils = '0.2.0'
dependencies {
    testImplementation "lol.hub:testutils-library:${testutils}"
    testImplementation platform('org.junit:junit-bom:6.1.3')
    testImplementation 'org.junit.jupiter:junit-jupiter'
    testImplementation 'com.google.code.gson:gson:2.14.0'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
    testutilsPlugin "lol.hub:testutils:${testutils}:${project.minecraft_version}-paper"
    testutilsPin "lol.hub:testutils:${testutils}@properties"
}

tasks.named('test') {
    useJUnitPlatform()
    dependsOn 'jar'
    def pluginUnderTest = tasks.named('jar').flatMap { it.archiveFile }
    def testutilsPlugin = configurations.testutilsPlugin
    inputs.files(testutilsPlugin)
    doFirst {
        systemProperty 'testutils.pluginJar', pluginUnderTest.get().asFile.path
        systemProperty 'testutils.testutilsJar', testutilsPlugin.singleFile.path
        systemProperty 'testutils.paperJar', '<the pinned Paper jar, see below>'
        systemProperty 'testutils.paperRepo', '<a directory for Paper\'s patched libraries>'
    }
}
```

The library reads the four system properties and downloads nothing itself. The last two values are yours to supply:

- `testutils.pluginJar`: the plugin under test, the project's own jar task output.
- `testutils.testutilsJar`: the TestUtils plugin jar of the same release.
- `testutils.paperJar`: the Paper jar the pin asset names. In a task the test task depends on, read the pin
  from `configurations.testutilsPin` and fail when its `minecraft_version` differs from the project's. Then
  fetch the `server:default` download of
  `https://fill.papermc.io/v3/projects/paper/versions/<minecraft_version>/builds/<paper_build>`
  and check it against `paper_sha256`. The `paperJar` task in `library/build.gradle` does the same for
  TestUtils' own tests. The test task resolves `testutilsPlugin` only after its dependencies ran. A release for
  another MC version has no plugin jar under the project's classifier, so without the earlier check that
  mismatch surfaces as a 404.
- `testutils.paperRepo`: where Paper's launcher puts Mojang's jar and the libraries it patches. A directory
  beside the cached Paper jar, outside `build/`, lets every run share them.

Keep downloaded jars out of `build/dist`: a release glob over it would upload them.

### Example

```
tu spawn bot 0 64 0
setblock 0 64 2 chest{Items:[{Slot:4b,id:"minecraft:dirt",count:3}]}
tu open bot 0 64 2
tu close bot
data get block 0 64 2 Items
tu remove bot
```

### Build

Two subprojects, two jars:

| Jar                                                    | Contents                                                                         |
|--------------------------------------------------------|----------------------------------------------------------------------------------|
| `plugin/build/dist/testutils-<version>+<mc>-paper.jar` | the Paper plugin                                                                 |
| `library/build/dist/testutils-library-<version>.jar`   | test-side library (JUnit and Gson are compile-only, consumers declare their own) |

Next to the plugin jar the build writes `testutils-<version>.properties`, the Paper pin from `gradle.properties`.

```
./gradlew check build         # builds both jars and verifies their names and the release globs
./gradlew :plugin:runServer   # dev server with the plugin
```

`runServer` boots the same pinned, verified Paper jar the tests use, not run-paper's latest build.
On every launch it rewrites these keys in `plugin/run/server.properties` and leaves the others alone:

| Key                                              | Value                |
|--------------------------------------------------|----------------------|
| `server-ip`, `server-port`                       | `127.0.0.1`, `25565` |
| `online-mode`, `white-list`, `enforce-whitelist` | `false`              |
| `enable-rcon`, `rcon.port`                       | `true`, `25575`      |
| `rcon.password`                                  | `testutils`          |

A stock client joins at `localhost`. An agent sends console commands over RCON on `127.0.0.1:25575` and gets
each command's output back. Loopback only, so the fixed password exposes nothing. A command that answers a tick
later (`version`) returns only its first line.

`check` also runs the library's tests against a real Paper server: the build
pins one Paper build (`paper_build`, `paper_sha256` in `gradle.properties`),
downloads and verifies it once into `<gradle user home>/caches/testutils/paper`,
boots it in a temp directory with the plugin jar, and talks to it over RCON.

### No orphan servers

When the test JVM is interrupted or terminated (Ctrl-C of Gradle), a shutdown hook sends the server SIGTERM
and kills it after 10 s. A `kill -9` runs no hooks, so every server also gets a run marker
(`-Dtestutils.run=<uuid>` on its command line) and a record in `<gradle user home>/caches/testutils/paper/servers/<pid>.properties`
with the server's and its owner's pid and start time. Before booting, a run stops every marked server whose owner
is gone. A process without our marker, such as `runServer`, is never touched, even when a record names its pid.

### Paper cache and offline runs

Gradle downloads the pinned Paper jar once into `<gradle user home>/caches/testutils/paper`. Paper's
launcher (Paperclip) then fetches Mojang's server jar and its libraries and patches them. The library
passes `-DbundlerRepoDir` so all of that lands in `repo/` beside the jar, shared by every run, instead of
in each server's temp directory. Before each boot it runs a `-Dpaperclip.patchonly=true` pass under an
exclusive file lock, so two builds starting on an empty cache wait for one patch instead of both writing the
same libraries. A warm pass takes under a second and needs no network.

The first run needs network. After that `./gradlew --offline test --rerun-tasks` passes without any.
`runServer` uses the same `repo/`. `SharedRepoTest` starts two servers at once on an empty repo, so it needs
network and skips itself without DNS.

In CI, cache `~/.gradle/caches/testutils/paper` keyed on `<minecraft_version>-<paper_build>`. See `.github/workflows/ci.yaml`,
which also fails the build when boot plus teardown exceeds 30 s on a cache hit.

### Limits

- Nothing ticks the fake player's connection, so it has no movement or
  physics. It stands where it was placed until `tp` moves it. The world still
  runs its player tick, so a container it opens stays open until `tu close`.
- Spawning a fake player does not load the chunks around it in time for the
  next command (`That position is not loaded`). Load the area with
  `forceload add <x> <z>` before placing blocks.
- Fake players don't survive a restart. `onDisable` removes them all.
