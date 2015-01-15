/*
	Copyright 2014 Cullin Moran
	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

		http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.
 */

package com.javadog.cgeowear;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.IBinder;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationListener;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.net.ConnectException;
import java.util.HashSet;
import java.util.NoSuchElementException;

public class WearService extends Service
		implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener,
		SensorEventListener {
	public static final String DEBUG_TAG = "com.javadog.cgeowear";

	private static final String INTENT_INIT = "cgeo.geocaching.wear.NAVIGATE_TO";
	private static final String INTENT_STOP = "com.javadog.cgeowear.STOP_APP";

	//Location update speed (preferred and max, respectively) in milliseconds
	private static final long LOCATION_UPDATE_INTERVAL = 2000;
	private static final long LOCATION_UPDATE_MAX_INTERVAL = 1000;

	//App will smooth out compass samples and send the result every n milliseconds
	private static final long DIRECTION_UPDATE_SPEED = 2000;

	private LocationRequest locationRequest;

	private static final String PREFIX = "cgeo.geocaching.intent.extra.";
	private static final String EXTRA_CACHE_NAME = PREFIX + "name";
	private static final String EXTRA_GEOCODE = PREFIX + "geocode";
	private static final String EXTRA_LATITUDE = PREFIX + "latitude";
	private static final String EXTRA_LONGITUDE = PREFIX + "longitude";

	private static final String MESSAGE_NAVIGATING_NOW = "Navigating on Android Wear";
	private static final String MESSAGE_NO_WEAR_DEVICE = "No Android Wear device paired!";
	private static final String MESSAGE_ERROR_COMMUNICATING = "Error communicating with Wear device. Contact support.";

	private GoogleApiClient apiClient;
	private WearInterface wearInterface;

	private SensorManager sensorManager;
	private Sensor accelerometer;
	private Sensor magnetometer;

	private String cacheName;
	private String geocode;
	private Location geocacheLocation;
	private Location currentLocation;

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		if(intent != null) {
			final String action = intent.getAction();
			if(INTENT_INIT.equals(action)) {
				cacheName = intent.getStringExtra(EXTRA_CACHE_NAME);
				geocode = intent.getStringExtra(EXTRA_GEOCODE);

				final double latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0d);
				final double longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0d);
				geocacheLocation = new Location("c:geo");
				geocacheLocation.setLatitude(latitude);
				geocacheLocation.setLongitude(longitude);

				//Connect to Google APIs
				if(apiClient != null) {
					apiClient.unregisterConnectionCallbacks(this);
					apiClient.unregisterConnectionFailedListener(this);
					apiClient.disconnect();
				}
				apiClient = new GoogleApiClient.Builder(this, this, this)
						.addApi(Wearable.API)
						.addApi(LocationServices.API)
						.build();
				apiClient.connect();
			}
		}

		return START_STICKY;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		handleInit();
	}

	/**
	 * Starts service & watch app.
	 */
	private void handleInit() {
		SharedPreferences userPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

		//Register listener for INTENT_STOP events
		IntentFilter filter = new IntentFilter(INTENT_STOP);
		registerReceiver(intentReceiver, filter);

		//Register listener for when watch app closes
		IntentFilter localFilter = new IntentFilter(ListenerService.PATH_KILL_APP);
		LocalBroadcastManager.getInstance(this).registerReceiver(localReceiver, localFilter);

		//Show a persistent notification
		Intent stopServiceIntent = new Intent(INTENT_STOP);
		PendingIntent nIntent = PendingIntent.getBroadcast(getApplicationContext(), 0, stopServiceIntent, 0);
		Notification notification = new NotificationCompat.Builder(getApplicationContext())
				.setOngoing(true)
				.setContentIntent(nIntent)
				.setSmallIcon(R.drawable.ic_launcher)
				.setContentTitle(getText(R.string.app_name))
				.setContentText(getText(R.string.notification_text))
				.build();

		//Specify how quickly we want to receive location updates
		locationRequest = LocationRequest.create()
				.setInterval(LOCATION_UPDATE_INTERVAL)
				.setFastestInterval(LOCATION_UPDATE_MAX_INTERVAL)
				.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

		//Listen for updates from the phone compass
		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
		sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_NORMAL);
		sensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_NORMAL);

		//Start service in foreground
		startForeground(R.string.app_name, notification);
	}

	/**
	 * Handles INTENT_STOP event.
	 */
	BroadcastReceiver intentReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if(INTENT_STOP.equals(action)) {
				Log.d(DEBUG_TAG, "Received 'stop' intent; stopping phone service.");
				stopSelf();
			}
		}
	};

	/**
	 * Handles broadcast originated by watch app closing; stops this service.
	 */
	BroadcastReceiver localReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			if(ListenerService.PATH_KILL_APP.equals(action)) {
				Log.d(DEBUG_TAG, "Watch app closed; stopping phone service.");
				stopSelf();
			}
		}
	};

	@Override
	public void onDestroy() {
		stopWearApp();
		stopForeground(true);
		apiClient.disconnect();

		//Stop listeners
		unregisterReceiver(intentReceiver);
		LocalBroadcastManager.getInstance(this).unregisterReceiver(localReceiver);

		sensorManager.unregisterListener(this, accelerometer);
		sensorManager.unregisterListener(this, magnetometer);

		super.onDestroy();
	}

	/**
	 * Stops the Android Wear counterpart to this app.
	 */
	private void stopWearApp() {
		if(wearInterface != null) {
			wearInterface.sendKillRequest();
		}
	}

	final Handler initThreadHandler = new Handler(new Callback() {
		@Override
		public boolean handleMessage(Message msg) {
			//Display any String received as a Toast; quit if there was a fatal error
			String message = (String) msg.obj;
			Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();

			if(message.equals(MESSAGE_NO_WEAR_DEVICE) || message.equals(MESSAGE_ERROR_COMMUNICATING)) {
				stopSelf();
			}
			return true;
		}
	});

	@Override
	public void onConnected(Bundle bundle) {
		Log.d(DEBUG_TAG, "Connected to Play Services");

		//Subscribe to location updates
		LocationServices.FusedLocationApi.requestLocationUpdates(apiClient, locationRequest, locationListener);

		//Get ID of connected Wear device and send it the initial cache info (in another thread)
		new Thread(new Runnable() {
			@Override
			public void run() {
				HashSet<String> connectedWearDevices = new HashSet<>();
				NodeApi.GetConnectedNodesResult nodes = Wearable.NodeApi.getConnectedNodes(apiClient).await();
				for(Node node : nodes.getNodes()) {
					connectedWearDevices.add(node.getId());
				}

				Message m = new Message();
				try {
					wearInterface = new WearInterface(apiClient, connectedWearDevices.iterator().next());
					wearInterface.initTracking(cacheName, geocode, 0f, 0f, geocacheLocation);
					m.obj = MESSAGE_NAVIGATING_NOW;

				} catch(ConnectException e) {
					Log.e(DEBUG_TAG, "Couldn't send initial tracking data.");
					m.obj = MESSAGE_ERROR_COMMUNICATING;

				} catch(NoSuchElementException e) {
					Log.e(DEBUG_TAG, "No Wear devices connected. Killing service...");
					m.obj = MESSAGE_NO_WEAR_DEVICE;

				} finally {
					initThreadHandler.sendMessage(m);
				}
			}
		}).start();
	}

	@Override
	public void onConnectionSuspended(int i) {
		Log.d(DEBUG_TAG, "Play Services connection suspended.");
	}

	@Override
	public void onConnectionFailed(ConnectionResult connectionResult) {
		Log.e(DEBUG_TAG, "Failed to connect to Google Play Services.");
	}

	private LocationListener locationListener = new LocationListener() {
		@Override
		public void onLocationChanged(Location location) {
			//Update stored currentLocation
			currentLocation = location;

			//Calculate new distance (meters) to geocache
			float distance = currentLocation.distanceTo(geocacheLocation);

			//Send these values off to Android Wear
			wearInterface.sendDistanceUpdate(distance);
		}
	};

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	float[] gravity;
	float[] geomagnetic;
	float oldDirection = 0, oldLatitude = 0, oldLongitude = 0, oldAltitude = 0;
	long prevTime = System.currentTimeMillis();

	/**
	 * Handles compass rotation.
	 */
	@Override
	public void onSensorChanged(SensorEvent event) {
		if(event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			gravity = event.values.clone();
		} else if(event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
			geomagnetic = event.values.clone();
		}

		if(gravity != null && geomagnetic != null) {
			float[] R = new float[9];
			float[] I = new float[9];

			boolean success = SensorManager.getRotationMatrix(R, I, gravity, geomagnetic);
			if(success) {
				float[] orientation = new float[3];
				SensorManager.getOrientation(R, orientation);
				float azimuth = (float) Math.toDegrees(orientation[0]);
				float pitch = (float) Math.toDegrees(orientation[1]);

				if(currentLocation != null) {
					float smoothedLatitude = smoothSensorValues(
							oldLatitude, (float) currentLocation.getLatitude(), 1 / 3f);
					float smoothedLongitude = smoothSensorValues(
							oldLongitude, (float) currentLocation.getLongitude(), 1 / 3f);
					float smoothedAltitude = smoothSensorValues(
							oldAltitude, (float) currentLocation.getAltitude(), 1 / 3f);

					GeomagneticField geomagneticField = new GeomagneticField(
							smoothedLatitude,
							smoothedLongitude,
							smoothedAltitude,
							System.currentTimeMillis()
					);
					azimuth += geomagneticField.getDeclination();

					float bearing = currentLocation.bearingTo(geocacheLocation);

					float direction = smoothSensorValues(oldDirection, -(azimuth - bearing), 1 / 5f);

					//If the user puts the phone in his/her pocket upside-down, invert the compass
					if(pitch > 0) {
						direction += 180f;
					}

					//Set old values to current values (for smoothing)
					oldDirection = direction;
					oldLatitude = smoothedLatitude;
					oldLongitude = smoothedLongitude;
					oldAltitude = smoothedAltitude;

					//Send direction update to Android Wear if update interval has passed
					long currentTime = System.currentTimeMillis();
					if((currentTime - prevTime) > DIRECTION_UPDATE_SPEED) {
						wearInterface.sendDirectionUpdate(direction);
						prevTime = currentTime;
					}
				}
			}
		}
	}

	/**
	 * A low-pass filter for smoothing out noisy sensor values.
	 *
	 * @param oldVal      The previous value.
	 * @param newVal      The new value.
	 * @param decayFactor Decay factor. (1 / decayFactor) = number of samples to smooth over.
	 * @return The smoothed value.
	 */
	private float smoothSensorValues(float oldVal, float newVal, float decayFactor) {
		return oldVal * (1 - decayFactor) + newVal * decayFactor;
	}

	@Override
	public void onAccuracyChanged(Sensor sensor, int accuracy) {
	}
}
