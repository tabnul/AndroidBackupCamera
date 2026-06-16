package com.serenegiant.backgroundcam;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.RelativeLayout;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.NotificationCompat;

import com.serenegiant.usb_libuvccamera.LibUVCCameraUSBMonitor;
import com.serenegiant.utils.PermissionCheck;


public class CamService extends Service {

    final String TAG = "CamService";


    final static int ONGOING_NOTIFICATION_ID = 6660;
    final static String CHANNEL_ID = "cam_service_channel_id";

    private boolean visible = false;



    // UI - Primary
    private View mPrimaryRootView;
    private TextureView mPrimaryTextureView;
    private WindowManager mPrimaryWindowManager;
    private volatile Surface mPrimarySurface;

    // UI - Secondary
    private View mSecondaryRootView;
    private TextureView mSecondaryTextureView;
    private WindowManager mSecondaryWindowManager;
    private volatile Surface mSecondarySurface;
    private volatile boolean mHasSecondaryDisplay = false;
    private Context mSecondaryDisplayContext;
    private int mSecondaryWidthPx = 0;
    private int mSecondaryHeightPx = 0;
    private int mSecondaryDisplayId = -1;

    private DisplayManager mDisplayManager;
    private Handler mMainHandler;

    WindowManager.LayoutParams invisibleParams;
    WindowManager.LayoutParams visibleParams;
    WindowManager.LayoutParams secondaryVisibleParams;


    // UVC Camera
    private LibUVCCameraUSBMonitor mUSBMonitor;
    private Looper serviceLooper;
    private MyCameraHandler cameraHandler;


    // Camera2-related stuff
    private CameraManager cameraManager;
    private Size previewSize;
    private CameraDevice cameraDevice;
    private CaptureRequest captureRequest;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;

