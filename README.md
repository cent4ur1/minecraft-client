# RottenApple Client

Based on **`Myau-250910`**, with added features and improvements not found in the original, focused on expanding and refining the original Myau client.

Includes the ported combat module **`Displace`** (from `raven-bS-beta` `keystrokesmod.module.impl.combat.Displace`), adapted to this client's module/event system (`myau.module.modules.Displace`).

> Note: internal code identifiers (`myau` package, `myau` mod id, `./config/Myau/`, `openmyau.accounts.json`) were intentionally left unchanged so existing mixins, configs, and saved accounts keep working. Only user-facing branding (client name, `mcmod.info`, output jar name) was renamed to RottenApple.

## Requirements

- JDK 8 (the build uses a Gradle toolchain for Java 8; a newer JDK running Gradle can auto-provision it via the Foojay resolver)
- Minecraft 1.8.9 + Forge `1.8.9-11.15.1.2318-1.8.9` (resolved automatically by Loom)
- No Minecraft launcher account needed for building

## Building

```bash
./gradlew build
```

Output jars:

- `build/libs/RottenApple-<version>.jar` — the obfuscated/remapped mod jar (use this in your `mods` folder)
- `build/libs/RottenApple-<version>-without-deps.jar` and `build/intermediates/*` — intermediate artifacts

To force a clean rebuild:

```bash
./gradlew clean build
```

## Running from source (dev client)

```bash
./gradlew runClient
```

On macOS the build already strips `-XstartOnFirstThread` for the `client` run config (see `build.gradle.kts`).

## Installing the compiled jar

1. Install Minecraft 1.8.9 with Forge `11.15.1.2318`.
2. Copy `build/libs/RottenApple-<version>.jar` into `.minecraft/mods/`.
3. Launch the Forge 1.8.9 profile.

## Displace module

- Location: `src/main/java/myau/module/modules/Displace.java`
- Category: Combat (registered in `myau/Myau.java` and `myau/ui/ClickGui.java`)
- Modes: `Offset` (fixed yaw offset left/right) and `Void` (scans for void columns and displaces toward them)
- Settings: `mode`, `yaw-offset`, `scan-radius`, `delay`, `direction`, `find-void`, `blink`, `ignore-one-block-wall`, `ignore-teammates`, `only-knockback-items`, `override-attack`, `render-arrow`, `weapon-only`
- Port notes: Raven's `LagRequest` blink is emulated with `Myau.lagManager.setDelay(...)`; `ClientRotationEvent` is emulated with `UpdateEvent` PRE; Raven DEBUG-only void visualisation was omitted; override-attack is a simplified flick/attack/restore state machine.

## Contributing

You can open an issue or submit a pull request to help improve RottenApple Client.

If you’re interested in co-developing or have questions, feel free to reach out:
