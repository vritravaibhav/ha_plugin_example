package com.hardwareAccelerated.deltav100;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hardware-accelerated drawing surface backed by the Delta V100 motherboard's
 * Nomivision {@code WhiteBoardSpeedup} framebuffer. Mirrors the public API of
 * the {@code hikvision} / {@code langov1} drawing views so the host app can
 * switch panels without changing call sites.
 */
public class Deltav100FbDrawingView extends SurfaceView implements SurfaceHolder.Callback {

  private static final String TAG = "Deltav100FbDrawingView";

  private static final float DEFAULT_STROKE_WIDTH = 6f;
  private static final float DEFAULT_ERASER_WIDTH = 32f;
  private static final int DEFAULT_COLOR = Color.WHITE;
  private static final int HIGHLIGHTER_ALPHA = 75;

  /**
   * How long after the last pointer goes up we wait before clearing the
   * native FB pixels for the just-finished strokes. The delay lets Flutter
   * paint the strokes in its Skia layer first, so the handoff is visually
   * seamless. If the user puts a finger back down within the window, the
   * pending clear is cancelled (see {@link #handlePointerDown}).
   */
  private static final long CLEAR_LAYER_DELAY_MS = 300;

  private static final int[] RAINBOW_COLORS = new int[] {
      0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50, 0xFF2196F3, 0xFF3F51B5,
      0xFF9C27B0, 0xFFE91E63, 0xFFFF5722, 0xFFFFC107, 0xFFCDDC39, 0xFF8BC34A,
      0xFF00BCD4, 0xFF03A9F4, 0xFF009688, 0xFF673AB7
  };

  @Nullable
  private static WeakReference<Deltav100FbDrawingView> sInstance;

  @Nullable
  public static Deltav100FbDrawingView getInstance() {
    return sInstance != null ? sInstance.get() : null;
  }

  private static class StrokeState {
    float lastX;
    float lastY;
    float dashPhase;
    boolean started;
    /**
     * Accumulated dirty rect (in FB-bitmap coordinates) of every segment we
     * have drawn for this stroke. Used on pointer-up to clear exactly the
     * pixels we wrote, once Flutter has the stroke data.
     */
    final RectF dirtyBounds = new RectF();
    final List<float[]> points = new ArrayList<>();
  }

  private final Map<Integer, StrokeState> mActiveStrokes = new HashMap<>();
  private final RectF mSegmentBounds = new RectF();
  private final Rect mSrcRect = new Rect();
  private final Rect mDstRect = new Rect();
  private final Handler mClearHandler = new Handler(Looper.getMainLooper());
  /**
   * Union of every just-finished stroke's dirty rect that's still waiting
   * for the {@link #CLEAR_LAYER_DELAY_MS} timer to expire. Multiple strokes
   * completed back-to-back accumulate here and are wiped in one batch.
   */
  private final RectF mPendingClearBounds = new RectF();
  private final Runnable mClearLayerAfterDelay = new Runnable() {
    @Override
    public void run() {
      clearRectOnFb(mPendingClearBounds);
    }
  };

  /**
   * When the FB bitmap is in use ({@code mDirectFbMode == true}), writes to
   * {@link #mBufferCanvas} land directly on the panel framebuffer and the
   * {@link SurfaceView} surface only renders an empty, transparent overlay.
   * When the FB is unavailable (e.g. running on a tablet during dev), we fall
   * back to an in-memory bitmap that is blitted to the surface via
   * {@link #refreshSurface()}.
   */
  private Bitmap mBuffer;
  private Canvas mBufferCanvas;
  private int mBufferWidth;
  private int mBufferHeight;
  private boolean mDirectFbMode;

  private Bitmap mHighlightLayer;
  private Canvas mHighlightCanvas;
  private final Paint mHighlightCompositePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
  private final Paint mClearPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private Paint mPaint;
  private int mCurrentColor = DEFAULT_COLOR;
  private boolean mEraserMode;
  private float mCurrentStrokeWidth = DEFAULT_STROKE_WIDTH;
  private float mNativeStrokeWidth = DEFAULT_STROKE_WIDTH;
  private float mPenStrokeSize = DEFAULT_STROKE_WIDTH;
  private float mEraserSize = DEFAULT_ERASER_WIDTH;
  private boolean mDashedStroke;
  private boolean mRainbowMode;
  private boolean mHighlightMode;

  public Deltav100FbDrawingView(Context context) {
    super(context);
    init();
  }

