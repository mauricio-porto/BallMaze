/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ebrain.mazeball;

import android.app.ActionBar;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentActivity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.ebrain.mazeball.logger.Log;

/**
 * This fragment controls Bluetooth to communicate with other devices.
 */
public class BluetoothChatFragment extends Fragment {

	private static final String TAG = "BluetoothChatFragment";

	// Intent request codes
	private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
	private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
	private static final int REQUEST_ENABLE_BT = 3;

	// Layout Views
	private ListView mConversationView;
	private EditText mOutEditText;
	private Button mSendButton;

	/**
	 * Name of the connected device
	 */
	private String mConnectedDeviceName = null;

	/**
	 * Array adapter for the conversation thread
	 */
	private ArrayAdapter<String> mConversationArrayAdapter;

	/**
	 * String buffer for outgoing messages
	 */
	private StringBuffer mOutStringBuffer;

	/**
	 * Local Bluetooth adapter
	 */
	private BluetoothAdapter mBluetoothAdapter = null;

	private SensorManager mSensorManager;

	private MySensor mSensor;

	/**
	 * Member object for the chat services
	 */
	private BluetoothChatService mChatService = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setHasOptionsMenu(true);
		// Get local Bluetooth adapter
		mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

		FragmentActivity activity = getActivity();
		// If the adapter is null, then Bluetooth is not supported
		if (mBluetoothAdapter == null) {
			Toast.makeText(activity, "Bluetooth is not available", Toast.LENGTH_LONG).show();
			activity.finish();
		}

