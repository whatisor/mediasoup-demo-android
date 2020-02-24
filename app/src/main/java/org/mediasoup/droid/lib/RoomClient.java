package org.mediasoup.droid.lib;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mediasoup.droid.Consumer;
import org.mediasoup.droid.Device;
import org.mediasoup.droid.Logger;
import org.mediasoup.droid.Producer;
import org.mediasoup.droid.RecvTransport;
import org.mediasoup.droid.SendTransport;
import org.mediasoup.droid.Transport;
import org.mediasoup.droid.lib.lv.RoomStore;
import org.mediasoup.droid.lib.socket.WebSocketTransport;
import org.protoojs.droid.Message;
import org.protoojs.droid.ProtooException;
import org.webrtc.AudioTrack;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.MediaCodecVideoDecoder;
import org.webrtc.VideoFrame;
import org.webrtc.VideoSink;
import org.webrtc.VideoTrack;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.reactivex.disposables.CompositeDisposable;

import static org.mediasoup.droid.lib.JsonUtils.jsonPut;
import static org.mediasoup.droid.lib.JsonUtils.toJsonObject;

@SuppressWarnings("WeakerAccess")
public class RoomClient extends RoomMessageHandler {

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
  // TODO(Haiyangwu):Next expected dataChannel test number.
  private long mNextDataChannelTestNumber;
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

  public RoomClient(
      Context context, RoomStore roomStore, String roomId, String peerId, String displayName) {
    this(context, roomStore, roomId, peerId, displayName, false, false, null);
  }

  public RoomClient(
      Context context,
      RoomStore roomStore,
      String roomId,
      String peerId,
      String displayName,
      RoomOptions options) {
    this(context, roomStore, roomId, peerId, displayName, false, false, options);
  }

  public RoomClient(
      Context context,
      RoomStore roomStore,
      String roomId,
      String peerId,
      String displayName,
      boolean forceH264,
      boolean forceVP9,
      RoomOptions options) {
    super(roomStore);
    this.mContext = context.getApplicationContext();
    this.mOptions = options == null ? new RoomOptions() : options;
    this.mDisplayName = displayName;
    this.mClosed = false;
    this.mProtooUrl = UrlFactory.getProtooUrl(roomId, peerId, forceH264, forceVP9);

    this.mStore.setMe(peerId, displayName, this.mOptions.getDevice());
    this.mStore.setRoomUrl(roomId, UrlFactory.getInvitationLink(roomId, forceH264, forceVP9));
    this.mPreferences = PreferenceManager.getDefaultSharedPreferences(this.mContext);

    // support for selfSigned cert.
    UrlFactory.enableSelfSignedHttpClient();

    // init worker handler.
    HandlerThread handlerThread = new HandlerThread("worker");
    handlerThread.start();
    mWorkHandler = new Handler(handlerThread.getLooper());
    mMainHandler = new Handler(Looper.getMainLooper());
  }

  @MainThread
  public void join() {
    Logger.d(TAG, "join() " + this.mProtooUrl);
    mStore.setRoomState(ConnectionState.CONNECTING);
    WebSocketTransport transport = new WebSocketTransport(mProtooUrl);
    mProtoo = new Protoo(transport, peerListener);
  }

  @MainThread
  public void enableMic() {
    Logger.d(TAG, "enableMic()");
    if (!mMediasoupDevice.isLoaded()) {
      Logger.w(TAG, "enableMic() | not loaded");
      return;
    }
    if (!mMediasoupDevice.canProduce("audio")) {
      Logger.w(TAG, "enableMic() | cannot produce audio");
      return;
    }
    if (mSendTransport == null) {
      Logger.w(TAG, "enableMic() | mSendTransport doesn't ready");
      return;
    }
    if (mLocalAudioTrack == null) {
      mLocalAudioTrack = PeerConnectionUtils.createAudioTrack(mContext, "mic");
      mLocalAudioTrack.setEnabled(true);
    }
    mMicProducer =
        mSendTransport.produce(
            producer -> {
              Logger.e(TAG, "onTransportClose(), micProducer");
            },
            mLocalAudioTrack,
            null,
            null);
    mStore.addProducer(mMicProducer);
  }

