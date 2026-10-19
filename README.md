# LocalChat (Hands-Free Intercom)

Direct local network peer-to-peer walkie-talkie and text chat application for Android and iOS, built specifically for sterile/hands-free environments such as operating theaters.

## Documentation

Full documentation detailing:
- Every library and native framework used for each task
- Network signaling protocol (`SIGNAL|CALL_REQ`, `CALL_ACK`, `HANGUP`)
- Low-latency UDP PCM 16kHz audio pipeline
- Foreground service & background audio session configurations
- Comprehensive test cases & testing strategy

can be found in:
👉 **[`/APP_INFO.md`](APP_INFO.md)**

## Project Structure
- `/app`: Android Kotlin + Jetpack Compose application
  - `/app/src/main/java/com/example/MainActivity.kt`: M3 UI and call overlay
  - `/app/src/main/java/com/example/NetworkManager.kt`: NSD discovery & TCP signaling
  - `/app/src/main/java/com/example/AudioStreamer.kt`: 16kHz PCM audio record/track & UDP stream
  - `/app/src/main/java/com/example/LocalChatService.kt`: Microphone + DataSync Foreground Service
  - `/app/src/test/java/com/example/`: Unit & Robolectric test suites
- `/ios_migration`: iOS Swift + SwiftUI native migration
  - `NetworkManager.swift`: Bonjour discovery (`NWBrowser`, `NWListener`) & TCP/UDP sockets
  - `AudioStreamer.swift`: `AVAudioEngine` + `AVAudioSession` loudspeaker routing
  - `ContentView.swift` & `CallOverlay.swift`: SwiftUI user interface
  - `Info.plist`: Background modes (`audio`, `voip`), Bonjour, and permissions
