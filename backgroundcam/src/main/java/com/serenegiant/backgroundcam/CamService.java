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
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbDevice;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
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



    // UI
    private View rootView;
    private TextureView textureView;

    WindowManager.LayoutParams invisibleParams;
    WindowManager.LayoutParams visibleParams;


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



    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {

        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
            // ... (Your existing Aspect Ratio code) ...

            // FORCE initial visibility check
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(rootView, visibleParams);
            visible = true;

            // Start the camera handler after layout is set
            if (textureView.getSurfaceTexture() != null) {
                cameraHandler.startPreview(new Surface(textureView.getSurfaceTexture()));
            }
        }

        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
        }

        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
            return true;
        }

        public void onSurfaceTextureUpdated(SurfaceTexture texture) {
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
            // We divide by (3 * n) because there are 3 color channels per pixel
            int averageBrightness = (int) ((sumRed + sumGreen + sumBlue) / (3 * n));

            // BLACK DETECTION LOGIC:
            // 0 is pure black, 255 is pure white.
            // 16-20 is a good threshold for "black" to account for sensor noise.
            int threshold = 16;
            boolean isBlackDetected = (averageBrightness <= threshold);

            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

            // If it's black (Signal Lost/Lens Covered), we HIDE the overlay
            if (isBlackDetected && visible) {
                Log.v(TAG, "------ Black Screen Detected (No Signal) -> Hiding ------");
                wm.updateViewLayout(rootView, invisibleParams);
                visible = false;
            }
            // If it's NOT black (Valid Video), we SHOW the overlay
            else if (!isBlackDetected && !visible) {
                Log.v(TAG, "------ Light Detected (Signal Found) -> Showing ------");
                wm.updateViewLayout(rootView, visibleParams);
                visible = true;
            }
        }
    };


    public void onCreate() {
        Log.v(TAG, "--service on create");
        super.onCreate();
        startForeground();

        invisibleParams = new WindowManager.LayoutParams(
                1,
                1,
                -1080,
                -1215,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE
        );

        // ... (existing invisibleParams) ...

        visibleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        initOverlay();
    }



    public void onDestroy() {
        Log.v(TAG, "--service onDestroy");
        cameraHandler.close();
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        serviceLooper.quit();
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (rootView != null) {
            wm.removeView(rootView);
        }
        super.onDestroy();
    }




    private void initOverlay() {
        Log.v(TAG, "init overlay");

        LayoutInflater li = (LayoutInflater) this.getSystemService(Context.LAYOUT_INFLATER_SERVICE);    rootView = li.inflate(R.layout.overlay, null);
        textureView = rootView.findViewById(R.id.texPreview);

        // This ensures the TextureView maintains its aspect ratio within the fullscreen layout
        // Center it in the parent container
        RelativeLayout.LayoutParams lp = new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        lp.addRule(RelativeLayout.CENTER_IN_PARENT);
        textureView.setLayoutParams(lp);

        textureView.setSurfaceTextureListener(surfaceTextureListener);

        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        // Add with invisibleParams initially
        wm.addView(rootView, invisibleParams);
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
//			Toast.makeText(MainActivity.this, "USB Device Attached", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            Log.v(TAG, "--onConnect " + device.getProductName());
            Toast.makeText(CamService.this.getApplicationContext(),"Connected " + device.getProductName(),Toast.LENGTH_SHORT).show();
            cameraHandler.open(ctrlBlock);
            startPreview();
        }


        private void startPreview() {
//            final SurfaceTexture st = MainActivity.mUVCCameraView.getSurfaceTexture();
            final SurfaceTexture st = textureView.getSurfaceTexture();
            Log.v(TAG, "service is starting preview; SurfaceTexture null =  " + (st == null));
            if (st != null) {
                cameraHandler.startPreview(new Surface(st));
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final LibUVCCameraUSBMonitor.UsbControlBlock ctrlBlock) {
            Log.v(TAG, "--onDisconnect " + device.getProductName());
            if ((device.getDeviceClass() == 239) & (device.getDeviceSubclass() == 2)) {
                stopSelf();
//                cameraHandler.close();
            }
        }
        @Override
        public void onDettach(final UsbDevice device) {
            Log.v(TAG, "--onDettach " + device.getProductName());
        }

        @Override
        public void onCancel(final UsbDevice device) {
//			setCameraButton(false);
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
