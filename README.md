# Inkfield

A vector sketching app for Android tablets and phones: an infinite canvas where every stroke stays editable. Fully offline and free, with no accounts or network access.

## Install

1. Open the [latest release](../../releases/latest) on your device.
2. Download `Inkfield-0.1.x.apk` and open it. Android will ask you to allow installs from your browser or file manager the first time.
3. Newer builds install over older ones and keep your drawings.

Every push to `main` builds a new APK automatically (see the Actions tab).

## Phase 1 features

- Infinite canvas: pan, zoom and rotate with two fingers (snaps at 100% and right angles)
- Seven brushes: Pen, Fountain Pen, Dynamic Pen (pressure), Fixed Width, Wire, Marker, Airbrush
- Every stroke keeps its raw input, so brush, size, opacity, smoothing and color can change later
- Tool Wheel with 8 configurable slots, size/opacity/smoothing presets, undo/redo
- HSV and RGB color picker, hex input, 8-color palette
- Slice eraser (also the pen's eraser end)
- Lasso and tap selection; move, scale, rotate, pinch, flip, duplicate, delete, restyle
- Layers: automatic per-tool layers or manual; visibility, lock, opacity, rename, duplicate, merge, reorder
- Gallery with autosave, rename, duplicate and Recently Deleted
- Export PNG, JPG (72–600 ppi) and SVG; save to device or share
- Gestures: two-finger tap undo, three-finger tap redo, hold to select, palm rejection, motion prediction
- Keyboard: Ctrl+Z, Ctrl+Shift+Z / Ctrl+Y, Ctrl+D, Delete, Esc

## Building locally

Open the project in Android Studio (JDK 17), or run `./gradlew assembleRelease`.

The `signing/` folder holds the key that signs every build, so updates install over each other. Because this repository is public, anyone could sign an APK with it; only install builds from this repository's releases.