  @MainThread
  public void disableMic() {
    Logger.d(TAG, "disableMic()");

    if (mMicProducer == null) {
      return;
    }

    mMicProducer.close();
    mStore.removeProducer(mMicProducer.getId());

    // TODO(HaiyangWu) : await
    mCompositeDisposable.add(
        mProtoo
            .request("closeProducer", req -> jsonPut(req, "producerId", mMicProducer.getId()))
            .subscribe(
                res -> {},
                throwable ->
                    mStore.addNotify("Error closing server-side mic Producer: ", throwable)));

    mMicProducer = null;
  }

  @MainThread
  public void muteMic() {
    Logger.d(TAG, "muteMic()");
  }

  @MainThread
  public void unmuteMic() {
    Logger.d(TAG, "unmuteMic()");
    // TODO:
  }

  @MainThread
  public void enableCam() {
    Logger.d(TAG, "enableCam()");
    if (!mMediasoupDevice.isLoaded()) {
      Logger.w(TAG, "enableCam() | not loaded");
      return;
    }
    if (!mMediasoupDevice.canProduce("video")) {
      Logger.w(TAG, "enableCam() | cannot produce video");
      return;
    }
    if (mSendTransport == null) {
      Logger.w(TAG, "enableCam() | mSendTransport doesn't ready");
      return;
    }
    if (mLocalVideoTrack == null) {
      mLocalVideoTrack = PeerConnectionUtils.createVideoTrack(mContext, "cam");
      mLocalVideoTrack.setEnabled(true);
    }
    mCamProducer =
        mSendTransport.produce(
            producer -> {
              Logger.e(TAG, "onTransportClose(), camProducer");
            },
            mLocalVideoTrack,
            null,
            null);
    mStore.addProducer(mCamProducer);
  }

  @MainThread
  public void disableCam() {
    Logger.d(TAG, "disableCam()");
    // TODO:
  }

  @MainThread
  public void changeCam() {
    Logger.d(TAG, "changeCam()");
    mStore.setCamInProgress(true);
    PeerConnectionUtils.switchCam(
        new CameraVideoCapturer.CameraSwitchHandler() {
          @Override
          public void onCameraSwitchDone(boolean b) {
            mStore.setCamInProgress(false);
          }

          @Override
          public void onCameraSwitchError(String s) {
            Logger.w(TAG, "changeCam() | failed: " + s);
            mStore.addNotify("error", "Could not change cam: " + s);
            mStore.setCamInProgress(false);
          }
        });
  }

  @MainThread
  public void disableShare() {
    Logger.d(TAG, "disableShare()");
  }

  @MainThread
  public void enableShare() {
    Logger.d(TAG, "enableShare()");
  }

  @MainThread
  public void enableAudioOnly() {
    Logger.d(TAG, "enableAudioOnly()");
    mStore.setAudioOnlyInProgress(true);

    disableCam();
    mWorkHandler.post(
        () -> {
          for (ConsumerHolder holder : mConsumers.values()) {
            if (!"video".equals(holder.mConsumer.getKind())) {
              continue;
            }
            pauseConsumer(holder.mConsumer);
          }
          mStore.setAudioOnlyState(true);
          mStore.setAudioOnlyInProgress(false);
        });
  }

  @MainThread
  public void disableAudioOnly() {
    Logger.d(TAG, "disableAudioOnly()");
    mStore.setAudioOnlyInProgress(true);

    if (mCamProducer != null && mOptions.isProduce()) {
      enableCam();
    }
    mWorkHandler.post(
        () -> {
          for (ConsumerHolder holder : mConsumers.values()) {
            if (!"video".equals(holder.mConsumer.getKind())) {
              continue;
            }
            resumeConsumer(holder.mConsumer);
          }
          mStore.setAudioOnlyState(false);
          mStore.setAudioOnlyInProgress(false);
        });
  }

  @MainThread
  public void muteAudio() {
    Logger.d(TAG, "muteAudio()");
    // TODO: Mute/unmute participants\' audio
    mStore.setAudioMutedState(true);
  }

  @MainThread
  public void unmuteAudio() {
    Logger.d(TAG, "unmuteAudio()");
    // TODO: Mute/unmute participants\' audio
    mStore.setAudioMutedState(false);
  }

