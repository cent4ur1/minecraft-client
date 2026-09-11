# AGENTS.md — RottenApple contributor guide

> Read this file before touching anything. It documents the architecture,
> the hard constraints that shaped it, and the conventions every change
> must follow. The build has no test suite and no compiler on the user's
> machine is guaranteed — correctness comes from following these rules.

## 1. What this project is

RottenApple is a dependency-free utility client for **Lunar Client 1.8.9 on
macOS**. It ships as a pure-JDK Java agent (`RottenApple-Agent.jar`) loaded
via Lunar's **JVM arguments** field:

```
-javaagent:/absolute/path/to/RottenApple-Agent.jar
```

There are intentionally **zero runtime dependencies**: no Weave, no Forge,
no Mixin, no mappings artifacts, no JNA. Everything compiles against the
plain JDK (`java` plugin only, no repositories, no dependencies in
`build.gradle.kts`). Game interaction happens exclusively through
reflection against MCP 1.8.9 names.

Combat/player modules are behavior ports of **Raven BS** (`examples/`,
reference only — never built, never shipped).

## 2. Repository layout

```
RottenApple/
├── build.gradle.kts                 # java-only, dual jars + dist/, see §5
├── settings.gradle.kts              # rootProject.name only
├── gradle.properties
├── launch.sh                        # terminal doctor/launcher, see §4
├── src/main/java/
│   ├── com/rottenapple/agent/
│   │   └── RottenAppleAgent.java    # premain/agentmain, bootstrap + poll loop
│   ├── com/rottenapple/bridge/
│   │   ├── GameBridge.java          # game discovery, menu toggle poll, base effects
│   │   └── MC.java                  # full reflection helper library for modules
│   ├── com/rottenapple/module/
│   │   ├── Module.java              # base: enable/disable/bind/onTick
│   │   ├── ModuleManager.java       # registry, tickAll, handleKeybinds
│   │   ├── ConfigManager.java       # ~/.rottenapple.cfg persistence
│   │   ├── Setting.java             # base (name + visible flag)
│   │   ├── SliderSetting.java       # numeric + mode flavors
│   │   ├── ButtonSetting.java       # toggle + method-button flavors
│   │   ├── KeySetting.java          # hold-to-activate key (mouse codes >= 1000 too)
│   │   ├── ColorSetting.java        # RGB(A)
│   │   ├── BlockListSetting.java    # registry-id list, :* wildcards
│   │   ├── ItemListSetting.java     # BlockListSetting + stack matching
│   │   ├── DescriptionSetting.java  # label row
│   │   ├── GroupSetting.java        # section header
│   │   └── impl/
│   │       ├── combat/AutoClicker.java, JumpReset.java, Velocity.java, Autoblock.java
│   │       ├── player/AutoTool.java, AutoBlockin.java, BridgeAssist.java, FastPlace.java
│   │       └── render/Chams.java, Nametags.java  # @Deprecated stubs, see §8
│   ├── com/rottenapple/client/
│   │   ├── RottenAppleConfig.java   # client-wide toggles (opacity, accent, sprint…)
│   │   ├── RottenAppleMod.java      # @Deprecated Weave-era shim, do not revive
│   │   ├── gui/RottenAppleGui.java  # Swing menu (Client/Modules/Players tabs)
│   │   └── listener/GameOverlayListener.java  # @Deprecated Weave-era shim
│   ├── com/rottenapple/util/
│   │   ├── RavenUtil.java           # pure-java math/string ports (no game imports)
│   │   └── AntiBotUtil.java         # bot checks, first-seen tracking (off by default)
│   └── rottenapple/launcher/
│       └── Injector.java            # reflection-only Attach API helper (see §4)
├── src/main/resources/
│   └── rottenapple.mixins.json      # stale Weave-era file, excluded from the jar
├── examples/                        # reference sources only (Raven BS, OpenMyau)
└── dist/RottenApple/                # build output: agent jar + injector jar + launch.sh
```

## 3. Architecture

```
Lunar JVM (Zulu 17, attach disabled)
  └─ -javaagent → RottenAppleAgent.premain
       ├─ bootstrap thread: GameBridge.waitForGame (finds Minecraft class,
       │   exact MCP name first, then signature scan for static Self getMinecraft())
       ├─ Swing menu (RottenAppleGui, Metal LAF, EDT)
       └─ 50ms poll loop:
            GameBridge.refresh → menu toggle (P/INSERT) → applyEffects
            → ModuleManager.handleKeybinds → ModuleManager.tickAll
```

Key facts:

- The agent runs in the **system (app) classloader**, not the game loader.
  It never extends game classes. All game access is reflection via `MC`
  (`com.rottenapple.bridge`), which resolves MCP 1.8.9 names lazily and
  degrades to safe defaults on any failure. **Never let a module throw out
  of `onTick`** — `tickAll` disables the offending module and logs it.
