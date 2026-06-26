# Relic Rush 3D (Godot 4)

A **true 3D** two-lane city endless runner, built in **Godot 4.3**. Real
perspective camera, directional sunlight with **shadows**, 3D materials, fog,
and a day/night + weather cycle. The whole world is assembled in code from
Godot primitive meshes (`main.gd`), so there are **no imported art assets** —
the project is fully original and ready for you to drop in real 3D models later.

> **Not a Temple Run clone.** *Temple Run* is a trademark of Imangi Studios.
> This is an independent game in the endless-runner genre — its own name, art,
> characters and design. Keep it that way before publishing.

## Controls

- **Swipe left / right** — switch lane
- **Swipe up** (or tap) — jump barriers
- **Swipe down** — slide under sign gantries
- Dodge **cars**, collect **coins**
- Keyboard (desktop testing): arrow keys + space

Opens with an auto-playing **demo** that demonstrates each move; tap to play.
A 10-second on-screen guide reinforces the controls on your first run. The city
cycles through **day, sunset, night, dawn, rain, snow and fog** (random each run,
cross-fading during play); building windows light up at night.

## Run it

1. Install **Godot 4.3** (standard build) from https://godotengine.org/download
2. Open the editor → **Import** → select this folder's `project.godot`
3. Press **Play** (F5). Works on desktop with mouse/keyboard.

## Replace the blockout art with real 3D models

Everything visual is built in `main.gd` via helpers (`_box`, `_cyl`) and
`_build_player()`, `_make_car()`, `_make_building()`, `_add_obstacle()`. To use
real models, import `.glb`/`.gltf` files and instance them in those functions
instead of the primitive meshes — the gameplay code stays the same.

## Build an Android APK / AAB

One-time setup in the editor:

1. **Editor → Manage Export Templates → Download** the 4.3 templates.
2. **Editor → Editor Settings → Export → Android**: set the **Android SDK path**
   (and a debug keystore; the editor can use the standard `~/.android/debug.keystore`).
3. **Project → Export** → the **Android** preset is already defined in
   `export_presets.cfg` (package `com.relicrush.game`).
   - **Export Project** → `.apk` for sideloading/testing.
   - For Google Play, enable **Use Gradle Build**, switch the export format to
     **AAB**, and sign with your own **upload keystore** (not the debug one).

### Publishing checklist (Google Play)

- Change `package/unique_name` in `export_presets.cfg` to your own unique id
  (can't change after first upload).
- Create an upload keystore (`keytool -genkey -v -keystore upload.jks -keyalg RSA
  -keysize 2048 -validity 10000 -alias upload`) and sign the release AAB with it.
- Play Console (US$25 one-time): store listing, screenshots, 512×512 icon,
  content rating, data-safety form (this game collects no data), privacy policy.

## Project layout

```
project.godot          – engine config (mobile renderer, portrait)
main.tscn              – root scene (one Node3D running main.gd)
main.gd               – the entire game: world build, simulation, UI, weather
export_presets.cfg    – Android export preset
icon.svg              – app icon
```
