package org.mediasoup.droid.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Choreographer;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mediasoup.droid.Consumer;
import org.mediasoup.droid.Device;
import org.mediasoup.droid.Logger;
import org.mediasoup.droid.MediasoupException;
import org.mediasoup.droid.Producer;
import org.mediasoup.droid.RecvTransport;
import org.mediasoup.droid.SendTransport;
import org.mediasoup.droid.Transport;
import org.mediasoup.droid.demo.RoomActor;
import org.mediasoup.droid.lib.socket.WebSocketTransport;
import org.protoojs.droid.Message;
import org.protoojs.droid.ProtooException;
import org.webrtc.AudioTrack;
import org.webrtc.MediaCodecVideoDecoder;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;
import org.webrtc.voiceengine.WebRtcAudioManager;

import java.io.File;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.CompositeDisposable;

import static org.mediasoup.droid.lib.JsonUtils.jsonPut;
import static org.mediasoup.droid.lib.JsonUtils.toJsonObject;

//socketio IF
import com.github.nkzawa.emitter.Emitter;
import com.github.nkzawa.socketio.client.Ack;
import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

@SuppressWarnings("WeakerAccess")
public class RoomClient {

    public enum ConnectionState {
        // initial state.
        NEW,
        // connecting or reconnecting.
        CONNECTING,
        // connected.
        CONNECTED,
        // mClosed.
        CLOSED,
    }

    // Closed flag.
    private boolean mClosed = false;
    // Android context.
    private Context mContext = null;
    // Room mOptions.
    private final @NonNull
    RoomOptions mOptions;
    // Display name.
    private String mDisplayName;
    // Protoo URL.
    private String mProtooUrl;
    private Socket mSocket;
    // mProtoo-client Protoo instance.
    private Protoo mProtoo;
    // mediasoup-client Device instance.
    private Device mMediasoupDevice = null;
    // mediasoup Transport for sending.
    private SendTransport mSendTransport = null;
    // mediasoup Transport for receiving.
    private RecvTransport mRecvTransport = null;
    //private PlainRTCTransport mRecvTransport;
    // Local Audio Track for mic.
    private AudioTrack mLocalAudioTrack = null;
    // Local mic mediasoup Producer.
    private Producer mMicProducer = null;
    // local Video Track for cam.
    private VideoTrack mLocalVideoTrack = null;
    // jobs worker handler.
    private final Handler mWorkHandler;
    private final Handler mMainHandler;
    // Disposable Composite. used to cancel running
    private CompositeDisposable mCompositeDisposable = new CompositeDisposable();
    // Share preferences
    private SharedPreferences mPreferences;

    //External rendering //FIXME private
    public static List<RenderCallback> onFrameListeners = new ArrayList<RenderCallback>();

    //Additional control for audio consumer
    private AudioTrack audioTrack = null;

    private static String TAG = "RoomClient";

    public AudioTrack getAudioTrack() {
        return audioTrack;
    }

    public RoomClient(
            Context context, String roomId, String peerId, String displayName, boolean secured) {
        this(context, roomId, peerId, displayName, false, false, null, secured);
    }

    public RoomClient(
            Context context,
            String roomId,
            String peerId,
            String displayName,
            RoomOptions options, boolean secured) {
        this(context, roomId, peerId, displayName, false, false, options, secured);
    }

