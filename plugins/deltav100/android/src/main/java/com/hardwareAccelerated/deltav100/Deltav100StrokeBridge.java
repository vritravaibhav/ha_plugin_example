package com.hardwareAccelerated.deltav100;

import android.util.Log;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;

public final class Deltav100StrokeBridge {

  @Nullable
  public static EventChannel.EventSink strokeEventSink;

  private Deltav100StrokeBridge() {}

  public static void sendStroke(List<float[]> points,
                                int color,
                                float strokeWidth,
                                boolean dashed,
                                boolean rainbow,
                                int viewWidth,
                                int viewHeight,
                                int pointerId) {
    if (points == null || points.isEmpty()) return;
    EventChannel.EventSink sink = strokeEventSink;
    if (sink == null) return;

    List<Map<String, Object>> pointMaps = new ArrayList<>();
    for (float[] xy : points) {
      Map<String, Object> p = new HashMap<>();
      float x = (xy != null && xy.length > 0) ? xy[0] : 0f;
      float y = (xy != null && xy.length > 1) ? xy[1] : 0f;
      p.put("x", (double) x);
      p.put("y", (double) y);
      pointMaps.add(p);
    }

    Map<String, Object> data = new HashMap<>();
    data.put("points", pointMaps);
    data.put("color", color);
    data.put("strokeWidth", (double) strokeWidth);
    data.put("dashed", dashed);
    data.put("rainbow", rainbow);
    data.put("viewWidth", (double) viewWidth);
    data.put("viewHeight", (double) viewHeight);
    data.put("pointer", pointerId);

    try {
      sink.success(data);
    } catch (Exception e) {
      Log.e("Deltav100StrokeBridge", "sendStroke error: " + e.getMessage());
    }
  }

  public static void sendStrokeStarted() {
    EventChannel.EventSink sink = strokeEventSink;
    if (sink == null) return;
    try {
      Map<String, Object> data = new HashMap<>();
      data.put("strokeStarted", true);
      sink.success(data);
    } catch (Exception e) {
      Log.e("Deltav100StrokeBridge", "sendStrokeStarted error: " + e.getMessage());
    }
  }

  public static void sendRequestBrushSync() {
    EventChannel.EventSink sink = strokeEventSink;
    if (sink == null) return;
    try {
      Map<String, Object> data = new HashMap<>();
      data.put("requestBrushSync", true);
      sink.success(data);
    } catch (Exception e) {
      Log.e("Deltav100StrokeBridge", "sendRequestBrushSync error: " + e.getMessage());
    }
  }
}
