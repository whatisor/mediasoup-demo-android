package org.mediasoup.droid.demo;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.KeyEvent;

import com.nabinbhandari.android.permissions.PermissionHandler;
import com.nabinbhandari.android.permissions.Permissions;

import org.mediasoup.droid.Logger;
import org.mediasoup.droid.MediasoupClient;
import org.mediasoup.droid.lib.RoomClient;
import org.mediasoup.droid.lib.RoomOptions;
import org.mediasoup.droid.lib.lv.RoomStore;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;

import java.nio.ByteBuffer;
import java.util.LinkedList;

import jp.co.cyberagent.android.gpuimage.GPUImage;

public class RoomActor {
    private static final String TAG = RoomActor.class.getSimpleName();
    private String mRoomId, mPeerId, mDisplayName;
    private boolean mForceH264, mForceVP9;

    private RoomOptions mOptions = new RoomOptions();
    private RoomStore mRoomStore = null;//new RoomStore();
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
                mRoomClient.getAudioTrack().setVolume(currentVolume);
            }
        }
    }


    public RoomActor(Activity context,String roomID) {
        this.context = context;

        Logger.setLogLevel(Logger.LogLevel.LOG_WARN);
        Logger.setDefaultHandler();
        MediasoupClient.initialize(context);
        mRoomId = roomID;
        loadRoomConfig();
        initRoomClient();
        //checkPermission();
        mRoomClient.join();

//        gpuImage = new GPUImage(this.context);
//        GPUImageI420RGBFilter transform = new GPUImageI420RGBFilter();
//        gpuImage.setFilter(transform);

        //additional control
        //volume
        audioContentObserver = new AudioContentObserver(context,null);//mRoomClient.getmWorkHandler()
        context.getApplicationContext().getContentResolver().registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, audioContentObserver );
    }

    private void loadRoomConfig() {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this.context);

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
                        this.context, mRoomStore, mRoomId, mPeerId, mDisplayName, mForceH264, mForceVP9, mOptions);
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
                Manifest.permission.CAMERA,
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
        final private int MAX_BUFFER = 5;
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
            //Log.d("RenderCallback", "render frame getRotatedWidth" + videoFrame.getRotatedWidth());
            //data prepare
            //extract frame data
            if(true) {
                VideoFrame.I420Buffer i420 = videoFrame.getBuffer().toI420();
                ByteBuffer y = i420.getDataY();
                ByteBuffer u = i420.getDataU();
                ByteBuffer v = i420.getDataV();
                int width = i420.getWidth();
                int height = i420.getHeight();
                //synchronized (locker)
                {
                    int n = width * height;
                    if (RoomActor.currentFrame.length != n * 3) {
                        RoomActor.currentFrame = new byte[n * 3];
                    }
                    RoomActor.width = width;
                    RoomActor.height = height;
                    //Log.d(TAG, "renderFrame  frame.yuvPlanes[0] " + y.capacity());
                    //Log.d(TAG, "renderFrame  frame.yuvPlanes[1] " + u.capacity());
                    //Log.d(TAG, "renderFrame  frame.yuvPlanes[2] " + v.capacity());

                    //cpu conversion
                    if (true) {
                        for (int i = 0; i < height; i++) {
                            for (int j = 0; j < width; j++) {
                                int index = i * width + j;
                                //color.fromYUV(y.get(index) & 0xff, u.get((i / 2) * width / 2 + j / 2) & 0xff,
                                //      v.get((i / 2) * width / 2 + j / 2) & 0xff);
                                RoomActor.currentFrame[index * 3] = y.get(index);//(byte) color.r;
                                RoomActor.currentFrame[index * 3 + 1] = u.get((i / 2) * width / 2 + j / 2);//(byte) color.g;
                                RoomActor.currentFrame[index * 3 + 2] = v.get((i / 2) * width / 2 + j / 2);///(byte) color.b;
                            }

                        }

                        //while(queue.size() > MAX_BUFFER){
                        //    queue.pop();
                        //}
                        if(queue.size() < MAX_BUFFER)
                            queue.push(RoomActor.currentFrame);
                        //Log.d("RenderCallback ","" +queue.size());
                    } else {//gpu conversion
                        //Bitmap bmp = BitmapFactory.
                        //gpuImage.setImage(bitmap);

                    }
                }
            }
            videoFrame.release();

        }
    }

    static public int width = 1, height = 1;
    static public byte[] currentFrame = new byte[width * height * 3];
    static public byte[] currentFrameTmp = new byte[width * height * 3];
    static public LinkedList<byte[]> queue = new LinkedList<>();
    static public Object locker = new Object();
    static public boolean isUsed = false;


    //public interface
    public static byte[] getFrame() {
        //Log.d("RenderCallback", "getFrame");
        //synchronized (locker)
        {
            if(queue.isEmpty())return null;
            currentFrameTmp = queue.pop();
        }
        return currentFrameTmp;
    }

    public static int getFrameWidth() {
        return RoomActor.width;
    }

    public static int getFrameHeight() {
        return RoomActor.height;
    }

}
