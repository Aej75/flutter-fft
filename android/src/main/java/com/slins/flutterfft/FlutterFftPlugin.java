package com.slins.flutterfft;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.media.AudioRecord;
import android.os.Looper;
import android.util.Log;
import android.app.Activity;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import be.tarsos.dsp.pitch.FastYin;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.plugin.common.PluginRegistry;

public class FlutterFftPlugin implements ActivityAware, FlutterPlugin, PluginRegistry.RequestPermissionsResultListener, AudioInterface, MethodCallHandler, EventChannel.StreamHandler {
  
  final public static String TAG = "FlutterFftPlugin";
  final private static String RECORD_STREAM = "com.slins.flutterfft/record";
  final private static String AUDIO_STREAM = "com.slins.flutterfft/audio_stream";
  
  // ERROR CODES
  public static final String ERROR_MIC_PERMISSSION_DENIED = "ERROR_MIC_PERMISSION_DENIED";
  public static final String ERROR_RECORDER_IS_NULL = "ERROR_RECORDER_IS_NULL";
  public static final String ERROR_FAILED_RECORDER_INITIALIZATION = "ERROR_FAILED_RECORDER_INITIALIZATION";
  public static final String ERROR_RECORDER_IS_NOT_INITIALIZED = "ERROR_RECORDER_IS_NOT_INITIALIZED";
  public static final String ERROR_FAILED_RECORDER_PROGRESS = "ERROR_FAILED_RECORDER_PROGRESS";
  public static final String ERROR_FAILED_RECORDER_UPDATE = "ERROR_FAILED_RECORDER_UPDATE";
  public static final String ERROR_WRONG_BUFFER_SIZE = "ERROR_WRONG_BUFFER_SIZE";
  public static final String ERROR_FAILED_FREQUENCIES_AND_OCTAVES_INSTANTIATION = "ERROR_FAILED_FREQUENCIES_AND_OCTAVES_INSTANTIATION";

  public static int bufferSize;
  private boolean doneBefore = false;

  public static float frequency = 0;
  public static String note = "";
  public static float target = 0;
  public static float distance = 0;
  public static int octave = 0;
  public static String nearestNote = "";
  public static float nearestTarget = 0;
  public static float nearestDistance = 0;
  public static int nearestOctave = 0;

  private final ExecutorService taskScheduler = Executors.newSingleThreadExecutor();

  final private AudioModel audioModel = new AudioModel();
  final private PitchModel pitchModel = new PitchModel();

  // CRITICAL: Make channel volatile and check before use
  public static volatile MethodChannel channel;
  private EventChannel eventChannel;
  public static volatile EventChannel.EventSink eventSink;

  final static public Handler recordHandler = new Handler(Looper.getMainLooper());
  final static public Handler mainHandler = new Handler(Looper.getMainLooper());

  private ActivityPluginBinding activityBinding;
  private Activity activity;

  /**
   * Called when the plugin is attached to the Flutter engine
   * Sets up MethodChannel for commands and EventChannel for audio streaming
   * @param flutterPluginBinding Provides access to Flutter's binary messenger
   */
  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    // Log.d(TAG, "⚡ onAttachedToEngine called - Setting up channel");
    