  public Deltav100FbDrawingView(Context context, AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public Deltav100FbDrawingView(Context context, AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    sInstance = new WeakReference<>(this);
    getHolder().addCallback(this);
    getHolder().setFormat(PixelFormat.TRANSPARENT);
    setZOrderOnTop(true);
    setBackgroundColor(Color.TRANSPARENT);

    mPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    mPaint.setStyle(Paint.Style.STROKE);
    mPaint.setStrokeJoin(Paint.Join.ROUND);
    mPaint.setStrokeCap(Paint.Cap.ROUND);

    mClearPaint.setStyle(Paint.Style.FILL);
    mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

    mHighlightCompositePaint.setAlpha(HIGHLIGHTER_ALPHA);

    updatePaint();
  }

  @Override
  protected void onDetachedFromWindow() {
    super.onDetachedFromWindow();
    mClearHandler.removeCallbacks(mClearLayerAfterDelay);
    if (sInstance != null && sInstance.get() == this) {
      sInstance = null;
    }
  }

  @Override
  public void surfaceCreated(@NonNull SurfaceHolder holder) {
    try {
      Deltav100HardwareAccel.init();
      ensureBuffer();
      // One-time clean slate when the platform view's surface is first
      // created. After this point the FB bitmap is treated as persistent
      // display memory; only eraseAt() / clear() / surfaceDestroyed() ever
      // mutate it via clear primitives.
      Deltav100HardwareAccel.clearFramebuffer();
      refreshSurface();
      Deltav100StrokeBridge.sendRequestBrushSync();
    } catch (Throwable t) {
      Log.w(TAG, "surfaceCreated: " + t.getMessage());
    }
  }

  @Override
  public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    try {
      ensureBuffer();
      refreshSurface();
    } catch (Throwable t) {
      Log.w(TAG, "surfaceChanged: " + t.getMessage());
    }
  }