- Modules run on the **poller thread (~50ms), not the Minecraft main
  thread**. Wall-clock schedulers (AutoClicker CPS) and edge detectors
  (hurtTime rising edges, not exact `== maxHurtTime`, which a 50ms poll can
  skip) are written for this. Never assume tick-phase alignment with the game.
- The menu is a **separate native window** (always-on-top Swing), not an
  in-game GuiScreen. Subclassing game GUI classes from an attach-time agent
  is blocked by JPMS module barriers on modern Java — don't try.

## 4. Injection model (read before "fixing" the launcher)

- **Primary path: `-javaagent` in Lunar's JVM arguments.** The JVM loads the
  agent at startup (`premain`). This is also how other macOS clients do it.
- **Dynamic attach is impossible against Lunar, by design.** Lunar launches
  its game JVM with `-XX:+DisableAttachMechanism`; every attach attempt ends
  in `AttachNotSupportedException`. Do not "fix" this — it cannot be fixed
  from our side.
- `launch.sh` is therefore a **doctor, not an injector**, on Lunar: it
  detects the game JVM (`jps`, `ps` fallback), prefers the 1.8.9 *game* JVM
  over launcher helpers, validates the target (java cmdline, hsperfdata,
  arch match, `DisableAttachMechanism` presence), verifies `-javaagent`
  **placement** (JVM ignores flags placed *after* the main class —
  `arg position < main-class position` is checked explicitly), prints the
  exact argument, and offers clipboard copy. Dynamic attach is still
  attempted (with retries) for clients that allow it (vanilla/Forge).
- `rottenapple.launcher.Injector` uses the Attach API purely through
  reflection so it compiles on any JDK. Exit codes: `0` ok, `1` usage,
  `2` no Attach API (launching JVM is a JRE), `3` target refused
  (retryable), `4` other failure (fatal).

## 5. Build

```bash
gradle build        # → dist/RottenApple/{RottenApple-Agent.jar,rottenapple-launcher.jar,launch.sh}
```

