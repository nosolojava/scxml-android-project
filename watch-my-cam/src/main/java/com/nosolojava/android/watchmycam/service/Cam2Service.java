package com.nosolojava.android.watchmycam.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.ImageFormat;
import android.hardware.camera2.*;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.*;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;
import com.nosolojava.android.fsm.io.AndroidBroadcastIOProcessor;
import com.nosolojava.android.fsm.io.MESSAGE_DATA;
import org.apache.commons.beanutils.MethodUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Cam2Service extends Service {
    private static final String REMOTE_CAM_LOG_TAG = "remoteCam";

    /**
     * Target we publish for clients to send messages to IncomingHandler.
     */
    final Handler internalHandler = new IncomingHandler();
    final Messenger mMessenger = new Messenger(internalHandler);
    private CameraManager manager;
    private Uri sessionUri;

    private final Pattern controllerActionPattern = Pattern.compile("controller\\.action\\.(.*)$");
    private CameraDevice currentDevice = null;

    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    /**
     * Handler of incoming messages from clients.
     */
    class IncomingHandler extends Handler {

        @Override
        public void handleMessage(Message msg) {
            logI("handle message");


            String messageName = msg.getData().getString(MESSAGE_DATA.NAME.toString());

            logI(String.format("message name: %s", messageName));

            Matcher matcher = controllerActionPattern.matcher(messageName);
            if (matcher.matches()) {
                callMethod(msg, matcher);
            }

        }


    }

    private void callMethod(Message msg, Matcher matcher) {
        String action = matcher.replaceFirst("$1");

        Class<? extends Cam2Service> clazz = this.getClass();
        Method actionMethod = MethodUtils.getAccessibleMethod(clazz, action, Message.class);
        try {
            //getDevices(msg);
            if (actionMethod != null) {
                actionMethod.invoke(this, msg);
            }

        } catch (Exception e) {
            logE("Error calling service method", e);
            Toast.makeText(getApplicationContext(), "Camera service call error", Toast.LENGTH_SHORT).show();

            sendEventToFSM("service.camera.error", e);

        }
    }

    public void getDevices(Message msg) throws CameraAccessException {
        logI("getting devices");
        String[] devicesIds = manager.getCameraIdList();

        logI(String.format("device found:  %s", java.util.Arrays.toString(devicesIds)));

        sendEventToFSM("service.camera.getDevices.result", Arrays.asList(devicesIds));
    }

    protected CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            currentDevice = camera;

            logI(String.format("Camera opened %s", camera.getId()));
            Toast.makeText(getApplicationContext(), "Camera opened", Toast.LENGTH_SHORT).show();

            sendEventToFSM("service.camera.opened", camera.getId());
        }

        @Override
        public void onDisconnected(CameraDevice camera) {

            closeCurrentCamera();


            logI(String.format("Camera disconnected %s", camera.getId()));
            Toast.makeText(getApplicationContext(), "Camera disconnected", Toast.LENGTH_SHORT).show();

            sendEventToFSM("service.camera.disconnected", camera.getId());

        }

        @Override
        public void onError(CameraDevice camera, int error) {
            closeCurrentCamera();

            logE(String.format("Error opening camera %s, errorCode: %d", camera.getId(), error));
            Toast.makeText(getApplicationContext(), "Camera error when opening", Toast.LENGTH_SHORT).show();

            sendEventToFSM("service.camera.error", camera.getId());

        }

        @Override
        public void onClosed(CameraDevice camera) {
            super.onClosed(camera);

            currentDevice = null;

            logE(String.format("Camera closed %s", camera.getId()));
            Toast.makeText(getApplicationContext(), "Camera closed", Toast.LENGTH_SHORT).show();

            sendEventToFSM("service.camera.closed", camera.getId());

        }
    };


    public void openCamera(Message msg) throws CameraAccessException {
        String deviceId = msg.getData().getString(MESSAGE_DATA.CONTENT.toString());

        Handler openCameraHandler = this.internalHandler;
        this.manager.openCamera(deviceId, cameraStateCallback, openCameraHandler);

    }

    public void closeCurrentCamera(Message msg) {
        closeCurrentCamera();
    }


    protected CameraCaptureSession.StateCallback previewStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {
            logI("CaptureSession--> configured");
            sendEventToFSM("service.camera.capture.session.configured", session.getDevice().getId());


            try {
                mPreviewBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                session.setRepeatingRequest(mPreviewBuilder.build(), null, internalHandler);
            } catch (CameraAccessException e) {
                logE("Error sending repeating request", e);
            }

        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            logI("CaptureSession--> configureFailed");
            sendEventToFSM("service.camera.capture.session.configureFailed", session.getDevice().getId());

        }

        @Override
        public void onReady(CameraCaptureSession session) {
            super.onReady(session);
            logI("CaptureSession--> ready");
            sendEventToFSM("service.camera.capture.session.ready", session.getDevice().getId());

        }

        @Override
        public void onActive(CameraCaptureSession session) {
            super.onActive(session);

            logI("CaptureSession--> active");
            sendEventToFSM("service.camera.capture.session.active", session.getDevice().getId());

        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            super.onClosed(session);

            logI("CaptureSession--> closed");
            sendEventToFSM("service.camera.capture.session.closed", session.getDevice().getId());

        }
    };

    private ReentrantLock imageReaderLock = new ReentrantLock();
    public ImageReader imageReader;

    public static AtomicInteger imageCounter= new AtomicInteger(0);

    static class SendImageTask extends AsyncTask<ImageReader,Object,Object>{
        private Cam2Service service;

        public SendImageTask(Cam2Service service) {
            this.service = service;
        }

        @Override
        protected Object doInBackground(ImageReader... params) {



            byte[] imageBytes;
            while ((imageBytes = getNextImageBytes(service.imageReader)) != null) {

                HashMap<String, Object> eventData = new HashMap<String, Object>();
                eventData.put("image",imageBytes);
                eventData.put("counter",imageCounter.get());
                    service.sendEventToFSM("service.camera.capture.newImage", eventData);
                //logI("image sent "+imageCounter.get());
            }

            return null;
        }

        protected byte[] getNextImageBytes(ImageReader reader) {
          //  logI("new image "+imageCounter.incrementAndGet());

            byte[] result = null;
            try (Image image = reader.acquireNextImage()) {
                if (image != null) {
                    result = getImageBytes(image);
                }
            }

            return result;
        }

        protected byte[] getImageBytes(Image image) {
            byte[] result = null;

            ByteBuffer buffer = image.getPlanes()[0].getBuffer();

            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);

            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {

                output.write(bytes);

                result = output.toByteArray();
            } catch (IOException e) {
                logE("Error getting image bytes", e);
            } finally {
                image.close();
            }

            return result;

        }
    }

    protected ImageReader.OnImageAvailableListener onImageReadyListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {

            new SendImageTask(Cam2Service.this).execute(reader);

        }
    };

    protected CaptureRequest.Builder mPreviewBuilder = null;

    public void startPreviewSession(Message msg) throws CameraAccessException {
        logI("Start preview Session");

        //get camera characteristics
        final CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(this.currentDevice.getId());

        //orientation
        Integer orientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        logI("Orientation "+orientation);

        //config session
        final StreamConfigurationMap configs = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        final Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
        imageReader = ImageReader.newInstance(sizes[0].getWidth(), sizes[0].getHeight(), ImageFormat.JPEG, 100);

        final Surface imageSurface = imageReader.getSurface();
        final List<Surface> outputs = new ArrayList<>();
        outputs.add(imageSurface);


        //create capture request

        mPreviewBuilder = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

        int rotation = ((WindowManager)Cam2Service.this.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
        mPreviewBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS.get(rotation));
        mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON);
        mPreviewBuilder.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_AUTO);
        mPreviewBuilder.set(CaptureRequest.CONTROL_MODE,CaptureRequest.CONTROL_MODE_AUTO);

        mPreviewBuilder.addTarget(imageSurface);


        // create session
        currentDevice.createCaptureSession(outputs, previewStateCallback, this.internalHandler);

        imageReader.setOnImageAvailableListener(onImageReadyListener, this.internalHandler);

    }