  @Override
  public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
    try {
      mClearHandler.removeCallbacks(mClearLayerAfterDelay);
      mPendingClearBounds.setEmpty();
      Deltav100HardwareAccel.clearFramebuffer();
      Deltav100HardwareAccel.uninit();
    } catch (Throwable t) {
      Log.w(TAG, "surfaceDestroyed: " + t.getMessage());
    }
  }

  private void ensureBuffer() {
    Bitmap fb = Deltav100HardwareAccel.acquireFramebufferBitmap();
    if (fb != null && fb.getWidth() > 0 && fb.getHeight() > 0) {
      if (mBuffer != fb) {
        mBuffer = fb;
        mBufferWidth = fb.getWidth();
        mBufferHeight = fb.getHeight();
        mBufferCanvas = new Canvas(mBuffer);
        mDirectFbMode = true;
        mHighlightLayer = null;
        mHighlightCanvas = null;
        Deltav100Config.SCREEN_WIDTH = mBufferWidth;
        Deltav100Config.SCREEN_HEIGHT = mBufferHeight;
      }
      return;
    }

    int w = Deltav100Config.SCREEN_WIDTH;
    int h = Deltav100Config.SCREEN_HEIGHT;
    if (w <= 0 || h <= 0) {
      w = getWidth();
      h = getHeight();
    }
    if (w <= 0 || h <= 0) return;
    if (mBuffer == null || mDirectFbMode
        || mBuffer.getWidth() != w || mBuffer.getHeight() != h) {
      mBufferWidth = w;
      mBufferHeight = h;
      mBuffer = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
      mBufferCanvas = new Canvas(mBuffer);
      mBufferCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      mDirectFbMode = false;
      mHighlightLayer = null;
      mHighlightCanvas = null;
    }
  }

  private void ensureHighlightLayer() {
    if (mBufferWidth <= 0 || mBufferHeight <= 0) return;
    if (mHighlightLayer == null
        || mHighlightLayer.getWidth() != mBufferWidth
        || mHighlightLayer.getHeight() != mBufferHeight) {
      mHighlightLayer = Bitmap.createBitmap(mBufferWidth, mBufferHeight, Bitmap.Config.ARGB_8888);
      mHighlightCanvas = new Canvas(mHighlightLayer);
      clearHighlightLayer();
    }
  }

  private void clearHighlightLayer() {
    if (mHighlightCanvas != null) {
      mHighlightCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    }
  }

  private void viewToBuffer(float vx, float vy, float[] out) {
    int vw = getWidth();
    int vh = getHeight();
    if (vw <= 0 || vh <= 0) {
      out[0] = vx;
      out[1] = vy;
      return;
    }
    out[0] = vx * mBufferWidth / Math.max(1, vw);
    out[1] = vy * mBufferHeight / Math.max(1, vh);
  }

  private void clampToBuffer(float[] xy) {
    xy[0] = Math.max(0, Math.min(mBufferWidth - 1, xy[0]));
    xy[1] = Math.max(0, Math.min(mBufferHeight - 1, xy[1]));
  }

  private void refreshSurface() {
    if (mBuffer == null || mDirectFbMode) {
      // In direct FB mode the panel framebuffer already shows the latest
      // bitmap content; we only paint a transparent overlay on the surface
      // so it stays visible without occluding the FB.
      paintTransparentSurface();
      return;
    }
    SurfaceHolder holder = getHolder();
    if (holder == null) return;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        && !holder.getSurface().isValid()) {
      return;
    }

    Canvas canvas = null;
    try {
      canvas = holder.lockCanvas();
      if (canvas == null) return;
      int vw = getWidth();
      int vh = getHeight();
      if (vw <= 0 || vh <= 0) return;
      mSrcRect.set(0, 0, mBuffer.getWidth(), mBuffer.getHeight());
      mDstRect.set(0, 0, vw, vh);
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      canvas.drawBitmap(mBuffer, mSrcRect, mDstRect, null);
    } catch (Throwable t) {
      Log.w(TAG, "refreshSurface: " + t.getMessage());
    } finally {
      if (canvas != null) {
        try {
          holder.unlockCanvasAndPost(canvas);
        } catch (Throwable t) {
          Log.w(TAG, "unlockCanvasAndPost: " + t.getMessage());
        }
      }
    }
  }

  private void paintTransparentSurface() {
    SurfaceHolder holder = getHolder();
    if (holder == null) return;
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O
        && !holder.getSurface().isValid()) {
      return;
    }
    Canvas canvas = null;
    try {
      canvas = holder.lockCanvas();
      if (canvas == null) return;
      canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    } catch (Throwable t) {
      Log.w(TAG, "paintTransparentSurface: " + t.getMessage());
    } finally {
      if (canvas != null) {
        try {
          holder.unlockCanvasAndPost(canvas);
        } catch (Throwable t) {
          Log.w(TAG, "unlockCanvasAndPost: " + t.getMessage());
        }
      }
    }
  }

  private void drawSegment(StrokeState state, float x0, float y0, float x1, float y1) {
    if (mBufferCanvas == null) return;
    float drawWidth = Math.max(1f, mNativeStrokeWidth);

    boolean useHighlightLayer = mHighlightMode && !mEraserMode;
    Canvas target = mBufferCanvas;
    if (useHighlightLayer) {
      ensureHighlightLayer();
      if (mHighlightCanvas != null) {
        target = mHighlightCanvas;
      } else {
        useHighlightLayer = false;
      }
    }

    if (mDashedStroke && !mEraserMode) {
      float dx = x1 - x0;
      float dy = y1 - y0;
      float distance = (float) Math.hypot(dx, dy);
      if (distance <= 0f) {
        return;
      }

      float dash = Math.max(6f, mNativeStrokeWidth * 4f);
      float gap = Math.max(4f, mNativeStrokeWidth * 4f);
      float pattern = dash + gap;
      float ux = dx / distance;
      float uy = dy / distance;
      float phase = state != null ? state.dashPhase % pattern : 0f;
      boolean drawing = phase < dash;
      float patternOffset = drawing ? phase : phase - dash;
      float travelled = 0f;
      boolean drewAny = false;
      float minX = Float.MAX_VALUE;
      float minY = Float.MAX_VALUE;
      float maxX = -Float.MAX_VALUE;
      float maxY = -Float.MAX_VALUE;

      while (travelled < distance) {
        float remainingPattern = drawing ? (dash - patternOffset) : (gap - patternOffset);
        float nextTravelled = Math.min(distance, travelled + remainingPattern);
        if (drawing && nextTravelled > travelled) {
          float sx = x0 + (ux * travelled);
          float sy = y0 + (uy * travelled);
          float ex = x0 + (ux * nextTravelled);
          float ey = y0 + (uy * nextTravelled);
          target.drawLine(sx, sy, ex, ey, mPaint);
          drewAny = true;
          minX = Math.min(minX, Math.min(sx, ex));
          minY = Math.min(minY, Math.min(sy, ey));
          maxX = Math.max(maxX, Math.max(sx, ex));
          maxY = Math.max(maxY, Math.max(sy, ey));
        }
        travelled = nextTravelled;
        if (travelled >= distance) {
          break;
        }
        drawing = !drawing;
        patternOffset = 0f;
      }

      if (state != null) {
        state.dashPhase = (phase + distance) % pattern;
      }

      if (!drewAny) {
        return;
      }
      mSegmentBounds.set(minX, minY, maxX, maxY);
    } else if (x0 == x1 && y0 == y1) {
      // Holding a finger still emits a stream of ACTION_MOVE events at the
      // same coordinates. Drawing a circle here stamps a visible dot on the
      // FB after a brief hold, which is the same artefact we deliberately
      // avoid in handlePointerDown(). Skip the segment entirely; the first
      // real movement will start drawing.
      return;
    } else {
      target.drawLine(x0, y0, x1, y1, mPaint);
      mSegmentBounds.set(
          Math.min(x0, x1),
          Math.min(y0, y1),
          Math.max(x0, x1),
          Math.max(y0, y1)
      );
    }

    int pad = (int) Math.ceil(drawWidth / 2f) + 2;
    int ix = (int) Math.max(0, mSegmentBounds.left - pad);
    int iy = (int) Math.max(0, mSegmentBounds.top - pad);
    int iw = (int) Math.min(mBufferWidth - ix, mSegmentBounds.width() + 2 * pad);
    int ih = (int) Math.min(mBufferHeight - iy, mSegmentBounds.height() + 2 * pad);
    if (iw <= 0 || ih <= 0) return;

    if (useHighlightLayer) {
      mBufferCanvas.save();
      mBufferCanvas.clipRect(ix, iy, ix + iw, iy + ih);
      mBufferCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      mBufferCanvas.drawBitmap(mHighlightLayer, 0, 0, mHighlightCompositePaint);
      mBufferCanvas.restore();
    }

    if (state != null) {
      // RectF.union(...) replaces an empty rect with the argument, so the
      // first segment seeds dirtyBounds and subsequent segments expand it.
      state.dirtyBounds.union(ix, iy, ix + iw, iy + ih);
    }

    if (!mDirectFbMode) {
      refreshSurface();
    }
  }

  /**
   * Erases just the FB pixels covered by {@code bounds} (and the same rect on
   * the offscreen highlight layer). Mutates {@code bounds} to empty when it
   * finishes so the same RectF can be re-accumulated for the next batch.
   *
   * <p>WhiteBoardSpeedup has no per-rect clear native API, but writes via
   * {@link Canvas#drawColor(int, PorterDuff.Mode) drawColor(CLEAR)} inside a
   * {@link Canvas#clipRect} land on the FB bitmap directly (in DirectFB mode),
   * so this gives us the "only required pixels" clear we want without
   * touching anything else on screen.
   */
  private void clearRectOnFb(RectF bounds) {
    if (bounds == null || mBufferCanvas == null || bounds.isEmpty()) {
      if (bounds != null) bounds.setEmpty();
      return;
    }
    int left = (int) Math.max(0, Math.floor(bounds.left));
    int top = (int) Math.max(0, Math.floor(bounds.top));
    int right = (int) Math.min(mBufferWidth, Math.ceil(bounds.right));
    int bottom = (int) Math.min(mBufferHeight, Math.ceil(bounds.bottom));
    bounds.setEmpty();
    if (right <= left || bottom <= top) return;

    mBufferCanvas.save();
    mBufferCanvas.clipRect(left, top, right, bottom);
    mBufferCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    mBufferCanvas.restore();

    // Reset the same rect in the offscreen highlight layer so a future
    // highlight stroke that overlaps this region doesn't composite the
    // just-cleared stroke's stale alpha back into the FB.
    if (mHighlightCanvas != null) {
      mHighlightCanvas.save();
      mHighlightCanvas.clipRect(left, top, right, bottom);
      mHighlightCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
      mHighlightCanvas.restore();
    }

    if (!mDirectFbMode) {
      refreshSurface();
    }
  }

  /**
   * Merges a finished stroke's dirty rect into {@link #mPendingClearBounds},
   * which the delayed runnable wipes once all fingers have been up for
   * {@link #CLEAR_LAYER_DELAY_MS} ms. Drains {@code state.dirtyBounds}.
   */
  private void enqueueDelayedClear(StrokeState state) {
    if (state == null || state.dirtyBounds.isEmpty()) return;
    mPendingClearBounds.union(state.dirtyBounds);
    state.dirtyBounds.setEmpty();
  }

  private void eraseAt(float viewX, float viewY) {
    ensureBuffer();
    if (mBufferCanvas == null) return;
    float[] xy = new float[2];
    viewToBuffer(viewX, viewY, xy);
    clampToBuffer(xy);

    float radius = Math.max(1f, mEraserSize * getResources().getDisplayMetrics().density * 0.5f);
    mBufferCanvas.drawCircle(xy[0], xy[1], radius, mClearPaint);

    if (!mDirectFbMode) {
      refreshSurface();
    }
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    int action = event.getActionMasked();
    int index = event.getActionIndex();
    switch (action) {
      case MotionEvent.ACTION_DOWN:
      case MotionEvent.ACTION_POINTER_DOWN:
        handlePointerDown(event, index);
        return true;
      case MotionEvent.ACTION_MOVE:
        handlePointerMove(event);
        return true;
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_POINTER_UP:
        handlePointerUp(event, index);
        return true;
      case MotionEvent.ACTION_CANCEL:
        handleCancel();
        return true;
      default:
        return super.onTouchEvent(event);
    }
  }

  private void handlePointerDown(MotionEvent event, int index) {
    if (index < 0 || index >= event.getPointerCount()) return;

    if (mActiveStrokes.isEmpty()) {
      Deltav100StrokeBridge.sendStrokeStarted();
      // A finger is back down within the delay window: keep the pending FB
      // pixels visible so back-to-back strokes don't flicker. The pending
      // bounds will be re-extended by this new stroke and cleared once all
      // fingers stay up for CLEAR_LAYER_DELAY_MS.
      mClearHandler.removeCallbacks(mClearLayerAfterDelay);
      // The standalone offscreen highlight layer still gets reset so highlight
      // strokes don't bleed alpha across separate gestures.
      clearHighlightLayer();
    }

    int pointerId = event.getPointerId(index);
    float viewX = event.getX(index);
    float viewY = event.getY(index);

    if (mEraserMode) {
      StrokeState state = new StrokeState();
      state.started = true;
      state.points.add(new float[] {viewX, viewY});
      mActiveStrokes.put(pointerId, state);
      eraseAt(viewX, viewY);
      return;
    }

    float[] xy = new float[2];
    viewToBuffer(viewX, viewY, xy);
    clampToBuffer(xy);

    StrokeState state = new StrokeState();
    state.lastX = xy[0];
    state.lastY = xy[1];
    state.started = true;
    state.points.add(new float[] {viewX, viewY});
    mActiveStrokes.put(pointerId, state);
    // Intentionally do NOT call drawSegment(state, lastX, lastY, lastX, lastY)
    // here. With identical start/end coordinates that path falls back to
    // Canvas.drawCircle(...) which leaves a visible dot at the touch-down
    // point. The first real segment is drawn from the first ACTION_MOVE.
  }

  private void handlePointerMove(MotionEvent event) {
    if (mActiveStrokes.isEmpty()) return;

    int count = event.getPointerCount();
    float[] xy = new float[2];
    for (int i = 0; i < count; i++) {
      int pointerId = event.getPointerId(i);
      StrokeState state = mActiveStrokes.get(pointerId);
      if (state == null || !state.started) continue;

      float viewX = event.getX(i);
      float viewY = event.getY(i);
      state.points.add(new float[] {viewX, viewY});

      if (mEraserMode) {
        eraseAt(viewX, viewY);
        continue;
      }

      viewToBuffer(viewX, viewY, xy);
      clampToBuffer(xy);
      float x = xy[0];
      float y = xy[1];
      drawSegment(state, state.lastX, state.lastY, x, y);
      state.lastX = x;
      state.lastY = y;
    }
  }

  private void handlePointerUp(MotionEvent event, int index) {
    if (index < 0 || index >= event.getPointerCount()) return;
    int pointerId = event.getPointerId(index);
    StrokeState state = mActiveStrokes.remove(pointerId);
    if (state == null || !state.started) return;

    float viewX = event.getX(index);
    float viewY = event.getY(index);
    state.points.add(new float[] {viewX, viewY});

    if (!mEraserMode) {
      float[] xy = new float[2];
      viewToBuffer(viewX, viewY, xy);
      clampToBuffer(xy);
      if (state.lastX != xy[0] || state.lastY != xy[1]) {
        drawSegment(state, state.lastX, state.lastY, xy[0], xy[1]);
      }

      Deltav100StrokeBridge.sendStroke(
          new ArrayList<>(state.points),
          mCurrentColor,
          mCurrentStrokeWidth,
          mDashedStroke,
          mRainbowMode,
          Math.max(1, getWidth()),
          Math.max(1, getHeight()),
          pointerId
      );
      // Flutter now owns this stroke; queue this stroke's pixels for the
      // delayed batch clear so Skia has time to paint before we wipe.
      enqueueDelayedClear(state);
    } else {
      eraseAt(viewX, viewY);
    }

    if (mActiveStrokes.isEmpty() && !mPendingClearBounds.isEmpty()) {
      mClearHandler.removeCallbacks(mClearLayerAfterDelay);
      mClearHandler.postDelayed(mClearLayerAfterDelay, CLEAR_LAYER_DELAY_MS);
    }
  }

  private void handleCancel() {
    // ACTION_CANCEL aborts in-progress strokes. Wipe just the FB pixels we
    // wrote for those strokes (their dirty rects); we never sent them to
    // Flutter, so nothing is going to repaint them. Pixels outside the
    // cancelled strokes' bounds stay untouched.
    for (StrokeState state : mActiveStrokes.values()) {
      clearRectOnFb(state.dirtyBounds);
    }
    mActiveStrokes.clear();
    // Any already-pending delayed clear is now superseded: cancel and wipe
    // those pixels too. Aborted gestures shouldn't leave native pixels
    // hanging around.
    mClearHandler.removeCallbacks(mClearLayerAfterDelay);
    clearRectOnFb(mPendingClearBounds);
  }

  private void updatePaint() {
    if (mPaint == null) return;
    float density = getResources().getDisplayMetrics().density;
    if (mEraserMode) {
      mPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
      mPaint.setColor(Color.TRANSPARENT);
      mPaint.setAlpha(255);
      mPaint.setShader(null);
      mPaint.setPathEffect(null);
      mNativeStrokeWidth = Math.max(1f, mEraserSize * density);
    } else {
      mPaint.setXfermode(null);
      if (mRainbowMode && mBufferWidth > 0 && mBufferHeight > 0) {
        mPaint.setShader(new LinearGradient(
            0, 0, mBufferWidth, mBufferHeight,
            RAINBOW_COLORS, null, Shader.TileMode.CLAMP
        ));
        mPaint.setColor(mCurrentColor);
      } else {
        mPaint.setShader(null);
        mPaint.setColor(mCurrentColor);
      }
      mPaint.setAlpha(255);
      mHighlightCompositePaint.setAlpha(mHighlightMode ? HIGHLIGHTER_ALPHA : 255);
      mPaint.setPathEffect(null);
      mNativeStrokeWidth = Math.max(1f, mCurrentStrokeWidth * density);
    }
    mPaint.setStrokeWidth(mNativeStrokeWidth);
  }

  public void setDrawColor(int color) {
    mCurrentColor = color;
    if (!mHighlightMode) {
      mEraserMode = false;
    }
    mCurrentStrokeWidth = mPenStrokeSize;
    updatePaint();
  }

  public void setEraserMode(boolean eraser) {
    mEraserMode = eraser;
    if (eraser) {
      mHighlightMode = false;
    }
    mCurrentStrokeWidth = eraser ? mEraserSize : mPenStrokeSize;
    updatePaint();
  }

  public void setStrokeSize(float size) {
    if (size > 0) {
      mPenStrokeSize = size;
      if (!mEraserMode) {
        mCurrentStrokeWidth = mPenStrokeSize;
      }
      updatePaint();
    }
  }

  public void setEraserSize(float size) {
    if (size > 0) {
      mEraserSize = size;
      if (mEraserMode) {
        mCurrentStrokeWidth = mEraserSize;
        updatePaint();
      }
    }
  }

  public void setDashedStroke(boolean dashed) {
    mDashedStroke = dashed;
    if (!mEraserMode) updatePaint();
  }

  public void setRainbowMode(boolean rainbow) {
    mRainbowMode = rainbow;
    if (!mEraserMode) updatePaint();
  }

  public void setHighlightMode(boolean highlight) {
    mHighlightMode = highlight;
    if (highlight) {
      mEraserMode = false;
      mCurrentStrokeWidth = mPenStrokeSize;
    }
    updatePaint();
  }

  public void clear() {
    ensureBuffer();
    // A user-driven full clear supersedes any in-flight delayed batch clear.
    mClearHandler.removeCallbacks(mClearLayerAfterDelay);
    mPendingClearBounds.setEmpty();
    clearHighlightLayer();
    if (mBufferCanvas != null) {
      mBufferCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    }
    Deltav100HardwareAccel.clearFramebuffer();
    refreshSurface();
  }
}
