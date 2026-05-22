import 'package:deltav100/deltav100.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(const MainApp());
}

class MainApp extends StatelessWidget {
  const MainApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      home: Scaffold(
        body: SafeArea(
          child: HardwareAcceleratedCanvas(
            penColor: Colors.black,
            isRainbow: false,
            isDashed: false,
            strokeWidth: 5,
            isHighlight: false,
            isMultiFingerEnabled: true,
            onStrokeStarted: () {
              debugPrint('Hardware stroke started');
            },
            onStrokeCommitted: (data) {
              final points = data['points'] as List<dynamic>?;
              debugPrint(
                'Hardware stroke committed: ${points?.length ?? 0} points',
              );
            },
            gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
              Factory<OneSequenceGestureRecognizer>(
                () => EagerGestureRecognizer(),
              ),
            },
          ),
        ),
      ),
    );
  }
}