//    public void takePicture(Message msg) throws CameraAccessException {
//
//        logI("Taking picture");
//
//
//        //get camera characteristics
//        CameraCharacteristics cameraCharacteristics = manager.getCameraCharacteristics(this.currentDevice.getId());
//
//        StreamConfigurationMap configs = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
//
//        Size[] sizes = configs.getOutputSizes(ImageFormat.JPEG);
//
//
//        final ImageReader imageReader = ImageReader.newInstance(sizes[0].getWidth(), sizes[0].getHeight(), ImageFormat.JPEG, 2);
//        final Surface jpegSurface = imageReader.getSurface();
//
//        List<Surface> outputs = new ArrayList<Surface>();
//        outputs.add(jpegSurface);
//
//        currentDevice.createCaptureSession(outputs, new CameraCaptureSession.StateCallback() {
//            @Override
//            public void onConfigured(CameraCaptureSession session) {
//                logI("on camera session configured");
//
//                try {
//                    CaptureRequest.Builder snapshotBuilder = currentDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
//                    snapshotBuilder.addTarget(jpegSurface);
//
//                    CaptureRequest snapshotRequest = snapshotBuilder.build();
//
//                    session.capture(snapshotRequest, new CameraCaptureSession.CaptureCallback() {
//                        @Override
//                        public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request, long timestamp, long frameNumber) {
//                            logI("On picture capture started");
//                            super.onCaptureStarted(session, request, timestamp, frameNumber);
//                        }
//
//                        @Override
//                        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
//                            logI("On picture capture completed");
//                            super.onCaptureCompleted(session, request, result);
//
//                            Image image = imageReader.acquireLatestImage();
//
//                            if (image != null) {
//
//                                File sdCardDirectory = Environment.getExternalStorageDirectory();
//                                File imageFile = new File(sdCardDirectory, "test.jpg");
//                                logI(String.format("saving jpg in file %s", imageFile));
//
//
//                                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
//                                byte[] bytes = new byte[buffer.remaining()];
//                                buffer.get(bytes);
//                                FileOutputStream output = null;
//                                try {
//                                    output = new FileOutputStream(imageFile);
//                                    output.write(bytes);
//                                } catch (FileNotFoundException e) {
//                                    e.printStackTrace();
//                                } catch (IOException e) {
//                                    e.printStackTrace();
//                                } finally {
//                                    image.close();
//                                    if (null != output) {
//                                        try {
//                                            output.close();
//                                        } catch (IOException e) {
//                                            e.printStackTrace();
//                                        }
//                                    }
//                                }
//
//                            }
//
//                        }
//                    }, internalHandler);
//
//                } catch (CameraAccessException e) {
//                    logE("error creating snapshot", e);
//                }
//            }
//
//            @Override
//            public void onConfigureFailed(CameraCaptureSession session) {
//                logE("on camera session error");
//            }
//        }, this.internalHandler);
//
//
//    }

    private void closeCurrentCamera() {
        if (currentDevice != null) {
            currentDevice.close();
        }
        currentDevice = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        logI("creating cam service");
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

    }


    @Override
    public void onDestroy() {
        logI("destroying cam service");

        closeCurrentCamera();
        super.onDestroy();
    }


    /**
     * When binding to the service, we return an interface to our messenger for sending messages to the service.
     */
    @Override
    public IBinder onBind(Intent intent) {
        logI("onbind");

        Toast.makeText(getApplicationContext(), "binding", Toast.LENGTH_SHORT).show();

        this.sessionUri = intent.getData();

        sendEventToFSM("service.camera.ready");

        return mMessenger.getBinder();
    }

    protected void sendEventToFSM(String event) {
        sendEventToFSM(event, null);
    }

    protected void sendEventToFSM(String event, Object data) {
        AndroidBroadcastIOProcessor.sendMessageToFSM(this, MainFSMService.class, this.sessionUri, event, data);
    }


    public static void logI(String message) {
        Log.i(REMOTE_CAM_LOG_TAG, message);
    }

    public static void logE(String message) {
        Log.e(REMOTE_CAM_LOG_TAG, message);
    }

    public static void logE(String message, Throwable t) {
        Log.e(REMOTE_CAM_LOG_TAG, message, t);
    }
}
