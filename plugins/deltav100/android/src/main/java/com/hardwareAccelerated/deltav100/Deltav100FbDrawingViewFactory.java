package com.hardwareAccelerated.deltav100;

import android.content.Context;
import android.graphics.Color;

import androidx.annotation.NonNull;

import io.flutter.plugin.common.StandardMessageCodec;
import io.flutter.plugin.platform.PlatformView;
import io.flutter.plugin.platform.PlatformViewFactory;

public class Deltav100FbDrawingViewFactory extends PlatformViewFactory {

  public Deltav100FbDrawingViewFactory() {
    super(StandardMessageCodec.INSTANCE);
  }

  @NonNull
  @Override
  public PlatformView create(@NonNull Context context, int viewId, Object args) {
    return new Deltav100FbDrawingPlatformView(context);
  }

  private static class Deltav100FbDrawingPlatformView implements PlatformView {
    private final Deltav100FbDrawingView view;

    Deltav100FbDrawingPlatformView(Context context) {
      this.view = new Deltav100FbDrawingView(context);
      this.view.setBackgroundColor(Color.TRANSPARENT);
    }

    @NonNull
    @Override
    public android.view.View getView() {
      return view;
    }

    @Override
    public void dispose() {
      // No-op for now.
    }
  }
}
