package com.affectiva.framedetectordemo;

import com.felhr.serialportexample.UsbService;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import com.affectiva.android.affdex.sdk.Frame;
import com.affectiva.android.affdex.sdk.detector.Face;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;

/**
 * This is a sample app using the FrameDetector object, which is not multi-threaded, and running it
 * on a background thread in a custom object called AsyncFrameDetector.
 *
 * This app also contains sample code for using the camera.
 */
public class MainActivity extends Activity implements CameraView.OnCameraViewEventListener, AsyncFrameDetector.OnDetectorEventListener {

    private static final String LOG_TAG = "Affectiva";
    private UsbService UsbService;
    private MyHandler mHandler;

    MetricsPanel metricsPanel; //Fragment to display metric scores

    //UI Elements
    Button sdkButton;
    Button cameraButton;
    TextView processorFPS;
    TextView cameraFPS;
    ToggleButton frontBackToggle;

    //state booleans
    boolean isCameraStarted = false;
    boolean isCameraFront = true;
    boolean isCameraRequestedByUser = false;
    boolean isSDKRunning = false;

    //variables used to determine the FPS rates of frames sent by the camera and processed by the SDK
    long numberCameraFramesReceived = 0;
    long lastCameraFPSResetTime = -1L;
    long numberSDKFramesReceived = 0;
    long lastSDKFPSResetTime = -1L;

    //floats to ensure the timestamps we send to FrameDetector are sequentially increasing
    float lastTimestamp = -1f;
    final float epsilon = .01f;
    char recieved = 'n';

    CameraView cameraView; // controls the camera
    AsyncFrameDetector asyncDetector; // runs FrameDetector on a background thread

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mHandler = new MyHandler(this);

        //set up metrics view
        metricsPanel = new MetricsPanel();
        getFragmentManager().beginTransaction().add(R.id.fragment_container, metricsPanel).commit();

        //Init TextViews
        cameraFPS = (TextView) findViewById(R.id.camera_fps_text);
        processorFPS = (TextView) findViewById(R.id.processor_fps_text);

        //set up CameraView
        cameraView = (CameraView) findViewById(R.id.camera_view);
        cameraView.setOnCameraViewEventListener(this);

