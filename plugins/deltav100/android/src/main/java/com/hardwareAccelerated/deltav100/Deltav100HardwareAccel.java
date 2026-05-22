package com.hardwareAccelerated.deltav100;

import android.graphics.Bitmap;
import android.util.Log;

import androidx.annotation.Nullable;

import com.nomivision.sys.WhiteBoardSpeedup;

/**
 * Thin wrapper around Nomivision's {@code WhiteBoardSpeedup} so that the
 * Delta V100 motherboard panel can be driven through the same plugin shape as
 * the other hardware-accelerated drawing plugins.
 *
 * <p>The accelerated framebuffer bitmap returned by
 * {@link WhiteBoardSpeedup#getAccelFbCurFrameBitmap()} is wired directly into
 * the platform view's drawing canvas; writes to that bitmap are visible on
 * the FB without an explicit push step.
 */
public final class Deltav100HardwareAccel {

  private static final String TAG = "Deltav100HardwareAccel";

  @Nullable
  private static WhiteBoardSpeedup sWhiteBoardSpeedup;
  private static volatile boolean sInitialized = false;

  private Deltav100HardwareAccel() {}

  public static synchronized boolean init() {
    if (sInitialized && sWhiteBoardSpeedup != null) {
      return true;
    }
    try {
      sWhiteBoardSpeedup = new WhiteBoardSpeedup();
      // Delta V100 framebuffer expects ARGB_4444 (matches the reference
      // RendLibSurfaceView in the BUILD-32-DELTA-HA-2 branch).
      sWhiteBoardSpeedup.init(Bitmap.Config.ARGB_4444);
      sInitialized = true;
      return true;
    } catch (Throwable t) {
      sInitialized = false;
      sWhiteBoardSpeedup = null;
      Log.w(TAG, "WhiteBoardSpeedup.init failed: " + t);
      return false;
    }
  }

  @Nullable
  public static synchronized Bitmap acquireFramebufferBitmap() {
    if (!sInitialized && !init()) {
      return null;
    }
    try {
      Bitmap bitmap = sWhiteBoardSpeedup != null
          ? sWhiteBoardSpeedup.getAccelFbCurFrameBitmap()
          : null;
      if (bitmap != null
          && bitmap.getWidth() > 0
          && bitmap.getHeight() > 0) {
        Deltav100Config.SCREEN_WIDTH = bitmap.getWidth();
        Deltav100Config.SCREEN_HEIGHT = bitmap.getHeight();
      }
      return bitmap;
    } catch (Throwable t) {
      Log.w(TAG, "getAccelFbCurFrameBitmap failed: " + t);
      return null;
    }
  }

  public static synchronized void clearFramebuffer() {
    if (!sInitialized || sWhiteBoardSpeedup == null) return;
    try {
      sWhiteBoardSpeedup.clearFbFrame(WhiteBoardSpeedup.WhichFrameFlags.ALL);
    } catch (Throwable t) {
      Log.w(TAG, "clearFbFrame failed: " + t);
    }
  }

  public static synchronized void uninit() {
    if (sWhiteBoardSpeedup == null) {
      sInitialized = false;
      return;
    }
    try {
      sWhiteBoardSpeedup.uninit();
    } catch (Throwable t) {
      Log.w(TAG, "WhiteBoardSpeedup.uninit failed: " + t);
    } finally {
      sWhiteBoardSpeedup = null;
      sInitialized = false;
    }
  }

  public static boolean isInitialized() {
    return sInitialized && sWhiteBoardSpeedup != null;
  }
}
