package org.mediasoup.droid.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

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
import org.mediasoup.droid.lib.socket.WebSocketTransport;
import org.protoojs.droid.Message;
import org.protoojs.droid.ProtooException;
import org.webrtc.AudioTrack;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.CompositeDisposable;

import static org.mediasoup.droid.lib.JsonUtils.jsonPut;
import static org.mediasoup.droid.lib.JsonUtils.toJsonObject;

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
  private boolean mClosed;
  // Android context.
  private final Context mContext;
  // Room mOptions.
  private final @NonNull RoomOptions mOptions;
  // Display name.
  private String mDisplayName;
  // Protoo URL.
  private String mProtooUrl;
  // mProtoo-client Protoo instance.
  private Protoo mProtoo;
  // mediasoup-client Device instance.
  private Device mMediasoupDevice;
  // mediasoup Transport for sending.
  private SendTransport mSendTransport;
  // mediasoup Transport for receiving.
  private RecvTransport mRecvTransport;
  //private PlainRTCTransport mRecvTransport;
  // Local Audio Track for mic.
  private AudioTrack mLocalAudioTrack;
  // Local mic mediasoup Producer.
  private Producer mMicProducer;
  // local Video Track for cam.
  private VideoTrack mLocalVideoTrack;
  // Local cam mediasoup Producer.
  private Producer mCamProducer;
  // TODO(Haiyangwu): Local share mediasoup Producer.
  private Producer mShareProducer;
  // TODO(Haiyangwu): Local chat DataProducer.
  private Producer mChatDataProducer;
  // TODO(Haiyangwu): Local bot DataProducer.
  private Producer mBotDataProducer;
  // jobs worker handler.
  private Handler mWorkHandler;
  private Handler mMainHandler;
  // Disposable Composite. used to cancel running
  private CompositeDisposable mCompositeDisposable = new CompositeDisposable();
  // Share preferences
  private SharedPreferences mPreferences;

  //External rendering //FIXME private
  public VideoSink frameChecker = null;

  //Additional control for audio consumer
  private AudioTrack audioTrack = null;

  private static String TAG = "RoomClient";

  public  AudioTrack getAudioTrack(){
    return audioTrack;
  }

  public RoomClient(
      Context context,  String roomId, String peerId, String displayName) {
    this(context,  roomId, peerId, displayName, false, false, null);
  }

  public RoomClient(
      Context context,
      String roomId,
      String peerId,
      String displayName,
      RoomOptions options) {
    this(context, roomId, peerId, displayName, false, false, options);
  }

  public RoomClient(
      Context context,
      String roomId,
      String peerId,
      String displayName,
      boolean forceH264,
      boolean forceVP9,
      RoomOptions options) {
    //super(roomStore);
    this.mContext = context.getApplicationContext();
    this.mOptions = options == null ? new RoomOptions() : options;
    this.mDisplayName = displayName;
    this.mClosed = false;
    this.mProtooUrl = UrlFactory.getProtooUrl(roomId, peerId, forceH264, forceVP9);

   // this.mStore.setMe(peerId, displayName, this.mOptions.getDevice());
   // this.mStore.setRoomUrl(roomId, UrlFactory.getInvitationLink(roomId, forceH264, forceVP9));

    // support for selfSigned cert.
    UrlFactory.enableSelfSignedHttpClient();

    // init worker handler.
    HandlerThread handlerThread = new HandlerThread("worker");
      handlerThread.setPriority(Thread.MAX_PRIORITY);
    handlerThread.start();
    mWorkHandler = new Handler(handlerThread.getLooper());
    mMainHandler = new Handler(Looper.getMainLooper());
  }

  public Handler getmWorkHandler(){
    return mWorkHandler;
  }

  @WorkerThread
  public void join() {
    Logger.d(TAG, "join() " + this.mProtooUrl);
    //mStore.setRoomState(ConnectionState.CONNECTING);
    WebSocketTransport transport = new WebSocketTransport(mProtooUrl);
    mProtoo = new Protoo(transport, peerListener);
  }

  @WorkerThread
  public void enableMic() {
    Logger.d(TAG, "enableMic()");
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
      mMicProducer =
          mSendTransport.produce(
              producer -> {
                Logger.e(TAG, "onTransportClose(), micProducer");
              },
              mLocalAudioTrack,
              null,
              null);
    } catch (MediasoupException e) {
      e.printStackTrace();
    }
    //mStore.addProducer(mMicProducer);
  }

  @WorkerThread
  public void disableMic() {
    Logger.d(TAG, "disableMic()");

    if (mMicProducer == null) {
      return;
    }

    mMicProducer.close();
   // mStore.removeProducer(mMicProducer.getId());

    // TODO(HaiyangWu) : await
    mCompositeDisposable.add(
        mProtoo
            .request("closeProducer", req -> jsonPut(req, "producerId", mMicProducer.getId()))
            .subscribe(
                res -> {}
                //,
                //throwable ->
                    //mStore.addNotify("Error closing server-side mic Producer: ", throwable)
                         ));

    mMicProducer = null;
  }

  @WorkerThread
  public void muteMic() {
    Logger.d(TAG, "muteMic()");
  }

  @WorkerThread
  public void unmuteMic() {
    Logger.d(TAG, "unmuteMic()");
    // TODO:
  }


  @WorkerThread
  public void muteAudio() {
    Logger.d(TAG, "muteAudio()");
    // TODO: Mute/unmute participants\' audio
   // mStore.setAudioMutedState(true);
  }

  @WorkerThread
  public void unmuteAudio() {
    Logger.d(TAG, "unmuteAudio()");
    // TODO: Mute/unmute participants\' audio
    //mStore.setAudioMutedState(false);
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
  public void setMaxSendingSpatialLayer() {
    Logger.d(TAG, "setMaxSendingSpatialLayer()");
    // TODO:
  }

  @WorkerThread
  public void setConsumerPreferredLayers(String spatialLayer) {
    Logger.d(TAG, "setConsumerPreferredLayers()");
    // TODO:
  }

  @WorkerThread
  public void setConsumerPreferredLayers(
      String consumerId, String spatialLayer, String temporalLayer) {
    Logger.d(TAG, "setConsumerPreferredLayers()");
    // TODO:
  }

  @WorkerThread
  public void requestConsumerKeyFrame(String consumerId) {
    Logger.d(TAG, "requestConsumerKeyFrame()");
    mCompositeDisposable.add(
        mProtoo
            .request("requestConsumerKeyFrame", req -> jsonPut(req, "consumerId", "consumerId"))
            .subscribe(
                (res) -> {
                  //mStore.addNotify("Keyframe requested for video consumer");
                },
                e -> {
                  logError("restartIce() | failed:", e);
                  //mStore.addNotify("error", "ICE restart failed: " + e.getMessage());
                }));
  }

  @WorkerThread
  public void enableChatDataProducer() {
    Logger.d(TAG, "enableChatDataProducer()");
    // TODO:
  }

  @WorkerThread
  public void enableBotDataProducer() {
    Logger.d(TAG, "enableBotDataProducer()");
    // TODO:
  }

  @WorkerThread
  public void sendChatMessage(String txt) {
    Logger.d(TAG, "sendChatMessage()");
    // TODO:
  }

  @WorkerThread
  public void sendBotMessage(String txt) {
    Logger.d(TAG, "sendBotMessage()");
    // TODO:
  }

  @WorkerThread
  public void changeDisplayName(String displayName) {
    Logger.d(TAG, "changeDisplayName()");

    // Store in cookie.
    mPreferences.edit().putString("displayName", displayName).apply();

    mCompositeDisposable.add(
        mProtoo
            .request("changeDisplayName", req -> jsonPut(req, "displayName", displayName))
            .subscribe(
                (res) -> {
                  mDisplayName = displayName;
                  //mStore.setDisplayName(displayName);
                  //mStore.addNotify("Display name change");
                },
                e -> {
                  logError("changeDisplayName() | failed:", e);
                  //mStore.addNotify("error", "Could not change display name: " + e.getMessage());

                  // We need to refresh the component for it to render the previous
                  // displayName again.
                  //mStore.setDisplayName(mDisplayName);
                }));
  }

  @WorkerThread
  public void getSendTransportRemoteStats() {
    Logger.d(TAG, "getSendTransportRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getRecvTransportRemoteStats() {
    Logger.d(TAG, "getRecvTransportRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getAudioRemoteStats() {
    Logger.d(TAG, "getAudioRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getVideoRemoteStats() {
    Logger.d(TAG, "getVideoRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getConsumerRemoteStats(String consumerId) {
    Logger.d(TAG, "getConsumerRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getChatDataProducerRemoteStats(String consumerId) {
    Logger.d(TAG, "getChatDataProducerRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getBotDataProducerRemoteStats() {
    Logger.d(TAG, "getBotDataProducerRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getDataConsumerRemoteStats(String dataConsumerId) {
    Logger.d(TAG, "getDataConsumerRemoteStats()");
    // TODO:
  }

  @WorkerThread
  public void getSendTransportLocalStats() {
    Logger.d(TAG, "getSendTransportLocalStats()");
    // TODO:
  }

  @WorkerThread
  public void getRecvTransportLocalStats() {
    Logger.d(TAG, "getRecvTransportLocalStats()");
    // TODO:
  }

  @WorkerThread
  public void getAudioLocalStats() {
    Logger.d(TAG, "getAudioLocalStats()");
    // TODO:
  }

  @WorkerThread
  public void getVideoLocalStats() {
    Logger.d(TAG, "getVideoLocalStats()");
    // TODO:
  }

  @WorkerThread
  public void getConsumerLocalStats(String consumerId) {
    Logger.d(TAG, "getConsumerLocalStats()");
    // TODO:
  }

  @WorkerThread
  public void applyNetworkThrottle(String uplink, String downlink, String rtt, String secret) {
    Logger.d(TAG, "applyNetworkThrottle()");
    // TODO:
  }

  @WorkerThread
  public void resetNetworkThrottle(boolean silent, String secret) {
    Logger.d(TAG, "applyNetworkThrottle()");
    // TODO:
  }

  @WorkerThread
  public void close() {
    if (this.mClosed) {
      return;
    }
    this.mClosed = true;
    Logger.d(TAG, "close()");

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
          mWorkHandler.post(() -> joinImpl());
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
                    case "newConsumer":
                      {
                        onNewConsumer(request, handler);
                        break;
                      }
                    case "newDataConsumer":
                      {
                        onNewDataConsumer(request, handler);
                        break;
                      }
                    default:
                      {
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
  private void joinImpl() {
    Logger.d(TAG, "joinImpl()");

    try {
      mMediasoupDevice = new Device();
      String routerRtpCapabilities = mProtoo.syncRequest("getRouterRtpCapabilities");
      mMediasoupDevice.load(routerRtpCapabilities);
      String rtpCapabilities =  mMediasoupDevice.getRtpCapabilities();
      Log.d(TAG,"createRecvTransport  routerRtpCapabilities "+routerRtpCapabilities);

      // Create mediasoup Transport for sending (unless we don't want to produce).
      if (mOptions.isProduce()) {
        createSendTransport();
      }

      // Create mediasoup Transport for sending (unless we don't want to consume).
     // if (mOptions.isConsume()) {
        Log.d(TAG,"createRecvTransport rtpCapabilities "+rtpCapabilities);
        createRecvTransport();
     // }

      // Join now into the room.
      // TODO(HaiyangWu): Don't send our RTP capabilities if we don't want to consume.
      String joinResponse =
          mProtoo.syncRequest(
              "join",
              req -> {
                jsonPut(req, "displayName", mDisplayName);
                jsonPut(req, "device", mOptions.getDevice().toJSONObject());
                jsonPut(req, "rtpCapabilities", toJsonObject(rtpCapabilities));
                // TODO (HaiyangWu): add sctpCapabilities
                jsonPut(req, "sctpCapabilities", "");
              });

     // mStore.setRoomState(ConnectionState.CONNECTED);
     // mStore.addNotify("You are in the room!", 3000);

      JSONObject resObj = JsonUtils.toJsonObject(joinResponse);
      JSONArray peers = resObj.optJSONArray("peers");
      for (int i = 0; peers != null && i < peers.length(); i++) {
        JSONObject peer = peers.getJSONObject(i);
        //mStore.addPeer(peer.optString("id"), peer);
      }

      // Enable mic/webcam.
      if (mOptions.isProduce()) {
        boolean canSendMic = mMediasoupDevice.canProduce("audio");
        boolean canSendCam = false;//NOT USE//mMediasoupDevice.canProduce("video");
        //mStore.setMediaCapabilities(canSendMic, canSendCam);
       mWorkHandler.post(this::enableMic);
       // mWorkHandler.post(this::enableCam);
      }
    } catch (Exception e) {
      e.printStackTrace();
      logError("joinRoom() failed:", e);
      //mStore.addNotify("error", "Could not join the room: " + e.getMessage());
      mMainHandler.post(this::close);
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

    String res =
        mProtoo.syncRequest(
            "createWebRtcTransport",
            req -> {
              jsonPut(req, "forceTcp", mOptions.isForceTcp());
              jsonPut(req, "producing", false);
              jsonPut(req, "consuming", true);
              // TODO (HaiyangWu): add sctpCapabilities
              jsonPut(req, "sctpCapabilities", "");
            });
    JSONObject info = new JSONObject(res);
    Logger.d(TAG, "device#createRecvTransport() " + info);
    String id = info.optString("id");
    String iceParameters = info.optString("iceParameters");
    String iceCandidates = info.optString("iceCandidates");
    String dtlsParameters = info.optString("dtlsParameters");
    String sctpParameters = info.optString("sctpParameters");

    mRecvTransport =
        mMediasoupDevice.createRecvTransport(
            recvTransportListener, id, iceParameters, iceCandidates, dtlsParameters);
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
          mCompositeDisposable.add(
              mProtoo
                  .request(
                      "connectWebRtcTransport",
                      req -> {
                        jsonPut(req, "transportId", transport.getId());
                        jsonPut(req, "dtlsParameters", toJsonObject(dtlsParameters));
                      })
                  .subscribe(
                      d -> Logger.d(listenerTAG, "connectWebRtcTransport res: " + d),
                      t -> logError("connectWebRtcTransport for mSendTransport failed", t)));
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
          mCompositeDisposable.add(
              mProtoo
                  .request(
                      "connectWebRtcTransport",
                      req -> {
                        jsonPut(req, "transportId", transport.getId());
                        jsonPut(req, "dtlsParameters", toJsonObject(dtlsParameters));
                      })
                  .subscribe(
                      d -> Logger.d(listenerTAG, "connectWebRtcTransport res: " + d),
                      t -> logError("connectWebRtcTransport for mRecvTransport failed", t)));
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
    try {
      JSONObject data = request.getData();
      String peerId = data.optString("peerId");
      String producerId = data.optString("producerId");
      String id = data.optString("id");
      String kind = data.optString("kind");
      String rtpParameters = data.optString("rtpParameters");
      JSONObject rtpParametersJSON = new JSONObject(rtpParameters);
      Log.d(TAG,"headerExtensions "+rtpParametersJSON.getString("headerExtensions"));
      String type = data.optString("type");
      String appData = data.optString("appData");
      boolean producerPaused = data.optBoolean("producerPaused");

      Log.d(TAG,"onNewConsumer "+producerId+" rtpParameters "+rtpParameters.toString());
      Consumer consumer =
          mRecvTransport.consume(
              c -> {
               // mConsumers.remove(c.getId());
                Logger.w(TAG, "onTransportClose for consume");
              },
              id,
              producerId,
              kind,
              rtpParameters,
              appData);

      //mConsumers.put(consumer.getId(), new ConsumerHolder(peerId, consumer));
     // mStore.addConsumer(peerId, type, consumer, producerPaused);

      Log.d(TAG,"onNewConsumer " + consumer.getTrack().kind());
      if(consumer.getTrack() != null && consumer.getTrack().kind().indexOf("video")>=0) {
        Log.d(TAG,"onNewConsumer addSink " + consumer.getId());
        VideoTrack video = (VideoTrack)consumer.getTrack();
        if(frameChecker != null) {
         // Log.d(TAG,"MediaCodecVideoDecoder.isVp8HwSupported() "+MediaCodecVideoDecoder.isVp8HwSupported());
         // Log.d(TAG,"MediaCodecVideoDecoder.isH264HwSupported() "+MediaCodecVideoDecoder.isH264HwSupported());
         // Log.d(TAG,"MediaCodecVideoDecoder.isVp9HwSupported() "+MediaCodecVideoDecoder.isVp9HwSupported());
         // Log.d(TAG,"MediaCodecVideoDecoder.isH264HighProfileHwSupported() "+MediaCodecVideoDecoder.isH264HighProfileHwSupported());
          video.addSink(frameChecker);
        }
        else{
          Log.d(TAG,"frameChecker is null");
        }
      }
      //we get audio track to control volume. TODO: Support multiple tracks
      else if(consumer.getTrack() != null && consumer.getTrack().kind().indexOf("audio")>=0) {
        audioTrack = (AudioTrack)consumer.getTrack();

        //test
        //audioTrack.setVolume(0);
      }
      // We are ready. Answer the protoo request so the server will
      // resume this Consumer (which was paused for now if video).
      handler.accept();

      // If audio-only mode is enabled, pause it.
      //if ("video".equals(consumer.getKind()) && mStore.getMe().getValue().isAudioOnly()) {
      //  pauseConsumer(consumer);
     // }
    } catch (Exception e) {
      e.printStackTrace();
      logError("\"newConsumer\" request failed:", e);
      //mStore.addNotify("error", "Error creating a Consumer: " + e.getMessage());
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
}
