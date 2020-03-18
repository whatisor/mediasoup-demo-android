package org.mediasoup.droid.lib;

import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mediasoup.droid.Consumer;
import org.mediasoup.droid.Logger;
import org.protoojs.droid.Message;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class RoomMessageHandler {

  static final String TAG = "RoomClient";

  // mediasoup Consumers.
  @NonNull final Map<String, ConsumerHolder> mConsumers;

  static class ConsumerHolder {
    @NonNull final String peerId;
    @NonNull final Consumer mConsumer;

    public ConsumerHolder(@NonNull String peerId, @NonNull Consumer consumer) {
      this.peerId = peerId;
      mConsumer = consumer;
    }
  }

  RoomMessageHandler() {
    this.mConsumers = new ConcurrentHashMap<>();
  }

  @WorkerThread
  void handleNotification(Message.Notification notification) throws JSONException {
    JSONObject data = notification.getData();
    switch (notification.getMethod()) {
      case "producerScore":
        {
          // {"producerId":"bdc2e83e-5294-451e-a986-a29c7d591d73","score":[{"score":10,"ssrc":196184265}]}
          String producerId = data.getString("producerId");
          JSONArray score = data.getJSONArray("score");
          break;
        }
      case "newPeer":
        {
          String id = data.getString("id");
          String displayName = data.optString("displayName");
          break;
        }
      case "peerClosed":
        {
          String peerId = data.getString("peerId");
          break;
        }
      case "peerDisplayNameChanged":
        {
          String peerId = data.getString("peerId");
          String displayName = data.optString("displayName");
          String oldDisplayName = data.optString("oldDisplayName");
          break;
        }
      case "consumerClosed":
        {
          String consumerId = data.getString("consumerId");
          ConsumerHolder holder = mConsumers.remove(consumerId);
          if (holder == null) {
            break;
          }
          holder.mConsumer.close();
          mConsumers.remove(consumerId);
          break;
        }
      case "consumerPaused":
        {
          String consumerId = data.getString("consumerId");
          ConsumerHolder holder = mConsumers.get(consumerId);
          if (holder == null) {
            break;
          }
          break;
        }
      case "consumerResumed":
        {
          String consumerId = data.getString("consumerId");
          ConsumerHolder holder = mConsumers.get(consumerId);
          if (holder == null) {
            break;
          }
          break;
        }
      case "consumerLayersChanged":
        {
          String consumerId = data.getString("consumerId");
          int spatialLayer = data.getInt("spatialLayer");
          int temporalLayer = data.getInt("temporalLayer");
          ConsumerHolder holder = mConsumers.get(consumerId);
          if (holder == null) {
            break;
          }
          break;
        }
      case "consumerScore":
        {
          String consumerId = data.getString("consumerId");
          JSONArray score = data.optJSONArray("score");
          ConsumerHolder holder = mConsumers.get(consumerId);
          if (holder == null) {
            break;
          }
          break;
        }
      case "dataConsumerClosed":
        {
          String dataConsumerId = data.getString("dataConsumerId");
          // TODO(HaiyangWu); support data consumer
          break;
        }
      case "activeSpeaker":
        {
          String peerId = data.getString("peerId");
          break;
        }
      default:
        {
          Logger.e(TAG, "unknown protoo notification.method " + notification.getMethod());
        }
    }
  }
}
