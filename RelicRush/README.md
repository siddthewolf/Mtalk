# Relic Rush

An original 3-lane **endless runner** for Android, written in Kotlin with a
`SurfaceView` + `Canvas` render loop. All graphics are drawn procedurally —
there are **no image, audio, or font assets** from any third party — so the game
is 100% original work that you can publish and monetize.

> **Note on "Temple Run":** *Temple Run* is a registered trademark and
> copyrighted game owned by Imangi Studios. This project does **not** use its
> name, characters, art, or level design. It is an independent game in the same
> *genre* (the endless-runner genre is not protected). Keep it that way: do not
> add the "Temple Run" name, its characters, or copied art before publishing.

## Gameplay

- A skater auto-rolls down a **two-lane** city street (pseudo-3D perspective).
- **Swipe left / right** — switch lane (clean, deliberate swipes; no jittery drag)
- **Swipe up** (or tap) — jump over orange **road barriers**
- **Swipe down** — slide under **sign gantries**
- **Cars** block a lane — switch lanes to dodge them
- Most hazards span both lanes, so the game is mostly **timing jumps and slides**;
  lane-changes are the rarer move
- Obstacle types and spacing are **randomized**; collect **coins** for points
- Speed starts slow and ramps up gradually; high score is saved locally
- The game opens with an **auto-playing demo** that demonstrates each move —
  tap any time to start your own run
- A 10-second in-game guide reinforces the controls on your first run

## Atmosphere

The city cycles through randomized environments while you play — clear day,
sunset, night (with stars, a moon and **lit building windows**), dawn, **rain**,
**snow**, and **fog** — cross-fading every ~20–30 seconds. Each run starts on a
random one.

## Project layout

```
app/src/main/java/com/relicrush/game/
  MainActivity.kt   – fullscreen/immersive host activity
  GameView.kt       – game state, simulation, projection & all rendering
  GameThread.kt     – the render/update loop thread
  Player.kt         – runner: lane changes, jump physics, slide
  Models.kt         – GameState, Obstacle, Coin, Pillar
app/src/main/res/   – icon (adaptive vector), theme, strings
```

## Build & run

You need **Android Studio** (or the Android SDK + JDK 17). The Gradle wrapper is
included, so no separate Gradle install is required.

```bash
# Debug APK (install on a device/emulator)
./gradlew assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk

# Open in Android Studio instead: File > Open > select the RelicRush folder
```

`compileSdk`/`targetSdk` = 34, `minSdk` = 26 (Android 8.0+).

## Publishing to Google Play

1. **Rename it as yours.** In `app/build.gradle.kts` change `applicationId`
   to something globally unique you control (e.g. `com.yourstudio.relicrush`).
   The package id can never be changed after the first upload. Optionally change
   the display name in `res/values/strings.xml`.

2. **Create an upload keystore** (keep it safe — losing it means you can't
   update the app):

   ```bash
   keytool -genkey -v -keystore upload-keystore.jks \
     -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```

3. **Wire signing into the release build.** Create `keystore.properties` (it's
   git-ignored) in the project root:

   ```
   storeFile=/absolute/path/to/upload-keystore.jks
   storePassword=********
   keyAlias=upload
   keyPassword=********
   ```

   Then add a `signingConfigs` block in `app/build.gradle.kts` that reads it and
   reference it from `buildTypes.release`. (See Android's "Sign your app" docs.)

4. **Build the release App Bundle** (the format Play requires):

   ```bash
   ./gradlew bundleRelease
   # -> app/build/outputs/bundle/release/app-release.aab
   ```

5. **In the Google Play Console** (one-time US$25 developer registration):
   - Create the app, upload the `.aab` to a testing track first.
   - Provide store listing: title, short/full description, screenshots
     (phone screenshots are mandatory), a 512×512 icon, and a 1024×500
     feature graphic.
   - Complete the **content rating** questionnaire, **data safety** form
     (this app collects no data — see `PRIVACY.md`), target audience, and
     ads declaration (this build has no ads).
   - Add a **privacy policy URL** (host `PRIVACY.md` somewhere public).
   - Roll out to internal testing, then production.

## Ideas for v2

- Sound effects / music (add your own or royalty-free, properly licensed assets)
- Power-ups (magnet, shield, score multiplier), curved paths and turns
- More obstacle variety and themed environments
- Play Games Services leaderboards
- Optional rewarded ads (AdMob) for revenue