    //returns whether the microphone is available
    private static boolean validateMicAvailability() {
        Boolean available = true;
        AudioRecord recorder =
                new AudioRecord(MediaRecorder.AudioSource.MIC, 44100,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_DEFAULT, 44100);
        try {
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_STOPPED) {
                available = false;

                Log.w(TAG, "MIC is busy");

            }

            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop();
                available = false;
                Log.w(TAG, "Stop MIC");

            }
            recorder.stop();
        } finally {
            Log.w(TAG, "Release MIC");
            recorder.release();
            recorder = null;
        }

        return available;
    }

    public RoomClient(
            Context context,
            String roomId,
            String peerId,
            String displayName,
            boolean forceH264,
            boolean forceVP9,
            RoomOptions options, boolean secured) {
        //super(roomStore);
        this.mContext = context.getApplicationContext();
        this.mOptions = options == null ? new RoomOptions() : options;
        this.mDisplayName = displayName;
        this.mClosed = false;
        this.mProtooUrl = UrlFactory.getSocketIOQuery(roomId, peerId, forceH264, forceVP9);

        // this.mStore.setMe(peerId, displayName, this.mOptions.getDevice());
        // this.mStore.setRoomUrl(roomId, UrlFactory.getInvitationLink(roomId, forceH264, forceVP9));

        // support for selfSigned cert.
        UrlFactory.enableSelfSignedHttpClient();

        // init worker handler.
        HandlerThread handlerThread = new HandlerThread("worker");
        handlerThread.start();
        mWorkHandler = new Handler(handlerThread.getLooper());
        mMainHandler = new Handler(Looper.getMainLooper());

        WebRtcAudioManager.setBlacklistDeviceForOpenSLESUsage(true);

        //check microphone
//        if(!validateMicAvailability()){
//            Log.e(TAG,"Mic in use !");
//        }
    }

    public Handler getmWorkHandler() {
        return mWorkHandler;
    }


    private Emitter.Listener onRoomJoined = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            mWorkHandler.post(() -> {
                for (Object arg : args)
                    Log.d(TAG, "onRoomJoined " + arg.toString());
                try {
                    String rtpCapabilities = ((JSONObject) args[0]).getString("rtpCapabilities");
                    joinImpl(rtpCapabilities, null);
                } catch (JSONException e) {
                    e.printStackTrace();
                }


            });
        }
    };
    private Emitter.Listener onRoomError = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            mWorkHandler.post(() -> {
                for (Object arg : args)
                    Log.d(TAG, "onRoomError " + arg.toString());
                if (mClosed) {
                    return;
                }
                close();
            });
        }
    };
    VideoTrack videoTrack = null;
    private Emitter.Listener onConsumerNew = new Emitter.Listener() {
        @Override
        public void call(final Object... args) {
            //mWorkHandler.post(() ->
            {
                for (Object arg : args)
                    Log.d(TAG, "onConsumerNew " + arg.toString());
                //id, producerId, kind, rtpParameters, appData
                JSONObject obj = (JSONObject) args[0];
                Consumer consumer =
                        null;
                try {
                    Log.d(TAG, "onNewConsumer " + obj.getString("producerId") + " rtpParameters " + obj.getString("rtpParameters"));

                    consumer = mRecvTransport.consume(
                            c -> {
                                // mConsumers.remove(c.getId());d
                                Logger.w(TAG, "onTransportClose for consume");
                            },
                            obj.getString("id"),
                            obj.getString("producerId"),
                            obj.getString("kind"),
                            obj.getString("rtpParameters"),
                            obj.getString("appData"));
                } catch (MediasoupException | JSONException e) {
                    e.printStackTrace();
                }

                //mConsumers.put(consumer.getId(), new ConsumerHolder(peerId, consumer));
                // mStore.addConsumer(peerId, type, consumer, producerPaused);

                Log.d(TAG, "onNewConsumer " + consumer.getTrack().kind());
                if (consumer.getTrack() != null && consumer.getTrack().kind().indexOf("video") >= 0) {
                    Log.d(TAG, "onNewConsumer addSink " + consumer.getId());
                    VideoTrack video = (VideoTrack) consumer.getTrack();
                    RenderCallback listener = new RenderCallback();
                    onFrameListeners.add(listener);
                    listener.setUuid(consumer.getId());
                    video.addSink(listener);
                }
                //we get audio track to control volume. TODO: Support multiple tracks
                else if (consumer.getTrack() != null && consumer.getTrack().kind().indexOf("audio") >= 0) {
                    audioTrack = (AudioTrack) consumer.getTrack();

                    //test
                    audioTrack.setVolume(0.5);
                }

                // Tell media server to resume the Consumer which was paused
                Ack ack = (Ack) args[args.length - 1];
                ack.call();
            }
            //);
        }
    };


    @WorkerThread
    public void join() {
        mWorkHandler.post(
                () -> {
                    Logger.d(TAG, "join() " + this.mProtooUrl);
                    //mStore.setRoomState(ConnectionState.CONNECTING);
                    //WebSocketTransport transport = new WebSocketTransport(mProtooUrl);
                    //mProtoo = new Protoo(transport, peerListener);
                    try {

                        IO.Options opts = new IO.Options();
                        opts.query = "token=" + Base64.encodeToString(mProtooUrl.getBytes(), Base64.DEFAULT);
                        mSocket = IO.socket(UrlFactory.getHOSTNAME(), opts);
                        mSocket.on("room-joined", onRoomJoined);
                        mSocket.on("room-error", onRoomError);
                        mSocket.on("consumer-new", onConsumerNew);
                        mSocket.connect();
                    } catch (URISyntaxException e) {
                        Log.d(TAG, "socket.io " + e.getMessage());
                    }
                });
    }

    public void enableMic() {

        Logger.d(TAG, "enableMic");

        mWorkHandler.post(() -> {
            enableMicImpl();
        });
    }

    @WorkerThread
    public void enableMicImpl() {
        Logger.w(TAG, "enableMic");
        if (!mMediasoupDevice.isLoaded()) {
            Logger.w(TAG, "enableMic() | not loaded");
            return;
        }
        try {
            if (!mMediasoupDevice.canProduce("audio")) {
                Logger.w(TAG, "enableMic() | cannot produce audio");
                return;
            }
        } catch (MediasoupException e) {
            e.printStackTrace();
        }
        if (mSendTransport == null) {
            Logger.w(TAG, "enableMic() | mSendTransport doesn't ready");
            return;
        }
        if (mLocalAudioTrack == null) {
            mLocalAudioTrack = PeerConnectionUtils.createAudioTrack(mContext, "mic");
            mLocalAudioTrack.setEnabled(true);
        }
        try {
            if (mMicProducer == null) {

                Logger.w(TAG, "enableMic produce");
                mMicProducer =
                        mSendTransport.produce(
                                producer -> {
                                    Logger.e(TAG, "onTransportClose(), micProducer");
                                },
                                mLocalAudioTrack,
                                null,
                                null);
                Logger.w(TAG, "enableMic produce DONE");
            }
        } catch (MediasoupException e) {
            e.printStackTrace();
        }
        //mStore.addProducer(mMicProducer);
    }


    @WorkerThread
    public void restartIce() {
        Logger.d(TAG, "restartIce()");
        //mStore.setRestartIceInProgress(true);
        mWorkHandler.post(
                () -> {
                    try {
                        if (mSendTransport != null) {
                            String iceParameters =
                                    mProtoo.syncRequest(
                                            "restartIce", req -> jsonPut(req, "transportId", mSendTransport.getId()));
                            mSendTransport.restartIce(iceParameters);
                        }
                        if (mRecvTransport != null) {
                            String iceParameters =
                                    mProtoo.syncRequest(
                                            "restartIce", req -> jsonPut(req, "transportId", mRecvTransport.getId()));
                            mRecvTransport.restartIce(iceParameters);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        logError("restartIce() | failed:", e);
                        // mStore.addNotify("error", "ICE restart failed: " + e.getMessage());
                    }
                    //mStore.setRestartIceInProgress(false);
                });
    }

    @WorkerThread
    public void close() {
        if (this.mClosed) {
            return;
        }
        this.mClosed = true;
        Logger.d(TAG, "close()");

        mWorkHandler.post(
                () -> {
                    // Close mProtoo Protoo
                    if (mProtoo != null) {
                        mProtoo.close();
                        mProtoo = null;
                    }

                    closeTransportAndDevice();

                    // dispose track and media source.
                    if (mLocalAudioTrack != null) {
                        mLocalAudioTrack.dispose();
                        mLocalAudioTrack = null;
                    }
                    if (mLocalVideoTrack != null) {
                        mLocalVideoTrack.dispose();
                        mLocalVideoTrack = null;
                    }

                });


        // mStore.setRoomState(ConnectionState.CLOSED);
    }

    private void closeTransportAndDevice() {
        Logger.d(TAG, "closeTransportAndDevice()");
        // Close mediasoup Transports.
        if (mSendTransport != null) {
            mSendTransport.close();
            mSendTransport.dispose();
            mSendTransport = null;
        }

        if (mRecvTransport != null) {
            mRecvTransport.close();
            mRecvTransport.dispose();
            mRecvTransport = null;
        }

        // dispose device.
        if (mMediasoupDevice != null) {
            mMediasoupDevice.dispose();
            mMediasoupDevice = null;
        }
    }

    public void dispose() {
        // quit worker handler thread.
        mWorkHandler.getLooper().quit();

        // dispose request.
        mCompositeDisposable.dispose();

        // dispose PeerConnectionUtils.
        PeerConnectionUtils.dispose();
    }

    private Protoo.Listener peerListener =
            new Protoo.Listener() {
                @Override
                public void onOpen() {
                    mWorkHandler.post(() -> joinImpl(null, null));
                }

                @Override
                public void onFail() {
                    mWorkHandler.post(
                            () -> {
                                // mStore.addNotify("error", "WebSocket connection failed");
                                // mStore.setRoomState(ConnectionState.CONNECTING);
                            });
                }

                @Override
                public void onRequest(
                        @NonNull Message.Request request, @NonNull Protoo.ServerRequestHandler handler) {
                    Logger.d(TAG, "onRequest() " + request.getData().toString());
                    mMainHandler.post(
                            () -> {
                                try {
                                    switch (request.getMethod()) {
                                        case "newConsumer": {
                                            onNewConsumer(request, handler);
                                            break;
                                        }
                                        case "newDataConsumer": {
                                            onNewDataConsumer(request, handler);
                                            break;
                                        }
                                        default: {
                                            handler.reject(403, "unknown protoo request.method " + request.getMethod());
                                            Logger.w(TAG, "unknown protoo request.method " + request.getMethod());
                                        }
                                    }
                                } catch (Exception e) {
                                    Logger.e(TAG, "handleRequestError.", e);
                                }
                            });
                }

                @Override
                public void onNotification(@NonNull Message.Notification notification) {
                    Logger.d(
                            TAG,
                            "onNotification() "
                                    + notification.getMethod()
                                    + ", "
                                    + notification.getData().toString());
                    mWorkHandler.post(
                            () -> {
                                try {
                                    //handleNotification(notification);
                                } catch (Exception e) {
                                    Logger.e(TAG, "handleNotification error.", e);
                                }
                            });
                }

                @Override
                public void onDisconnected() {
                    mWorkHandler.post(
                            () -> {
                                //mStore.addNotify("error", "WebSocket disconnected");
                                //mStore.setRoomState(ConnectionState.CONNECTING);

                                // Close All Transports created by device.
                                // All will reCreated After ReJoin.
                                closeTransportAndDevice();
                            });
                }

                @Override
                public void onClose() {
                    mWorkHandler.post(
                            () -> {
                                if (mClosed) {
                                    return;
                                }
                                close();
                            });
                }
            };

    @WorkerThread
    private void joinImpl(String routerRtpCapabilities, JSONObject peers) {
        Logger.d(TAG, "joinImpl()");

        try {
            Log.d(TAG, "createRecvTransport  routerRtpCapabilities " + routerRtpCapabilities);
            mMediasoupDevice = new Device();
            mMediasoupDevice.load(routerRtpCapabilities);
            String rtpCapabilities = mMediasoupDevice.getRtpCapabilities();
            Log.d(TAG, "createRecvTransport  rtpCapabilities " + rtpCapabilities);

            // Create mediasoup Transport for sending (unless we don't want to produce).
            mOptions.setProduce(false);
            if (mOptions.isProduce()) {
                //createSendTransport();
            }

            // Create mediasoup Transport for sending (unless we don't want to consume).
            // if (mOptions.isConsume()) {
            createRecvTransport();
            // }


            // Enable mic/webcam.
            if (mOptions.isProduce()) {
                Log.d(TAG, "isProduce TRUE");
                boolean canSendMic = mMediasoupDevice.canProduce("audio");
                boolean canSendCam = false;//NOT USE//mMediasoupDevice.canProduce("video");
                //mStore.setMediaCapabilities(canSendMic, canSendCam);
                if (canSendMic) {
                    Log.d(TAG, "canSendMic TRUE");
                    //mMainHandler.post(() -> {
                        enableMic();
                   // });
                }
                // mWorkHandler.post(this::enableCam);
            }
        } catch (Exception e) {
            e.printStackTrace();
            logError("joinRoom() failed:", e);
            //mStore.addNotify("error", "Could not join the room: " + e.getMessage());
            mMainHandler.post(() -> {
                close();
            });
        }
    }

    @WorkerThread
    private void createSendTransport() throws JSONException, ProtooException {
        Logger.d(TAG, "createSendTransport()");
        String res =
                mProtoo.syncRequest(
                        "createWebRtcTransport",
                        (req -> {
                            jsonPut(req, "forceTcp", mOptions.isForceTcp());
                            jsonPut(req, "producing", true);
                            jsonPut(req, "consuming", false);
                            // TODO: sctpCapabilities
                            jsonPut(req, "sctpCapabilities", "");
                        }));
        JSONObject info = new JSONObject(res);

        Logger.d(TAG, "device#createSendTransport() " + info);
        String id = info.optString("id");
        String iceParameters = info.optString("iceParameters");
        String iceCandidates = info.optString("iceCandidates");
        String dtlsParameters = info.optString("dtlsParameters");
        String sctpParameters = info.optString("sctpParameters");

        try {
            mSendTransport =
                    mMediasoupDevice.createSendTransport(
                            sendTransportListener, id, iceParameters, iceCandidates, dtlsParameters);
        } catch (MediasoupException e) {
            e.printStackTrace();
        }
    }

    @WorkerThread
    private void createRecvTransport() throws Exception {
        Logger.d(TAG, "createRecvTransport()");

        mSocket.emit("transport-create", "receive", (Ack) args -> {
            for (Object arg : args)
                Log.d(TAG, "transport-create  receive " + arg.toString());
            JSONObject obj = (JSONObject) args[0];
            try {
//                    JSONObject iceServers = toJsonObject("[{\"urls\": [\"turn:relay.immertec.com:5349\"," +
//                            " \"turn:relay.immertec.com:3478\"], \"username\": \"ftnk\", \"credential\": \"ftnk\"}," +
//                            " {\"urls\": \"stun:stun.l.google.com:19302\"}]");
                mRecvTransport =
                        mMediasoupDevice.createRecvTransport(
                                recvTransportListener, obj.getString("id"), obj.getString("iceParameters"),
                                obj.getString("iceCandidates"), obj.getString("dtlsParameters"));


                // Tell media server that we're ready to consume media stream
                JSONObject mediaData = new JSONObject();
                JSONObject deviceRtpCapabilities = new JSONObject(mMediasoupDevice.getRtpCapabilities());
                mediaData.put("rtpCapabilities", deviceRtpCapabilities);
                mSocket.emit("media-ready", mediaData);

            } catch (MediasoupException | JSONException e) {
                e.printStackTrace();
            }

        });
    }

    private SendTransport.Listener sendTransportListener =
            new SendTransport.Listener() {

                private String listenerTAG = TAG + "_SendTrans";

                @Override
                public String onProduce(
                        Transport transport, String kind, String rtpParameters, String appData) {
                    Logger.d(listenerTAG, "onProduce() ");
                    String producerId =
                            fetchProduceId(
                                    req -> {
                                        jsonPut(req, "transportId", transport.getId());
                                        jsonPut(req, "kind", kind);
                                        jsonPut(req, "rtpParameters", toJsonObject(rtpParameters));
                                        jsonPut(req, "appData", appData);
                                    });
                    Logger.d(listenerTAG, "producerId: " + producerId);
                    return producerId;
                }

                @Override
                public void onConnect(Transport transport, String dtlsParameters) {
                    Logger.d(listenerTAG + "_send", "onConnect()");
                    //{ transportId: receiveTransport.id, dtlsParameters }

                }

                @Override
                public void onConnectionStateChange(Transport transport, String connectionState) {
                    Logger.d(listenerTAG, "onConnectionStateChange: " + connectionState);
                }
            };

    private RecvTransport.Listener recvTransportListener =
            new RecvTransport.Listener() {

                private String listenerTAG = TAG + "_RecvTrans";

                @Override
                public void onConnect(Transport transport, String dtlsParameters) {
                    Logger.d(listenerTAG, "onConnect()");
                    JSONObject opt = new JSONObject();
                    try {
                        opt.put("transportId", transport.getId());
                        opt.put("dtlsParameters", toJsonObject(dtlsParameters));
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    mSocket.emit("transport-connect", opt,new Ack() {
                        @Override
                        public void call(Object... args) {
                            String errMessage = (String) args[0];
                            if (errMessage != null) {
                                Log.e("media", errMessage);
                            } else {
                                Log.d("media", "Transport connected: " + transport.getId() + " [receive]");
                            }
                        }
                    });
                }

                @Override
                public void onConnectionStateChange(Transport transport, String connectionState) {
                    Logger.d(listenerTAG, "onConnectionStateChange: " + connectionState);
                }
            };

    private String fetchProduceId(Protoo.RequestGenerator generator) {
        StringBuffer result = new StringBuffer();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        mCompositeDisposable.add(
                mProtoo
                        .request("produce", generator)
                        .map(data -> toJsonObject(data).optString("id"))
                        .subscribe(
                                id -> {
                                    result.append(id);
                                    countDownLatch.countDown();
                                },
                                t -> {
                                    logError("send produce request failed", t);
                                    countDownLatch.countDown();
                                }));
        try {
            // TODO(HaiyangWU): timeout or better solution ?
            countDownLatch.await(5000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return result.toString();
    }

    private void logError(String message, Throwable t) {
        Logger.e(TAG, message, t);
    }

    private void onNewConsumer(Message.Request request, Protoo.ServerRequestHandler handler) {
        if (!mOptions.isConsume()) {
            handler.reject(403, "I do not want to consume");
            return;
        }
    }

    private void onNewDataConsumer(Message.Request request, Protoo.ServerRequestHandler handler) {
        handler.reject(403, "I do not want to data consume");
        Logger.d(TAG, "onNewDataConsumer() " + request.toString());
        // TODO(HaiyangWu): support data consume
    }

    @WorkerThread
    private void pauseConsumer(Consumer consumer) {
        Logger.d(TAG, "pauseConsumer() " + consumer.getId());
        if (consumer.isPaused()) {
            return;
        }

        try {
            mProtoo.syncRequest("pause" + "", req -> jsonPut(req, "consumerId", consumer.getId()));
            consumer.pause();
            // mStore.setConsumerPaused(consumer.getId(), "local");
        } catch (Exception e) {
            e.printStackTrace();
            logError("pauseConsumer() | failed:", e);
            //mStore.addNotify("error", "Error pausing Consumer: " + e.getMessage());
        }
    }

    @WorkerThread
    private void resumeConsumer(Consumer consumer) {
        Logger.d(TAG, "resumeConsumer() " + consumer.getId());
        if (!consumer.isPaused()) {
            return;
        }

        try {
            mProtoo.syncRequest("resumeConsumer", req -> jsonPut(req, "consumerId", consumer.getId()));
            consumer.resume();
            //mStore.setConsumerResumed(consumer.getId(), "local");
        } catch (Exception e) {
            e.printStackTrace();
            logError("resumeConsumer() | failed:", e);
            //mStore.addNotify("error", "Error resuming Consumer: " + e.getMessage());
        }
    }




    //Unity interface
    public class RenderCallback implements VideoSink {
        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        private String uuid = "";
        final private int MAX_BUFFER = 1;
        public int width = 0, height = 0;
        //static public byte[] currentFrame = new byte[width * height];
        public byte[] currentFrameTmpY = null;// =  new byte[width * height];
        public byte[] currentFrameTmpU = null;//=  new byte[width * height / 2];
        public byte[] currentFrameTmpV = null;// =  new byte[width * height / 2];
        public byte[] y = null, u = null, v = null;
        public LinkedList<byte[]> queueY = new LinkedList<>();
        public LinkedList<byte[]> queueU = new LinkedList<>();
        public LinkedList<byte[]> queueV = new LinkedList<>();
        public Object locker = new Object();


        //public interface
        public byte[] getY() {
            currentFrameTmpY = queueY.pop();
            return currentFrameTmpY;
        }

        public byte[] getU() {
            currentFrameTmpU = queueU.pop();
            return currentFrameTmpU;
        }

        public byte[] getV() {
            currentFrameTmpV = queueV.pop();
            return currentFrameTmpV;
        }


        public int getFrameWidth() {
            return width;
        }

        public int getFrameHeight() {
            return height;
        }

        //check if no more frame;
        public boolean isNewFrame() {
            return !queueY.isEmpty();
        }

        //DEPRECATED
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
            //\\Log.d("RenderCallback", "render frame getRotatedWidth" + videoFrame.getRotatedWidth() +" "+videoFrame.getTimestampNs());
            if (queueY.size() < MAX_BUFFER) {
                VideoFrame.I420Buffer i420 = videoFrame.getBuffer().toI420();
                ByteBuffer ybuf = i420.getDataY();
                ByteBuffer ubuf = i420.getDataU();
                ByteBuffer vbuf = i420.getDataV();
                width = i420.getWidth();
                height = i420.getHeight();
                //synchronized (locker)
                {
                    //Log.d("RenderCallback", "render frame y " + y.remaining());

                    if (y == null || y.length != ybuf.remaining()) {
                        y = new byte[ybuf.remaining()];
                        u = new byte[ubuf.remaining() + vbuf.remaining()];
                        //RoomActor.v = new byte[v.remaining()];
                    }
                    ybuf.get(y);
                    ubuf.get(u,0,u.length / 2);
                    vbuf.get(u, u.length / 2, u.length / 2);

                    queueY.push(y);
                    queueU.push(u);
                    //queueV.push(RoomActor.v);
                }

                videoFrame.release();
            }
        }
    }

}
