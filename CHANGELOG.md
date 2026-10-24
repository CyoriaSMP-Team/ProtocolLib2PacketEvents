## Unreleased — Folia bootstrap and scheduler compatibility

- Declare `folia-supported: true` so Folia accepts the bridge at plugin load.
- Select the Folia scheduler facade instead of Bukkit's unsupported legacy scheduler when running under Folia.
- Migrate `NetworkManagerInjector`'s periodic PacketEvents channel scan to that scheduler, avoiding the `CraftScheduler.runTaskTimer` `UnsupportedOperationException` observed on Folia 1.21.11.
- Use actual `ScheduledTask` cancellation for Folia scans; add scheduler regression coverage. The global scheduler is only for plugin-wide work: player/world operations still require region-aware dispatch.
- **Validation:** Maven build/tests pass. Live Folia + Java Premium/FastLoginPlus/QuickShop behavior is not yet certified; do not treat this as a production-safe drop-in without a staged join/chunk test.

## 1.0.11 — Strict Channel Field Resolution in FastLoginPlus Adapter

- Fix `FuzzyReflection.getFieldValue(injector, Channel.class, true)` selecting the **preceding Object-typed `networkManager` field** instead of NettyChannelInjector's actual `Channel channel` field. The wrong bidirectional assignability check caused an inactive/null Channel or ClassCastException when FastLoginPlus examined a Java Premium connection during async Mojang session verification, then logged `disconnected during session verification` and timed the player out.
- Only match fields whose declared type is assignable to the requested class. This is the reflection rule that prevents unsafe casts; don't fall back to unrelated superclasses like `Object`.
- Add regression tests against the real NettyChannelInjector and a separate Object-before-Channel fixture. Both tests **fail on v1.0.10 and pass on v1.0.11**.
- Preserve all previous 1.0.7–1.0.10 transport/encryption/late-handler fixes and do not disable FastLoginPlus or AuthMe authentication.

## 1.0.10 — Late-binding race during Java Premium login

- Fix the v1.0.9 startup race where `NetworkManagerInjector.getTemporaryInjector` refuses to associate a valid active PacketEvents pre-login Channel with the generated `TemporaryPlayer` if native `packet_handler` has not appeared yet. FastLoginPlus thus loses its Channel for the whole verification session and aborts as disconnected.
- Bind **active Channel first**, without requiring the native Minecraft Connection to exist yet; then refresh/resolve the genuine NMS Connection lazily every time FastLoginPlus calls `TemporaryPlayerFactory.getInjectorFromPlayer`. A non-active or non-Netty channel remains rejected.
- Do not change the authentication policy or inject encryption into incomplete channels. The native Connection is still required when the AES setter is called; missing Connection cannot silently allow a cracked bypass.
- Add regression coverage for active pre-login channels before native `packet_handler` is present and late handler attachment.

## 1.0.9 — FastLoginPlus native login channel association

- Associate the ProtocolLib `TemporaryPlayer` generated during PacketEvents pre-login callbacks with its actual Netty injector instead of leaving `TemporaryPlayerFactory.getInjectorFromPlayer()` null. FastLoginPlus uses that accessor during Mojang session verification.
- Resolve the real `net.minecraft.network.Connection` from the channel's existing `packet_handler`, and update cached Netty injectors that were created before the native handler was installed. FastLoginPlus needs the genuine Connection (not the bare Netty Channel) to enable packet encryption.
- Explicitly refuse binding a non-active channel or an injector lacking a native Connection; never fall back to cracked login in P2P code and never circumvent Mojang verification.
- Handle FastLoginPlus's legacy method lookup `a(SecretKey)` on remapped Minecraft 1.21.11 where the native Connection method is named `setEncryptionKey(SecretKey)`; fallback only for one unambiguous void/SecretKey method.
- Keep v1.0.8's encryption wrapper constructor fix and v1.0.7's temporary-user receive/send transport.

## 1.0.8 — Constructor-safe PacketEvents wrapper allocation

- Fix `NullPointerException: wrappper.bufferLock is null` when FastLoginPlus sends `LOGIN.SERVER.ENCRYPTION_REQUEST` with a `PacketContainer` constructed from scratch.
- ProtocolLib packet constructors use `ObjectAllocator` to allocate wrappers without their constructors. For `LOGIN.ENCRYPTION_REQUEST`, use PacketEvents' own security-preserving public constructor (`shouldAuthenticate=true`). For other wrappers, restore mandatory per-instance `bufferLock` and registry holder using a genuine constructor-initialized PacketWrapper of the same client/server protocol version.
- Keep existing packet-specific fields and ProtocolLib write behavior (including FastLoginPlus's `shouldAuthenticate=true` field), and never share locks between newly created wrappers.
- Leave pre-login PacketEvents User/Channel routing from v1.0.7 intact; no changes to the AuthMe or FastLoginPlus login policies.

## 1.0.7 — Deferred Pre-login Channel Fix

- Fix deferred inbound `LOGIN.CLIENT.LOGIN_START` failure when ProtocolLib2PacketEvents had a generated `TemporaryPlayer` instead of a Bukkit `CraftPlayer`: use the already associated PacketEvents pre-login `User` and its live Netty Channel for silent transport instead of `PlayerManager.receivePacketSilently(Player, ...)`.
- Also route pre-login clientbound packets through the same User to avoid the analogous invalid CraftPlayer cast.
- Reject missing temporary User/channel rather than bypassing login authentication or swallowing the packet; ordinary CraftPlayer packet transport is unchanged.
- Add focused offline transport-selection regression tests; Maven test suite passes.

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