- Gradle 9 + system JDK 17+ recommended. No wrapper checked in on purpose.
- `sourceCompatibility = targetCompatibility = 1.8`: the agent must load on
  Lunar's JRE 17, and Java 8 bytecode runs everywhere. **Java 8 language
  level only** — no `var`, no switch expressions, no `List.of`, no text
  blocks. Lambdas are fine (they're Java 8).
- `dist/` is assembled by `build` (jars unversioned for stable `-javaagent`
  paths; `launch.sh` copied with `0755`).
- **Bump `RottenAppleAgent.BUILD` on every behavior change.** It prints as
  `agent build <X> loaded…` in the status log and is the only reliable way
  to confirm which build a user is running. Never debug without it.

## 6. Porting rules (Raven BS → RottenApple)

Raven is a Forge mod. Our agent has no events, no packets, no mixins.
 translation table:

| Raven mechanism | Our equivalent |
|---|---|
| `@SubscribeEvent` tick/update handlers | `onTick()` per 50ms poll |
| `KeyBinding.setKeyBindState / onTick` | same, via `MC` — works, game consumes identically |
| `Minecraft.rightClickDelayTimer` / `leftClickCounter` fields | direct field set via `MC` (AutoClicker zeroes the latter while clicking — this is what Raven's DelayRemover does; without it CPS caps at ~2) |
| `playerController.*` actions (`attackEntity`, `windowClick`, `syncCurrentPlayItem`, `onPlayerRightClick`) | same calls via `MC`, which falls back to arity matching when MCP declares narrower types (`EntityPlayerSP` etc.) |
| `Mouse.buttons` ByteBuffer poke | same via `MC.pokeMouseButton` (unnamed modules allow it) |
| `KeySetting` hold-to-activate | `module/KeySetting.java`, polled in `onTick` |
| `sendMessage` / chat output | status log instead (`RottenAppleAgent.status`) |
| `ModuleManager.killAura / bedAura / antiKnockback / relationships`, `LongJump.stopVelocity`, `Freecam.freeEntity` | absent by design — **null-guard every cross-module reference** (fresh-Raven-equivalent behavior) |
| Raven `Settings` client defaults (stick weapon, plain-number health) | hardcoded with a comment citing the Raven default |
| `SimulatedPlayer` (1284-line physics sim) | motion-offset box prediction + same edge math (documented) |
| Scroll/slot-cancel Forge events | hotbar-change detection (documented) |
| C02-attack / C08-place packet hooks | observed-damage edges / held-stack-size decrease (documented) |

**Drop rule:** anything needing packet interception (Autoblock Lag ping-spoof,
FastPlace air-place cancel), mixin-added fields (AutoTool `spoofItem`),
rotation spoofing (aim assists, BridgeAssist pre-place), or render-thread GL
(Chams visuals, Nametags draw/vanilla-hide) is **dropped, not approximated** —
remove the setting/module and say so in the reply. Tombstone `@Deprecated`
stubs live in `impl/render/` so references fail loudly.

Every port keeps, in order: identical settings (names/defaults/ranges),
`onEnable`/`onDisable` resets **plus physical key release** (Raven mutated
events so it never held state; we hold real key states — always release on
disable), `guiUpdate` visibility rules, `getInfo`, then logic. Document any
deviation in the class comment.

## 7. Reflection rules (`MC.java`)

- All lookups lazy-cached (`cls`/`fld`/`mth` maps). `fld` tries `getField`
  then `setAccessible` `getDeclaredField`. `mth` tries exact signature, then
  **arity fallback** — game classes often declare narrower parameter types.
- Every helper catches `Throwable` and returns a safe default. Callers must
  remain correct under all-null returns.
- Prefer existing helpers over new one-off reflection. New helpers go in
  `MC`, not in modules.
- Class names as literals (`net.minecraft.*`, `org.lwjgl.*`) are fine —
  string literals are not dependencies. **No game/LWJGL imports anywhere.**

## 8. GUI rules (`RottenAppleGui.java`)

- **Metal LAF, set before first frame construction** (`ensureLookAndFeel`
  runs at the top of `showGui()`/`toggle()` runnables — `super()` triggers
  LAF init, so the constructor is already too late). Lunar's module flags
  break Aqua with `IllegalAccessError`; never rely on the default LAF.
- **Never derive fonts from components** (`getFont().deriveFont` NPEs when
  LAF defaults are broken — this exact crash happened twice). Always
  `new Font(...)`.
- Toggles are custom `ToggleSquare`s (accent fill when on), never
  `JCheckBox` ticks. Sliders use `ThickSliderUI`. Accent comes from
  `RottenAppleConfig.accentRgb` live at paint time.
- `refreshDetail()` rebuilds the settings panel — call it only via
  `refreshDetailIfNeeded()` (visibility-signature check) from high-frequency
  listeners, or sliders will jitter and lose drags.
- Key capture uses a `KeyEventDispatcher` (frame `KeyListener`s miss keys
  when buttons hold focus). ESC **clears** the bind; captures auto-disarm
  after 8s. AWT→LWJGL mapping table covers keyboard only.
- All game reads on the poller thread; all Swing work on EDT
  (`invokeLater`). `theme()` must skip `GradientPanel` (it would paint over
  the gradient).

## 9. Persistence, logs, diagnostics

- `~/.rottenapple.cfg` (Java `Properties`): `client.*` keys plus
  `module.<Name>.enabled|bind|<profileKey>`. Lists join with `;;`, colors as
  `r,g,b,a`. Saved on toggles/setting changes/binds.
- Agent lifecycle → `~/RottenApple-status.log` (`status()` writes console +
  file; module tick failures disable just that module with a logged reason;
  BridgeAssist logs sneak engage/release with edge offsets).
- `launch.sh` doubles as diagnostics: full runs append to
  `RottenApple-launcher.log`; `./launch.sh --logs` builds a
  `RottenApple-logs-<ts>.tar.gz` (launcher log + `jps`/`ps`/hsperfdata/java
  snapshot) and offers clipboard copy **first** — if taken, no further log
  questions.
- macOS specifics: INSERT is `Fn+Return`; Lunar CPS overlay counts real
  OS-level clicks, so AutoClicker uses `java.awt.Robot` press+release pairs
  (falls back to synthetic `onTick` with a status note) — needs an
  Accessibility grant or it silently falls back; fullscreen hides the Swing
  menu (windowed mode for setup).

## 10. Testing protocol (no test suite exists)

1. `gradle build` must print `BUILD SUCCESSFUL`.
2. Fully quit Lunar (Dock icon gone — the JVM reads the agent once at boot),
   relaunch 1.8.9, wait ~30s past game load.
3. Check `~/RottenApple-status.log` starts with the expected
   `agent build <X>` line. Anything else is a stale jar — stop and fix paths.
4. Test in singleplayer first: menu toggle (P/INSERT), one module at a time.
5. Failures to report: status-log lines around the repro + `--logs` bundle.

## 11. Known limitations (do not "fix" without a design discussion)

- No render-thread hooks → no ESP/Chams visuals, no in-world Nametags, no
  vanilla-tag hiding. Nametags data feeds the Players tab instead.
- No packet interception → no ping-spoof/lag modes, no packet cancels.
- No bytecode/mixins → no accessor-added fields, no event injection.
- Poller thread (~50ms) instead of tick-phase callbacks; exact-tick
  equalities must be written as rising edges (see Velocity/JumpReset).
- `java.awt.Robot` needs focus + Accessibility grant; Lunar overlay CPS
  depends on it.
- `launch.sh` prompts assume an interactive terminal.

## 12. Contribution hygiene

- One behavior change per change-set; bump `BUILD`.
- New files over edits where cleaner, but **never create `*.md` unless
  asked**; never add dependencies, repositories, or plugins.
- When editing, use unique multi-line anchors (a lone `}` once corrupted a
  file — verify with grep/read after risky edits).
- User pastes terminal output and logs as ground truth. When a diagnosis is
  uncertain, say what would confirm it and which file/line to watch, rather
  than guessing.