    // You can start service in 2 modes - 1.) with preview 2.) without preview (only bg processing)
    private boolean shouldShowPreview = true;

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partialResult) {
        }

        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
        }
    };

    private ImageReader.OnImageAvailableListener imageListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            Log.d(TAG, "Got image: " + image.getWidth() + " x " + image.getHeight());

            image.close();
        }
    };



    private TextureView.SurfaceTextureListener mPrimarySurfaceListener = new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            mPrimarySurface = new Surface(texture);
            checkAndStartPreview();
        }
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            mPrimarySurface = null;
            return true;
        }
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            detectSignal(mPrimaryTextureView);
        }
    };

    private TextureView.SurfaceTextureListener mSecondarySurfaceListener = new TextureView.SurfaceTextureListener() {
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            // The UVC capture path copies each frame into the surface buffer 1:1,
            // top-left aligned, with NO scaling, and never sets buffer geometry. So
            // pin the buffer to the full camera frame size: the whole frame is copied
            // in, and the TextureView then scales it down to the fitted view bounds.
            forceSecondaryBufferToFrameSize(texture);
            mSecondarySurface = new Surface(texture);
            applySecondaryFit();
            // Hand the secondary surface to the camera handler. It starts mirroring
            // immediately if the preview is already running, or remembers it and
            // starts when the preview begins.
            if (cameraHandler != null) {
                cameraHandler.setSecondaryCapture(mSecondarySurface);
            }
        }
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
            forceSecondaryBufferToFrameSize(texture);
            applySecondaryFit();
        }
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            mSecondarySurface = null;
            if (cameraHandler != null) {
                cameraHandler.setSecondaryCapture(null);
            }
            return true;
        }
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    };

    private void forceSecondaryBufferToFrameSize(SurfaceTexture texture) {
        if (texture == null || cameraHandler == null) return;
        final int fw = cameraHandler.getFrameWidth();
        final int fh = cameraHandler.getFrameHeight();
        if (fw > 0 && fh > 0) {
            texture.setDefaultBufferSize(fw, fh);
        }
    }

    /**
     * Sizes the secondary TextureView to the largest rectangle with the camera's
     * aspect ratio that fits inside the (full-screen, black) container, and centers
     * it. A TextureView always scales its content to its own bounds, so making the
     * view exactly the fitted size gives a tight, undistorted image with black bars
     * only where the screen and camera aspect ratios differ.
     *
     * The available area is read from the container (which stays full-screen), not
     * from the TextureView, so repeatedly fitting is stable and never shrinks itself.
     */
    private void applySecondaryFit() {
        if (mSecondaryRootView == null || mSecondaryTextureView == null || cameraHandler == null) return;
        final int availW = mSecondaryRootView.getWidth();
        final int availH = mSecondaryRootView.getHeight();
        if (availW < 64 || availH < 64) return; // hidden/placeholder; wait for full layout
        final int frameW = cameraHandler.getFrameWidth();
        final int frameH = cameraHandler.getFrameHeight();
        if (frameW <= 0 || frameH <= 0) return;

        final float scale = Math.min((float) availW / frameW, (float) availH / frameH);
        final int fitW = Math.max(1, Math.round(frameW * scale));
        final int fitH = Math.max(1, Math.round(frameH * scale));

        try {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mSecondaryTextureView.getLayoutParams();
            if (lp.width != fitW || lp.height != fitH) {
                lp.width = fitW;
                lp.height = fitH;
                lp.gravity = Gravity.CENTER;
                mSecondaryTextureView.setLayoutParams(lp);
                Log.i(TAG, "secondary fit: avail=" + availW + "x" + availH + " frame=" + frameW + "x" + frameH
                        + " -> tex=" + fitW + "x" + fitH);
            }
        } catch (final Throwable t) {
            Log.w(TAG, "applySecondaryFit failed: " + t);
        }
    }

    private void checkAndStartPreview() {
        // Idempotent: the camera handler ignores this until the camera is open and
        // ignores duplicates while already previewing. Called both when the primary
        // surface becomes available and when the USB camera connects.
        if (mPrimarySurface != null) {
            cameraHandler.startPreview(mPrimarySurface);
        }
    }

    private void detectSignal(TextureView textureView) {
        // OPTIMIZATION: Get a tiny version of the bitmap (16x12 pixels).
        Bitmap bitmap = textureView.getBitmap(16, 12);
        if (bitmap == null) return;

        long sumRed = 0;
        long sumGreen = 0;
        long sumBlue = 0;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int n = width * height;
        int[] pixels = new int[n];

        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);

        for (int color : pixels) {
            sumRed += Color.red(color);
            sumGreen += Color.green(color);
            sumBlue += Color.blue(color);
        }

        // Clean up bitmap memory immediately
        bitmap.recycle();

        // Calculate the overall average brightness
        int averageBrightness = (int) ((sumRed + sumGreen + sumBlue) / (3 * n));

        // BLACK DETECTION LOGIC:
        int threshold = 16;
        boolean isBlackDetected = (averageBrightness <= threshold);

        // If it's black (Signal Lost/Lens Covered), we HIDE the overlays
        if (isBlackDetected && visible) {
            Log.v(TAG, "------ Black Screen Detected (No Signal) -> Hiding ------");
            updateVisibility(false);
        }
        // If it's NOT black (Valid Video), we SHOW the overlays
        else if (!isBlackDetected && !visible) {
            Log.v(TAG, "------ Light Detected (Signal Found) -> Showing ------");
            updateVisibility(true);
        }
    }

    private void updateVisibility(boolean shouldShow) {
        visible = shouldShow;
        WindowManager.LayoutParams params = shouldShow ? visibleParams : invisibleParams;

        if (mPrimaryRootView != null) {
            mPrimaryWindowManager.updateViewLayout(mPrimaryRootView, params);
        }
        if (mSecondaryRootView != null && mHasSecondaryDisplay && mSecondaryWindowManager != null) {
            WindowManager.LayoutParams secParams = shouldShow
                    ? (secondaryVisibleParams != null ? secondaryVisibleParams : params)
                    : invisibleParams;
            mSecondaryWindowManager.updateViewLayout(mSecondaryRootView, secParams);
        }
    }


    public void onCreate() {
        Log.v(TAG, "--service on create");
        super.onCreate();
        startForeground();

        mPrimaryWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        mMainHandler = new Handler(Looper.getMainLooper());
        mDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        invisibleParams = new WindowManager.LayoutParams(
                1,
                1,
                -10000, // Position off-screen
                0,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
        );

        visibleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                PixelFormat.TRANSPARENT
        );

        // Force Landscape
        visibleParams.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;


        mUSBMonitor = new LibUVCCameraUSBMonitor(this, mOnDeviceConnectListener);
        checkPermissionCamera();
        mUSBMonitor.register();


        HandlerThread thread = new HandlerThread("Camera Thread", 10);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        serviceLooper = thread.getLooper();
        cameraHandler = new MyCameraHandler(serviceLooper);

        initOverlays();

        // Watch for the HDMI/secondary screen appearing or disappearing at any time
        // (it often attaches a moment after the service starts), then attach now if
        // one is already present.
        mDisplayManager.registerDisplayListener(mDisplayListener, mMainHandler);
        attachSecondaryDisplayIfPresent();
    }

    private final DisplayManager.DisplayListener mDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
            Log.i(TAG, "onDisplayAdded: " + displayId);
            if (!mHasSecondaryDisplay) {
                attachSecondaryDisplayIfPresent();
            }
        }

        @Override
        public void onDisplayRemoved(int displayId) {
            Log.i(TAG, "onDisplayRemoved: " + displayId);
            if (mHasSecondaryDisplay && displayId == mSecondaryDisplayId) {
                detachSecondaryDisplay();
            }
        }

        @Override
        public void onDisplayChanged(int displayId) { }
    };

    /**
     * Looks for a non-default (presentation) display and, if one is present and not
     * already attached, sets up the secondary overlay on it. Runs both at startup
     * and whenever a display is later added. Must run on the main thread (it touches
     * WindowManager) -- the DisplayListener is registered with the main Handler.
     */
    private void attachSecondaryDisplayIfPresent() {
        if (mHasSecondaryDisplay) return;

        Display[] displays = mDisplayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            displays = mDisplayManager.getDisplays();
        }
        for (Display display : displays) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY
                    && display.getState() != Display.STATE_OFF) {
                attachSecondaryDisplay(display);
                return;
            }
        }
        Log.i(TAG, "No secondary display present yet.");
    }

    private void attachSecondaryDisplay(Display display) {
        try {
            Log.i(TAG, "Attaching secondary display: " + display.getName() + " (ID: " + display.getDisplayId() + ")");
            mSecondaryDisplayId = display.getDisplayId();

            // Real pixel size (kept for logging/reference).
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            mSecondaryWidthPx = dm.widthPixels;
            mSecondaryHeightPx = dm.heightPixels;

            Context displayContext = createDisplayContext(display);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // On API 30+ a window context bound to the target display is required to
                // add a TYPE_APPLICATION_OVERLAY window there.
                mSecondaryDisplayContext = displayContext.createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            } else {
                mSecondaryDisplayContext = displayContext;
            }
            mSecondaryWindowManager = (WindowManager) mSecondaryDisplayContext.getSystemService(Context.WINDOW_SERVICE);

            Log.i(TAG, "Secondary reported size = " + mSecondaryWidthPx + "x" + mSecondaryHeightPx);
            // Use MATCH_PARENT (like the primary) rather than the reported pixel size:
            // some external displays report a larger logical size than the panel actually
            // shows, which makes a pixel-sized window overflow. MATCH_PARENT lets the
            // system resolve the true drawable area; applySecondaryFit() fits within it.
            secondaryVisibleParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                            | WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    PixelFormat.TRANSPARENT
            );
            secondaryVisibleParams.gravity = Gravity.TOP | Gravity.START;
            secondaryVisibleParams.x = 0;
            secondaryVisibleParams.y = 0;

            // Build the secondary overlay in code: a full-screen black container with a
            // centered TextureView. applySecondaryFit() resizes the TextureView to the
            // aspect-fit rectangle; the black container provides the letterbox bars.
            FrameLayout root = new FrameLayout(mSecondaryDisplayContext);
            root.setBackgroundColor(Color.BLACK);
            TextureView tv = new TextureView(mSecondaryDisplayContext);
            FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER);
            tv.setLayoutParams(tlp);
            tv.setSurfaceTextureListener(mSecondarySurfaceListener);
            root.addView(tv);
            mSecondaryRootView = root;
            mSecondaryTextureView = tv;

            // Re-fit whenever the container's size changes -- in particular when it grows
            // from the hidden 1x1 placeholder to full-screen as a signal appears.
            root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View v, int l, int t, int r, int b,
                                           int oldL, int oldT, int oldR, int oldB) {
                    if ((r - l) != (oldR - oldL) || (b - t) != (oldB - oldT)) {
                        applySecondaryFit();
                    }
                }
            });

            // Start visible if a signal is currently showing, otherwise hidden off-screen.
            WindowManager.LayoutParams startParams = visible ? secondaryVisibleParams : invisibleParams;
            mSecondaryWindowManager.addView(mSecondaryRootView, startParams);

            mHasSecondaryDisplay = true;
            // If the camera is already streaming, the secondary surface becoming available
            // (via mSecondarySurfaceListener) will attach the capture target.
        } catch (final Throwable t) {
            Log.w(TAG, "attachSecondaryDisplay failed; continuing with primary only: " + t);
            mHasSecondaryDisplay = false;
            try {
                if (mSecondaryRootView != null && mSecondaryWindowManager != null) {
                    mSecondaryWindowManager.removeView(mSecondaryRootView);
                }
            } catch (final Throwable ignored) {}
            mSecondaryRootView = null;
            mSecondaryTextureView = null;
            mSecondaryWindowManager = null;
            mSecondaryDisplayContext = null;
            secondaryVisibleParams = null;
            mSecondaryDisplayId = -1;
        }
    }

    private void detachSecondaryDisplay() {
        Log.i(TAG, "Detaching secondary display: " + mSecondaryDisplayId);
        mHasSecondaryDisplay = false;
        if (cameraHandler != null) {
            cameraHandler.setSecondaryCapture(null);
        }
        if (mSecondaryRootView != null && mSecondaryWindowManager != null) {
            try {
                mSecondaryWindowManager.removeView(mSecondaryRootView);
            } catch (final Exception e) {
                Log.w(TAG, "removeView (secondary) failed: " + e);
            }
        }
        mSecondaryRootView = null;
        mSecondaryTextureView = null;
        mSecondaryWindowManager = null;
        mSecondaryDisplayContext = null;
        mSecondarySurface = null;
        secondaryVisibleParams = null;
        mSecondaryDisplayId = -1;
    }



    public void onDestroy() {
        Log.v(TAG, "--service onDestroy");
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        cameraHandler.close();
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        serviceLooper.quit();

        if (mPrimaryRootView != null) {
            mPrimaryWindowManager.removeView(mPrimaryRootView);
        }
        if (mSecondaryRootView != null && mSecondaryWindowManager != null) {
            mSecondaryWindowManager.removeView(mSecondaryRootView);
        }
        super.onDestroy();
    }




    private void initOverlays() {
        Log.v(TAG, "init overlays");
        LayoutInflater li = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        // Primary. The secondary overlay is created dynamically by
        // attachSecondaryDisplay() whenever the external screen is present.
        mPrimaryRootView = li.inflate(R.layout.overlay, null);
        mPrimaryTextureView = mPrimaryRootView.findViewById(R.id.texPreview);
        setupTextureView(mPrimaryTextureView);
        mPrimaryTextureView.setSurfaceTextureListener(mPrimarySurfaceListener);
        mPrimaryWindowManager.addView(mPrimaryRootView, invisibleParams);
    }

    private void setupTextureView(TextureView tv) {
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        tv.setLayoutParams(lp);
    }


    protected boolean checkPermissionCamera() {
        if (!PermissionCheck.hasCamera(this)) {
            Toast.makeText(CamService.this, "no permission", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }



    private final LibUVCCameraUSBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new LibUVCCameraUSBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            Log.v(TAG, "--onAttach " + device.getProductName() + ", class = " + device.getDeviceClass() + ", subclass = " + device.getDeviceSubclass());
            if ((device.getDeviceClass() == 239) & (device.getDeviceSubclass() == 2)) {
                Toast.makeText(CamService.this.getApplicationContext(),"Attached " + device.getProductName(),Toast.LENGTH_SHORT).show();
                mUSBMonitor.requestPermission(device);
            }
        }

        @Override
        public void onConnect(final UsbDevice device, final LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.v(TAG, "--onConnect " + device.getProductName());
            Toast.makeText(CamService.this.getApplicationContext(),"Connected " + device.getProductName(),Toast.LENGTH_SHORT).show();
            cameraHandler.open(ctrlBlock);
            startPreview();
        }


        private void startPreview() {
            checkAndStartPreview();
        }

        @Override
        public void onDisconnect(final UsbDevice device, final LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock) {
            Log.v(TAG, "--onDisconnect " + device.getProductName());
            if ((device.getDeviceClass() == 239) & (device.getDeviceSubclass() == 2)) {
                stopSelf();
            }
        }
        @Override
        public void onDettach(final UsbDevice device) {
            Log.v(TAG, "--onDettach " + device.getProductName());
        }

        @Override
        public void onCancel(final UsbDevice device) {
        }
    };


    private void startForeground() {


        createNotificationChannel();
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this,
                0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getText(R.string.app_name))
                .setContentText(getText(R.string.app_name))
                .setSmallIcon(R.drawable.scion)
                .setContentIntent(pendingIntent)
                .setTicker(getText(R.string.app_name))
                .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }



    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Foreground Service Channel",
                    NotificationManager.IMPORTANCE_MIN
            );

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }



    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}