#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint flutter_fft.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'flutter_fft'
  s.version          = '0.0.1'
  s.summary          = 'Flutter FFT plugin for real-time pitch detection and audio analysis.'
  s.description      = <<-DESC
A Flutter plugin for real-time pitch detection and audio analysis using microphone input.
Supports frequency detection, musical note identification, and tuning analysis.
                       DESC
  s.homepage         = 'https://github.com/Slins-23/flutter-fft'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Slins' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  s.platform = :ios, '9.0'

  # Add required frameworks for audio processing
  s.frameworks = 'AVFoundation', 'Accelerate'
  
  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
