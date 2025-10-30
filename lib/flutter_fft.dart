import 'dart:async';
import 'package:flutter/services.dart';

class FlutterFft {
  static const MethodChannel _channel =
      const MethodChannel("com.slins.flutterfft/record");
  static const EventChannel _eventChannel =
      const EventChannel("com.slins.flutterfft/audio_stream");

  StreamController<List<Object>>? _recorderController;
  StreamSubscription? _eventSubscription;

  /**
   * Constructor - Sets up the FlutterFft instance
   * Initializes EventChannel stream listener for audio data
   */
  FlutterFft() {
    // print("🔥🔥 FlutterFft: Constructor called, setting up callback 🔥🔥🔥");
    _setRecorderCallback();
  }

  /**
   * Returns the recorder stream for audio data
   * Creates stream controller if it doesn't exist
   * @return Stream<List<Object>> containing audio frequency data
   */
  Stream<List<Object>> get onRecorderStateChanged {
    if (_recorderController == null) {
      // print("FlutterFft: Stream accessed but controller is null, setting up callback");
      _setRecorderCallback();
    }
    return _recorderController!.stream;
  }

  bool _isRecording = false;
  double _subscriptionDuration = 0.25;
  int _numChannels = 1;
  int _sampleRate = 44100;
  AndroidAudioSource _androidAudioSource = AndroidAudioSource.MIC;
  double _tolerance = 1.0;
  double _frequency = 0;
  String _note = "";
  double _target = 0;
  double _distance = 0;
  int _octave = 0;
  String _nearestNote = "";
  double _nearestTarget = 0;
  double _nearestDistance = 0;
  int _nearestOctave = 0;
  bool _isOnPitch = false;
  List<String> _tuning = ["E4", "B3", "G3", "D3", "A2", "E2"];

  // Getters
  bool get getIsRecording => _isRecording;
  double get getSubscriptionDuration => _subscriptionDuration;
  int get getNumChannels => _numChannels;
  int get getSampleRate => _sampleRate;
  AndroidAudioSource get getAndroidAudioSource => _androidAudioSource;
  double get getTolerance => _tolerance;
  double get getFrequency => _frequency;
  String get getNote => _note;
  double get getTarget => _target;
  double get getDistance => _distance;
  int get getOctave => _octave;
  String get getNearestNote => _nearestNote;
  double get getNearestTarget => _nearestTarget;
  double get getNearestDistance => _nearestDistance;
  int get getNearestOctave => _nearestOctave;
  bool get getIsOnPitch => _isOnPitch;
  List<String> get getTuning => _tuning;

  // Setters
  set setIsRecording(bool isRecording) => _isRecording = isRecording;
  set setSubscriptionDuration(double subscriptionDuration) =>
      _subscriptionDuration = subscriptionDuration;
  set setTolerance(double tolerance) => _tolerance = tolerance;
  set setFrequency(double frequency) => _frequency = frequency;
  set setNumChannels(int numChannels) => _numChannels = numChannels;
  set setSampleRate(int sampleRate) => _sampleRate = sampleRate;
  set setAndroidAudioSource(AndroidAudioSource androidAudioSource) =>
      _androidAudioSource = androidAudioSource;
  set setNote(String note) => _note = note;
  set setTarget(double target) => _target = target;
  set setDistance(double distance) => _distance = distance;
  set setOctave(int octave) => _octave = octave;
  set setNearestNote(String nearestNote) => _nearestNote = nearestNote;
  set setNearestTarget(double nearestTarget) => _nearestTarget = nearestTarget;
  set setNearestDistance(double nearestDistance) =>
      _nearestDistance = nearestDistance;
  set setNearestOctave(int nearestOctave) => _nearestOctave = nearestOctave;
  set setIsOnPitch(bool isOnPitch) => _isOnPitch = isOnPitch;
  set setTuning(List<String> tuning) => _tuning = tuning;

  /**
   * Sets up the recorder stream using EventChannel
   * Creates broadcast stream controller and EventChannel listener
   * Handles audio data from native Android code
   */
  void _setRecorderCallback() {
    // print("FlutterFft: Setting up recorder callback with EventChannel");

    // Create controller once and never recreate it
    if (_recorderController == null) {
      _recorderController = StreamController<List<Object>>.broadcast();
      // print("FlutterFft: Created new stream controller");
    }

    // Cancel any existing subscription
    _eventSubscription?.cancel();

    // Set up EventChannel stream listener
    _eventSubscription = _eventChannel.receiveBroadcastStream().listen(
      (dynamic data) {
        // print("FlutterFft: ✅ Received data from EventChannel: $data");

        if (_recorderController != null &&
            !_recorderController!.isClosed &&
            data != null) {
          try {
            List<Object> audioData = List<Object>.from(data);
            // print("FlutterFft: Sending to stream: $audioData");
            _recorderController!.add(audioData);
            // print("FlutterFft: Successfully added to stream");
          } catch (e) {
            // print("FlutterFft: ❌ ERROR processing EventChannel data: $e");
          }
        } else {
          // print("FlutterFft: ❌ ERROR - recorder controller is null/closed or data is null");
        }
      },
      onError: (error) {
        // print("FlutterFft: ❌ EventChannel stream error: $error");
        if (_recorderController != null && !_recorderController!.isClosed) {
          _recorderController!.addError(error);
        }
      },
      onDone: () {
        // print("FlutterFft: EventChannel stream closed");
      },
    );

    // print("FlutterFft: EventChannel stream listener setup complete");
  }

