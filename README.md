# TestUtils

Paper plugin. Fake players for headless plugin tests.

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
- With no real player online, chunks around world spawn may be unloaded.
  Spawn the fake player first: it keeps the chunks around it loaded.
- Fake players don't survive a restart. `onDisable` removes them all.
