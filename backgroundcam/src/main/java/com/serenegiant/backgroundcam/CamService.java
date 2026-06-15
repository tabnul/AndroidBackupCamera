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
    private Surface mPrimarySurface;

    // UI - Secondary
    private View mSecondaryRootView;
    private TextureView mSecondaryTextureView;
    private WindowManager mSecondaryWindowManager;
    private Surface mSecondarySurface;
    private boolean mHasSecondaryDisplay = false;
    private Context mSecondaryDisplayContext;
    private int mSecondaryWidthPx = 0;
    private int mSecondaryHeightPx = 0;

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
            mSecondarySurface = new Surface(texture);
            checkAndStartPreview();
        }
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {}
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            mSecondarySurface = null;
            return true;
        }
        public void onSurfaceTextureUpdated(SurfaceTexture texture) {}
    };

    private void checkAndStartPreview() {
        if (mPrimarySurface != null) {
            if (mHasSecondaryDisplay) {
                if (mSecondarySurface != null) {
                    cameraHandler.startPreview(mPrimarySurface, mSecondarySurface);
                }
            } else {
                cameraHandler.startPreview(mPrimarySurface);
            }
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
        mSecondaryWindowManager = getWindowManagerForSecondaryDisplay();

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

        // Secondary display gets its own params sized to its REAL pixel resolution,
        // so it fills that screen regardless of how it differs from the primary.
        if (mHasSecondaryDisplay && mSecondaryWidthPx > 0 && mSecondaryHeightPx > 0) {
            secondaryVisibleParams = new WindowManager.LayoutParams(
                    mSecondaryWidthPx,
                    mSecondaryHeightPx,
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
        }


        mUSBMonitor = new LibUVCCameraUSBMonitor(this, mOnDeviceConnectListener);
        checkPermissionCamera();
        mUSBMonitor.register();


        HandlerThread thread = new HandlerThread("Camera Thread", 10);
        thread.start();

        // Get the HandlerThread's Looper and use it for our Handler
        serviceLooper = thread.getLooper();
        cameraHandler = new MyCameraHandler(serviceLooper);

        initOverlays();
    }

    private WindowManager getWindowManagerForSecondaryDisplay() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);

        // The PRESENTATION category returns ONLY secondary/external displays
        // (the primary/default display is excluded), already sorted with the most
        // preferred presentation display first. So a single external screen gives
        // an array of length 1 here -- do NOT gate this on length > 1.
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        // Fallback: nothing reported as a presentation display, scan all displays.
        // This list DOES include the default display, so it gets filtered below.
        if (displays.length == 0) {
            displays = displayManager.getDisplays();
        }

        // Pick the first display that isn't the primary one. Correct for both paths:
        // the presentation list never contains the default display, and the full
        // list has the default filtered out here.
        for (Display display : displays) {
            if (display.getDisplayId() != Display.DEFAULT_DISPLAY
                    && display.getState() != Display.STATE_OFF) {
                Log.i(TAG, "Secondary display detected: " + display.getName() + " (ID: " + display.getDisplayId() + ")");
                mHasSecondaryDisplay = true;

                // Capture the secondary display's REAL pixel size so we can size the
                // overlay window in absolute pixels (MATCH_PARENT can resolve against
                // the wrong display's metrics and only fill part of the screen).
                DisplayMetrics dm = new DisplayMetrics();
                display.getRealMetrics(dm);
                mSecondaryWidthPx = dm.widthPixels;
                mSecondaryHeightPx = dm.heightPixels;

                Context displayContext = createDisplayContext(display);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // On API 30+ adding a TYPE_APPLICATION_OVERLAY window to a
                    // secondary display requires a window context bound to that
                    // display; a plain display context is not sufficient.
                    Context windowContext = displayContext.createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                    mSecondaryDisplayContext = windowContext;
                    return (WindowManager) windowContext.getSystemService(Context.WINDOW_SERVICE);
                }
                mSecondaryDisplayContext = displayContext;
                return (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);
            }
        }

        Log.i(TAG, "No secondary display detected.");
        return null;
    }



    public void onDestroy() {
        Log.v(TAG, "--service onDestroy");
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

        // Primary
        mPrimaryRootView = li.inflate(R.layout.overlay, null);
        mPrimaryTextureView = mPrimaryRootView.findViewById(R.id.texPreview);
        setupTextureView(mPrimaryTextureView);
        mPrimaryTextureView.setSurfaceTextureListener(mPrimarySurfaceListener);
        mPrimaryWindowManager.addView(mPrimaryRootView, invisibleParams);

        // Secondary
        if (mHasSecondaryDisplay && mSecondaryWindowManager != null) {
            // Inflate against the secondary display's context so the view tree is
            // laid out with that display's resources/density, not the primary's.
            LayoutInflater secondaryLi = (mSecondaryDisplayContext != null)
                    ? LayoutInflater.from(mSecondaryDisplayContext) : li;
            mSecondaryRootView = secondaryLi.inflate(R.layout.overlay, null);
            mSecondaryTextureView = mSecondaryRootView.findViewById(R.id.texPreview);
            setupTextureView(mSecondaryTextureView);
            mSecondaryTextureView.setSurfaceTextureListener(mSecondarySurfaceListener);
            mSecondaryWindowManager.addView(mSecondaryRootView, invisibleParams);
        }
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