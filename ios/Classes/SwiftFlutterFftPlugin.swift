import Flutter
import UIKit
import AVFoundation
import Accelerate

public class SwiftFlutterFftPlugin: NSObject, FlutterPlugin, FlutterStreamHandler {
    private static let RECORD_STREAM = "com.slins.flutterfft/record"
    private static let AUDIO_STREAM = "com.slins.flutterfft/audio_stream"
    
    // Audio engine and processing
    private var audioEngine: AVAudioEngine?
    private var inputNode: AVAudioInputNode?
    private var isRecording = false
    private var isAudioProcessingPaused = false
    
    // EventChannel for streaming audio data
    private var eventSink: FlutterEventSink?
    
    // Audio parameters
    private var sampleRate: Double = 44100
    private var subscriptionDuration: Double = 0.25
    private var tolerance: Float = 1.0
    private var tuning: [String] = ["E4", "B3", "G3", "D3", "A2", "E2"]
    
    // Processing timer
    private var processingTimer: Timer?
    
    // Frequency data storage
    private var frequencyData: [(note: String, frequency: Float, octave: Int)] = []
    private var targetFrequencies: [Float] = []
    private var tuningData: [(note: String, octave: Int)] = []
    
    // Current detection results
    private var currentFrequency: Float = 0
    private var currentNote: String = ""
    private var currentOctave: Int = 0
    private var currentTarget: Float = 0
    private var currentDistance: Float = 0
    private var nearestNote: String = ""
    private var nearestTarget: Float = 0
    private var nearestDistance: Float = 0
    private var nearestOctave: Int = 0
    private var isOnPitch: Bool = false
    
