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
    private boolean secured;

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
            if (mRoomClient.getAudioTrack() != null) {
                float vol = currentVolume * 1.0f / audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                Log.d(TAG, "Volume now " + vol);
                mRoomClient.getAudioTrack().setVolume(vol);
            }
        }
    }


    public RoomActor(Activity context, String roomID, int logLevel, boolean secured) {
        this.context = context;
        this.secured = secured;
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
        audioContentObserver = new AudioContentObserver(context, null);//mRoomClient.getmWorkHandler()
        context.getApplicationContext().getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, audioContentObserver);
    }

    private void loadRoomConfig() {
        // Room initial config.
        // mRoomId = "immertec";//preferences.getString("roomId", "");
        mPeerId = "huong";
        mDisplayName = "huong";
        mForceH264 = false;
        mForceVP9 = false;


        // Room action config.
        mOptions.setProduce(true);
        mOptions.setConsume(true);
        mOptions.setForceTcp(false);

        // Device config.
        //String camera = "front");
        //PeerConnectionUtils.setPreferCameraFace(camera);
    }

    private void initRoomClient() {
        Log.d(TAG, "Room ID " + mRoomId);
        mRoomClient =
                new RoomClient(
                        this.context, mRoomId, mPeerId, mDisplayName, mForceH264, mForceVP9, mOptions, this.secured);
        //mRoomClient.frameChecker = new RenderCallback();
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
            if (audioContentObserver != null)
                context.getApplicationContext().getContentResolver().unregisterContentObserver(audioContentObserver);
            mRoomClient.close();
            mRoomClient.dispose();
        }
    }
    //test
    public void testMic() {
        mRoomClient.enableMicImpl();
    }

    public static boolean isNewFrame(int index){
        return  RoomClient.onFrameListeners.get(index).isNewFrame();
    }
    public static int getCount(){
        return  RoomClient.onFrameListeners.size();
    }
    public static int getFrameWidth(int index){
        return  RoomClient.onFrameListeners.get(index).getFrameWidth();
    }
    public static int getFrameHeight(int index){
        return  RoomClient.onFrameListeners.get(index).getFrameHeight();
    }

    public static byte[] getY(int index){
        return  RoomClient.onFrameListeners.get(index).getY();
    }
    public static byte[] getU(int index){
        return  RoomClient.onFrameListeners.get(index).getU();
    }

}
