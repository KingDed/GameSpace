# GameSpace (native Android app)

This is an Android app, not a website. It cannot run on web hosting.

## Get the APK without a PC
1. Create a free GitHub repo and upload all files in this folder (keep the .github folder).
2. Open the repo's Actions tab -> "Build APK" -> wait for the green check.
3. Open the finished run -> Artifacts -> download GameSpace-debug-apk.
4. Unzip it, install app-debug.apk, then open Game Space and grant overlay + notification permissions.

## New: Features tab
The panel now has HOME and FEATURES tabs. FEATURES lists 618 features (assets/features.tsv), searchable, by category. Everything there is saved to a profile (SharedPreferences); only the original Home toggles change the system.

## With a PC
Open this folder in Android Studio and press Run.

## Using it in a game
1. Open Game Space, tap START PANEL, then tap a game tile (launching from here applies your launch settings).
2. Swipe/tap the red edge handle over the game to open the panel, go to FEATURES.
3. Features marked "live" work over the game: Live overlays (Crosshair, Auxiliary line, Counter, Stopwatch, Data panel, Clock), FPS counter overlay (shows display refresh rate, not the game's own FPS), Ping overlay, Night tint, Screen dim overlay, Temp alert.
4. Launch-only: RAM clean on launch, Do-not-disturb, Mute notifications.
5. Shoulder triggers: tap "Enable shoulder triggers (Accessibility)" on the main screen and switch it on. In FEATURES > Live overlays turn on Shoulder triggers, then Edit trigger markers and drag L and R onto the on-screen buttons. Turn editing off when done. Gamepad L1/R1/L2/R2 keys, or the volume keys (Touch & Triggers > Volume keys as triggers), then tap the markers' spots.
Everything not marked "live" is still only saved to the profile.
