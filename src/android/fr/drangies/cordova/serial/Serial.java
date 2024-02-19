package fr.drangies.cordova.serial;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.hoho.android.usbserial.driver.CdcAcmSerialDriver;
import com.hoho.android.usbserial.driver.Ch34xSerialDriver;
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver;
import com.hoho.android.usbserial.driver.FtdiSerialDriver;
import com.hoho.android.usbserial.driver.ProbeTable;
import com.hoho.android.usbserial.driver.ProlificSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
//import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbManager;
import android.util.Base64;
import android.util.Log;


/**
 * Cordova plugin to communicate with the android serial port
 * @author Xavier Seignard <xavier.seignard@gmail.com>
 */
public class Serial extends CordovaPlugin {
	// logging tag
	private final String TAG = Serial.class.getSimpleName();
	// actions definitions
	private static final String ACTION_REQUEST_PERMISSION = "requestPermission";
	private static final String ACTION_OPEN = "openSerial";
	private static final String ACTION_READ = "readSerial";
	private static final String ACTION_WRITE = "writeSerial";
	private static final String ACTION_WRITE_HEX = "writeSerialHex";
	private static final String ACTION_CLOSE = "closeSerial";
	private static final String ACTION_READ_CALLBACK = "registerReadCallback";

	// UsbManager instance to deal with permission and opening
	private UsbManager manager;
	// The current driver that handle the serial port
	private UsbSerialDriver driver;
	// The serial port that will be used in this plugin
	private UsbSerialPort port;
	// Read buffer, and read params
	private static final int READ_WAIT_MILLIS = 200;
	private static final int BUFSIZ = 4096;
	private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);
	// Connection info
	private int baudRate;
	private int dataBits;
	private int stopBits;
	private int parity;
	private boolean setDTR;
	private boolean setRTS;
	private boolean sleepOnPause;

	// callback that will be used to send back data to the cordova app
	private CallbackContext readCallback;

	// I/O manager to handle new incoming serial data
	private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
	private SerialInputOutputManager mSerialIoManager;
	private final SerialInputOutputManager.Listener mListener =
			new SerialInputOutputManager.Listener() {
				@Override
				public void onRunError(Exception e) {
					Log.d(TAG, "Runner stopped.");
				}
				@Override
				public void onNewData(final byte[] data) {
					Serial.this.updateReceivedData(data);
				}
			};

	/**
	 * Overridden execute method
	 * @param action the string representation of the action to execute
	 * @param callbackContext the cordova {@link CallbackContext}
	 * @return true if the action exists, false otherwise
	 * @throws JSONException if the args parsing fails
	 */
	@Override
	public boolean execute(String action, JSONArray args, final CallbackContext callbackContext) throws JSONException {
		Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
		Log.d(TAG, "Action: " + action);
		JSONObject arg_object = args.optJSONObject(0);
		// request permission
		if (ACTION_REQUEST_PERMISSION.equals(action)) {
			JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
			requestPermission(opts, callbackContext);
			return true;
		}
		// open serial port
		else if (ACTION_OPEN.equals(action)) {
			JSONObject opts = arg_object.has("opts")? arg_object.getJSONObject("opts") : new JSONObject();
			openSerial(opts, callbackContext);
			return true;
		}
		// write to the serial port
		else if (ACTION_WRITE.equals(action)) {
			String data = arg_object.getString("data");
			writeSerial(data, callbackContext);
			return true;
		}
		// Register read callback
		else if (ACTION_READ_CALLBACK.equals(action)) {
			registerReadCallback(callbackContext);
			return true;
		}
		else if (ACTION_CLOSE.equals(action)) {
			closeSerial(callbackContext);
			return true;
		}
		// the action doesn't exist
		return false;
	}

	/**
	 * Request permission the the user for the app to use the USB/serial port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void requestPermission(final JSONObject opts, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				// get UsbManager from Android
				manager = (UsbManager) cordova.getActivity().getSystemService(Context.USB_SERVICE);
				UsbSerialProber prober;

				if (opts.has("vid") && opts.has("pid")) {
					ProbeTable customTable = new ProbeTable();
					Object o_vid = opts.opt("vid"); //can be an integer Number or a hex String
					Object o_pid = opts.opt("pid"); //can be an integer Number or a hex String
					int vid = o_vid instanceof Number ? ((Number) o_vid).intValue() : Integer.parseInt((String) o_vid,16);
					int pid = o_pid instanceof Number ? ((Number) o_pid).intValue() : Integer.parseInt((String) o_pid,16);
					String driver = opts.has("driver") ? (String) opts.opt("driver") : "CdcAcmSerialDriver";

					assert driver != null;
					switch (driver) {
						case "FtdiSerialDriver":
							customTable.addProduct(vid, pid, FtdiSerialDriver.class);
							break;
						case "CdcAcmSerialDriver":
							customTable.addProduct(vid, pid, CdcAcmSerialDriver.class);
							break;
						case "Cp21xxSerialDriver":
							customTable.addProduct(vid, pid, Cp21xxSerialDriver.class);
							break;
						case "ProlificSerialDriver":
							customTable.addProduct(vid, pid, ProlificSerialDriver.class);
							break;
						case "Ch34xSerialDriver":
							customTable.addProduct(vid, pid, Ch34xSerialDriver.class);
							break;
						default:
							Log.d(TAG, "Unknown driver!");
							callbackContext.error("Unknown driver!");
							break;
					}

					prober = new UsbSerialProber(customTable);

				}
				else {
					// find all available drivers from attached devices.
					prober = UsbSerialProber.getDefaultProber();
				}

				List<UsbSerialDriver> availableDrivers = prober.findAllDrivers(manager);

				if (!availableDrivers.isEmpty()) {
					// get the first one as there is a high chance that there is no more than one usb device attached to your android
					driver = availableDrivers.get(0);
					UsbDevice device = driver.getDevice();
					// create the intent that will be used to get the permission
					PendingIntent pendingIntent = PendingIntent.getBroadcast(cordova.getActivity(), 0, new Intent(UsbBroadcastReceiver.USB_PERMISSION), PendingIntent.FLAG_MUTABLE);
					// and a filter on the permission we ask
					IntentFilter filter = new IntentFilter();
					filter.addAction(UsbBroadcastReceiver.USB_PERMISSION);
					// this broadcast receiver will handle the permission results
					UsbBroadcastReceiver usbReceiver = new UsbBroadcastReceiver(callbackContext, cordova.getActivity());
					cordova.getActivity().registerReceiver(usbReceiver, filter);
					// finally ask for the permission
					manager.requestPermission(device, pendingIntent);
				}
				else {
					// no available drivers
					Log.d(TAG, "No device found!");
					callbackContext.error("No device found!");
				}
			}
		});
	}

	/**
	 * Open the serial port from Cordova
	 * @param opts a {@link JSONObject} containing the connection paramters
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void openSerial(final JSONObject opts, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				if (driver == null) {
					callbackContext.error("Request permissions before attempting opening port");
					return;
				}
				UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				if (connection != null) {
					// get first port and open it
					port = driver.getPorts().get(0);
					try {
						// get connection params or the default values
						baudRate = opts.has("baudRate") ? opts.getInt("baudRate") : 9600;
						dataBits = opts.has("dataBits") ? opts.getInt("dataBits") : UsbSerialPort.DATABITS_8;
						stopBits = opts.has("stopBits") ? opts.getInt("stopBits") : UsbSerialPort.STOPBITS_1;
						parity = opts.has("parity") ? opts.getInt("parity") : UsbSerialPort.PARITY_NONE;
						setDTR = opts.has("dtr") && opts.getBoolean("dtr");
						setRTS = opts.has("rts") && opts.getBoolean("rts");
						// Sleep On Pause defaults to true
						sleepOnPause = !opts.has("sleepOnPause") || opts.getBoolean("sleepOnPause");

						port.open(connection);
						port.setParameters(baudRate, dataBits, stopBits, parity);
						if (setDTR) port.setDTR(true);
						if (setRTS) port.setRTS(true);
					}
					catch (IOException | JSONException e) {
						// deal with error
						Log.d(TAG, Objects.requireNonNull(e.getMessage()));
						callbackContext.error(e.getMessage());
					}

				}
				else {
					Log.d(TAG, "Cannot connect to the device!");
					callbackContext.error("Cannot connect to the device!");
				}
				onDeviceStateChange(callbackContext);
				Log.d(TAG, "Serial port opened!");
				callbackContext.success("Serial port opened!");
			}
		});
	}

	/**
	 * Write on the serial port
	 * @param data the {@link String} representation of the data to be written on the port
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void writeSerial(final String data, final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(() -> {
			if (port == null) {
				callbackContext.error("Writing a closed port.");
			}
			else {
				try {
					Log.d(TAG, data);
					byte[] buffer = data.getBytes();
					port.write(buffer, 1000);
					callbackContext.success(buffer.length + "character written.");
				}
				catch (IOException | NullPointerException e) {
					// deal with error
					Log.d(TAG, Objects.requireNonNull(e.getMessage()));
					callbackContext.error(e.getMessage());
				}
			}
		});
	}

	private void closeSerial(final CallbackContext callbackContext) {
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				try {
					// Make sure we don't die if we try to close an non-existing port!
					if (port != null) {
						port.close();
					}
					port = null;
					callbackContext.success("Serial port closed!");
				}
				catch (IOException | NullPointerException e) {
					// deal with error
					Log.d(TAG, Objects.requireNonNull(e.getMessage()));
					callbackContext.error(e.getMessage());
				}
				onDeviceStateChange(callbackContext);
			}
		});
	}

	/**
	 * Stop observing serial connection
	 */
	private void stopIoManager() {
		if (mSerialIoManager != null) {
			Log.i(TAG, "Stopping io manager.");
			try {
				mSerialIoManager.stop();
			} catch (Exception e) {

			}
			mSerialIoManager = null;
		}
	}

	/**
	 * Observe serial connection
	 */
	private void startIoManager(final CallbackContext callbackContext) {
		if (port != null && driver != null) {
//			UsbEndpoint endpoints = port.getReadEndpoint();
//			if (endpoints != null) {
				Log.i(TAG, "Starting io manager.");
				try {
					mSerialIoManager = new SerialInputOutputManager(port, mListener);
					mSerialIoManager.setThreadPriority(-20);
					mExecutor.submit(mSerialIoManager);
				} catch (Exception e) {
					closePort();
					if (callbackContext != null) {
						callbackContext.error("Error on opening port.");
					}
				}
//			} else {
//				closePort();
//				if (callbackContext != null) {
//					callbackContext.error("Error on opening port.");
//				}
//			}
		}
	}

	/**
	 * Restart the observation of the serial connection
	 */
	private void onDeviceStateChange(final CallbackContext callbackContext) {
		stopIoManager();
		startIoManager(callbackContext);
	}

	/**
	 * Dispatch read data to javascript
	 * @param data the array of bytes to dispatch
	 */
	private void updateReceivedData(byte[] data) {
		int x = data.length;
		Log.d("msg", Integer.toString(x));
		Log.d("msg", Boolean.toString(readCallback != null));
		if( readCallback != null ) {
			PluginResult result = new PluginResult(PluginResult.Status.OK, data);
			result.setKeepCallback(true);
			readCallback.sendPluginResult(result);
		}
	}

	/**
	 * Register callback for read data
	 * @param callbackContext the cordova {@link CallbackContext}
	 */
	private void registerReadCallback(final CallbackContext callbackContext) {
		Log.d(TAG, "Registering callback");
		cordova.getThreadPool().execute(new Runnable() {
			public void run() {
				Thread.currentThread().setPriority(Thread.MAX_PRIORITY);
				Log.d(TAG, "Registering Read Callback");
				readCallback = callbackContext;
				JSONObject returnObj = new JSONObject();
				addProperty(returnObj, "registerReadCallback", "true");
				// Keep the callback
				PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, returnObj);
				pluginResult.setKeepCallback(true);
				callbackContext.sendPluginResult(pluginResult);
			}
		});
	}

	private void closePort() {
		if (port != null) {
			try {
				port.close();
			} catch (IOException | NullPointerException e) {
				// Ignore
			}
		}
		port = null;
	}

	/**
	 * Paused activity handler
	 * @see org.apache.cordova.CordovaPlugin#onPause(boolean)
	 */
	@Override
	public void onPause(boolean multitasking) {
		if (sleepOnPause) {
			stopIoManager();
			if (port != null) {
				try {
					port.close();
				} catch (IOException | NullPointerException e) {
					// Ignore
				}
				port = null;
			}
		}
	}


	/**
	 * Resumed activity handler
	 * @see org.apache.cordova.CordovaPlugin#onResume(boolean)
	 */
	@Override
	public void onResume(boolean multitasking) {
		Log.d(TAG, "Resumed, driver=" + driver);
		if (sleepOnPause) {
			if (driver == null) {
				Log.d(TAG, "No serial device to resume.");
			}
			else {
				UsbDeviceConnection connection = manager.openDevice(driver.getDevice());
				if (connection != null) {
					// get first port and open it
					port = driver.getPorts().get(0);
					try {
						port.open(connection);
						port.setParameters(baudRate, dataBits, stopBits, parity);
						if (setDTR) port.setDTR(true);
						if (setRTS) port.setRTS(true);
					}
					catch (IOException e) {
						// deal with error
						Log.d(TAG, Objects.requireNonNull(e.getMessage()));
					}
					Log.d(TAG, "Serial port opened!");
				}
				else {
					Log.d(TAG, "Cannot connect to the device!");
				}
				Log.d(TAG, "Serial device: " + driver.getClass().getSimpleName());
			}

			onDeviceStateChange(null);
		}
	}


	/**
	 * Destroy activity handler
	 * @see org.apache.cordova.CordovaPlugin#onDestroy()
	 */
	@Override
	public void onDestroy() {
		Log.d(TAG, "Destroy, port=" + port);
		if(port != null) {
			try {
				port.close();
			}
			catch (IOException | NullPointerException e) {
				Log.d(TAG, Objects.requireNonNull(e.getMessage()));
			}
		}
		onDeviceStateChange(null);
	}

	/**
	 * Utility method to add some properties to a {@link JSONObject}
	 * @param obj the json object where to add the new property
	 * @param key property key
	 * @param value value of the property
	 */
	private void addProperty(JSONObject obj, String key, Object value) {
		try {
			obj.put(key, value);
		}
		catch (JSONException ignored){}
	}
}