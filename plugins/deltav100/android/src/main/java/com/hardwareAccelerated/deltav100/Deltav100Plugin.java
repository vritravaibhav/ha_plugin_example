package com.hardwareAccelerated.deltav100;

import androidx.annotation.NonNull;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.platform.PlatformViewRegistry;

public class Deltav100Plugin implements FlutterPlugin {

  private static final String VIEW_TYPE = "fb_drawing_view_deltav100";
  private static final String HARDWARE_STROKE_EVENT_CHANNEL =
      "com.demopaint.draw_app/hardware_stroke";
  private static final String FRAMEBUFFER_CHANNEL = "com.demopaint.draw_app/framebuffer";

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    PlatformViewRegistry registry = flutterPluginBinding.getPlatformViewRegistry();
    registry.registerViewFactory(VIEW_TYPE, new Deltav100FbDrawingViewFactory());

    new EventChannel(
        flutterPluginBinding.getBinaryMessenger(),
        HARDWARE_STROKE_EVENT_CHANNEL
    ).setStreamHandler(new EventChannel.StreamHandler() {
      @Override
      public void onListen(Object arguments, EventChannel.EventSink events) {
        Deltav100StrokeBridge.strokeEventSink = events;
      }

      @Override
      public void onCancel(Object arguments) {
        Deltav100StrokeBridge.strokeEventSink = null;
      }
    });

    new MethodChannel(
        flutterPluginBinding.getBinaryMessenger(),
        FRAMEBUFFER_CHANNEL
    ).setMethodCallHandler((MethodCall call, Result result) -> {
      try {
        String method = call.method;
        Deltav100FbDrawingView view = Deltav100FbDrawingView.getInstance();
        if (view == null) {
          result.success(null);
          return;
        }
        switch (method) {
          case "setDrawColor": {
            Number color = (Number) call.argument("color");
            view.setDrawColor(color != null ? color.intValue() : 0xFF0000FF);
            result.success(null);
            break;
          }
          case "setEraserMode": {
            Boolean enable = (Boolean) call.argument("enable");
            view.setEraserMode(enable != null && enable);
            result.success(null);
            break;
          }
          case "setStrokeSize": {
            Number size = (Number) call.argument("size");
            float stroke = size != null ? size.floatValue() : 5.0f;
            view.setStrokeSize(stroke);
            result.success(null);
            break;
          }
          case "setEraserSize": {
            Number size = (Number) call.argument("size");
            float eraser = size != null ? size.floatValue() : 20.0f;
            view.setEraserSize(eraser);
            result.success(null);
            break;
          }
          case "clearNativeCanvas": {
            view.clear();
            result.success(null);
            break;
          }
          case "setDashedStroke": {
            Boolean enable = (Boolean) call.argument("enable");
            view.setDashedStroke(enable != null && enable);
            result.success(null);
            break;
          }
          case "setRainbowMode": {
            Boolean enable = (Boolean) call.argument("enable");
            view.setRainbowMode(enable != null && enable);
            result.success(null);
            break;
          }
          case "setHighlightMode": {
            Boolean enable = (Boolean) call.argument("enable");
            view.setHighlightMode(enable != null && enable);
            result.success(null);
            break;
          }
          default:
            result.notImplemented();
            break;
        }
      } catch (Exception e) {
        result.error("ERROR", e.getMessage(), null);
      }
    });
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    // No-op for now.
  }
}
