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

| Command | Effect |
|---|---|
| `/tu spawn <name> [x y z]` | Join a fake player, at world spawn by default. |
| `/tu remove <name>` | Disconnect it. |
| `/tu as <name> <command...>` | Run a command as the fake player. |
| `/tu open <name> <x> <y> <z>` | Open the container at that block. Fires `InventoryOpenEvent`. |
| `/tu close <name>` | Close its open inventory view. Fires `InventoryCloseEvent`. |
| `/tu use <name> <x> <y> <z> [face]` | Right-click the block with the main-hand item. Fires `PlayerInteractEvent`. Face defaults to `up`. |

Vanilla commands cover the rest, since the fake player is a real player:
`tp`, `give`, `clear`, `item replace entity <name> weapon.mainhand with ...`,
`data get entity <name> Inventory`, `data get block <x> <y> <z> Items`.

### Replies

Every `tu` command answers with one line of JSON. Success carries `"ok": true`
plus the fields below. Anything the caller can fix answers `{"ok":false,"error":"..."}`:
an unknown action, missing or non-numeric arguments, coordinates outside the world border or height
limits, an unknown face, a name that is taken or unknown, a block that is no container.
None of these log an error. An error reply holds exactly `ok` and `error`.

| Command | Reply |
|---|---|
| `spawn` | `{"ok":true,"name":"bot","x":0,"y":64,"z":0}` (block coordinates) |
| `remove` | `{"ok":true,"name":"bot"}` |
| `as` | `{"ok":true,"name":"bot","command":"help","success":true}`, `success` is false for an unknown command or one the bot may not run |
| `open` | `{"ok":true,"name":"bot","block":"minecraft:chest"}` |
| `close` | `{"ok":true,"name":"bot"}` |
| `use` | `{"ok":true,"name":"bot","item":"minecraft:iron_hoe","pos":[0,64,2],"result":"Success"}`, `item` is the main-hand item before the click, `result` the vanilla interaction result (`Success`, `Pass`, `Fail`, ...) |

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
- `command(line)` runs any console command and returns everything it wrote back.
- `Waiting.waitUntil(timeout, query, holds)` re-runs the query (any `Supplier`, such as
  `() -> server.check("if ...")`) about every 50 ms until `holds` accepts its value, and returns that value.
  After the timeout it fails with the last value. Use it instead of a fixed sleep.
- `Waiting.holdsFor(window, query, holds)` re-runs the query for the whole window and fails
  as soon as a value breaks `holds`, with that value. It returns only after the window has
  passed, so a control case can show that something did not happen.

The test server runs a flat world: bedrock at y=-64, dirt up to y=62, grass at y=63, air from y=64.
It has no fluids, structures or animals. Random ticks, mob spawning and the day cycle are off, the
time is fixed at 6000 and the difficulty is peaceful. The library reads each setting back before
the first test and fails the run if the server did not take it.

The library compiles against JUnit and Gson without shipping them: declare both in the consuming build.

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

| Jar | Contents |
|---|---|
| `plugin/build/dist/testutils-<version>+<mc>-paper.jar` | the Paper plugin |
| `library/build/dist/testutils-library-<version>.jar` | test-side library (JUnit and Gson are compile-only, consumers declare their own) |

```
./gradlew check build         # builds both jars and verifies their names and the release globs
./gradlew :plugin:runServer   # dev server with the plugin
```

`check` also runs the library's tests against a real Paper server: the build
pins one Paper build (`paper_build`, `paper_sha256` in `gradle.properties`),
downloads and verifies it once into `<gradle user home>/caches/testutils/paper`,
boots it in a temp directory with the plugin jar, and talks to it over RCON.

### Limits

- Nothing ticks the fake player's connection, so it has no physics and no
  per-tick player logic. It stands where it was placed until `tp` moves it.
- Spawning a fake player does not load the chunks around it in time for the
  next command (`That position is not loaded`). Load the area with
  `forceload add <x> <z>` before placing blocks.
- Fake players don't survive a restart. `onDisable` removes them all.
