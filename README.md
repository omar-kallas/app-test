# Inkfield

A vector sketching app for Android tablets and phones: an infinite canvas where every stroke stays editable. Fully offline and free, with no accounts or network access.

## Install

1. Open the [latest release](../../releases/latest) on your device.
2. Download `Inkfield-0.1.x.apk` and open it. Android will ask you to allow installs from your browser or file manager the first time.
3. Newer builds install over older ones and keep your drawings.

Every push to `main` builds a new APK automatically (see the Actions tab).

## Features

- **Canvas:** infinite, with pan, zoom and rotate (snapping optional); an optional artboard for exact sizes
- **Brushes:** pen, fountain, dynamic, fixed width, wire, marker, airbrush, soft/hard pencil, watercolor, dotted, fill; a stamp-brush library and a brush editor; brush packs as files
- **Editable strokes:** every stroke keeps its raw input, so brush, size, opacity, smoothing and color can change later
- **Tools:** Slice, Hard/Soft Mask (non-destructive), Nudge, Select, Text, Color picker, Pan; tool wheel or toolbar
- **Color:** swatch wheel, HSV/RGB, hex, multiple palettes, harmonies, palette strip with mixing
- **Precision:** grids (incl. 1/2/3-point perspective), snapping, shape guides, shape recognition, measurement with units and drawing scale
- **Selection:** lasso or tap, move/scale/rotate/distort, flip, duplicate, group, lock, restyle, exact sizes
- **Layers:** automatic per-tool or manual; visibility, lock, opacity, rename, duplicate, merge, reorder
- **Import:** photos, camera, image and PDF files, clipboard; object library (shapes, arrows, marks, people, vehicles, 1:100 furniture) and your own packs
- **Gallery:** folders with notes, sorting and grouping, Recently Deleted, backups, .inkfield files, optional copy to a device folder
- **Export:** PNG, JPG, SVG, PDF (vector or flattened), PSD with layers, DXF, .inkfield — whole drawing, artboard, selection or screen
- **Settings:** finger action, configurable multi-finger taps and stylus button, hold-to-select, editable keyboard shortcuts, interface size

## Building locally

Open the project in Android Studio (JDK 17), or run `./gradlew assembleRelease`.

The `signing/` folder holds the key that signs every build, so updates install over each other. Because this repository is public, anyone could sign an APK with it; only install builds from this repository's releases.
