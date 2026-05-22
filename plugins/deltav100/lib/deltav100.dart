import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const String _kDeltav100ViewType = 'fb_drawing_view_deltav100';
const String _kHardwareStrokeEventChannel =
    'com.demopaint.draw_app/hardware_stroke';

typedef Deltav100StrokeCallback = void Function(Map<String, dynamic> data);

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

  @override
  State<HardwareAcceleratedCanvas> createState() =>
      _HardwareAcceleratedCanvasState();
}

class _HardwareAcceleratedCanvasState extends State<HardwareAcceleratedCanvas> {
  static const EventChannel _strokeChannel = EventChannel(
    _kHardwareStrokeEventChannel,
  );
  static const MethodChannel _framebufferChannel = MethodChannel(
    'com.demopaint.draw_app/framebuffer',
  );

  StreamSubscription<dynamic>? _subscription;

  Future<void> _syncBrushToNative() async {
    if (defaultTargetPlatform != TargetPlatform.android) return;
    try {
      await _framebufferChannel.invokeMethod<void>(
        'setDrawColor',
        <String, dynamic>{'color': widget.penColor.toARGB32()},
      );
      await _framebufferChannel.invokeMethod<void>(
        'setEraserMode',
        const <String, dynamic>{'enable': false},
      );
      await _framebufferChannel.invokeMethod<void>(
        'setStrokeSize',
        <String, dynamic>{'size': widget.strokeWidth},
      );
      await _framebufferChannel.invokeMethod<void>(
        'setDashedStroke',
        <String, dynamic>{'enable': widget.isDashed},
      );
      await _framebufferChannel.invokeMethod<void>(
        'setRainbowMode',
        <String, dynamic>{'enable': widget.isRainbow},
      );
      await _framebufferChannel.invokeMethod<void>(
        'setHighlightMode',
        <String, dynamic>{'enable': widget.isHighlight},
      );
    } catch (error) {
      debugPrint('Deltav100HardwareCanvas brush sync error: $error');
    }
  }

  @override
  void initState() {
    super.initState();
    _subscription = _strokeChannel.receiveBroadcastStream().listen(
      (dynamic event) {
        if (!mounted || event is! Map) return;
        final data = Map<String, dynamic>.from(event);

        if (data['strokeStarted'] == true) {
          widget.onStrokeStarted?.call();
          return;
        }

        if (data['requestBrushSync'] == true) {
          _syncBrushToNative();
          return;
        }

        widget.onStrokeCommitted?.call(data);
      },
      onError: (Object error, StackTrace? stackTrace) {
        debugPrint('Deltav100HardwareCanvas stroke stream error: $error');
      },
    );

    _syncBrushToNative();
  }

  @override
  void didUpdateWidget(covariant HardwareAcceleratedCanvas oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.penColor != widget.penColor ||
        oldWidget.strokeWidth != widget.strokeWidth ||
        oldWidget.isRainbow != widget.isRainbow ||
        oldWidget.isDashed != widget.isDashed ||
        oldWidget.isHighlight != widget.isHighlight ||
        oldWidget.isMultiFingerEnabled != widget.isMultiFingerEnabled) {
      _syncBrushToNative();
    }
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    if (defaultTargetPlatform != TargetPlatform.android) {
      return const SizedBox.shrink();
    }

    return SizedBox.expand(
      child: AndroidView(
        viewType: _kDeltav100ViewType,
        layoutDirection: TextDirection.ltr,
        gestureRecognizers:
            widget.gestureRecognizers ??
            <Factory<OneSequenceGestureRecognizer>>{},
      ),
    );
  }
}
