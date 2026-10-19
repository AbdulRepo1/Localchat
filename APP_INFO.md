# LocalChat (Hands-Free Intercom) - App Documentation & Specifications

This document outlines the architecture, libraries used per task, networking protocols, background lifecycle management, and test suites for both the **Android** and **iOS** implementations.

---

## 1. Application Overview

- **App Name:** Local Chat / Intercom
- **Purpose:** Low-latency local network (Wi-Fi / LAN / Hotspot) peer-to-peer walkie-talkie and text chat tailored for hands-free environments (e.g., operating theaters, sterile labs, and industrial cleanrooms).
- **Key Capability:** Zero-touch discovery, automatic call answering (Intercom mode), and forced loudspeaker routing without user screen interaction.

---

## 2. Architecture & Library Breakdown per Task

### A. Android Implementation

| Functional Task | Android Native API / Library | Dependency Coordinates | Purpose & Notes |
| :--- | :--- | :--- | :--- |
| **UI & Layout** | Jetpack Compose (Material 3) | `androidx.compose.material3:material3`<br>`androidx.activity:activity-compose` | Declarative UI, Call overlay, status badges, responsive layouts. |
| **State Management & Concurrency** | Kotlin Coroutines & Flow | `org.jetbrains.kotlinx:kotlinx-coroutines-core`<br>`org.jetbrains.kotlinx:kotlinx-coroutines-android` | Asynchronous socket I/O, `StateFlow` (`connectionState`, `callState`, `discoveredPeers`). |
| **Service Discovery** | `NsdManager` (mDNS / DNS-SD) | Android Framework (`android.net.nsd`) | Registers and browses `_localchat._tcp.` Bonjour services on local subnet. |
| **Signaling & Chat (TCP)** | `java.net.ServerSocket`, `java.net.Socket` | Java Standard Library / Kotlin Coroutines | Port 8888; manages text communication and signaling messages (`SIGNAL|CALL_REQ`, `CALL_ACK`, `HANGUP`). |
| **Voice Streaming (UDP)** | `java.net.DatagramSocket` | Java Standard Library | Ephemeral UDP port; packetizes 16kHz 16-bit mono PCM chunks for sub-50ms latency. |
| **Audio Capture & Render** | `AudioRecord` & `AudioTrack` | Android Framework (`android.media`) | `AudioSource.VOICE_COMMUNICATION` with hardware AEC/NS; `USAGE_VOICE_COMMUNICATION`. |
| **Audio Routing & Hardware** | `AudioManager` & `SensorManager` | Android Framework (`android.media`, `android.hardware`) | Forces `setSpeakerphoneOn(true)`; proximity sensor toggles earpiece/speakerphone dynamically. |
| **Background Execution** | Foreground Service (`LocalChatService`) | Android Framework (`android.app.Service`) | Types: `microphone` & `dataSync` + `WAKE_LOCK` to keep network and audio live when screen is off. |
| **Boot Autostart** | `BroadcastReceiver` (`BootReceiver`) | Android Framework (`android.content.BroadcastReceiver`) | Re-launches the discovery and listener service on device reboot. |
| **Local Persistence** | `SharedPreferences` | Android Framework | Stores user preferences such as `autoIntercom` toggle state. |

---

### B. iOS Migration (`/ios_migration`)

| Functional Task | iOS Framework / Library | Primary Classes | Purpose & Notes |
| :--- | :--- | :--- | :--- |
| **UI Framework** | SwiftUI | `ContentView`, `CallOverlay` | Declarative UI with state binding for call and discovery modes. |
| **State Management** | Combine / Swift Observation | `ObservableObject`, `@Published` | Centralized state in `NetworkManager` and `AudioStreamer`. |
| **Service Discovery** | Apple Network Framework (Bonjour) | `NWBrowser`, `NWListener` | Resolves and advertises service `_localchat._tcp`. |
| **Signaling & Chat (TCP)** | Network Framework (TCP) | `NWListener`, `NWConnection` | Non-blocking TCP connection handling protocol commands and chat text. |
| **Voice Streaming (UDP)** | Network Framework (UDP) | `NWConnection(using: .udp)` | Low-latency async UDP streaming for PCM audio buffers. |
| **Audio Engine** | AVFoundation | `AVAudioEngine`, `AVAudioInputNode`, `AVAudioPlayerNode` | Captures mic tap and plays audio at 16000Hz, 16-bit Int PCM Mono. |
| **Audio Routing & Speaker** | AVFAudio (`AVAudioSession`) | `AVAudioSession.sharedInstance()` | Category `.playAndRecord`, mode `.voiceChat`, options `[.defaultToSpeaker, .allowBluetooth]`. |
| **Background & Permissions** | iOS Background Modes (`Info.plist`) | `audio`, `voip` | Keeps audio engine and socket loop alive during lock screen or app backgrounding. |