    // Stop any existing recording when channel is recreated
    if (audioModel.getAudioRecorder() != null) {
      try {
        // Log.w(TAG, "Stopping existing recorder due to hot reload");
        recordHandler.removeCallbacksAndMessages(null);
        audioModel.getAudioRecorder().stop();
        audioModel.getAudioRecorder().release();
        audioModel.setAudioRecorder(null);
      } catch (Exception e) {
        // Log.e(TAG, "Error cleaning up recorder: " + e.getMessage());
      }
    }
    
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), RECORD_STREAM);
    channel.setMethodCallHandler(this);
    // Log.d(TAG, "✅ Method channel set up successfully. Channel instance: " + channel);
    
    // Set up EventChannel for streaming audio data
    eventChannel = new EventChannel(flutterPluginBinding.getBinaryMessenger(), AUDIO_STREAM);
    eventChannel.setStreamHandler(this);
    // Log.d(TAG, "✅ Event channel set up successfully. EventChannel instance: " + eventChannel);
  }

  /**
   * Called when the plugin is attached to an Android Activity
   * Registers for permission request callbacks
   * @param binding Provides access to the Activity
   */
  @Override
  public void onAttachedToActivity(@NonNull ActivityPluginBinding binding) {
    // Log.d(TAG, "onAttachedToActivity called");
    activityBinding = binding;
    activity = binding.getActivity();
    binding.addRequestPermissionsResultListener(this);
  }

  /**
   * Checks if microphone permission is granted
   * @return true if permission is granted, false otherwise
   */
  public boolean checkPermission() {
    if (activity == null) {
      // Log.e(TAG, "Activity is null in checkPermission");
      return false;
    }
    
    if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      return false;
    }
    return true;
  }

  /**
   * Requests microphone permission from the user
   */
  public void requestPermission() {
    if (activity == null) {
      // Log.e(TAG, "Activity is null in requestPermission");
      return;
    }
    ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, 0);
  }

  /**
   * Handles method calls from Flutter
   * Processes commands like startRecorder, stopRecorder, permission checks
   * @param call The method call with parameters
   * @param result Callback to send results back to Flutter
   */
  @RequiresApi(api = Build.VERSION_CODES.N)
  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    // Log.d(TAG, "onMethodCall: " + call.method + " | Channel: " + channel);
    
    switch (call.method) {
      case "startRecorder":
        // Log.d(TAG, "startRecorder method called");
        taskScheduler.submit(() -> {
          try {
            List<Object> tuning = call.argument("tuning");
            Integer sampleRate = call.argument("sampleRate");
            Integer numChannels = call.argument("numChannels");
            Integer androidAudioSourceInt = call.argument("androidAudioSource");
            Double toleranceDouble = call.argument("tolerance");
            
            int androidAudioSource = (androidAudioSourceInt != null) ? androidAudioSourceInt : 1;
            float tolerance = (toleranceDouble != null) ? toleranceDouble.floatValue() : 1.0f;
            
            // Log.d(TAG, "Starting recorder with params - tuning: " + tuning + ", sampleRate: " + sampleRate + ", channels: " + numChannels);
            
            startRecorder(tuning, numChannels, sampleRate, androidAudioSource, tolerance, result);
          } catch (Exception e) {
            // Log.e(TAG, "Error in startRecorder: " + e.getMessage(), e);
            mainHandler.post(() -> result.error("START_RECORDER_ERROR", e.getMessage(), null));
          }
        });
        break;
        
      case "stopRecorder":
        // Log.d(TAG, "stopRecorder method called");
        taskScheduler.submit(() -> {
          try {
            stopRecorder(result);
          } catch (Exception e) {
            // Log.e(TAG, "Error in stopRecorder: " + e.getMessage(), e);
            mainHandler.post(() -> result.error("STOP_RECORDER_ERROR", e.getMessage(), null));
          }
        });
        break;
        
      case "setSubscriptionDuration":
        // Log.d(TAG, "setSubscriptionDuration method called");
        Double duration = call.argument("sec");
        if (duration == null) {
          result.error("INVALID_ARGUMENT", "sec argument is null", null);
          return;
        }
        setSubscriptionDuration(duration, result);
        break;
        
      case "checkPermission":
        // Log.d(TAG, "checkPermission method called");
        result.success(checkPermission());
        break;
        
      case "requestPermission":
        // Log.d(TAG, "requestPermission method called");
        requestPermission();
        result.success(null);
        break;
        
      default:
        // Log.d(TAG, "Method not implemented: " + call.method);
        result.notImplemented();
        break;
    }
  }

  /**
   * Handles permission request results
   * @param requestCode The request code for the permission
   * @param permissions Array of requested permissions
   * @param grantResults Results of the permission requests
   * @return true if permission was granted, false otherwise
   */
  @Override
  public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    // Log.d(TAG, "Permission result received");
    final int REQUEST_RECORD_AUDIO_PERMISSION = 200;
    if (requestCode == REQUEST_RECORD_AUDIO_PERMISSION) {
      if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
        // Log.d(TAG, "Permission granted");
        return true;
      }
    }
    // Log.d(TAG, "Permission denied");
    return false;
  }

  /**
   * Utility method for error logging (currently commented out)
   * @param message Error message
   * @param err Exception details
   */
  public static void printError(String message, Exception err) {
    // Log.e(TAG, message + ". Error: " + err.toString(), err);
  }

  /**
   * Utility method for error logging (currently commented out)
   * @param message Error message
   */
  public static void printError(String message) {
    // Log.e(TAG, message);
  }

  /**
   * Starts the audio recorder with specified parameters
   * Initializes pitch detection and begins audio processing
   * @param tuning List of target notes for tuning
   * @param numChannels Number of audio channels
   * @param sampleRate Audio sample rate in Hz
   * @param androidAudioSource Audio input source
   * @param tolerance Frequency tolerance for pitch detection
   * @param result Callback to report success or failure
   */
  @RequiresApi(api = Build.VERSION_CODES.N)
  @Override
  public void startRecorder(List<Object> tuning, Integer numChannels, Integer sampleRate, int androidAudioSource, Float tolerance, final Result result) {
    // Log.d(TAG, "startRecorder implementation called. Channel available: " + (channel != null));
    
    try {
      checkIfPermissionGranted();

      if (!doneBefore) {
        try {
          // Log.d(TAG, "Getting frequencies and octaves");
          pitchModel.getFrequenciesAndOctaves(result);
          doneBefore = true;
        } catch (Exception err) {
          printError("Could not get frequencies and octaves", err);
          mainHandler.post(() -> result.error(ERROR_FAILED_FREQUENCIES_AND_OCTAVES_INSTANTIATION, err.getMessage(), null));
          return;
        }
      }

      // Log.d(TAG, "Initializing audio recorder");
      initializeAudioRecorder(result, tuning, sampleRate, numChannels, androidAudioSource, tolerance);

      // Log.d(TAG, "Starting recording");
      audioModel.getAudioRecorder().startRecording();
      recordHandler.removeCallbacksAndMessages(null);

      audioModel.setRecorderTicker(() -> pitchModel.updateFrequencyAndNote(result, audioModel));
      recordHandler.post(audioModel.getRecorderTicker());

      // Log.d(TAG, "Recorder started successfully, posting success result");
      mainHandler.post(() -> {
        try {
          result.success("Recorder successfully set up.");
          // Log.d(TAG, "Success result sent to Flutter");
        } catch (Exception e) {
          // Log.e(TAG, "Error sending success result: " + e.getMessage(), e);
        }
      });
    } catch (Exception e) {
      // Log.e(TAG, "Exception in startRecorder: " + e.getMessage(), e);
      mainHandler.post(() -> result.error("START_RECORDER_ERROR", e.getMessage(), null));
    }
  }

  /**
   * Stops the audio recorder and releases resources
   * @param result Callback to report success or failure
   */
  @Override
  public void stopRecorder(final Result result) {
    // Log.d(TAG, "stopRecorder implementation called");
    recordHandler.removeCallbacksAndMessages(null);

    if (audioModel.getAudioRecorder() == null) {
      // Log.e(TAG, "Recorder is null and cannot be stopped");
      mainHandler.post(() -> result.error(ERROR_RECORDER_IS_NULL, "Can't stop recorder, it is NULL.", null));
      return;
    }

    try {
      audioModel.getAudioRecorder().stop();
      audioModel.getAudioRecorder().release();
      audioModel.setAudioRecorder(null);

      mainHandler.post(() -> result.success("Recorder stopped."));
    } catch (Exception e) {
      // Log.e(TAG, "Error stopping recorder: " + e.getMessage(), e);
      mainHandler.post(() -> result.error("STOP_RECORDER_ERROR", e.getMessage(), null));
    }
  }

  /**
   * Sets the interval for audio processing updates
   * @param sec Update interval in seconds
   * @param result Callback to report success
   */
  @Override
  public void setSubscriptionDuration(double sec, Result result) {
    // Log.d(TAG, "setSubscriptionDuration: " + sec);
    audioModel.subsDurationMillis = (int) (sec * 1000);
    result.success("setSubscriptionDuration: " + audioModel.subsDurationMillis);
  }

  /**
   * Checks and requests microphone permission if needed
   */
  @Override
  public void checkIfPermissionGranted() {
    if (activity == null) {
      // Log.e(TAG, "Activity is null in checkIfPermissionGranted");
      return;
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
        // Log.w(TAG, "Microphone permission not granted");
        ActivityCompat.requestPermissions(activity, new String[]{Manifest.permission.RECORD_AUDIO}, 0);
      }
    }
  }

  /**
   * Initializes the Android AudioRecorder with specified parameters
   * Sets up pitch detection and audio processing components
   * @param result Callback for error reporting
   * @param tuning Target notes for tuning
   * @param sampleRate Audio sample rate
   * @param numChannels Number of audio channels
   * @param androidAudioSource Audio input source
   * @param tolerance Frequency tolerance for pitch detection
   */
  @Override
  public void initializeAudioRecorder(Result result, List<Object> tuning, Integer sampleRate, Integer numChannels, int androidAudioSource, Float tolerance) {
    // Log.d(TAG, "initializeAudioRecorder called");
    
    if (audioModel.getAudioRecorder() != null) {
      // Log.d(TAG, "Releasing existing audio recorder");
      audioModel.getAudioRecorder().release();
    }
    
    bufferSize = 0;

    try {
      bufferSize = AudioRecord.getMinBufferSize(sampleRate, numChannels, audioModel.audioFormat) * 3;
      // Log.d(TAG, "Calculated buffer size: " + bufferSize);

      if (bufferSize != AudioRecord.ERROR_BAD_VALUE) {
        audioModel.setAudioRecorder(new AudioRecord(androidAudioSource, sampleRate, numChannels, audioModel.audioFormat, bufferSize));
        audioModel.setAudioData(new short[bufferSize / 2]);
        pitchModel.setPitchDetector(new FastYin(sampleRate, bufferSize / 2));
        pitchModel.setTolerance(tolerance);
        pitchModel.setTuning(tuning);
        // Log.d(TAG, "Audio recorder initialized successfully");
      } else {
        printError("Failed to initialize recorder, wrong buffer data: " + bufferSize);
        throw new Exception("Wrong buffer size: " + bufferSize);
      }
    } catch (Exception e) {
      printError("Failed to initialize recorder", e);
      throw new RuntimeException(e);
    }
  }

  /**
   * Called when the plugin is detached from the Flutter engine
   * Performs cleanup of resources and stops any active recording
   * @param binding Flutter plugin binding
   */
  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    // Log.d(TAG, "onDetachedFromEngine called - Cleaning up");
    
    // Stop recording if active
    if (audioModel.getAudioRecorder() != null) {
      try {
        recordHandler.removeCallbacksAndMessages(null);
        audioModel.getAudioRecorder().stop();
        audioModel.getAudioRecorder().release();
        audioModel.setAudioRecorder(null);
      } catch (Exception e) {
        // Log.e(TAG, "Error stopping recorder in onDetachedFromEngine: " + e.getMessage());
      }
    }
    
    if (channel != null) {
      channel.setMethodCallHandler(null);
      channel = null;
    }
    
    if (eventChannel != null) {
      eventChannel.setStreamHandler(null);
      eventChannel = null;
    }
    
    if (eventSink != null) {
      eventSink.endOfStream();
      eventSink = null;
    }
  }

  /**
   * Called when Activity is detached due to configuration changes
   */
  @Override
  public void onDetachedFromActivityForConfigChanges() {
    onDetachedFromActivity();
  }

  /**
   * Called when Activity is reattached after configuration changes
   * @param binding Activity plugin binding
   */
  @Override
  public void onReattachedToActivityForConfigChanges(@NonNull ActivityPluginBinding binding) {
    onAttachedToActivity(binding);
  }

  /**
   * Called when the plugin is detached from the Android Activity
   */
  @Override
  public void onDetachedFromActivity() {
    // Log.d(TAG, "onDetachedFromActivity called");
    if (activityBinding != null) {
      activityBinding.removeRequestPermissionsResultListener(this);
      activityBinding = null;
    }
    activity = null;
  }

  /**
   * EventChannel.StreamHandler implementation - called when Flutter starts listening
   * @param arguments Optional arguments from Flutter
   * @param events Event sink to send data to Flutter
   */
  @Override
  public void onListen(Object arguments, EventChannel.EventSink events) {
    // Log.d(TAG, "EventChannel onListen called - Setting up event sink");
    eventSink = events;
  }

  /**
   * EventChannel.StreamHandler implementation - called when Flutter stops listening
   * @param arguments Optional arguments from Flutter
   */
  @Override
  public void onCancel(Object arguments) {
    // Log.d(TAG, "EventChannel onCancel called - Clearing event sink");
    eventSink = null;
  }
}