		// Get an instance of the SensorManager
		mSensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
		mSensor = new MySensor();
	}

	@Override
	public void onStart() {
		super.onStart();
		// If BT is not on, request that it be enabled.
		// setupChat() will then be called during onActivityResult
		if (!mBluetoothAdapter.isEnabled()) {
			Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
			startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
			// Otherwise, setup the chat session
		} else if (mChatService == null) {
			setupChat();
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if (mChatService != null) {
			mChatService.stop();
		}
	}

	@Override
	public void onResume() {
		super.onResume();

		// Performing this check in onResume() covers the case in which BT was
		// not enabled during onStart(), so we were paused to enable it...
		// onResume() will be called when ACTION_REQUEST_ENABLE activity
		// returns.
		if (mChatService != null) {
			// Only if the state is STATE_NONE, do we know that we haven't
			// started already
			if (mChatService.getState() == BluetoothChatService.STATE_NONE) {
				// Start the Bluetooth chat services
				mChatService.start();
			}
		}
		mSensor.start();
	}

	
	/* (non-Javadoc)
	 * @see android.support.v4.app.Fragment#onPause()
	 */
	@Override
	public void onPause() {
		super.onPause();
		mSensor.stop();
	}

	@Override
	public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_bluetooth_chat, container, false);
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		mConversationView = (ListView) view.findViewById(R.id.in);
		mOutEditText = (EditText) view.findViewById(R.id.edit_text_out);
		mSendButton = (Button) view.findViewById(R.id.button_send);
	}

	/**
	 * Set up the UI and background operations for chat.
	 */
	private void setupChat() {
		Log.d(TAG, "setupChat()");

		// Initialize the array adapter for the conversation thread
		mConversationArrayAdapter = new ArrayAdapter<String>(getActivity(), R.layout.message);

		mConversationView.setAdapter(mConversationArrayAdapter);

		// Initialize the compose field with a listener for the return key
		mOutEditText.setOnEditorActionListener(mWriteListener);

		// Initialize the send button with a listener that for click events
		mSendButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				// Send a message using content of the edit text widget
				View view = getView();
				if (null != view) {
					TextView textView = (TextView) view.findViewById(R.id.edit_text_out);
					String message = textView.getText().toString();
					sendMessage(message);
				}
			}
		});

		// Initialize the BluetoothChatService to perform bluetooth connections
		mChatService = new BluetoothChatService(getActivity(), mHandler);

		// Initialize the buffer for outgoing messages
		mOutStringBuffer = new StringBuffer("");
	}

	/**
	 * Makes this device discoverable.
	 */
	private void ensureDiscoverable() {
		if (mBluetoothAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
			Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
			discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
			startActivity(discoverableIntent);
		}
	}

	/**
	 * Sends a message.
	 *
	 * @param message
	 *            A string of text to send.
	 */
	private void sendMessage(String message) {
		// Check that we're actually connected before trying anything
		if (mChatService.getState() != BluetoothChatService.STATE_CONNECTED) {
			Toast.makeText(getActivity(), R.string.not_connected, Toast.LENGTH_SHORT).show();
			return;
		}

		// Check that there's actually something to send
		if (message.length() > 0) {
			// Get the message bytes and tell the BluetoothChatService to write
			byte[] send = message.getBytes();
			mChatService.write(send);

			// Reset out string buffer to zero and clear the edit text field
			mOutStringBuffer.setLength(0);
			mOutEditText.setText(mOutStringBuffer);
		}
	}

	/**
	 * The action listener for the EditText widget, to listen for the return key
	 */
	private TextView.OnEditorActionListener mWriteListener = new TextView.OnEditorActionListener() {
		public boolean onEditorAction(TextView view, int actionId, KeyEvent event) {
			// If the action is a key-up event on the return key, send the
			// message
			if (actionId == EditorInfo.IME_NULL && event.getAction() == KeyEvent.ACTION_UP) {
				String message = view.getText().toString();
				sendMessage(message);
			}
			return true;
		}
	};

	/**
	 * Updates the status on the action bar.
	 *
	 * @param resId
	 *            a string resource ID
	 */
	private void setStatus(int resId) {
		FragmentActivity activity = getActivity();
		if (null == activity) {
			return;
		}
		final ActionBar actionBar = activity.getActionBar();
		if (null == actionBar) {
			return;
		}
		actionBar.setSubtitle(resId);
	}

	/**
	 * Updates the status on the action bar.
	 *
	 * @param subTitle
	 *            status
	 */
	private void setStatus(CharSequence subTitle) {
		FragmentActivity activity = getActivity();
		if (null == activity) {
			return;
		}
		final ActionBar actionBar = activity.getActionBar();
		if (null == actionBar) {
			return;
		}
		actionBar.setSubtitle(subTitle);
	}

	/**
	 * The Handler that gets information back from the BluetoothChatService
	 */
	private final Handler mHandler = new Handler() {
		@Override
		public void handleMessage(Message msg) {
			FragmentActivity activity = getActivity();
			switch (msg.what) {
			case Constants.MESSAGE_STATE_CHANGE:
				switch (msg.arg1) {
				case BluetoothChatService.STATE_CONNECTED:
					setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));
					mConversationArrayAdapter.clear();
					break;
				case BluetoothChatService.STATE_CONNECTING:
					setStatus(R.string.title_connecting);
					break;
				case BluetoothChatService.STATE_LISTEN:
				case BluetoothChatService.STATE_NONE:
					setStatus(R.string.title_not_connected);
					break;
				}
				break;
			case Constants.MESSAGE_WRITE:
				byte[] writeBuf = (byte[]) msg.obj;
				// construct a string from the buffer
				String writeMessage = new String(writeBuf);
				mConversationArrayAdapter.add("Me:  " + writeMessage);
				break;
			case Constants.MESSAGE_READ:
				byte[] readBuf = (byte[]) msg.obj;
				// construct a string from the valid bytes in the buffer
				String readMessage = new String(readBuf, 0, msg.arg1);
				mConversationArrayAdapter.add(mConnectedDeviceName + ":  " + readMessage);
				break;
			case Constants.MESSAGE_DEVICE_NAME:
				// save the connected device's name
				mConnectedDeviceName = msg.getData().getString(Constants.DEVICE_NAME);
				if (null != activity) {
					Toast.makeText(activity, "Connected to " + mConnectedDeviceName, Toast.LENGTH_SHORT).show();
				}
				break;
			case Constants.MESSAGE_TOAST:
				if (null != activity) {
					Toast.makeText(activity, msg.getData().getString(Constants.TOAST), Toast.LENGTH_SHORT).show();
				}
				break;
			}
		}
	};

	public void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case REQUEST_CONNECT_DEVICE_SECURE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				connectDevice(data, true);
			}
			break;
		case REQUEST_CONNECT_DEVICE_INSECURE:
			// When DeviceListActivity returns with a device to connect
			if (resultCode == Activity.RESULT_OK) {
				connectDevice(data, false);
			}
			break;
		case REQUEST_ENABLE_BT:
			// When the request to enable Bluetooth returns
			if (resultCode == Activity.RESULT_OK) {
				// Bluetooth is now enabled, so set up a chat session
				setupChat();
			} else {
				// User did not enable Bluetooth or an error occurred
				Log.d(TAG, "BT not enabled");
				Toast.makeText(getActivity(), R.string.bt_not_enabled_leaving, Toast.LENGTH_SHORT).show();
				getActivity().finish();
			}
		}
	}

	/**
	 * Establish connection with other divice
	 *
	 * @param data
	 *            An {@link Intent} with
	 *            {@link DeviceListActivity#EXTRA_DEVICE_ADDRESS} extra.
	 * @param secure
	 *            Socket Security type - Secure (true) , Insecure (false)
	 */
	private void connectDevice(Intent data, boolean secure) {
		// Get the device MAC address
		String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
		// Get the BluetoothDevice object
		BluetoothDevice device = mBluetoothAdapter.getRemoteDevice(address);
		// Attempt to connect to the device
		mChatService.connect(device, secure);
	}

	@Override
	public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
		inflater.inflate(R.menu.bluetooth_chat, menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
		case R.id.secure_connect_scan: {
			// Launch the DeviceListActivity to see devices and do scan
			Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
			startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_SECURE);
			return true;
		}
		case R.id.insecure_connect_scan: {
			// Launch the DeviceListActivity to see devices and do scan
			Intent serverIntent = new Intent(getActivity(), DeviceListActivity.class);
			startActivityForResult(serverIntent, REQUEST_CONNECT_DEVICE_INSECURE);
			return true;
		}
		case R.id.discoverable: {
			// Ensure this device is discoverable by others
			ensureDiscoverable();
			return true;
		}
		}
		return false;
	}

	class MySensor implements SensorEventListener {

		private Sensor mRotationVectorSensor;

		// Definição baseada na orientação "default", ou seja, celular vertical com tela de frente para o usuário
		private float x;	// Eixo horizontal, apontando para a direita
		private float y;	// Eixo vertical, apontando para cima
		private float z;	// Eixo ortogonal, apontando para o rosto do usuário

		/**
		 * Quando o celular está na horizontal, os valores "estáveis" são:
		 * 
		 * X: -0.0036621094
		 * Y: 0.021728516
		 * Z: -0.40441895  // Está "deitado"
		 * 
		 * Se inclinado para a direita, os valores "estáveis" são:
		 * 
		 * X: -0.1219000
		 * Y: 0.24145508
		 * Z: 0.40979004
		 * 
		 * Se inclinado para a esquerda, os valores "estáveis" são:
		 * 
		 * X: 0.1400000
		 * Y: -0.215866
		 * Z: 0.5267800
		 * 
		 * Se inclinado com o topo do celular para cima:
		 * 
		 * X: 0.00861864
		 * Y: -0.0435791
		 * Z: -0.44836426
		 * 
		 * Se inclinado com o topo do celular para baixo:
		 * 
		 * X: -0.028320313
		 * Y: 0.088867766
		 * Z: -0.94262484
		 * 
		 * CONCLUSÕES:
		 * 
		 * Na posição "deitado", o valor de Y representa sozinho a inclicação.
		 * Se inclinado para direita são valores positivos.
		 * 
		 * As inclinações para "frente" e para "trás" são determinadas pelo produto X*Z
		 * Se o valor de Z é positivo, está inclinado para cima/frente.
		 * 
		 */


		private float x_degrees;
		private float y_degrees;

		private float lastx;
		private float lasty;
		private float lastz;
		
		private float threshold = 0.05f;

		public MySensor() {
			super();
			// find the rotation-vector sensor
            mRotationVectorSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);			
		}

		public void start() {
			// enable our sensor when the activity is resumed, ask for 10 ms updates.
			mSensorManager.registerListener(this, mRotationVectorSensor, SensorManager.SENSOR_DELAY_GAME);
		}

		public void stop() {
			// make sure to turn our sensor off when the activity is paused
			mSensorManager.unregisterListener(this);
		}

		@Override
		public void onSensorChanged(SensorEvent event) {
			boolean significant = false;
			// we received a sensor event. it is a good practice to check
			// that we received the proper event
			if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
				x = event.values[0];
				y = event.values[1];
				z = event.values[2];
				if (Math.abs(x - lastx) > threshold) {
					significant = true;
					lastx = x;
				}
				if (Math.abs(y - lasty) > threshold) {
					significant = true;
					lasty = y;
				}
				if (Math.abs(z - lastz) > threshold) {
					significant = true;
					lastz = z;
				}
				if (significant) {
					setDegrees();
					mConversationArrayAdapter.add("X: " + lastx);
					mConversationArrayAdapter.add("Y: " + lasty);
					mConversationArrayAdapter.add("Z: " + lastz);
					mConversationArrayAdapter.add("X degrees: " + x_degrees + " " + '\u00b0');
					mConversationArrayAdapter.add("Y degrees: " + y_degrees + " " + '\u00b0');
					significant = false;
				}
			}

		}

		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			// Nothing to do
		}

		private void setDegrees() {
	        double norm_of_degrees = Math.sqrt(Math.pow(x, 2) + Math.pow(y, 2) + Math.pow(z, 2));

	        // Normalize the accelerometer vector
	        float valuesAccel_X = (float) (x / norm_of_degrees);
	        float valuesAccel_Y = (float) (y / norm_of_degrees);

	        x_degrees = (float)(90 - Math.toDegrees(Math.acos(valuesAccel_X)));
	        y_degrees = (float)(90 - Math.toDegrees(Math.acos(valuesAccel_Y)));
	    };


	}
}