  /**
   * Closes the recorder stream and cancels EventChannel subscription
   * Performs cleanup of stream resources
   */
  Future<void> _removeRecorderCallback() async {
    // Cancel EventChannel subscription
    _eventSubscription?.cancel();
    _eventSubscription = null;

    if (_recorderController != null && !_recorderController!.isClosed) {
      await _recorderController!.close();
      _recorderController = null;
    }
  }

  /**
   * Checks if microphone permission is granted
   * @return Future<bool> true if permission granted, false otherwise
   */
  Future<bool> checkPermission() async {
    return await _channel.invokeMethod("checkPermission");
  }

  /**
   * Requests microphone permission from the user
   * Shows system permission dialog
   */
  Future<void> requestPermission() async {
    await _channel.invokeMethod("requestPermission");
  }

  /**
   * Starts the audio recorder with current configuration
   * Sets subscription duration and begins audio processing
   * @return Future<String> Success message or throws exception
   */
  Future<String> startRecorder() async {
    // print("FlutterFft: startRecorder called");

    try {
      await _channel.invokeMethod("setSubscriptionDuration",
          <String, double>{'sec': this.getSubscriptionDuration});
    } catch (err) {
      // print("Could not set subscription duration, error: $err");
    }

    if (this.getIsRecording) {
      throw RecorderRunningException("Recorder is already running.");
    }

    try {
      String result =
          await _channel.invokeMethod('startRecorder', <String, dynamic>{
        'tuning': this.getTuning,
        'numChannels': this.getNumChannels,
        'sampleRate': this.getSampleRate,
        'androidAudioSource': this.getAndroidAudioSource.value,
        'tolerance': this.getTolerance,
      });

      this.setIsRecording = true;
      // print("FlutterFft: Recorder started successfully: $result");

      return result;
    } catch (err) {
      // print("FlutterFft: Error starting recorder: $err");
      throw Exception(err);
    }
  }

  /**
   * Stops the audio recorder and closes the stream
   * Performs cleanup and releases audio resources
   * @return Future<String> Success message or throws exception
   */
  Future<String> stopRecorder() async {
    if (!this.getIsRecording) {
      throw RecorderStoppedException("Recorder is not running.");
    }

    String result = await _channel.invokeMethod("stopRecorder");
    this.setIsRecording = false;
    await _removeRecorderCallback();

    return result;
  }

  /**
   * Temporarily pauses audio processing to prevent feedback during sound playback
   * @return Future<String> Success message
   */
  Future<String> pauseAudioProcessing() async {
    String result = await _channel.invokeMethod("pauseAudioProcessing");
    return result;
  }

  /**
   * Resumes audio processing after pause
   * @return Future<String> Success message
   */
  Future<String> resumeAudioProcessing() async {
    String result = await _channel.invokeMethod("resumeAudioProcessing");
    return result;
  }

  /**
   * Convenience method to pause audio processing for a specified duration
   * Useful for preventing feedback during sound playback
   * @param duration Duration to pause (default 300ms)
   * @return Future<void>
   */
  Future<void> pauseForDuration([Duration duration = const Duration(milliseconds: 300)]) async {
    await pauseAudioProcessing();
    await Future.delayed(duration);
    await resumeAudioProcessing();
  }
}

/**
 * Exception thrown when trying to start recorder while it's already running
 */
class RecorderRunningException implements Exception {
  final String message;
  RecorderRunningException(this.message);
}

/**
 * Exception thrown when trying to stop recorder while it's not running
 */
class RecorderStoppedException implements Exception {
  final String message;
  RecorderStoppedException(this.message);
}

/**
 * Android audio source constants for microphone input
 * Maps to Android AudioSource constants
 */
class AndroidAudioSource {
  final int _value;
  const AndroidAudioSource._internal(this._value);
  @override
  String toString() => 'AndroidAudioSource.$_value';
  int get value => _value;

  static const DEFAULT = const AndroidAudioSource._internal(0);
  static const MIC = const AndroidAudioSource._internal(1);
  static const VOICE_UPLINK = const AndroidAudioSource._internal(2);
  static const VOICE_DOWNLINK = const AndroidAudioSource._internal(3);
  static const CAMCORDER = const AndroidAudioSource._internal(4);
  static const VOICE_RECOGNITION = const AndroidAudioSource._internal(5);
  static const VOICE_COMMUNICATION = const AndroidAudioSource._internal(6);
  static const REMOTE_SUBMIX = const AndroidAudioSource._internal(7);
  static const UNPROCESSED = const AndroidAudioSource._internal(8);
  static const RADIO_TUNER = const AndroidAudioSource._internal(9);
  static const HOTWORD = const AndroidAudioSource._internal(10);
}
