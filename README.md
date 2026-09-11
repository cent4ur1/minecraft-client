# RottenApple Client

A dependency-free utility client for **Lunar Client 1.8.9 on macOS** — combat
and player modules with an external menu, loaded through Lunar's own JVM
arguments. No mod loader, no Forge, no Mixin, no extra tools.

> **Disclaimer.** This is cheat software. Using it on servers (Hypixel and
> others) risks bans, including permanent ones. It exists for education and
> singleplayer/anarchy-style play. Use it at your own risk.

## How it works

RottenApple is a pure-JDK Java agent. Lunar launches its game JVM with
dynamic attach disabled (`-XX:+DisableAttachMechanism`), so injecting into a
running game is impossible — instead the JVM loads the agent itself at
startup via `-javaagent` (the same method other macOS clients use). Once
loaded, the agent finds the game through reflection, polls input and game
state roughly every 50ms, and shows a native menu window next to the game.

## Install (2 minutes)

1. Download or build `RottenApple-Agent.jar` (see below) and keep it
   somewhere permanent — the path goes into Lunar, so don't move it after.
2. In the Lunar Client launcher, open **Settings** and find the
   **JVM / Java arguments** field.
3. Add exactly this as **one** argument (replace the path with yours):

```text
-javaagent:/Users/macintosh/Documents/dev/RottenApple/dist/RottenApple/RottenApple-Agent.jar
```

4. Launch Lunar **1.8.9** normally — menu, singleplayer, or server, any stage.
5. The **RottenApple Client** window appears on its own. Press **P** or
   **INSERT** (`Fn+Return` on MacBooks) in game to toggle it.

To verify: with the game running, execute `./launch.sh` from the project
folder — it reports `agent is in this JVM's startup flags … nothing to
inject` when everything is wired correctly.

To uninstall: delete the `-javaagent` line and relaunch.

## Menu

- **Client tab** — Toggle Sprint, Fullbright, FPS readout, menu opacity,
  accent color (live, persisted).
- **Modules tab** — all modules below with their full Raven-style settings,
  per-module keybinds (click *Bind*, press a key, ESC clears), and toggles.
- **Players tab** — live player table (tag, distance, armor) fed by the
  Nametags pipeline. Enable the Nametags module to populate it.
- Settings persist to `~/.rottenapple.cfg` automatically.

## Modules

| Module | What it does |
|---|---|
| Auto Clicker | Target-CPS clicking with Raven's exhaust simulation, weapon-only, break-blocks, inventory clicking. Real OS-level clicks, so Lunar's CPS overlay counts them (needs a macOS Accessibility grant, see below). |
| Jump Reset | Jump-on-hit combo resets with chance/aim/movement gates. |
| Velocity | Horizontal/vertical knockback scaling with chance and S-key gates. |
| Auto Block | Vanilla-mode sword blocking: predictive timing, hold/cooldown windows, Once/Always out-of-range unblocking, manual block. |
| Auto Tool | Best-hotbar-tool swapping with hover/activation delays, whitelists/blacklists, swap-back. |
| AutoBlockin | Bed-defense auto-placer: slot picking, roof/side target search, Bed checks, hold-key activation. Places when your real aim is on a valid face. |
| Bridge Assist | Edge sneak with unsneak/sneak-on-jump delays and bridging conditions. |
| Fast Place | Place-delay override with hold-time gate and blacklists. |
| Nametags | Name/health/distance/armor data pipeline feeding the Players tab. |

Dropped as non-portable without render/packet hooks (documented in code):
Chams visuals, in-world Nametag drawing, Autoblock Lag mode, FastPlace packet
cancel, AutoTool item spoofing, rotation-based aim assists.

## macOS notes

- `INSERT` = `Fn+Return` on MacBook keyboards (or just use `P`).
- First click with Auto Clicker may prompt for **Accessibility** access
  (System Settings → Privacy & Security → Accessibility). Without the grant,
  clicks still land in game but Lunar's overlay won't count them — the status
  log says which path is active.
- A fullscreen game hides the menu window behind it; use windowed mode while
  setting up, or find it via Mission Control.
- Lunar must be **fully quit** (not just window-closed) for JVM-argument or
  agent-jar changes to take effect — the jar is read once at boot.

## Build from source

Requirements: any JDK 17+ and Gradle 9 (`gradle` on PATH). Zero
dependencies — no repositories, no downloads beyond Gradle itself.

```bash
gradle build
# → dist/RottenApple/RottenApple-Agent.jar
#   dist/RottenApple/rottenapple-launcher.jar
#   dist/RottenApple/launch.sh
```

Copy the `dist/RottenApple` folder anywhere; the `-javaagent` path must point
at the agent jar inside it.

## Doctor script

`launch.sh` validates a running setup and collects diagnostics:

```bash
./launch.sh          # detect Lunar 1.8.9, verify agent placement, offer fixes
./launch.sh --logs   # save a debug bundle (log + system snapshot) + copy it
```

Useful files: `RottenApple-launcher.log` (script runs, next to `launch.sh`),
`~/RottenApple-status.log` (agent lifecycle, module errors, key presses),
`~/.rottenapple.cfg` (all settings).

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| No menu after launch | JVM argument missing/misplaced (must be *before* the main class — `launch.sh` checks this), wrong Lunar profile/version, or Lunar not fully restarted. Check `~/RottenApple-status.log` for `agent build … loaded`. |
| Lunar fails to start with agent error | Agent jar moved/deleted after adding the argument. Fix the path or remove the line. |
| Lunar CPS overlay shows nothing | Accessibility grant missing (see above); confirm the Robot path in the status log. |
| A module misbehaves | Status log names failing modules (`module X tick failed`) and auto-disables them. BridgeAssist logs every sneak engage/release with edge offsets. |
| Binds don't stick | Saved automatically; ESC clears a bind during capture. |

## Credits

Module behavior ported from Raven BS (Forge 1.8.9 reference sources, see
`examples/`). Injection approach follows the standard `-javaagent` pattern
used by macOS Lunar utility clients.
