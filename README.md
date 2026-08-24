# ProtocolLib2PacketEvents (P2P)

A ProtocolLib-compatible [ProtocolLib](https://github.com/dmulloy2/ProtocolLib) compatibility layer powered by
[PacketEvents](https://github.com/retrooper/packetevents).

P2P re-implements the high-value ProtocolLib API used by the server's installed plugins — same
`com.comphenix.protocol.*` package names, class names, and packet constants — on top of PacketEvents.
Plugins already compiled against the covered ProtocolLib surface link without recompiling, while the
actual packet interception is done by PacketEvents. It is intentionally not an NMS-compatible fork
of every internal ProtocolLib class.

## Why this can exist

Both projects name packets after the same wiki.vg/Mojang vocabulary, and PacketEvents already
solves the hard part: version-independent packet identity and field decoding. What ProtocolLib
adds on top is an API shape. P2P supplies that shape and delegates every protocol detail
downwards, so it contains no version-specific packet logic of its own.

## Supported Minecraft versions

Whatever PacketEvents supports — verified from its `ServerVersion` enum, that is **1.7.2 through
the latest supported release** (protocol 776 / "26.2" at the time of writing).

P2P does not narrow that range: all packet id and field mapping is delegated to PacketEvents at
runtime. `plugin.yml` deliberately omits `api-version` so the plugin is not pinned to one Bukkit
API bucket. The `paper-api` version in `pom.xml` is a compile-time surface only (`JavaPlugin`,
`Player`, `Plugin`, `Logger`); it is not a runtime restriction.

## Requirements

- Java 17+
- The **PacketEvents** plugin installed on the server (P2P `depend`s on it; it is not shaded)

## Build

```bash
mvn package
# -> target/ProtocolLib2PacketEvents-1.0.4.jar
```

Drop the jar in `plugins/` alongside PacketEvents. `plugin.yml` declares `provides: [ProtocolLib]`,
so plugins whose `plugin.yml` lists `depend: [ProtocolLib]` resolve against P2P at the server
loader level, not just at the Java API level.

## How packet types work

This is the part that makes real source compatibility possible.

ProtocolLib exposes packet types as compile-time constants (`PacketType.Play.Server.ENTITY_ANIMATION`),
and compiled plugins reference those fields directly. Java cannot add static fields to a class
reflectively, so a runtime-lookup registry would not link against existing plugin jars. P2P
therefore **generates** `PacketType.java` at build time:

1. `maven-dependency-plugin` resolves the compile classpath (where PacketEvents lives).
2. `P2PPacketTypeGenerator` (run as a single-file source program) reflects over PacketEvents'
   `PacketType`, enumerating every per-phase `Client`/`Server` enum constant.
3. It emits one real `public static final PacketType` field per constant into
   `target/generated-sources/packettypes`, which `build-helper-maven-plugin` adds as a source root.

Bumping the PacketEvents dependency refreshes the constant set automatically. The current build
generates **288 PacketEvents constants + 149 ProtocolLib-named aliases** (aliases share the
generated PacketEvents instances where the names are equivalent).

### Name aliases

Most constants already agree between the two projects and are matched automatically (by exact
name, or by PacketEvents' wrapper class name matching one of ProtocolLib's declared packet class
aliases). The rest are pinned explicitly in `src/generator/resources/protocollib-aliases.tsv`, for
example:

| ProtocolLib | PacketEvents |
|---|---|
| `Play.Server.ANIMATION` | `ENTITY_ANIMATION` |
| `Play.Client.USE_ENTITY` | `INTERACT_ENTITY` |
| `Play.Server.MAP_CHUNK` | `CHUNK_DATA` |
| `Play.Server.ENTITY_DESTROY` | `DESTROY_ENTITIES` |
| `Play.Client.BLOCK_DIG` | `PLAYER_DIGGING` |

Fuzzy matching is deliberately **not** used for these. A wrong packet mapping fails silently and
corrupts plugin behaviour, which is worse than not mapping at all, so each row was checked against
ProtocolLib's own declarations and PacketEvents' wrapper classes. The generator fails the build if
any alias points at a packet PacketEvents no longer defines, so an upstream rename surfaces as a
build error instead of a silent behaviour change.

Both spellings resolve to the same instance:
`PacketType.Play.Server.ANIMATION == PacketType.Play.Server.ENTITY_ANIMATION`.

`PacketType.Handshake` (ProtocolLib's spelling) and `PacketType.Handshaking` (PacketEvents')
are both emitted.

## Compatibility status

The contract is pinned to ProtocolLib upstream SHA
`67ce937109eb7bcc6e380ffe745d9b4a01ce1987` and PacketEvents `2.13.0`. The latest local ABI
scan covers 428 upstream production classes and reports:

- `missing_classes=0`
- `missing_members=0`
- `descriptor_mismatches=0`
- `class_header_mismatches=37` (inheritance/modifier differences still require behavior review)

This is an implementation/ABI result, not a universal version certification. The 1.0.4 release
has additionally been live-tested on Leaf 1.21.11 + Java 25 + PacketEvents 2.13.0 with burst login
loads up to 50 clients. A 25-client validation completed 25/25 with zero client errors and ~20 TPS;
during the 50-client run P2P produced no synchronous-listener timeout, Netty ref-count, or watchdog
freeze errors. Broader Minecraft-version coverage still follows the matrix in
`compatibility/packetevents-version-matrix.properties`.

## What is implemented

- **`ProtocolManager`** — add/remove listeners, `sendServerPacket`, `broadcastServerPacket`,
  `receiveClientPacket` plus the historical `recieveClientPacket` overloads, asynchronous-manager
  access, `createPacket`, `getEntityFromID`, `getListeningTypes`
- **Listener plumbing** — `PacketAdapter`, `PacketListener`, `PacketEvent`, `ListeningWhitelist`,
  `ListenerPriority`, `ConnectionSide`, with ProtocolLib's priority ordering (low first, MONITOR
  last)
- **`PacketContainer`** with primitive, item, chat, profile, entity-type, chunk-coordinate,
  merchant/item-list, and 1.19.3+ DataValue/DataWatcher modifiers. `getHandle()` keeps the
  ProtocolLib `Object` descriptor; `getPacketWrapper()` is the PacketEvents-typed bridge method.
- **Wrappers** — `WrappedChatComponent` (Adventure-backed), `WrappedGameProfile`/signed properties,
  `BlockPosition`, `ChunkCoordIntPair`, `WrappedDataWatcher`, `WrappedDataValue`,
  `WrappedWatchableObject`, and serializer registry facades.
- **Reflection compatibility** — the legacy `FuzzyReflection`, `FieldUtils`, `MethodUtils`,
  accessor, `MinecraftReflection`, and `StreamSerializer` methods used by the installed plugins.
- **`AsynchronousManager`** — a real worker pool with per-player serial ordering, timeout/cancel
  handling, hold/release, and legacy bounded `AsyncListenerHandler`/`AsyncMarker` queues.
- **Hybrid transport** — PacketEvents owns structured packets; a native Netty interceptor handles
  unmodelled raw frames, protocol transitions, temporary players, cancellation, byte edits, and
  output handlers without dispatching structured PacketEvents twice.
- **Raw fallback** — unmodelled packet types can be intercepted and cancelled with
  `getRawBuffer()` byte-level access when the server exposes a compatible Netty channel. Check
  `hasStructuredAccess()` and runtime capability detection first.

Dispatch indexes listeners by packet type, so a plugin listening for one packet does not add
per-listener work to every other packet on the server. Since 1.0.4, packet-thread dispatch is
non-blocking: inbound listeners follow ProtocolLib's direction-sensitive threading contract and
thread hops use ordered continuations instead of waiting on Netty event-loop threads.

See [CHANGELOG.md](CHANGELOG.md) for release-by-release details.

## Known differences from real ProtocolLib

These are real behavioural differences, not TODOs to gloss over:

1. **Field indices differ.** `StructureModifier` orders fields by declaration order on
   PacketEvents' wrapper classes, not on NMS packet classes. `getIntegers().read(0)` may therefore
   refer to a different field than under real ProtocolLib. Prefer the typed getters on
   PacketEvents' wrapper (`packet.getHandle()`) when you need certainty.
2. **Direct Netty certification is pending.** The fallback is implemented for native Netty
   channels and uses explicit capability detection; shaded/reflection-only channels fall back to
   PacketEvents' public channel API. The actual handshake/status/login/configuration/play matrix
   still needs a live server/client run.
3. **`WrappedDataWatcher.setObject(index, value)`** infers the entity data type only for
   unambiguous primitives. For a new index holding a component, optional, item stack or particle,
   use `setObject(int, EntityDataType, Object)` — guessing would pick the wrong serializer.
4. **Pre-login is capability-gated.** The direct interceptor creates a temporary `Player` before
   Bukkit login and tracks handshake/status/login/configuration transitions. This path is not
   certified for every PacketEvents server version until the live matrix is run.
5. **NMS types are never exposed.** Anything expecting a literal NMS handle out of
   `getModifier().read(...)` gets a PacketEvents object instead.
6. **Behavior certification is narrower than class inventory.** The class/member inventory is
   complete against the pinned production source, but native-only internals can still return an
   explicit unsupported-capability exception when the server has no equivalent
   PacketEvents/Bukkit representation. P2P does not silently report success for those paths.

## Implementation notes

- **Packet allocation.** `ProtocolManager.createPacket` must build a wrapper whose class has no
  no-arg constructor. `ObjectAllocator` uses `sun.reflect.ReflectionFactory` (exported via
  `jdk.unsupported`, verified working on JDK 25), falling back to `sun.misc.Unsafe` only if that
  is unavailable — `Unsafe.allocateInstance` is deprecated for removal, so it is not the primary
  path. The strategy in use is logged on enable.
- **Re-encoding.** PacketEvents discards wrapper edits unless the event is marked for re-encode.
  P2P marks the event whenever a listener actually ran, so modifications reach the wire.
- **Lazy holders.** Generated holder classes initialize lazily, so registry-wide queries
  (`PacketType.values()`, `fromKey`) force all holders first.

## License

Copyright (C) 2026 **CyoriaSMP Team**

Licensed under the **GNU General Public License v3.0 or later** — see [LICENSE](LICENSE).

GPLv3 is required here rather than chosen freely: P2P links against PacketEvents, which is
GPLv3, so any distributed build must be GPLv3-compatible. Using P2P privately on your own server
is not distribution and carries no obligation.

Third-party components (see [NOTICE](NOTICE) for detail):

- **PacketEvents** (GPLv3) — not bundled; declared `provided` and installed separately as its own
  plugin.
- **ProtocolLib** (GPLv2) — no source code included. P2P independently re-implements ProtocolLib's
  public API surface so existing plugins can link against it; the alias table in
  `src/generator/resources/protocollib-aliases.tsv` is a packet-name mapping derived from
  ProtocolLib's public API declarations.