        //set up CameraButton
        cameraButton = (Button) findViewById(R.id.camera_button);
        cameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isCameraRequestedByUser) { //Turn camera off
                    isCameraRequestedByUser = false;
                    cameraButton.setText("Start Camera");
                    stopCamera();
                } else { //Turn camera on
                    isCameraRequestedByUser = true;
                    cameraButton.setText("Stop Camera");
                    startCamera();
                }
                resetFPS();
            }
        });

        //Set up front toggle button
        frontBackToggle = (ToggleButton) findViewById(R.id.front_back_toggle_button);
        frontBackToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                isCameraFront = !isChecked;
                if (isCameraRequestedByUser) {
                    startCamera();
                }
            }
        });

        asyncDetector = new AsyncFrameDetector(this);
        asyncDetector.setOnDetectorEventListener(this);

        //Set up SDK Button
        sdkButton = (Button) findViewById(R.id.start_sdk_button);
        sdkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isSDKRunning) {
                    isSDKRunning = false;
                    asyncDetector.stop();
                    sdkButton.setText("Start SDK");
                } else {
                    isSDKRunning = true;
                    asyncDetector.start();
                    sdkButton.setText("Stop SDK");
                }
                resetFPS();
            }
        });
        sdkButton.setText("Start SDK");
    }

    void resetFPS() {
        lastCameraFPSResetTime = lastSDKFPSResetTime = SystemClock.elapsedRealtime();
        numberCameraFramesReceived = numberSDKFramesReceived = 0;
    }

    void startCamera() {
        if (isCameraStarted) {
            cameraView.stopCamera();
        }
        cameraView.startCamera(isCameraFront ? CameraHelper.CameraType.CAMERA_FRONT : CameraHelper.CameraType.CAMERA_BACK);
        isCameraStarted = true;
        asyncDetector.reset();
    }

    void stopCamera() {
        if (!isCameraStarted)
            return;

        cameraView.stopCamera();
        isCameraStarted = false;
    }


    @Override
    public void onResume() {
        setFilters();  // Start listening notifications from UsbService
        startService(UsbService.class, usbConnection, null); // Start UsbService(if it was not started before) and Bind it
        if (isSDKRunning) {
            asyncDetector.start();
        }
        if (isCameraRequestedByUser) {
            startCamera();
        }

        resetFPS();
        super.onResume();

    }

    @Override
    public void onPause() {
        super.onPause();
        if (asyncDetector.isRunning()) {
            asyncDetector.stop();
        }
        stopCamera();
        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);
    }

    String emotion, prev_emotion;

    float getScore(Metrics metric, Face face) {

        float score;
        switch (metric) {
            case ANGER:
                score = face.emotions.getAnger();
                emotion = "Anger";
                break;
            case CONTEMPT:
                score = face.emotions.getContempt();
                emotion = "Contempt";
                break;
            case DISGUST:
                score = face.emotions.getDisgust();
                emotion = "Disgust";
                break;
            case FEAR:
                score = face.emotions.getFear();
                emotion = "Fear";
                break;
            case JOY:
                score = face.emotions.getJoy();
                emotion = "Joy";
                break;
            case SADNESS:
                score = face.emotions.getSadness();
                emotion = "Sadness";
                break;
            case SURPRISE:
                score = face.emotions.getSurprise();
                emotion = "surprise";
                break;
            /*case ATTENTION:
                score = face.expressions.getAttention();
                break;
            case BROW_FURROW:
                score = face.expressions.getBrowFurrow();
                break;
            case BROW_RAISE:
                score = face.expressions.getBrowRaise();
                break;
            case CHIN_RAISER:
                score = face.expressions.getChinRaise();
                break;
            case ENGAGEMENT:
                score = face.emotions.getEngagement();
                break;
            case EYE_CLOSURE:
                score = face.expressions.getEyeClosure();
                break;
            case INNER_BROW_RAISER:
                score = face.expressions.getInnerBrowRaise();
                break;
            case LIP_DEPRESSOR:
                score = face.expressions.getLipCornerDepressor();
                break;
            case LIP_PRESS:
                score = face.expressions.getLipPress();
                break;
            case LIP_PUCKER:
                score = face.expressions.getLipPucker();
                break;
            case LIP_SUCK:
                score = face.expressions.getLipSuck();
                break;
            case MOUTH_OPEN:
                score = face.expressions.getMouthOpen();
                break;
            case NOSE_WRINKLER:
                score = face.expressions.getNoseWrinkle();
                break;
            case SMILE:
                score = face.expressions.getSmile();
                break;
            case SMIRK:
                score = face.expressions.getSmirk();
                break;
            case UPPER_LIP_RAISER:
                score = face.expressions.getUpperLipRaise();
                break;
            case VALENCE:
                score = face.emotions.getValence();
                break;
            case YAW:
                score = face.measurements.orientation.getYaw();
                break;
            case ROLL:
                score = face.measurements.orientation.getRoll();
                break;
            case PITCH:
                score = face.measurements.orientation.getPitch();
                break;
            case INTER_OCULAR_DISTANCE:
                score = face.measurements.getInterocularDistance();
                break;*/
            default:
                score = Float.NaN;
                emotion = "?";
                break;
        }
        return score;
    }

    @Override
    public void onCameraFrameAvailable(byte[] frame, int width, int height, Frame.ROTATE rotation) {
        numberCameraFramesReceived += 1;
        //cameraFPS.setText(String.format("CAM: %.3f", 1000f * (float) numberCameraFramesReceived / (SystemClock.elapsedRealtime() - lastCameraFPSResetTime)));

        float timeStamp = (float) SystemClock.elapsedRealtime() / 1000f;
        if (timeStamp > (lastTimestamp + epsilon)) {
            lastTimestamp = timeStamp;
            asyncDetector.process(createFrameFromData(frame, width, height, rotation), timeStamp);
        }
    }

    @Override
    public void onCameraStarted(boolean success, Throwable error) {
        //TODO: change status here
    }

    @Override
    public void onSurfaceViewSizeChanged() {
        asyncDetector.reset();
    }

    float lastReceivedTimestamp = -1f;

    @Override
    public void onImageResults(List<Face> faces, Frame image, float timeStamp) {
        //statusTextView.setText(String.format("Most recent time stamp: %.4f",timeStamp));
        if (timeStamp < lastReceivedTimestamp)
            throw new RuntimeException("Got a timestamp out of order!");
        lastReceivedTimestamp = timeStamp;
        Log.e("MainActivity", String.valueOf(timeStamp));

        if (faces == null)
            return; //No Face Detected
        if (faces.size() == 0) {
            for (Metrics metric : Metrics.getEmotions()) {
                if (metric != Metrics.ENGAGEMENT && metric != Metrics.VALENCE) {
                    metricsPanel.setMetricNA(metric);
                }
            }
        } else {
            float max = 0;
            Face face = faces.get(0);
            for (Metrics metric : Metrics.getEmotions()) {
                if (metric != Metrics.ENGAGEMENT && metric != Metrics.VALENCE) {
                    float zcore = getScore(metric, face);
                    metricsPanel.setMetricValue(metric, zcore);
                    if (zcore > max) {
                        max = zcore;
                        if (UsbService != null && zcore > 10) { // if UsbService was correctly binded, Send data {
                            String e = metric.name();
                            String f = e.substring(0, 1);
                            if (recieved == 'n')
                                UsbService.write(f.getBytes());
                            cameraFPS.setText(e);
                        } else if (UsbService != null) {
                            UsbService.write("?".getBytes());
                            cameraFPS.setText("No Emotion");
                        }
                    }
                }
            }
        }

        numberSDKFramesReceived += 1;
        //processorFPS.setText(String.format("SDK: %.3f", 1000f * (float) numberSDKFramesReceived / (SystemClock.elapsedRealtime() - lastSDKFPSResetTime)));

    }

    @Override
    public void onDetectorStarted() {

    }

    static Frame createFrameFromData(byte[] frameData, int width, int height, Frame.ROTATE rotation) {
        Frame.ByteArrayFrame frame = new Frame.ByteArrayFrame(frameData, width, height, Frame.COLOR_FORMAT.YUV_NV21);
        frame.setTargetRotation(rotation);
        return frame;
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                Set<String> keys = extras.keySet();
                for (String key : keys) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }

    /*
     * This handler will be passed to UsbService. Dara received from serial port is displayed through this handler
     */
    private static class MyHandler extends Handler {
        private final WeakReference<MainActivity> mActivity;

        public MyHandler(MainActivity activity) {
            mActivity = new WeakReference<MainActivity>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case com.felhr.serialportexample.UsbService.MESSAGE_FROM_SERIAL_PORT:
                    String data = (String) msg.obj;
                    //mActivity.get().display.append(data);
                    if (data.length() > 0) {
                        mActivity.get().processorFPS.setText(data);
                        mActivity.get().recieved = data.charAt(0);
                    }
                    break;
            }
        }
    }

    /*
     * Notifications from UsbService will be received here.
     */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if (arg1.getAction().equals(UsbService.ACTION_USB_PERMISSION_GRANTED)) // USB PERMISSION GRANTED
            {
                Toast.makeText(arg0, "USB Ready", Toast.LENGTH_SHORT).show();
            } else if (arg1.getAction().equals(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED)) // USB PERMISSION NOT GRANTED
            {
                Toast.makeText(arg0, "USB Permission not granted", Toast.LENGTH_SHORT).show();
            } else if (arg1.getAction().equals(UsbService.ACTION_NO_USB)) // NO USB CONNECTED
            {
                Toast.makeText(arg0, "No USB connected", Toast.LENGTH_SHORT).show();
            } else if (arg1.getAction().equals(UsbService.ACTION_USB_DISCONNECTED)) // USB DISCONNECTED
            {
                Toast.makeText(arg0, "USB disconnected", Toast.LENGTH_SHORT).show();
            } else if (arg1.getAction().equals(UsbService.ACTION_USB_NOT_SUPPORTED)) // USB NOT SUPPORTED
            {
                Toast.makeText(arg0, "USB device not supported", Toast.LENGTH_SHORT).show();
            }
        }
    };

    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            UsbService = ((UsbService.UsbBinder) arg1).getService();
            UsbService.setHandler(mHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            UsbService = null;
        }
    };
}
