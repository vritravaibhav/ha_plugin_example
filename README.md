# ha_plugin_example — Hardware-Accelerated Drawing Plugin (Vendor Brief)

> Reference implementation + contract for vendors building a **hardware-accelerated drawing plugin** for their own motherboard / panel SDK.

This repo contains:

- A **working Flutter example app** (`lib/main.dart`) that uses the canvas widget.
- A **reference plugin** at [`plugins/deltav100/`](plugins/deltav100) targeting the Nomivision **Delta V100** motherboard.
- The **Flutter-side API contract** that every vendor plugin **must** implement identically.

If you are the vendor — read this whole document, then go to [What you have to do](#-what-you-have-to-do).

---

## TL;DR for vendors

1. **Fork this repo.** Do not start fresh.
2. Add your plugin under `plugins/<your_plugin_name>/`.
3. Implement the native (Android) side however you want, using your own SDK.
4. Expose the **exact same Flutter API** as `plugins/deltav100/lib/deltav100.dart` — same class name, same parameters, same callbacks, same channel names, same payload shape.
5. Deliver: your **GitHub fork URL** + a **working APK** built from that fork.

The host app (`lib/main.dart`) must continue to compile and run **unchanged**, with only the `pubspec.yaml` plugin path swapped.

---

## What you have to do

### Mandatory

- [x] **Fork** `https://github.com/vritravaibhav/ha_plugin_example`.
- [x] Keep `lib/main.dart` untouched (or near-untouched — only the plugin import line may change).
- [x] Add your plugin folder under `plugins/<your_plugin_name>/`.
- [x] Match the Flutter contract in [§ Flutter contract (frozen)](#flutter-contract-frozen) **exactly**.
- [x] Deliver a **working APK** built from the latest commit on your fork.

### Out of scope

- iOS / desktop builds. The widget already returns `SizedBox.shrink()` on non-Android — keep it that way.
- Redesigning the host app UI.
- Persistence / networking / accounts.

---

## Repo layout

```
ha_plugin_example/
├── lib/
│   └── main.dart                       # Host example app — DO NOT redesign
├── plugins/
│   └── deltav100/                      # Reference plugin (your model lives next to this)
│       ├── lib/deltav100.dart          # The Flutter API contract (study this file)
│       └── android/src/main/java/com/hardwareAccelerated/deltav100/
│           ├── Deltav100Plugin.java
│           ├── Deltav100FbDrawingViewFactory.java
│           ├── Deltav100FbDrawingView.java
│           ├── Deltav100HardwareAccel.java
│           ├── Deltav100StrokeBridge.java
│           └── Deltav100Config.java
├── docs/
│   └── vendor_brief.pdf                # Same content as this README, printable
└── pubspec.yaml
```

---

## Flutter contract (frozen)

You may rename your package and your typedef, but **everything below is non-negotiable**: class name, parameter names, parameter order, types, channel names, view type, and payload schema.

### 1. The widget

The host app uses **one widget only**: `HardwareAcceleratedCanvas`.

```dart
class HardwareAcceleratedCanvas extends StatefulWidget {
  const HardwareAcceleratedCanvas({
    super.key,
    required this.penColor,
    required this.isRainbow,
    required this.isDashed,
    required this.strokeWidth,
    required this.isHighlight,
    required this.isMultiFingerEnabled,
    this.onStrokeStarted,
    this.onStrokeCommitted,
    this.gestureRecognizers,
  });

  final Color penColor;
  final bool isRainbow;
  final bool isDashed;
  final double strokeWidth;
  final bool isHighlight;
  final bool isMultiFingerEnabled;
  final VoidCallback? onStrokeStarted;
  final Deltav100StrokeCallback? onStrokeCommitted;
  final Set<Factory<OneSequenceGestureRecognizer>>? gestureRecognizers;
}

typedef Deltav100StrokeCallback = void Function(Map<String, dynamic> data);
```

| Parameter | Type | Meaning |
|---|---|---|
| `penColor` | `Color` | Current pen colour (ARGB passed down to native). |
| `isRainbow` | `bool` | Rainbow gradient stroke shader. |
| `isDashed` | `bool` | Dashed stroke pattern. |
| `strokeWidth` | `double` | Pen width in logical pixels. |
| `isHighlight` | `bool` | Translucent highlighter mode. |
| `isMultiFingerEnabled` | `bool` | Allow simultaneous multi-touch strokes. |
| `onStrokeStarted` | `VoidCallback?` | Fires once when the first pointer goes down. |
| `onStrokeCommitted` | callback | Fires on pointer-up with the full stroke payload (see [§ 3](#3-onstrokecommitted-payload)). |
| `gestureRecognizers` | set | Passed through to your native `AndroidView`. |

> You may rename the typedef (e.g. `MyPanelStrokeCallback`), but the **signature must stay** `void Function(Map<String, dynamic> data)` and the widget property must remain `onStrokeCommitted`.

### 2. Platform-view & channel identifiers (must match)

| What | Required value |
|---|---|
| Android platform view type | `fb_drawing_view_deltav100` |
| Stroke `EventChannel` | `com.demopaint.draw_app/hardware_stroke` |
| Framebuffer `MethodChannel` | `com.demopaint.draw_app/framebuffer` |

### 3. `onStrokeCommitted` payload

The `Map<String, dynamic>` you deliver on every stroke **must** contain these keys with these types — this matches what [`Deltav100StrokeBridge.java`](plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100StrokeBridge.java) emits today:

```jsonc
{
  "points":      [ { "x": <double>, "y": <double> }, ... ],  // view-space px
  "color":       <int>,        // ARGB
  "strokeWidth": <double>,     // logical px
  "dashed":      <bool>,
  "rainbow":     <bool>,
  "viewWidth":   <double>,     // native view width  (px)
  "viewHeight":  <double>,     // native view height (px)
  "pointer":     <int>         // Android pointerId
}
```

Additionally, your event stream must emit these two control messages exactly as the reference does:

```jsonc
{ "strokeStarted":   true }   // when first finger goes down
{ "requestBrushSync": true }  // when native surface (re)creates and needs brush state
```

### 4. `MethodChannel` surface (must match)

The framebuffer method channel must accept these methods with these argument shapes:

| Method | Arguments |
|---|---|
| `setDrawColor` | `{ color: int /* ARGB */ }` |
| `setEraserMode` | `{ enable: bool }` |
| `setStrokeSize` | `{ size: double }` |
| `setEraserSize` | `{ size: double }` |
| `setDashedStroke` | `{ enable: bool }` |
| `setRainbowMode` | `{ enable: bool }` |
| `setHighlightMode` | `{ enable: bool }` |
| `clearNativeCanvas` | — |

### 5. Host-app usage that must compile unchanged

After dropping your plugin in (only the `pubspec.yaml` dependency path changes), this snippet from `lib/main.dart` must build and run with **zero edits to the widget call site**:

```dart
HardwareAcceleratedCanvas(
  penColor: Colors.black,
  isRainbow: false,
  isDashed: false,
  strokeWidth: 20,
  isHighlight: false,
  isMultiFingerEnabled: true,
  onStrokeStarted: () { /* ... */ },
  onStrokeCommitted: (data) { /* ... */ },
  gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
    Factory<OneSequenceGestureRecognizer>(() => EagerGestureRecognizer()),
  },
)
```

---

## Native side — your call

You are **free to use your own native SDK / framebuffer driver / motherboard APIs**. We do not require you to copy our Nomivision `WhiteBoardSpeedup` integration. The only native-side constraints are:

- Register your `PlatformView` under the view type `fb_drawing_view_deltav100`.
- Open the two channels (`hardware_stroke` `EventChannel`, `framebuffer` `MethodChannel`) under the names in [§ 2](#2-platform-view--channel-identifiers-must-match).
- Emit stroke events with the payload shape in [§ 3](#3-onstrokecommitted-payload).

For reference, our existing Android implementation is split into the six Java files listed under [Repo layout](#repo-layout). You may mirror this structure or use anything you prefer.

---

## Acceptance criteria

Your delivery is accepted when **all** of these pass on your target hardware:

- [ ] Example app builds and installs with no edits beyond the plugin `path:` in `pubspec.yaml`.
- [ ] Touching the screen draws strokes with hardware acceleration (no Flutter-level rasterisation).
- [ ] Stroke width, colour, dashed, rainbow, highlight, and eraser modes all visibly work and respond to `MethodChannel` calls from Flutter.
- [ ] Multi-finger drawing works when `isMultiFingerEnabled = true`.
- [ ] `onStrokeStarted` fires on first pointer down.
- [ ] `onStrokeCommitted` fires on pointer-up with the payload schema in [§ 3](#3-onstrokecommitted-payload).
- [ ] Native FB is wiped within ~300 ms after pointer-up so the Flutter side can take over rendering seamlessly (or document your equivalent handoff behaviour).
- [ ] `clearNativeCanvas` clears the panel.

---

## Build & run (sanity check before forking)

```bash
flutter pub get
flutter run -d <your-android-device>
```

The reference plugin only renders on Android — on iOS / desktop the widget returns `SizedBox.shrink()` by design.

---

## Handover checklist

When you deliver, please send us:

1. **GitHub URL** of your fork (public, or invite us as collaborator).
2. **APK** (debug or signed release) built from the latest commit on that fork.
3. **Commit SHA** the APK was built from.
4. **Target panel / motherboard model + Android version** verified on.
5. **License notes** for any AAR / `.so` / proprietary SDK you bundle.
6. **One short demo video (15–30 s)** of the example app running on the panel.

---

## Reference files to study (in order)

1. [`plugins/deltav100/lib/deltav100.dart`](plugins/deltav100/lib/deltav100.dart) — the entire Flutter contract you must mirror.
2. [`plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100Plugin.java`](plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100Plugin.java) — channel + factory registration.
3. [`plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100StrokeBridge.java`](plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100StrokeBridge.java) — exact payload Flutter expects.
4. [`plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100FbDrawingView.java`](plugins/deltav100/android/src/main/java/com/hardwareAccelerated/deltav100/Deltav100FbDrawingView.java) — reference `SurfaceView` doing the FB drawing.
5. [`lib/main.dart`](lib/main.dart) — how the host app calls the widget. **Your plugin must make this file work unchanged.**

A printable version of this brief is at [`docs/vendor_brief.pdf`](docs/vendor_brief.pdf).

---

## Questions?

Open an issue on this repository, or reply to the email this link was attached to.
