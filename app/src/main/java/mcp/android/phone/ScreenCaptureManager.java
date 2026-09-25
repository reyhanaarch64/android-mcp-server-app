package mcp.android.phone;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Surface;
import android.view.WindowManager;
import android.hardware.display.VirtualDisplay;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class ScreenCaptureManager {
    private static MediaProjection projection;
    private static int resultCode;
    private static Intent resultData;
    private static final Object LOCK = new Object();

    private ScreenCaptureManager() {
    }

    public static void setProjection(Context context, int code, Intent data) throws Exception {
        synchronized (LOCK) {
            clearLocked();
            MediaProjectionManager manager = (MediaProjectionManager) context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            MediaProjection p = manager.getMediaProjection(code, data);
            if (p == null) throw new IllegalStateException("MediaProjection tidak tersedia");
            projection = p;
            resultCode = code;
            resultData = data;
            final MediaProjection saved = p;
            try {
                p.registerCallback(new MediaProjection.Callback() {
                    public void onStop() {
                        synchronized (LOCK) {
                            if (projection == saved) {
                                projection = null;
                                resultData = null;
                            }
                        }
                    }
                }, new Handler(Looper.getMainLooper()));
            } catch (Exception ignored) {
            }
        }
    }

    public static boolean isReady() {
        synchronized (LOCK) {
            return projection != null;
        }
    }

    public static void clear() {
        synchronized (LOCK) {
            clearLocked();
        }
    }

    private static void clearLocked() {
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) { }
        }
        projection = null;
        resultData = null;
        resultCode = 0;
    }

    public static JSONObject capture(Context context, long delayMs) throws Exception {
        synchronized (LOCK) {
            if (projection == null) throw new SecurityException("Akses tangkapan layar belum diberikan. Aktifkan izin tangkapan layar pada aplikasi.");
            if (delayMs > 0) Thread.sleep(Math.min(delayMs, 30000L));

            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            final int width = Math.max(1, dm.widthPixels);
            final int height = Math.max(1, dm.heightPixels);
            final int density = dm.densityDpi;
            final ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            final CountDownLatch latch = new CountDownLatch(1);
            final Holder holder = new Holder();

            reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                public void onImageAvailable(ImageReader source) {
                    Image image = null;
                    try {
                        image = source.acquireLatestImage();
                        if (image == null) return;
                        Image.Plane[] planes = image.getPlanes();
                        if (planes == null || planes.length == 0) return;
                        ByteBuffer buffer = planes[0].getBuffer();
                        int pixelStride = planes[0].getPixelStride();
                        int rowStride = planes[0].getRowStride();
                        int rowPadding = Math.max(0, rowStride - pixelStride * width);
                        int bitmapWidth = width + (pixelStride == 0 ? 0 : rowPadding / pixelStride);
                        Bitmap bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888);
                        buffer.rewind();
                        bitmap.copyPixelsFromBuffer(buffer);
                        Bitmap cropped = bitmap;
                        if (bitmapWidth != width) cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
                        ByteArrayOutputStream out = new ByteArrayOutputStream();
                        cropped.compress(Bitmap.CompressFormat.PNG, 100, out);
                        holder.bytes = out.toByteArray();
                        holder.width = width;
                        holder.height = height;
                        if (cropped != bitmap) cropped.recycle();
                        bitmap.recycle();
                    } catch (Exception e) {
                        holder.error = e;
                    } finally {
                        if (image != null) image.close();
                        latch.countDown();
                    }
                }
            }, new Handler(Looper.getMainLooper()));

            VirtualDisplay display = null;
            try {
                display = projection.createVirtualDisplay(
                        "PhoneToMcpCapture",
                        width,
                        height,
                        density,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        reader.getSurface(),
                        null,
                        null);
                if (!latch.await(10000L, TimeUnit.MILLISECONDS)) throw new IllegalStateException("Tangkapan layar tidak tersedia dalam 10 detik");
                if (holder.error != null) throw holder.error;
                if (holder.bytes == null) throw new IllegalStateException("Tangkapan layar kosong");

                JSONObject o = new JSONObject();
                o.put("captured", true);
                o.put("width", holder.width);
                o.put("height", holder.height);
                o.put("format", "png");
                o.put("bytes", holder.bytes.length);
                o.put("__mcp_image_base64", Base64.encodeToString(holder.bytes, Base64.NO_WRAP));
                o.put("__mcp_image_mime", "image/png");
                return o;
            } finally {
                if (display != null) {
                    try { display.release(); } catch (Exception ignored) { }
                }
                try { reader.close(); } catch (Exception ignored) { }
            }
        }
    }

    private static final class Holder {
        byte[] bytes;
        int width;
        int height;
        Exception error;
    }
}
