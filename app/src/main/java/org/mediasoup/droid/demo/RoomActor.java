package org.mediasoup.droid.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.util.Log;

import com.nabinbhandari.android.permissions.PermissionHandler;
import com.nabinbhandari.android.permissions.Permissions;

import org.mediasoup.droid.Logger;
import org.mediasoup.droid.MediasoupClient;
import org.mediasoup.droid.lib.RoomClient;
import org.mediasoup.droid.lib.RoomOptions;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;
import java.util.LinkedList;


public class RoomActor {
    private static final String TAG = RoomActor.class.getSimpleName();
    private String mRoomId, mPeerId, mDisplayName;
    private boolean mForceH264, mForceVP9;

    private RoomOptions mOptions = new RoomOptions();
    private RoomClient mRoomClient;
    private Activity context;

    //volume change listener
    private AudioContentObserver audioContentObserver = null;

    public class AudioContentObserver extends ContentObserver {
        private AudioManager audioManager;

        public AudioContentObserver(Context context, Handler handler) {
            super(handler);
        }

        @Override
        public boolean deliverSelfNotifications() {
            return false;
        }

        @Override
        public void onChange(boolean selfChange) {
            audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

            Log.d(TAG, "Volume now " + currentVolume);
            if(mRoomClient.getAudioTrack() != null){
                float vol = currentVolume*1.0f/audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                Log.d(TAG, "Volume now " + vol);
                mRoomClient.getAudioTrack().setVolume(vol);
            }
        }
    }


    public RoomActor(Activity context,String roomID, int logLevel) {
        this.context = context;

        Logger.setLogLevel(Logger.LogLevel.values()[logLevel]);
        Logger.setDefaultHandler();
        MediasoupClient.initialize(context);
        mRoomId = roomID;
        loadRoomConfig();
        initRoomClient();
        checkPermission();
        //mRoomClient.join();

//        gpuImage = new GPUImage(this.context);
//        GPUImageI420RGBFilter transform = new GPUImageI420RGBFilter();
//        gpuImage.setFilter(transform);

        //additional control
        //volume
        audioContentObserver = new AudioContentObserver(context,null);//mRoomClient.getmWorkHandler()
        context.getApplicationContext().getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, audioContentObserver );
    }

    private void loadRoomConfig() {
        // Room initial config.
       // mRoomId = "immertec";//preferences.getString("roomId", "");
        mPeerId = "huong";
        mDisplayName = "huong";
        mForceH264 = false;
        mForceVP9 =false;


        // Room action config.
        mOptions.setProduce(true);
        mOptions.setConsume(true);
        mOptions.setForceTcp(false);

        // Device config.
        //String camera = "front");
        //PeerConnectionUtils.setPreferCameraFace(camera);
    }

    private void initRoomClient() {
        Log.d(TAG,"Room ID " + mRoomId);
        mRoomClient =
                new RoomClient(
                        this.context,  mRoomId, mPeerId, mDisplayName, mForceH264, mForceVP9, mOptions);
        mRoomClient.frameChecker = new RenderCallback();
    }


    private PermissionHandler permissionHandler =
            new PermissionHandler() {
                @Override
                public void onGranted() {
                    Logger.d(TAG, "permission granted");
                    mRoomClient.join();
                }
            };

    private void checkPermission() {
        String[] permissions = {
                Manifest.permission.INTERNET,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE,
                //Manifest.permission.CAMERA,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
        };
        String rationale = "Please provide permissions";
        Permissions.Options options =
                new Permissions.Options().setRationaleDialogTitle("Info").setSettingsDialogTitle("Warning");
        Permissions.check(this.context, permissions, rationale, options, permissionHandler);
    }

    public void onDestroy() {
        if (mRoomClient != null) {

            //remove volume change listener
            if(audioContentObserver != null)
            context.getApplicationContext().getContentResolver().unregisterContentObserver(audioContentObserver);
            mRoomClient.close();
            mRoomClient.dispose();
        }
    }


    //Unity interface
    private class RenderCallback implements VideoSink {
        final private int MAX_BUFFER = 2;
        RGBColor color = new RGBColor();
        private class RGBColor {
            public int r, g, b;

            //webrtc use ITU-R BT.601 conversion
            public void fromYUV(int y, int u, int v) {
                // Convert YUV to RGB
                r = (int) ((y - 16.0) * 255 / 219 + (v - 128.0) * 255 / 224 * 1.402);
                g = (int) ((y - 16.0) * 255 / 219 - (u - 128.0) * 255 / 224 * 1.772 * 0.114 / 0.587 - (v - 128.0) * 255 / 224 * 1.402 * 0.299 / 0.587);
                b = (int) ((y - 16.0) * 255 / 219 + (u - 128.0) * 255 / 224 * 1.772);

                // Clamp RGB values to [0,255]
                r = (r > 255) ? 255 : ((r < 0) ? 0 : r);
                g = (g > 255) ? 255 : ((g < 0) ? 0 : g);
                b = (b > 255) ? 255 : ((b < 0) ? 0 : b);
            }
        }

        @Override
        public void onFrame(VideoFrame videoFrame) {
            Log.d("RenderCallback", "render frame getRotatedWidth" + videoFrame.getRotatedWidth());

            //data prepare
            //extract frame data
            if(queueY.size() < MAX_BUFFER) {
                VideoFrame.I420Buffer i420 = videoFrame.getBuffer().toI420();
                 ByteBuffer y = i420.getDataY();
                ByteBuffer u = i420.getDataU();
                ByteBuffer v = i420.getDataV();
                RoomActor.width = i420.getWidth();
                RoomActor.height = i420.getHeight();
                //synchronized (locker)
                {
                    //Log.d("RenderCallback", "render frame y " + y.remaining());

                    if (RoomActor.y == null || RoomActor.y.length != y.remaining()) {
                        RoomActor.y = new byte[y.remaining()];
                        RoomActor.u = new byte[u.remaining()];
                        RoomActor.v = new byte[v.remaining()];
                    }
                    y.get(RoomActor.y);
                    u.get(RoomActor.u);
                    v.get(RoomActor.v);

                    queueY.push(RoomActor.y);
                    queueU.push(RoomActor.u);
                    queueV.push(RoomActor.v);
                }
                videoFrame.release();
            }

        }
    }

    static public int width = 0, height = 0;
    //static public byte[] currentFrame = new byte[width * height];
    static public byte[] currentFrameTmpY = null;// =  new byte[width * height];
    static public byte[] currentFrameTmpU = null ;//=  new byte[width * height / 2];
    static public byte[] currentFrameTmpV = null;// =  new byte[width * height / 2];
    static public  byte[] y = null,u = null,v = null;
    static public LinkedList<byte[]> queueY = new LinkedList<>();
    static public LinkedList<byte[]> queueU = new LinkedList<>();
    static public LinkedList<byte[]> queueV = new LinkedList<>();
    static public Object locker = new Object();


    //public interface
    public static byte[] getY() {
            currentFrameTmpY = queueY.pop();
            return currentFrameTmpY;
    }
    public static byte[] getU() {
        currentFrameTmpU = queueU.pop();
        return currentFrameTmpU;
    }

    public static byte[] getV() {
        currentFrameTmpV = queueV.pop();
        return currentFrameTmpV;
    }


    public static int getFrameWidth() {
        return RoomActor.width;
    }

    public static int getFrameHeight() {
        return RoomActor.height;
    }

    //check if no more frame;
    public static boolean isNewFrame() {
        return ! queueY.isEmpty();
    }

    //test

    public void testMic(){
        mRoomClient.enableMicImpl();
    }

}