  @MainThread
  public void restartIce() {
    Logger.d(TAG, "restartIce()");
    mStore.setRestartIceInProgress(true);
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
            mStore.addNotify("error", "ICE restart failed: " + e.getMessage());
          }
          mStore.setRestartIceInProgress(false);
        });
  }

  @MainThread
  public void setMaxSendingSpatialLayer() {
    Logger.d(TAG, "setMaxSendingSpatialLayer()");
    // TODO:
  }

  @MainThread
  public void setConsumerPreferredLayers(String spatialLayer) {
    Logger.d(TAG, "setConsumerPreferredLayers()");
    // TODO:
  }

  @MainThread
  public void setConsumerPreferredLayers(
      String consumerId, String spatialLayer, String temporalLayer) {
    Logger.d(TAG, "setConsumerPreferredLayers()");
    // TODO:
  }

  @MainThread
  public void requestConsumerKeyFrame(String consumerId) {
    Logger.d(TAG, "requestConsumerKeyFrame()");
    mCompositeDisposable.add(
        mProtoo
            .request("requestConsumerKeyFrame", req -> jsonPut(req, "consumerId", "consumerId"))
            .subscribe(
                (res) -> {
                  mStore.addNotify("Keyframe requested for video consumer");
                },
                e -> {
                  logError("restartIce() | failed:", e);
                  mStore.addNotify("error", "ICE restart failed: " + e.getMessage());
                }));
  }

  @MainThread
  public void enableChatDataProducer() {
    Logger.d(TAG, "enableChatDataProducer()");
    // TODO:
  }

  @MainThread
  public void enableBotDataProducer() {
    Logger.d(TAG, "enableBotDataProducer()");
    // TODO:
  }

  @MainThread
  public void sendChatMessage(String txt) {
    Logger.d(TAG, "sendChatMessage()");
    // TODO:
  }

  @MainThread
  public void sendBotMessage(String txt) {
    Logger.d(TAG, "sendBotMessage()");
    // TODO:
  }

  @MainThread
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
                  mStore.setDisplayName(displayName);
                  mStore.addNotify("Display name change");
                },
                e -> {
                  logError("changeDisplayName() | failed:", e);
                  mStore.addNotify("error", "Could not change display name: " + e.getMessage());

                  // We need to refresh the component for it to render the previous
                  // displayName again.
                  mStore.setDisplayName(mDisplayName);
                }));
  }

  @MainThread
  public void getSendTransportRemoteStats() {
    Logger.d(TAG, "getSendTransportRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getRecvTransportRemoteStats() {
    Logger.d(TAG, "getRecvTransportRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getAudioRemoteStats() {
    Logger.d(TAG, "getAudioRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getVideoRemoteStats() {
    Logger.d(TAG, "getVideoRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getConsumerRemoteStats(String consumerId) {
    Logger.d(TAG, "getConsumerRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getChatDataProducerRemoteStats(String consumerId) {
    Logger.d(TAG, "getChatDataProducerRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getBotDataProducerRemoteStats() {
    Logger.d(TAG, "getBotDataProducerRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getDataConsumerRemoteStats(String dataConsumerId) {
    Logger.d(TAG, "getDataConsumerRemoteStats()");
    // TODO:
  }

  @MainThread
  public void getSendTransportLocalStats() {
    Logger.d(TAG, "getSendTransportLocalStats()");
    // TODO:
  }

  @MainThread
  public void getRecvTransportLocalStats() {
    Logger.d(TAG, "getRecvTransportLocalStats()");
    // TODO:
  }

  @MainThread
  public void getAudioLocalStats() {
    Logger.d(TAG, "getAudioLocalStats()");
    // TODO:
  }

  @MainThread
  public void getVideoLocalStats() {
    Logger.d(TAG, "getVideoLocalStats()");
    // TODO:
  }

  @MainThread
  public void getConsumerLocalStats(String consumerId) {
    Logger.d(TAG, "getConsumerLocalStats()");
    // TODO:
  }

  @MainThread
  public void applyNetworkThrottle(String uplink, String downlink, String rtt, String secret) {
    Logger.d(TAG, "applyNetworkThrottle()");
    // TODO:
  }

  @MainThread
  public void resetNetworkThrottle(boolean silent, String secret) {
    Logger.d(TAG, "applyNetworkThrottle()");
    // TODO:
  }

  @MainThread
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

    mStore.setRoomState(ConnectionState.CLOSED);
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
                mStore.addNotify("error", "WebSocket connection failed");
                mStore.setRoomState(ConnectionState.CONNECTING);
              });
        }

        @Override
        public void onRequest(
            @NonNull Message.Request request, @NonNull Protoo.ServerRequestHandler handler) {
          Logger.d(TAG, "onRequest() " + request.getData().toString());
          mWorkHandler.post(
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
                  handleNotification(notification);
                } catch (Exception e) {
                  Logger.e(TAG, "handleNotification error.", e);
                }
              });
        }

        @Override
        public void onDisconnected() {
          mWorkHandler.post(
              () -> {
                mStore.addNotify("error", "WebSocket disconnected");
                mStore.setRoomState(ConnectionState.CONNECTING);

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
      String rtpCapabilities = mMediasoupDevice.getRtpCapabilities();

      // Create mediasoup Transport for sending (unless we don't want to produce).
      if (mOptions.isProduce()) {
        createSendTransport();
      }

      // Create mediasoup Transport for sending (unless we don't want to consume).
     // if (mOptions.isConsume()) {
        Log.d(TAG,"createRecvTransport");
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

      mStore.setRoomState(ConnectionState.CONNECTED);
      mStore.addNotify("You are in the room!", 3000);

      JSONObject resObj = JsonUtils.toJsonObject(joinResponse);
      JSONArray peers = resObj.optJSONArray("peers");
      for (int i = 0; peers != null && i < peers.length(); i++) {
        JSONObject peer = peers.getJSONObject(i);
        mStore.addPeer(peer.optString("id"), peer);
      }

      // Enable mic/webcam.
      if (mOptions.isProduce()) {
        boolean canSendMic = mMediasoupDevice.canProduce("audio");
        boolean canSendCam = mMediasoupDevice.canProduce("video");
        mStore.setMediaCapabilities(canSendMic, canSendCam);
        mWorkHandler.post(this::enableMic);
        mWorkHandler.post(this::enableCam);
      }
    } catch (Exception e) {
      e.printStackTrace();
      logError("joinRoom() failed:", e);
      mStore.addNotify("error", "Could not join the room: " + e.getMessage());
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

    mSendTransport =
        mMediasoupDevice.createSendTransport(
            sendTransportListener, id, iceParameters, iceCandidates, dtlsParameters);
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
                mConsumers.remove(c.getId());
                Logger.w(TAG, "onTransportClose for consume");
              },
              id,
              producerId,
              kind,
              rtpParameters,
              appData);

      mConsumers.put(consumer.getId(), new ConsumerHolder(peerId, consumer));
      mStore.addConsumer(peerId, type, consumer, producerPaused);

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
      // We are ready. Answer the protoo request so the server will
      // resume this Consumer (which was paused for now if video).
      handler.accept();

      // If audio-only mode is enabled, pause it.
      if ("video".equals(consumer.getKind()) && mStore.getMe().getValue().isAudioOnly()) {
        pauseConsumer(consumer);
      }
    } catch (Exception e) {
      e.printStackTrace();
      logError("\"newConsumer\" request failed:", e);
      mStore.addNotify("error", "Error creating a Consumer: " + e.getMessage());
    }
  }

  private void onNewDataConsumer(Message.Request request, Protoo.ServerRequestHandler handler) {
    handler.reject(403, "I do not want to data consume");
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
      mStore.setConsumerPaused(consumer.getId(), "local");
    } catch (Exception e) {
      e.printStackTrace();
      logError("pauseConsumer() | failed:", e);
      mStore.addNotify("error", "Error pausing Consumer: " + e.getMessage());
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
      mStore.setConsumerResumed(consumer.getId(), "local");
    } catch (Exception e) {
      e.printStackTrace();
      logError("resumeConsumer() | failed:", e);
      mStore.addNotify("error", "Error resuming Consumer: " + e.getMessage());
    }
  }
}