---

## 3. Communication Protocols

### Signaling Protocol (TCP on port 8888)
Commands are string lines terminated by `\n` with prefix `SIGNAL|`:

1. **`SIGNAL|CALL_REQ:<udp_port>`**
   - Sent by caller to initiate audio call.
   - Contains caller's local UDP port for receiving incoming audio stream.
2. **`SIGNAL|CALL_ACK:<udp_port>`**
   - Sent by callee to accept the call (auto-accepted when Intercom mode is ON).
   - Contains callee's local UDP port.
3. **`SIGNAL|CALL_REJECT`**
   - Sent when callee is busy in another call or manually declines.
4. **`SIGNAL|HANGUP`**
   - Terminates an active or pending call; resets both peers to `IDLE` and closes audio pipelines.

### Audio Stream Protocol (UDP)
- **Sample Rate:** 16,000 Hz
- **Format:** Linear PCM, 16-bit Signed Little-Endian
- **Channels:** 1 (Mono)
- **Buffer Size:** 1,024 – 2,048 bytes per packet (~32ms - 64ms per packet)

---

## 4. Test Cases & Test Strategy

### A. Implemented Tests
- **JVM Local Unit Test:**
  - File: `/app/src/test/java/com/example/ExampleUnitTest.kt`
  - Validates basic logic on host JVM without device dependencies.
- **Robolectric JVM Test:**
  - File: `/app/src/test/java/com/example/ExampleRobolectricTest.kt`
  - Validates Android resource strings and application context on JVM without emulator.

### B. Recommended Critical User Journey (CUJ) Test Suites

#### Test Suite 1: Signaling & State Transitions (`NetworkManagerTest`)
- **TC-SIG-01: Auto-Answer Incoming Call**
  - *Given:* Intercom auto-answer is enabled (`autoIntercomEnabled = true`), `callState = IDLE`.
  - *When:* An incoming signal `SIGNAL|CALL_REQ:5005` is received.
  - *Then:* `callState` transitions to `INCOMING` and automatically to `ACTIVE` within countdown, transmitting `SIGNAL|CALL_ACK:<localPort>`.
- **TC-SIG-02: Busy Call Rejection**
  - *Given:* `callState = ACTIVE`.
  - *When:* A second incoming `SIGNAL|CALL_REQ:5005` arrives.
  - *Then:* Respond with `SIGNAL|CALL_REJECT` without interrupting the ongoing call.
- **TC-SIG-03: Hangup Resets State**
  - *Given:* `callState = ACTIVE`.
  - *When:* `SIGNAL|HANGUP` is received or sendHangup is invoked.
  - *Then:* `callState` becomes `IDLE`, UDP audio streamer stops, audio session deactivates.

#### Test Suite 2: Audio Pipeline & Routing (`AudioStreamerTest`)
- **TC-AUD-01: PCM Configuration Match**
  - *Assert:* AudioRecord and AudioTrack sample rate is 16,000 Hz, mono, 16-bit PCM.
- **TC-AUD-02: Speakerphone Default Routing**
  - *Assert:* Initial audio mode is `MODE_IN_COMMUNICATION` with `isSpeakerphoneOn = true`.
- **TC-AUD-03: Proximity Sensor Toggle**
  - *When:* Proximity sensor detects distance < threshold.
  - *Then:* Temporarily route audio to earpiece (`isSpeakerphoneOn = false`).
  - *When:* Proximity sensor detects distance > threshold.
  - *Then:* Restore loudspeaker (`isSpeakerphoneOn = true`).

#### Test Suite 3: Discovery & Network Resilience
- **TC-NET-01: NSD Discovery Service Filter**
  - *Assert:* `NsdManager` filters out self-advertised service name from `discoveredPeers`.
- **TC-NET-02: Socket Disconnect Recovery**
  - *When:* Active TCP socket drops (`SocketException`).
  - *Then:* Clean up reader/writer, reset `connectionState` to `SEARCHING`, and resume NSD browsing.

---

## 5. How to Run Verification & Tests

### Compile Android Project:
To verify compilation of the entire Android project:
```bash
gradle assembleDebug
```

### Run JVM & Robolectric Tests:
```bash
gradle :app:testDebugUnitTest
```
