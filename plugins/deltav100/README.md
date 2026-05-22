# deltav100

Flutter plugin for the Delta V100 motherboard hardware-accelerated drawing
backend.

This package mirrors the `hikvision` / `langov1` / `mtk9679` plugin shape so the
app can switch between hardware backends while keeping the same exported
`HardwareAcceleratedCanvas` widget and the same event/method channel contract:

- Event channel: `com.demopaint.draw_app/hardware_stroke`
- Method channel: `com.demopaint.draw_app/framebuffer`
- Platform view type: `fb_drawing_view_deltav100`

Native rendering is implemented with `com.nomivision.sys.WhiteBoardSpeedup`
from `WhiteBoardSpeedupLib.jar` (bundled under `android/libs`) which exposes the
accelerated framebuffer bitmap used by the Delta V100 panel.

Adapted from the `BUILD-32-DELTA-HA-2` branch (`RendLibSurfaceView`).
