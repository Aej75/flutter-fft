package com.slins.flutterfft;

import android.media.AudioRecord;
import android.os.Build;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import be.tarsos.dsp.pitch.PitchDetector;
import io.flutter.plugin.common.MethodChannel;

import static com.slins.flutterfft.FlutterFftPlugin.TAG;

public class PitchModel implements PitchInterface {
    public static final String ERROR_PITCH_DETECTION_FAILURE = "ERROR_PITCH_DETECTION_FAILURE";
    public static final String ERROR_DATA_FAILURE = "ERROR_DATA_FAILURE";
    public static final String ERROR_FAILED_FREQUENCY_DATA_PROCESSING = "ERROR_FAILED_FREQUENCY_DATA_PROCESSING";

    private float tolerance;
    private List<Object> tuning;
    private ArrayList<Pair<String, Integer>> tuningData = new ArrayList<Pair<String, Integer>>();
    private float[] targetFrequencies = null;
    private boolean isOnPitch = false;

    private PitchDetector pitchDetector;

    ArrayList<FrequencyData<String, Float, Integer>> frequencyData = new ArrayList<FrequencyData<String, Float, Integer>>();

    /**
     * Updates frequency and note detection from audio input
     * Processes audio data to detect pitch and sends results via EventChannel
     * @param result MethodChannel result for error handling
     * @param audioModel Contains audio recorder and data
     */
    @Override
    public void updateFrequencyAndNote(MethodChannel.Result result, AudioModel audioModel) {
        try {
            if (audioModel.getAudioRecorder().getState() == AudioRecord.STATE_INITIALIZED) {
                audioModel.getAudioRecorder().read(audioModel.getAudioData(), 0, FlutterFftPlugin.bufferSize / 2);

                parseTuning();

                ArrayList<Object> returnData = new ArrayList<>();

                short[] bufferData = audioModel.getAudioData();
                float[] floatData = new float[FlutterFftPlugin.bufferSize / 2];

                for (int i = 0; i < bufferData.length; i++) {
                    floatData[i] = (float) bufferData[i];
                }

                FlutterFftPlugin.frequency = pitchDetector.getPitch(floatData).getPitch();
                // Log.d(TAG, "Frequency detected: " + FlutterFftPlugin.frequency);

                if (FlutterFftPlugin.frequency != -1) {
                    // Log.d(TAG, "Processing frequency: " + FlutterFftPlugin.frequency);
                    try {
                        processPitch(FlutterFftPlugin.frequency, result);
                    } catch(Exception e) {
                        FlutterFftPlugin.printError("Could not process pitch", e);
                        return;
                    }

                    try {
                        returnData.add(tolerance);
                        returnData.add(FlutterFftPlugin.frequency);
                        returnData.add(FlutterFftPlugin.note);
                        returnData.add(FlutterFftPlugin.target);
                        returnData.add(FlutterFftPlugin.distance);
                        returnData.add(FlutterFftPlugin.octave);
                        returnData.add(FlutterFftPlugin.nearestNote);
                        returnData.add(FlutterFftPlugin.nearestTarget);
                        returnData.add(FlutterFftPlugin.nearestDistance);
                        returnData.add(FlutterFftPlugin.nearestOctave);
                        returnData.add(isOnPitch);
                    } catch(Exception err) {
                        FlutterFftPlugin.printError("Could not set return data", err);
                        return;
                    }

                    try {
                        // Log.d(TAG, "Sending data to Flutter via EventChannel: " + returnData);
                        
                        // CRITICAL FIX: Use EventChannel instead of MethodChannel for streaming
                        FlutterFftPlugin.mainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    if (FlutterFftPlugin.eventSink != null) {
                                        // Log.d(TAG, "EventSink is available, sending data...");
                                        FlutterFftPlugin.eventSink.success(returnData);
                                        // Log.d(TAG, "✅ Data sent successfully to Flutter via EventChannel");
                                    } else {
                                        // Log.e(TAG, "❌ EventSink is NULL! Cannot send data to Flutter");
                                    }
                                } catch (Exception e) {
                                    // Log.e(TAG, "❌ Error sending data via EventChannel: " + e.getMessage(), e);
                                    // e.printStackTrace();
                                }
                            }
                        });
                    } catch (Exception err) {
                        FlutterFftPlugin.printError("Failed to post to main handler", err);
                        return;
                    }
                }

                FlutterFftPlugin.recordHandler.postDelayed(audioModel.getRecorderTicker(), audioModel.subsDurationMillis);
            } else {
                FlutterFftPlugin.printError("Recorder is not initialized. State: " + audioModel.getAudioRecorder().getState());
                return;
            }
        } catch (Exception e) {
            FlutterFftPlugin.printError("Failed to update recorder", e);
            return;
        }
    }

    /**
     * Processes detected pitch frequency to determine note and tuning accuracy
     * Calculates closest note, octave, and distance from target frequency
     * @param pitchInHz The detected frequency in Hz
     * @param result MethodChannel result for error handling
     */
    @Override
    public void processPitch(float pitchInHz, MethodChannel.Result result) {
        if (tuning.get(0) != "None") {
            float smallestTargetDistance = Float.MAX_VALUE;
            float smallestCurrentDistance = Float.MAX_VALUE;
            int targetIdx = 0;
            int currentIdx = -1;

            for (int i = 0; i < targetFrequencies.length; i++) {
                float currentDistance = Math.abs(pitchInHz - targetFrequencies[i]);
                if (currentDistance < smallestTargetDistance) {
                    smallestTargetDistance = currentDistance;
                    targetIdx = i;
                }
            }

            float currentDistance = smallestTargetDistance;
            
            if (currentDistance < tolerance) {
                FlutterFftPlugin.note = tuningData.get(targetIdx).first;
                FlutterFftPlugin.distance = currentDistance;
                FlutterFftPlugin.target = targetFrequencies[targetIdx];
                FlutterFftPlugin.octave = tuningData.get(targetIdx).second;
                isOnPitch = true;
                return;
            } else if (currentDistance > tolerance) {
                isOnPitch = false;

                FlutterFftPlugin.distance = currentDistance;
                FlutterFftPlugin.target = targetFrequencies[targetIdx];

                for (FrequencyData<String, Float, Integer> data : frequencyData) {
                    float curDistance = Math.abs(pitchInHz - data.second);
                    currentIdx++;
                    if (curDistance < smallestCurrentDistance) {
                        smallestCurrentDistance = curDistance;
                    } else if (curDistance > smallestCurrentDistance) {
                        break;
                    }
                }

                FlutterFftPlugin.note = frequencyData.get(currentIdx).first;
                FlutterFftPlugin.octave = frequencyData.get(currentIdx).third;

                FlutterFftPlugin.nearestNote = tuningData.get(targetIdx).first;
                FlutterFftPlugin.nearestDistance = FlutterFftPlugin.distance;
                FlutterFftPlugin.nearestOctave = tuningData.get(targetIdx).second;
                FlutterFftPlugin.nearestTarget = targetFrequencies[targetIdx];
                return;
            }
        } else if (tuning.get(0) == "None") {
            float smallestDistance = Float.MAX_VALUE;
            float secondSmallestDistance = Float.MAX_VALUE;

            for (FrequencyData<String, Float, Integer> data : frequencyData) {
                float currentDistance = Math.abs(pitchInHz - data.second);
                if (currentDistance < tolerance) {
                    smallestDistance = currentDistance;
                    FlutterFftPlugin.note = data.first;
                    FlutterFftPlugin.distance = smallestDistance;
                    FlutterFftPlugin.target = data.second;
                    FlutterFftPlugin.octave = data.third;
                    isOnPitch = true;
                    int dataIndex = frequencyData.indexOf(data);
                    if (dataIndex != 0 && dataIndex != frequencyData.size() - 1) {
                        float dist1 = Math.abs(pitchInHz - frequencyData.get(dataIndex - 1).second);
                        float dist2 = Math.abs(pitchInHz - frequencyData.get(dataIndex + 1).second);
                        if (dist1 > dist2) {
                            secondSmallestDistance = dist2;
                            FlutterFftPlugin.nearestNote = frequencyData.get(dataIndex + 1).first;
                            FlutterFftPlugin.nearestDistance = secondSmallestDistance;
                            FlutterFftPlugin.nearestTarget = frequencyData.get(dataIndex + 1).second;
                            FlutterFftPlugin.nearestOctave = frequencyData.get(dataIndex + 1).third;
                        } else if (dist1 < dist2) {
                            secondSmallestDistance = dist1;
                            FlutterFftPlugin.nearestNote = frequencyData.get(dataIndex - 1).first;
                            FlutterFftPlugin.nearestDistance = secondSmallestDistance;
                            FlutterFftPlugin.nearestTarget = frequencyData.get(dataIndex - 1).second;
                            FlutterFftPlugin.nearestOctave = frequencyData.get(dataIndex - 1).third;
                        } else {
                            FlutterFftPlugin.printError("Could not properly set up the frequency data");
                            return;
                        }
                    }
                    return;
                } else if (currentDistance > tolerance) {
                    isOnPitch = false;

                    if (currentDistance < smallestDistance) {
                        smallestDistance = currentDistance;
                    } else if (currentDistance > smallestDistance) {
                        int dataIndex = frequencyData.indexOf(data) - 1;
                        FlutterFftPlugin.note = data.first;
                        FlutterFftPlugin.distance = smallestDistance;
                        FlutterFftPlugin.target = data.second;
                        FlutterFftPlugin.octave = data.third;

                        if (dataIndex != 0 && dataIndex != frequencyData.size() - 1) {
                            float dist1 = Math.abs(pitchInHz - frequencyData.get(dataIndex - 1).second);
                            float dist2 = Math.abs(pitchInHz - frequencyData.get(dataIndex + 1).second);
                            if (dist1 > dist2) {
                                secondSmallestDistance = dist2;
                                FlutterFftPlugin.nearestNote = frequencyData.get(dataIndex + 1).first;
                                FlutterFftPlugin.nearestDistance = secondSmallestDistance;
                                FlutterFftPlugin.nearestTarget = frequencyData.get(dataIndex + 1).second;
                                FlutterFftPlugin.nearestOctave = frequencyData.get(dataIndex + 1).third;
                            } else if (dist1 < dist2) {
                                secondSmallestDistance = dist1;
                                FlutterFftPlugin.nearestNote = frequencyData.get(dataIndex - 1).first;
                                FlutterFftPlugin.nearestDistance = secondSmallestDistance;
                                FlutterFftPlugin.nearestTarget = frequencyData.get(dataIndex - 1).second;
                                FlutterFftPlugin.nearestOctave = frequencyData.get(dataIndex - 1).third;
                            } else {
                                FlutterFftPlugin.printError("Could not properly set up the frequency data");
                                return;
                            }
                        }
                        return;
                    }
                }
            }
        } else {
            FlutterFftPlugin.printError("Unknown tuning.");
            return;
        }
    }

    /**
     * Generates frequency data for all musical notes across octaves
     * Creates reference frequencies for pitch detection and tuning
     * Uses A4 = 440Hz as the standard tuning reference
     * @param result MethodChannel result for error handling
     */
    @RequiresApi(api = Build.VERSION_CODES.N)
    @Override
    public void getFrequenciesAndOctaves(MethodChannel.Result result) {
        float A4 = 440;
        float a;
        float aSharp;
        float b;
        float c;
        float cSharp;
        float d;
        float dSharp;
        float e;
        float f;
        float fSharp;
        float g;
        float gSharp;

        for (int i = -4; i < 4; i++) {
            a = A4 * (float) Math.pow(2, (float) i);
            aSharp = a * (float) Math.pow(2, (float) 1/12);
            b = a *  (float) Math.pow(2, (float) 2/12);
            c  = a * (float) Math.pow(2, (float) -9/12);
            cSharp = a * (float) Math.pow(2, (float) -8/12);
            d = a * (float) Math.pow(2, (float) -7/12);
            dSharp = a * (float) Math.pow(2, (float) -6/12);
            e = a * (float) Math.pow(2, (float) -5/12);
            f = a * (float)  Math.pow(2, (float) -4/12);
            fSharp = a * (float) Math.pow(2, (float) -3/12);
            g = a * (float)  Math.pow(2, (float) -2/12);
            gSharp = a * (float) Math.pow(2, (float) -1/12);

            frequencyData.add(new FrequencyData<String, Float, Integer>("B", b, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("A#", aSharp, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("A", a, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("G#", gSharp, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("G", g, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("F#", fSharp, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("F", f, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("E", e, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("D#", dSharp, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("D", d, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("C#", cSharp, i + 4));
            frequencyData.add(new FrequencyData<String, Float, Integer>("C", c, i + 4));
        }

        frequencyData.sort(new Comparator<FrequencyData<String, Float, Integer>>() {
            @Override
            public int compare(final FrequencyData<String, Float, Integer> FData, final FrequencyData<String, Float, Integer> otherFData) {
                if (FData.second > otherFData.second) {
                    return -1;
                } else if (FData.second < otherFData.second) {
                    return 1;
                } else {
                    return 0;
                }
            }
        });
    }

    public void setPitchDetector(PitchDetector pitchDetector) {
        this.pitchDetector = pitchDetector;
    }

    public void setTolerance(Float tolerance) {
        this.tolerance = tolerance;
    }

    public void setTuning(List<Object> tuning) {
        this.tuning = tuning;
    }

    /**
     * Parses tuning configuration and generates target frequencies
     * Converts string notation (e.g., "E4", "A#3") to frequency values
     */
    public void parseTuning() {
        tuningData = new ArrayList<Pair<String, Integer>>();

        if (tuning.get(0) != "None") {
            for (int i = 0; i < tuning.size(); i++) {
                if (tuning.get(i).toString().length() == 2) {
                    tuningData.add(new Pair<String, Integer>(Character.toString(tuning.get(i).toString().charAt(0)), (int) tuning.get(i).toString().charAt(1) - 50 + 2));
                }
                else if (tuning.get(i).toString().length() == 3) {
                    tuningData.add(new Pair<String, Integer>(Character.toString(tuning.get(i).toString().charAt(0)) + tuning.get(i).toString().charAt(1), (int) tuning.get(i).toString().charAt(2) - 50 + 2));
                }
            }

            targetFrequencies = new float[tuningData.size()];

            int currentTuneIdx = 0;

            for (Pair<String, Integer> tune : tuningData) {
                for (FrequencyData<String, Float, Integer> data : frequencyData) {
                    if (data.third.equals(tune.second) && data.first.equals(tune.first)) {
                        targetFrequencies[currentTuneIdx] = data.second;
                    }
                }
                currentTuneIdx++;
            }
        }
    }
}