    public static func register(with registrar: FlutterPluginRegistrar) {
        // Setup MethodChannel for commands
        let methodChannel = FlutterMethodChannel(name: RECORD_STREAM, binaryMessenger: registrar.messenger())
        let instance = SwiftFlutterFftPlugin()
        registrar.addMethodCallDelegate(instance, channel: methodChannel)
        
        // Setup EventChannel for audio streaming
        let eventChannel = FlutterEventChannel(name: AUDIO_STREAM, binaryMessenger: registrar.messenger())
        eventChannel.setStreamHandler(instance)
    }
    
    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "startRecorder":
            handleStartRecorder(call: call, result: result)
        case "stopRecorder":
            handleStopRecorder(result: result)
        case "setSubscriptionDuration":
            handleSetSubscriptionDuration(call: call, result: result)
        case "checkPermission":
            handleCheckPermission(result: result)
        case "requestPermission":
            handleRequestPermission(result: result)
        case "pauseAudioProcessing":
            handlePauseAudioProcessing(result: result)
        case "resumeAudioProcessing":
            handleResumeAudioProcessing(result: result)
        default:
            result(FlutterMethodNotImplemented)
        }
    }
    
    // MARK: - Method Handlers
    
    private func handleStartRecorder(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any] else {
            result(FlutterError(code: "INVALID_ARGUMENTS", message: "Invalid arguments", details: nil))
            return
        }
        
        // Extract parameters
        if let tuningArray = args["tuning"] as? [String] {
            tuning = tuningArray
        }
        if let sampleRateInt = args["sampleRate"] as? Int {
            sampleRate = Double(sampleRateInt)
        }
        if let toleranceDouble = args["tolerance"] as? Double {
            tolerance = Float(toleranceDouble)
        }
        
        // Initialize frequency data if not done yet
        if frequencyData.isEmpty {
            generateFrequencyData()
        }
        
        startRecording { [weak self] success, error in
            DispatchQueue.main.async {
                if success {
                    result("Recorder successfully set up.")
                } else {
                    result(FlutterError(code: "START_RECORDER_ERROR", message: error ?? "Unknown error", details: nil))
                }
            }
        }
    }
    
    private func handleStopRecorder(result: @escaping FlutterResult) {
        stopRecording()
        result("Recorder stopped.")
    }
    
    private func handleSetSubscriptionDuration(call: FlutterMethodCall, result: @escaping FlutterResult) {
        guard let args = call.arguments as? [String: Any],
              let sec = args["sec"] as? Double else {
            result(FlutterError(code: "INVALID_ARGUMENT", message: "sec argument is null", details: nil))
            return
        }
        
        subscriptionDuration = sec
        result("setSubscriptionDuration: \(Int(sec * 1000))")
    }
    
    private func handleCheckPermission(result: @escaping FlutterResult) {
        let permission = AVAudioSession.sharedInstance().recordPermission
        result(permission == .granted)
    }
    
    private func handleRequestPermission(result: @escaping FlutterResult) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted in
            DispatchQueue.main.async {
                result(nil)
            }
        }
    }
    
    private func handlePauseAudioProcessing(result: @escaping FlutterResult) {
        isAudioProcessingPaused = true
        result("Audio processing paused")
    }
    
    private func handleResumeAudioProcessing(result: @escaping FlutterResult) {
        isAudioProcessingPaused = false
        result("Audio processing resumed")
    }
    
    // MARK: - Audio Recording
    
    private func startRecording(completion: @escaping (Bool, String?) -> Void) {
        // Check permission first
        let permission = AVAudioSession.sharedInstance().recordPermission
        guard permission == .granted else {
            completion(false, "Microphone permission not granted")
            return
        }
        
        // Setup audio session
        do {
            let audioSession = AVAudioSession.sharedInstance()
            try audioSession.setCategory(.record, mode: .measurement, options: [.allowBluetooth])
            try audioSession.setPreferredSampleRate(sampleRate)
            try audioSession.setPreferredIOBufferDuration(0.005) // 5ms buffer for low latency
            try audioSession.setActive(true)
        } catch {
            completion(false, "Failed to setup audio session: \(error.localizedDescription)")
            return
        }
        
        // Setup audio engine
        audioEngine = AVAudioEngine()
        guard let audioEngine = audioEngine else {
            completion(false, "Failed to create audio engine")
            return
        }
        
        inputNode = audioEngine.inputNode
        guard let inputNode = inputNode else {
            completion(false, "Failed to get input node")
            return
        }
        
        // Use the input node's default format to avoid compatibility issues
        let recordingFormat = inputNode.outputFormat(forBus: 0)
        
        // Install tap on input node with larger buffer for better frequency resolution
        inputNode.installTap(onBus: 0, bufferSize: 4096, format: recordingFormat) { [weak self] buffer, time in
            self?.processAudioBuffer(buffer)
        }
        
        // Start audio engine
        do {
            try audioEngine.start()
            isRecording = true
            
            // Start processing timer
            DispatchQueue.main.async { [weak self] in
                self?.processingTimer = Timer.scheduledTimer(withTimeInterval: self?.subscriptionDuration ?? 0.25, repeats: true) { [weak self] _ in
                    self?.sendAudioData()
                }
            }
            
            completion(true, nil)
        } catch {
            completion(false, "Failed to start audio engine: \(error.localizedDescription)")
        }
    }
    
    private func stopRecording() {
        processingTimer?.invalidate()
        processingTimer = nil
        
        audioEngine?.inputNode.removeTap(onBus: 0)
        audioEngine?.stop()
        audioEngine = nil
        inputNode = nil
        
        isRecording = false
        
        // Deactivate audio session
        do {
            try AVAudioSession.sharedInstance().setActive(false)
        } catch {
            print("Failed to deactivate audio session: \(error)")
        }
    }
    
    // MARK: - Audio Processing with Accelerate Framework
    
    private var latestFrequency: Float = 0
    private var fftSetup: FFTSetup?
    private let fftSize = 4096  // Increased for better frequency resolution
    private var windowedSamples: [Float] = []
    private var fftReal: [Float] = []
    private var fftImag: [Float] = []
    private var magnitude: [Float] = []
    private var audioBuffer: [Float] = []  // Accumulation buffer
    private let noiseThreshold: Float = 0.001  // Minimum magnitude threshold
    
    private func processAudioBuffer(_ buffer: AVAudioPCMBuffer) {
        guard !isAudioProcessingPaused else { return }
        
        let frameLength = Int(buffer.frameLength)
        guard frameLength > 0, let channelData = buffer.floatChannelData else { return }
        
        // Setup FFT if needed
        if fftSetup == nil {
            fftSetup = vDSP_create_fftsetup(vDSP_Length(log2(Float(fftSize))), FFTRadix(kFFTRadix2))
            windowedSamples = Array(repeating: 0.0, count: fftSize)
            fftReal = Array(repeating: 0.0, count: fftSize/2)
            fftImag = Array(repeating: 0.0, count: fftSize/2)
            magnitude = Array(repeating: 0.0, count: fftSize/2)
            audioBuffer = Array(repeating: 0.0, count: fftSize)
        }
        
        // Get audio samples from first channel
        let samples = Array(UnsafeBufferPointer(start: channelData[0], count: frameLength))
        
        // Accumulate samples in buffer
        if audioBuffer.count >= fftSize {
            // Shift buffer and add new samples
            let samplesToShift = max(0, audioBuffer.count - frameLength)
            if samplesToShift > 0 {
                audioBuffer.removeFirst(frameLength)
            }
            audioBuffer.append(contentsOf: samples)
        } else {
            audioBuffer.append(contentsOf: samples)
        }
        
        // Only process when we have enough samples
        guard audioBuffer.count >= fftSize else { return }
        
        // Use the latest fftSize samples
        let samplesToAnalyze = Array(audioBuffer.suffix(fftSize))
        
        // Apply Hanning window to reduce spectral leakage
        for i in 0..<fftSize {
            let windowValue = 0.5 * (1.0 - cos(2.0 * Float.pi * Float(i) / Float(fftSize - 1)))
            windowedSamples[i] = samplesToAnalyze[i] * windowValue
        }
        
        // Perform FFT using vDSP
        guard let setup = fftSetup else { return }
        
        // Copy windowed samples to fft input arrays, interleaving real values
        for i in 0..<(fftSize/2) {
            fftReal[i] = windowedSamples[i * 2]
            fftImag[i] = i * 2 + 1 < fftSize ? windowedSamples[i * 2 + 1] : 0.0
        }
        
        // Split complex for FFT
        var splitComplex = DSPSplitComplex(realp: &fftReal, imagp: &fftImag)
        
        // Perform FFT
        vDSP_fft_zrip(setup, &splitComplex, 1, vDSP_Length(log2(Float(fftSize))), FFTDirection(FFT_FORWARD))
        
        // Calculate magnitude spectrum
        vDSP_zvmags(&splitComplex, 1, &magnitude, 1, vDSP_Length(fftSize/2))
        
        // Find the peak frequency
        let detectedFrequency = findPeakFrequency(magnitudes: magnitude, sampleRate: Float(sampleRate))
        
        // Only update if we have a valid frequency detection
        if detectedFrequency > 80.0 && detectedFrequency < 2000.0 {
            latestFrequency = detectedFrequency
        }
    }
    
    private func findPeakFrequency(magnitudes: [Float], sampleRate: Float) -> Float {
        // Only look in the musical frequency range (roughly 80Hz to 2000Hz)
        let minIndex = max(1, Int(80.0 * Float(fftSize) / sampleRate))
        let maxSearchIndex = min(magnitudes.count - 1, Int(2000.0 * Float(fftSize) / sampleRate))
        
        // Calculate average magnitude for noise filtering
        let validMagnitudes = Array(magnitudes[minIndex...maxSearchIndex])
        let averageMagnitude = validMagnitudes.reduce(0, +) / Float(validMagnitudes.count)
        let threshold = max(noiseThreshold, averageMagnitude * 2.0)
        
        // Find the index of the maximum magnitude that's above threshold
        var maxIndex = minIndex
        var maxMagnitude = magnitudes[minIndex]
        
        for i in minIndex...maxSearchIndex {
            if magnitudes[i] > maxMagnitude && magnitudes[i] > threshold {
                maxMagnitude = magnitudes[i]
                maxIndex = i
            }
        }
        
        // Return 0 if no significant peak found
        guard maxMagnitude > threshold else { return 0 }
        
        // Convert bin index to frequency
        let frequency = Float(maxIndex) * sampleRate / Float(fftSize)
        
        // Apply parabolic interpolation for better frequency accuracy
        if maxIndex > minIndex && maxIndex < maxSearchIndex {
            let y1 = magnitudes[maxIndex - 1]
            let y2 = magnitudes[maxIndex]
            let y3 = magnitudes[maxIndex + 1]
            
            let a = (y1 - 2*y2 + y3) / 2
            let b = (y3 - y1) / 2
            
            if a != 0 {
                let x0 = -b / (2 * a)
                let interpolatedFrequency = frequency + x0 * (sampleRate / Float(fftSize))
                
                // Validate interpolated frequency is within reasonable bounds
                if interpolatedFrequency > 80.0 && interpolatedFrequency < 2000.0 {
                    return interpolatedFrequency
                }
            }
        }
        
        return frequency
    }
    
    deinit {
        if let setup = fftSetup {
            vDSP_destroy_fftsetup(setup)
        }
    }
    
    private func sendAudioData() {
        guard isRecording, !isAudioProcessingPaused, latestFrequency > 0 else { return }
        
        // Process the detected frequency
        currentFrequency = latestFrequency
        processPitch(frequency: currentFrequency)
        
        // Prepare data array matching Android format
        let returnData: [Any] = [
            tolerance,           // 0
            currentFrequency,    // 1
            currentNote,         // 2
            currentTarget,       // 3
            currentDistance,     // 4
            currentOctave,       // 5
            nearestNote,         // 6
            nearestTarget,       // 7
            nearestDistance,     // 8
            nearestOctave,       // 9
            isOnPitch           // 10
        ]
        
        // Send via EventChannel
        DispatchQueue.main.async { [weak self] in
            self?.eventSink?(returnData)
        }
    }
    
    // MARK: - Pitch Processing
    
    private func processPitch(frequency: Float) {
        parseTuning()
        
        if tuning.first != "None" && !targetFrequencies.isEmpty {
            processTuningMode(frequency: frequency)
        } else {
            processGeneralMode(frequency: frequency)
        }
    }
    
    private func processTuningMode(frequency: Float) {
        var smallestDistance = Float.greatestFiniteMagnitude
        var targetIndex = 0
        
        // Find closest target frequency
        for (index, targetFreq) in targetFrequencies.enumerated() {
            let distance = abs(frequency - targetFreq)
            if distance < smallestDistance {
                smallestDistance = distance
                targetIndex = index
            }
        }
        
        currentDistance = smallestDistance
        currentTarget = targetFrequencies[targetIndex]
        
        if smallestDistance < tolerance {
            // On pitch
            currentNote = tuningData[targetIndex].note
            currentOctave = tuningData[targetIndex].octave
            isOnPitch = true
        } else {
            // Off pitch - find nearest note from frequency data
            isOnPitch = false
            findNearestNote(frequency: frequency)
            
            nearestNote = tuningData[targetIndex].note
            nearestOctave = tuningData[targetIndex].octave
            nearestTarget = targetFrequencies[targetIndex]
            nearestDistance = smallestDistance
        }
    }
    
    private func processGeneralMode(frequency: Float) {
        var smallestDistance = Float.greatestFiniteMagnitude
        var secondSmallestDistance = Float.greatestFiniteMagnitude
        var noteIndex = -1
        var secondNoteIndex = -1
        
        for (index, noteData) in frequencyData.enumerated() {
            let distance = abs(frequency - noteData.frequency)
            
            if distance < smallestDistance {
                secondSmallestDistance = smallestDistance
                secondNoteIndex = noteIndex
                smallestDistance = distance
                noteIndex = index
            } else if distance < secondSmallestDistance {
                secondSmallestDistance = distance
                secondNoteIndex = index
            }
        }
        
        if noteIndex >= 0 {
            currentNote = frequencyData[noteIndex].note
            currentOctave = frequencyData[noteIndex].octave
            currentTarget = frequencyData[noteIndex].frequency
            currentDistance = smallestDistance
            isOnPitch = smallestDistance < tolerance
            
            if secondNoteIndex >= 0 {
                nearestNote = frequencyData[secondNoteIndex].note
                nearestOctave = frequencyData[secondNoteIndex].octave
                nearestTarget = frequencyData[secondNoteIndex].frequency
                nearestDistance = secondSmallestDistance
            }
        }
    }
    
    private func findNearestNote(frequency: Float) {
        var smallestDistance = Float.greatestFiniteMagnitude
        var noteIndex = -1
        
        for (index, noteData) in frequencyData.enumerated() {
            let distance = abs(frequency - noteData.frequency)
            if distance < smallestDistance {
                smallestDistance = distance
                noteIndex = index
            }
        }
        
        if noteIndex >= 0 {
            currentNote = frequencyData[noteIndex].note
            currentOctave = frequencyData[noteIndex].octave
        }
    }
    
    // MARK: - Musical Data Generation
    
    private func generateFrequencyData() {
        frequencyData.removeAll()
        
        let A4: Float = 440.0
        let notes = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]
        
        // Generate frequencies for octaves 0-8
        for octave in 0...8 {
            for (noteIndex, noteName) in notes.enumerated() {
                // Calculate semitone offset from A4
                let semitoneOffset = (octave - 4) * 12 + (noteIndex - 9) // A is at index 9
                let frequency = A4 * pow(2.0, Float(semitoneOffset) / 12.0)
                
                frequencyData.append((note: noteName, frequency: frequency, octave: octave))
            }
        }
        
        // Sort by frequency
        frequencyData.sort { $0.frequency < $1.frequency }
    }
    
    private func parseTuning() {
        tuningData.removeAll()
        targetFrequencies.removeAll()
        
        guard tuning.first != "None" else { return }
        
        for tuningString in tuning {
            guard tuningString.count >= 2 else { continue }
            
            let noteChar = String(tuningString.prefix(tuningString.count - 1))
            let octaveChar = String(tuningString.suffix(1))
            
            guard let octave = Int(octaveChar) else { continue }
            
            tuningData.append((note: noteChar, octave: octave))
            
            // Find matching frequency
            for noteData in frequencyData {
                if noteData.note == noteChar && noteData.octave == octave {
                    targetFrequencies.append(noteData.frequency)
                    break
                }
            }
        }
    }
    
    // MARK: - FlutterStreamHandler
    
    public func onListen(withArguments arguments: Any?, eventSink events: @escaping FlutterEventSink) -> FlutterError? {
        self.eventSink = events
        return nil
    }
    
    public func onCancel(withArguments arguments: Any?) -> FlutterError? {
        self.eventSink = nil
        return nil
    }
}
