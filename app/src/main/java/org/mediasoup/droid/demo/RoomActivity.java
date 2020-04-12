package org.mediasoup.droid.demo;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;

public class RoomActivity extends Activity implements LifecycleOwner{

  private static final String TAG = RoomActivity.class.getSimpleName();

  private RoomActor roomActor;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    roomActor = new RoomActor(this,"immertec",1);
  }




  protected void onDestroy() {
    super.onDestroy();
    if (roomActor != null) {
      roomActor.onDestroy();
    }
  }

  @NonNull
  @Override
  public Lifecycle getLifecycle() {
    return null;
  }
}
