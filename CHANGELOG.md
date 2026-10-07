# Changelog

All notable changes to ProtocolLib2PacketEvents (P2P) are documented here.

## [1.0.6] - 2026-10-07

### Fixed

- Fixed QuickShop-Hikari `PLAY.SERVER.UNLOAD_CHUNK` compatibility. `PacketContainer#getLongs()` now exposes the packed chunk key at index `0`, and writes update PacketEvents' live chunk X/Z values.
- Made `getChunkCoordIntPairs()` a live read/write view over unload-chunk coordinates, including supported derived modifiers and target changes.
- Made the `LOGIN.CLIENT.LOGIN_START#getGameProfiles()` compatibility projection write username and UUID changes through to the PacketEvents wrapper.
- Made `CHUNK_DATA#getIntegers()` coordinate writes update the nested PacketEvents `Column` as well as reads.
- Added regression coverage for selector behavior, derived views, helper methods, retargeting, and wrapper write-through.

### Validation

- Java 17 Maven test suite: 19 tests, 0 failures, 0 errors, 1 skipped (PacketEvents server-runtime assumption).
- Maven package: passed for the `1.0.6` release JAR.

## [1.0.5] - 2026-10-05

### Fixed

- Fixed ProtocolLib `MAP_CHUNK` / PacketEvents `CHUNK_DATA` integer field compatibility. `PacketContainer#getIntegers()` now exposes the chunk X and Z coordinates from PacketEvents' nested `Column` object at indices `0` and `1`, matching ProtocolLib behavior used by QuickShop-Hikari and other ProtocolLib consumers.
- Added a regression test covering `getIntegers().read(0)` and `read(1)` on chunk data packets.

### Notes

- This fixes the `IndexOutOfBoundsException: No field of type int at index 0` seen when QuickShop-Hikari listens for outgoing chunk data through P2P.
- The compatibility view targets decoded/intercepted chunk packets whose PacketEvents wrapper already contains a `Column`; packet construction semantics outside that path are unchanged.

## [1.0.4] - 2026-08-24

### Fixed

- Fixed `LOGIN.CLIENT.LOGIN_START` compatibility for plugins that read a ProtocolLib `WrappedGameProfile`/`UserProfile` at index `0` (notably FastLogin-style listeners). P2P now exposes a synthetic profile view backed by PacketEvents' login username and UUID fields.
- Removed packet-thread blocking from the ProtocolLib bridge. Netty event-loop threads no longer wait on Bukkit/main-thread listeners through `CountDownLatch.await(...)` or `Future.get(...)`.
- Corrected ProtocolLib threading semantics by direction:
  - inbound/client packets stay on the network thread by default and only hop to the main thread when `ListenerOptions.SYNC` requests it;
  - outbound/server packets preserve main-thread behavior unless `ListenerOptions.ASYNC` opts out.
- Added ordered, per-player deferred continuations so packets that must cross thread boundaries retain ordering without blocking the network thread.
- Fixed deferred PacketEvents buffer lifetime handling. Retained event buffers now transfer ownership to Netty correctly, eliminating `IllegalReferenceCountException: refCnt: 0` seen during burst joins.
- Prevented recursive-map update races when a deferred continuation completes synchronously.

### Performance

- Added packet-type listener indexes so packets with no matching ProtocolLib listeners return on the hot path without global listener scans or unnecessary `PacketContainer` decoding.
- Collapsed synchronous dispatch for a packet into one continuation instead of scheduling one Bukkit task per listener.
- Converted asynchronous processing to `CompletableFuture` continuation flow rather than blocking callers while worker tasks complete.

### Validation

- `mvn test` passes, including new concurrency/threading regression coverage.
- Live-tested on Leaf 1.21.11 + Java 25 + PacketEvents 2.13.0.
- 25-client burst: **25/25 spawned, 0 client errors**, TPS remained ~20.
- 50-client burst: P2P remained free of synchronous-listener timeouts, Netty ref-count errors, and watchdog freezes; the remaining failed joins were isolated to the server's authentication/Mojang-profile pipeline rather than P2P.
- During the validated runs, the server log contained **0** occurrences of:
  - `Synchronous packet listener timed out`
  - `IllegalReferenceCountException`
  - watchdog `has not responded`

### Notes

- P2P continues to advertise ProtocolLib API compatibility version `5.4.0` in `plugin.yml`; the P2P release version is `1.0.4` and is also exposed as `p2p-version`.
- FastLogin is not required for P2P itself. Authentication architecture and Mojang API rate limiting are outside this release's scope.

## [1.0.3] - 2026-08-24

### Fixed

- Added the LOGIN_START `GameProfile` compatibility view used as the baseline for the 1.0.4 dispatcher work.

## [1.0.2]

- Previous public release.
