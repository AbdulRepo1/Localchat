# LocalChat Engineering & Architecture Learning Guide

This guide is designed as an interactive reference for building, refactoring, and mastering modern Android app development based on the **LocalChat** codebase.

---

## 1. Project Audit: Would a Senior Developer Start Like This?

### The Verdict: **No (This is a Proof-of-Concept / Prototype Structure)**
While the networking and audio logic inside this repository are functional, the codebase is structured as a **rapid prototype (PoC)** rather than an enterprise-grade or production-ready application.

### Why It Needs Refactoring:
1. **Flat Package Anti-Pattern (`com.example`)**:
   - All files are dumped into a single root folder. In production, code is organized by **layer** (`data`, `domain`, `ui`) or by **feature** (`feature_chat`, `feature_call`, `feature_discovery`).
2. **"God" Activity (`MainActivity.kt` - 700+ lines)**:
   - Contains UI composables, permission handling, battery optimization popups, socket calls, and hardware flags all in one place.
   - Violates the **Single Responsibility Principle (SRP)**.
3. **Singleton with Context Leaks (`NetworkManager.getInstance(context)`)**:
   - Singletons holding references to Android `Context` or system services risk memory leaks and make writing automated unit tests difficult.
4. **No ViewModel / State Separation**:
   - Compose UI directly reads and mutates state flows from services and singletons. A proper MVI/MVVM pattern with Android `ViewModel` is missing.
5. **No Data Persistence**:
   - Messages exist only in memory (`MutableStateFlow<List<ChatMessage>>`). Closing the app wipes out the conversation history. Room database dependencies exist in `build.gradle.kts` but are not implemented.
6. **Raw PCM Audio Over UDP**:
   - Streaming raw 16kHz uncompressed PCM over UDP consumes unnecessary bandwidth (~256 kbps) and lacks jitter buffering or packet sequencing.

---

## 2. Target Senior-Level Architecture (Clean Architecture + MVI/MVVM)

Here is what the project structure should look like to make it maintainable, scalable, and testable:

```text
com.localchat.app/
│
├── core/                               # Cross-cutting core infrastructure
│   ├── network/                        # Wi-Fi lock helper, Network connectivity observer
│   ├── common/                         # Result wrappers, Dispatcher providers, Constants
│   └── database/                       # Room database configuration, TypeConverters
│
├── data/                               # DATA LAYER: Source of Truth
│   ├── local/
│   │   ├── dao/MessageDao.kt           # Room SQL queries for chat history
│   │   └── entity/MessageEntity.kt     # Database table representation
│   ├── remote/
│   │   ├── nsd/NsdDiscoverySource.kt   # ZeroConf / mDNS discovery abstraction
│   │   ├── tcp/TcpSignalingClient.kt   # TCP handshake, call requests, chat text
│   │   └── udp/UdpAudioSocket.kt       # UDP raw datagram socket transport
│   └── repository/
│       ├── ChatRepositoryImpl.kt       # Implements ChatRepository
│       └── CallRepositoryImpl.kt       # Implements CallRepository
│
├── domain/                             # DOMAIN LAYER: Pure Business Logic
│   ├── model/                          # Clean domain data classes (Peer, Message, CallSession)
│   ├── repository/                     # Repository interfaces (contracts)
│   └── usecase/                        # Single-action business use cases
│       ├── SendMessageUseCase.kt
│       ├── ObserveMessagesUseCase.kt
│       ├── InitiateCallUseCase.kt
│       └── DiscoverPeersUseCase.kt
│
├── audio/                              # AUDIO PIPELINE SUBSYSTEM
│   ├── AudioRecorder.kt                # Hardware microphone recording (AudioRecord)
│   ├── AudioPlayer.kt                  # Hardware speaker playback (AudioTrack)
│   ├── ProximitySensorManager.kt       # Earpiece vs. Speaker switching via proximity
│   └── AudioStreamController.kt        # Orchestrates record/playback coroutine loops
│
├── service/                            # BACKGROUND EXECUTION
│   ├── LocalChatService.kt             # Foreground service holding Wi-Fi lock & sockets
│   └── BootReceiver.kt                 # Re-registers service on device reboot
│
├── di/                                 # DEPENDENCY INJECTION (Hilt)
│   ├── AppModule.kt                    # Context, CoroutineDispatchers
│   ├── NetworkModule.kt                # NSD, Sockets, Discovery
│   ├── DatabaseModule.kt               # Room database instance
│   └── RepositoryModule.kt             # Binds repository interfaces to implementations
│
└── ui/                                 # PRESENTATION LAYER (Jetpack Compose)
    ├── theme/                          # Color, Theme, Typography
    ├── common/                         # Reusable buttons, dialogs, status badges
    ├── discovery/                      # Peer list screen
    │   ├── PeerListScreen.kt
    │   └── PeerListViewModel.kt
    ├── chat/                           # Chat message screen
    │   ├── ChatScreen.kt
    │   └── ChatViewModel.kt
    └── call/                           # Call screen / overlay
        ├── CallOverlay.kt
        └── CallViewModel.kt
```

---

## 3. Libraries, APIs, and Functions Breakdown

### A. Network Service Discovery (NSD / mDNS)
* **Android Class**: `android.net.nsd.NsdManager`
* **What it does**: Discovers devices on the local Wi-Fi without needing a centralized server (similar to Apple Bonjour).
* **Key Functions**:
  - `registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)`: Advertises this phone as a peer on `_localchat._tcp.`.
  - `discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)`: Scans for other phones advertising `_localchat._tcp.`.
  - `resolveService(serviceInfo, resolveListener)`: Obtains the peer's resolved IP address (`InetAddress`) and port number.

### B. TCP Signaling vs. UDP Audio
This app uses **two different network protocols** for distinct jobs:

| Protocol | Implementation | Purpose | Why? |
| :--- | :--- | :--- | :--- |
| **TCP** | `ServerSocket`, `Socket`, `PrintWriter`, `BufferedReader` | Signaling (Call requests, Accepts, Hangup, Text Chat) | **Guaranteed Delivery**: Chat text and call controls cannot afford dropped packets. |
| **UDP** | `DatagramSocket`, `DatagramPacket` | Real-time Voice Audio (PCM) | **Low Latency**: If a voice packet arrives late, it is discarded. TCP retries would cause audible lag and stutter. |

### C. Low-Latency Audio Pipeline
* **`android.media.AudioRecord`**:
  - `AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT, bufferSize)`
  - *Purpose*: Captures raw microphone input with hardware acoustic echo cancellation (AEC) and noise suppression enabled via `VOICE_COMMUNICATION`.
* **`android.media.AudioTrack`**:
  - `AudioTrack.Builder().setAudioAttributes(...).setTransferMode(AudioTrack.MODE_STREAM).build()`
  - *Purpose*: Streams incoming byte packets directly to the device DAC (digital-to-analog converter) without saving to disk first.
* **`android.media.AudioManager`**:
  - `mode = AudioManager.MODE_IN_COMMUNICATION`: Configures audio system for VoIP calls.
  - `isSpeakerphoneOn`: Toggles audio output between the bottom loudspeaker and top earpiece.

### D. Android Proximity Sensor
* **`android.hardware.SensorManager` & `Sensor.TYPE_PROXIMITY`**:
  - Listens for distance changes (`event.values[0] < sensor.maximumRange`).
  - *Purpose*: When you hold the phone to your ear, it turns off the loudspeaker and routes audio to the earpiece to prevent deafening the user.

### E. Background Services & OS Power Management
* **`LocalChatService` (`android.app.Service`)**:
  - Calls `startForeground(NOTIFICATION_ID, notification)` with `ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE`.
  - *Purpose*: Modern Android aggressively kills background background apps. A foreground service displays an ongoing notification, keeping the app alive while locked or minimized.
* **`WifiManager.createWifiLock(WIFI_MODE_FULL_HIGH_PERF, ...)`**:
  - Prevents the Wi-Fi chip from entering power-saving sleep mode during active calls.
* **`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**:
  - Prompts the user to exempt the app from Android Doze Mode so incoming calls can ring when the screen is dark.

---

## 4. Professional Implementation Roadmap

To turn this into a production-grade application, follow these steps:

### Phase 1: Separation of Concerns (ViewModel & UI Cleanup)
1. Extract Compose screens out of `MainActivity.kt` into `ui/chat/` and `ui/call/`.
2. Create `ChatViewModel` and `CallViewModel`.
3. Move `NetworkManager` method calls into ViewModels; make UI observe ViewModels exclusively.

### Phase 2: Dependency Injection (Hilt)
1. Add Hilt plugins to Gradle.
2. Replace `NetworkManager.getInstance(context)` with `@Inject constructor(...)`.
3. Inject repositories into ViewModels using `@HiltViewModel`.

### Phase 3: Persistence with Room Database
1. Create `ChatEntity` (id, peerName, text, timestamp, isMine, deliveryStatus).
2. Create `MessageDao` with Flow queries (`fun getMessagesForPeer(peer: String): Flow<List<ChatEntity>>`).
3. Store incoming and outgoing messages in Room so chats persist across restarts.

### Phase 4: Audio Compression (Opus Codec)
1. Raw PCM uses ~256 kbps.
2. Integrating the **Opus** codec (via JNI or Android's MediaCodec API) reduces bandwidth to ~16-32 kbps with built-in packet loss concealment (PLC) and crystal-clear voice clarity.

---

## 5. Architectural Changelog & Implementation Notes

### Step 1: UI Decoupling & ViewModel Introduction (Completed)

#### What Changed:
- **`MainActivity.kt`**: Reduced from ~700 lines down to ~150 lines. The Activity now acts strictly as an entry point responsible only for Android lifecycle, screen wake/lock flags, and runtime permission launchers.
- **Created `com.example.ui.chat.ChatUiState`**: An immutable data class bundling all UI-related states (connection state, connected peer, messages, search dialog, text input).
- **Created `com.example.ui.chat.ChatViewModel`**: 
  - Extends Android `ViewModel`.
  - Uses Kotlin `combine` and `stateIn` with `SharingStarted.WhileSubscribed(5000)` to expose a single observable `StateFlow<ChatUiState>`.
  - Survives screen rotations and prevents UI memory leaks.
- **Created `com.example.ui.call.CallUiState` & `CallViewModel`**:
  - Manages call state (`IDLE`, `INCOMING`, `OUTGOING`, `ACTIVE`), microphone muting, and speakerphone toggling.
- **Created `com.example.ui.call.CallOverlay`**:
  - Decoupled from `NetworkManager`. Uses hoisted events (`onAccept`, `onEndCall`, `onToggleSpeaker`) for testability and Compose `@Preview` support.
- **Created `com.example.ui.chat.components.MessageBubble`**:
  - Reusable chat message bubble component.

#### Deep Dive: Key Functions & Patterns Used in Step 1:
1. **Unidirectional Data Flow (UDF)**:
   - State flows down: `ViewModel` -> `ChatScreen` -> UI Composables.
   - Events flow up: UI interactions (clicks, text input) -> ViewModel functions (`sendMessage()`, `onMessageTextChanged()`).
2. **`combine()` in Kotlin Flows**:
   - Takes multiple independent flows (`connectionState`, `messages`, `discoveredPeers`, `messageText`) and combines their latest values into a single unified `ChatUiState`.
3. **`SharingStarted.WhileSubscribed(5000)`**:
   - Keeps the flow active while the UI is observing it. If the app is sent to the background, it keeps running for 5 seconds (to tolerate quick screen rotations) and then pauses upstream collection, saving battery.
4. **State Hoisting**:
   - Composable functions like `CallOverlay` and `MessageBubble` do not hold state or talk to singletons. They receive state and emit callbacks, making them reusable and testable.

---

### Step 2: Room Database Persistence & Reactive Flows (Completed)

#### What Changed:
- **Created `com.example.data.local.entity.MessageEntity`**:
  - `@Entity(tableName = "messages")` with a primary key UUID, `peerName`, `text`, `isMine`, and `timestamp`.
- **Created `com.example.data.local.dao.MessageDao`**:
  - Defines SQL queries returning reactive `Flow<List<MessageEntity>>`.
  - When new rows are inserted, Room automatically dispatches the new list to observers.
- **Created `com.example.data.local.AppDatabase`**:
  - Extends `RoomDatabase`. Provides SQLite table instantiation with fallback migrations.
- **Created `com.example.data.repository.ChatRepository`**:
  - The single source of truth mediating between the database and UI.
  - Maps database `MessageEntity` to UI domain `ChatMessage` objects.
- **Upgraded `NetworkManager.kt`**:
  - Replaced volatile `MutableStateFlow<List<ChatMessage>>` with `_connectedPeer.flatMapLatest { peer -> chatRepository.getMessagesForPeer(peer.name) }`.
  - All incoming TCP messages and outgoing user messages are written straight to SQLite. Chat history now persists permanently across restarts and peer reconnects.

#### Deep Dive: Key Functions & Patterns Used in Step 2:
1. **Room Reactive Queries (`Flow<T>`)**:
   - Instead of manually querying the database every time you send a message, Room registers SQLite table invalidation trackers. Whenever an `@Insert` occurs, Room re-runs the `@Query` on a background thread and emits the new data to the flow.
2. **`flatMapLatest` Operator**:
   - Switches the upstream flow dynamically. When `connectedPeer` changes from "Alice" to "Bob", `flatMapLatest` cancels the observation of Alice's chat history and starts observing Bob's chat history from the database.
3. **Domain Mapping (`toDomainModel()`)**:
   - Database entities (`MessageEntity`) should never leak into the UI layer. `ChatRepository` maps them into clean domain models (`ChatMessage`), preventing database schema changes from breaking UI composables.

---

### Step 3: Audio Pipeline Reliability, Routing & Zero-Allocation Loops (Completed)

#### What Changed:
- **Created `com.example.audio.AudioConfig`**:
  - Centralizes VoIP constants (16kHz, mono, 16-bit PCM, 20ms frame size = 640 bytes).
- **Created `com.example.audio.AudioPacketHelper`**:
  - Adds a 4-byte sequence number header to each UDP datagram packet.
- **Created `com.example.audio.AudioPlayer`**:
  - Uses `AudioTrack` with `USAGE_VOICE_COMMUNICATION`.
  - Drops out-of-order and duplicate UDP packets to eliminate audible clicks and pops caused by network jitter.
- **Created `com.example.audio.AudioRecorder`**:
  - Encapsulates hardware microphone input with `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (built-in Android Acoustic Echo Cancellation and Noise Suppression).
- **Created `com.example.audio.AudioRouter`**:
  - Modernized audio routing using Android 12+ (API 31) `setCommunicationDevice` (`TYPE_BUILTIN_SPEAKER` and `TYPE_BUILTIN_EARPIECE`), with fallback for older versions.
- **Created `com.example.audio.ProximitySensorHelper`**:
  - Extracts hardware proximity sensor logic completely away from audio code.
- **Refactored `AudioStreamer.kt`**:
  - Acts as a clean orchestrator.
  - Implements **zero-allocation in steady-state**: Buffers and `DatagramPacket` instances are allocated once before the while loops start and reused on every frame, eliminating garbage collection (GC) pauses that cause audio stutter.

#### Deep Dive: Key Functions & Patterns Used in Step 3:
1. **Zero-Allocation in High-Frequency Loops**:
   - At 16kHz with 20ms frames, audio packets are processed 50 times per second. Allocating `ByteArray` or `DatagramPacket` objects 50 times/sec triggers Android's Garbage Collector (GC), causing thread suspension and audio underruns. Reusing byte arrays fixes this completely.
2. **Acoustic Echo Cancellation (AEC)**:
   - By specifying `MediaRecorder.AudioSource.VOICE_COMMUNICATION` and `AudioAttributes.USAGE_VOICE_COMMUNICATION`, Android activates the hardware DSP (Digital Signal Processor) to cancel out the loudspeaker sound from being picked up by the microphone.
3. **Sequence Numbering over UDP**:
   - UDP is unreliable and unordered. A simple 32-bit counter prefixed to each packet allows `AudioPlayer` to identify late or out-of-order packets and discard them before they hit the hardware DAC.

---

### Step 4: Package Reorganization, Domain Layer & Background Services (Completed)

#### What Changed:
- **Created `com.example.domain.model.Models.kt`**:
  - Contains core domain entities: `Peer`, `ChatMessage`, `CallState`, and `ConnectionState`.
  - Independent of Android framework or third-party dependencies.
- **Moved Background Execution to `com.example.service`**:
  - `LocalChatService.kt`: Ongoing foreground service maintaining network responsiveness.
  - `BootReceiver.kt`: `BroadcastReceiver` listening to `BOOT_COMPLETED` and `QUICKBOOT_POWERON`.
- **Updated `AndroidManifest.xml`**:
  - Registered `.service.LocalChatService` and `.service.BootReceiver`.
- **Eliminated Flat Package Anti-Pattern**:
  - The codebase now follows the industry-standard **Clean Architecture** layout.

#### Complete Production Codebase Map:
```text
com.example/
│
├── audio/                               # Hardware Audio & VoIP Pipeline
│   ├── AudioConfig.kt                   # Audio constants & frame sizing
│   ├── AudioPacketHelper.kt             # UDP header sequence packing/unpacking
│   ├── AudioRecorder.kt                 # Microphone capture + hardware AEC
│   ├── AudioPlayer.kt                   # Speaker playback + packet jitter rejection
│   ├── AudioRouter.kt                   # AudioDeviceInfo routing (Android 12+)
│   └── ProximitySensorHelper.kt         # Proximity sensor listener
│
├── data/                                # Data Layer (Single Source of Truth)
│   ├── local/
│   │   ├── AppDatabase.kt               # Room Database (SQLite)
│   │   ├── dao/MessageDao.kt            # Reactive Flow SQL queries
│   │   └── entity/MessageEntity.kt      # Message database table
│   └── repository/
│       └── ChatRepository.kt           # Repositories mediating data to UI
│
├── domain/                              # Domain Layer (Pure Business Logic)
│   └── model/
│       └── Models.kt                    # Peer, ChatMessage, CallState, ConnectionState
│
├── service/                             # Background Android Services
│   ├── LocalChatService.kt              # Foreground service (Microphone + DataSync)
│   └── BootReceiver.kt                  # Autostarts service on phone reboot
│
├── ui/                                  # Presentation Layer (Jetpack Compose)
│   ├── chat/                            # ChatScreen, ChatViewModel, ChatUiState
│   │   └── components/MessageBubble.kt  # Reusable message bubble
│   ├── call/                            # CallOverlay, CallViewModel, CallUiState
│   └── theme/                           # Color, Theme, Typography
│
├── MainActivity.kt                      # Focused Activity (~170 lines)
├── NetworkManager.kt                    # NSD & TCP transport coordinator
└── AudioStreamer.kt                     # Audio coordinator
```

#### Deep Dive: Key Functions & Patterns Used in Step 4:
1. **Dynamic Foreground Service Types (Android 11+)**:
   - In `LocalChatService`: Android 11+ prohibits apps from accessing the microphone while in the background unless the foreground service explicitly has `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
   - The service runs as `DATA_SYNC` while in standby (listening for incoming calls), and dynamically attaches `MICROPHONE` when a call transitions to `CallState.ACTIVE`.
2. **Wi-Fi and Multicast Locks**:
   - `WifiManager.createMulticastLock()`: Required on Android devices to receive mDNS / NSD multicast discovery packets when the screen is dark.
   - `WifiManager.createWifiLock()`: Prevents the Wi-Fi chip from downclocking or entering deep power-saving mode during live VoIP audio.

---

## 6. Security Audit & Remediation Log

### Phase 1: Network & Protocol Hardening (Completed)

#### Vulnerabilities Addressed:
1. **[VULN-05] UDP Source IP/Port Validation (Critical)**
   - *The Issue:* The audio receiver accepted UDP datagrams from any arbitrary IP/port. Any device on the Wi-Fi could inject rogue audio into an active call.
   - *How We Solved It:* In [`AudioStreamer.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/AudioStreamer.kt), the receiver verifies:
     ```kotlin
     if (receivePacket.address != peerIp || receivePacket.port != peerPort) {
         continue // Reject rogue packet!
     }
     ```
2. **[VULN-11] Auto-Intercom Remote Microphone Activation (High)**
   - *The Issue:* `autoIntercomEnabled` defaulted to `true`. An unauthenticated peer could initiate a call and activate the target device's microphone after 2 seconds with zero user consent.
   - *How We Solved It:* Changed default value to `false`. Users must explicitly opt-in to auto-intercom.
3. **[VULN-16] Unbounded Line Reading / DoS (Medium)**
   - *The Issue:* Standard `BufferedReader.readLine()` reads indefinitely until `\n`. Streaming infinite bytes without a newline would cause an immediate `OutOfMemoryError` crash.
   - *How We Solved It:* Created `safeReadLine(reader, maxChars = 65536)`. If an incoming frame exceeds 64 KB without a newline, it stops buffering and discards the overflow.
4. **[VULN-15 & VULN-28] Message Size Capping & DB Flooding (Medium/Low)**
   - *The Issue:* Inbound and outbound messages had no length limit, opening vectors for database bloat and memory exhaustion.
   - *How We Solved It:* Enforced a 4,096 character upper bound on all messages before saving to SQLite or transmitting over TCP.
5. **[VULN-02] Audio Stream Cryptographic Pipeline (Critical)**
   - *The Issue:* Voice audio was transmitted as raw unencrypted 16-bit PCM packets.
   - *How We Solved It:* Built [`CryptoHelper.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/audio/CryptoHelper.kt) implementing AES-GCM (Galois/Counter Mode with 128-bit authentication tag) and wired authenticated decryption directly into [`AudioPlayer.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/audio/AudioPlayer.kt).
6. **[VULN-10] Signaling Port Range Validation (High)**
   - *The Issue:* Raw strings in `CALL_REQ:<port>` and `CALL_ACK:<port>` were parsed without validation.
   - *How We Solved It:* Ports are strictly checked against `1024..65535`.

---

### Phase 2: Android Manifest, Build, & OS Hardening (Completed)

#### Vulnerabilities Addressed:
1. **[VULN-13] Insecure Data Backup via ADB (High)**
   - *The Issue:* `android:allowBackup="true"` enabled physical/ADB extraction of the app's SQLite database and SharedPreferences via `adb backup`.
   - *How We Solved It:* Set `android:allowBackup="false"` in [`AndroidManifest.xml`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/AndroidManifest.xml).
2. **[VULN-09] Unprotected Exported BroadcastReceiver (High)**
   - *The Issue:* `BootReceiver` was exported with no permissions. Any malicious app on the phone could fire implicit broadcasts to start the foreground service.
   - *How We Solved It:* Added `android:permission="android.permission.RECEIVE_BOOT_COMPLETED"` to the receiver in [`AndroidManifest.xml`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/AndroidManifest.xml).
3. **[VULN-22] Unused Dangerous Permissions (Medium)**
   - *The Issue:* Declared `SYSTEM_ALERT_WINDOW` without any active use in the app, expanding the attack surface.
   - *How We Solved It:* Removed the permission from [`AndroidManifest.xml`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/AndroidManifest.xml).
4. **[VULN-14] Permanent WakeLock Battery Exhaustion (High)**
   - *The Issue:* `wakeLock.acquire()` was called with no timeout, causing the CPU to stay awake indefinitely if not cleanly stopped.
   - *How We Solved It:* Added a 10-minute timeout in [`LocalChatService.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/service/LocalChatService.kt):
     ```kotlin
     wakeLock.acquire(10 * 60 * 1000L)
     ```
5. **[VULN-08] Disabled R8 Code Minification in Release (High)**
   - *The Issue:* `isMinifyEnabled = false` meant release APKs could be decompiled with 100% symbol clarity.
   - *How We Solved It:* Enabled R8 code minification (`isMinifyEnabled = true`) and resource shrinking in [`app/build.gradle.kts`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/build.gradle.kts).
6. **[VULN-06 & VULN-07] Hardcoded Keystore Passwords & Insecure Path Fallback (High)**
   - *The Issue:* Passwords (`"android"`) were hardcoded in Gradle scripts and release keys defaulted to the repo root.
   - *How We Solved It:* Keystore paths and passwords now strictly depend on environment variables, and `*.jks` and `*.keystore` are protected in [`.gitignore`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/.gitignore).

---

### Phase 3: Privacy, Screen Lifecycle, & Notification Hardening (Completed)

#### Vulnerabilities Addressed:
1. **[VULN-18] Privacy & Device Fingerprinting via NSD Service Name (Medium)**
   - *The Issue:* The application broadcast `${Build.MANUFACTURER} ${Build.MODEL}` over the Wi-Fi mDNS stack, leaking the exact phone hardware and model to anyone sniffing the subnet.
   - *How We Solved It:* Anonymized the broadcast service name in [`NetworkManager.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/NetworkManager.kt) to `Peer-<6-digit-random-id>` and updated the UI header to `"Encrypted P2P Node"`.
2. **[VULN-29] Permanent `FLAG_KEEP_SCREEN_ON` Battery Drain (Low)**
   - *The Issue:* The screen was permanently forced awake across all screens and states, draining the user's battery rapidly.
   - *How We Solved It:* Scoped `FLAG_KEEP_SCREEN_ON` in [`MainActivity.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/MainActivity.kt) using a Compose `DisposableEffect` that exclusively keeps the screen awake when `callState != CallState.IDLE`. When the call ends, the screen turns off normally.
3. **[VULN-30] Permanent Lock Screen Bypass (`setShowWhenLocked`) (Low)**
   - *The Issue:* `setShowWhenLocked(true)` and `setTurnScreenOn(true)` were set permanently on the window, allowing unauthorized app visibility without unlocking the device.
   - *How We Solved It:* Dynamically toggled in [`MainActivity.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/MainActivity.kt) to only show over the lock screen during incoming and active calls, automatically clearing the flags once the call concludes.
4. **[VULN-31] Intrusive Notification Channel Importance (Low)**
   - *The Issue:* The persistent foreground service notification channel was set to `IMPORTANCE_HIGH`, triggering loud sound/vibration alerts whenever the service updated its state.
   - *How We Solved It:* In [`LocalChatService.kt`](file:///C:/Users/lenovo/.gemini/antigravity/scratch/Localchat/app/src/main/java/com/example/service/LocalChatService.kt), separated notifications into two distinct channels:
     - `local_chat_channel` (`IMPORTANCE_LOW`): Silent, non-intrusive ongoing background status.
     - `local_chat_call_channel` (`IMPORTANCE_HIGH`): High-priority ringing and full-screen intent specifically for incoming voice calls.

---

## 7. How to Build, Run, and Test the Application

### 1. Prerequisites (Tools Required)
To compile and run this Android project, you need:
- **Java Development Kit (JDK 17)**: Modern Android Gradle Plugin requires JDK 17.
- **Android SDK / Android Studio**: Contains `adb`, Android platform build tools, and emulator engines.

Install them via Windows PowerShell:
```powershell
# Install OpenJDK 17
winget install Microsoft.OpenJDK.17

# Install Android Studio (includes SDK, Platform-tools, ADB, and Emulators)
winget install Google.AndroidStudio
```

---

### 2. How to Build the App (Generate APKs)
All builds are managed via the Gradle Wrapper (`gradlew.bat`) at the root of the project:

#### A. Generate Debug APK (For Testing & Sideloading)
```powershell
cd C:\Users\lenovo\.gemini\antigravity\scratch\Localchat
.\gradlew.bat assembleDebug
```
- **Output Location:**
  `app/build/outputs/apk/debug/app-debug.apk`
- **Use Case:** Transfer this `.apk` to any Android device via USB, Google Drive, or local share, and tap to install.

#### B. Generate Release APK (Optimized & Minified)
```powershell
.\gradlew.bat assembleRelease
```
- **Output Location:**
  `app/build/outputs/apk/release/app-release.apk`
- **Use Case:** Production deployment with R8 code shrinking and symbol obfuscation.

---

### 3. How to Run the App

#### Method A: Android Studio (Recommended / Visual)
1. Open **Android Studio**.
2. Select **Open** and navigate to:
   `C:\Users\lenovo\.gemini\antigravity\scratch\Localchat`
3. Wait for the Gradle project sync to finish.
4. Plug in your physical Android phone (ensure **USB Debugging** is turned on in Developer Options) OR create an Android Virtual Device (AVD Emulator) via Device Manager.
5. Click the green **Run (▶)** button on the top toolbar.

#### Method B: Command Line (ADB)
With your phone connected via USB:
1. Verify device connection:
   ```powershell
   adb devices
   ```
2. Build and automatically install to the phone:
   ```powershell
   .\gradlew.bat installDebug
   ```
3. Launch the app on the phone screen:
   ```powershell
   adb shell am start -n com.aistudio.localchat.xcfghy/com.example.MainActivity
   ```

---

### 4. How to Test the P2P Walkie-Talkie Between 2 Devices

LocalChat communicates peer-to-peer over the local Wi-Fi subnet without an external cloud server:

1. **Network Setup:**
   - Connect **Device 1** and **Device 2** to the **same Wi-Fi network**.
   - *(Alternative)*: Turn on the mobile hotspot on Device 1 and connect Device 2 to that hotspot.
2. **Install:**
   - Install `app-debug.apk` on both devices.
3. **Launch & Permissions:**
   - Open LocalChat on both devices and grant the **Microphone** and **Notification** permissions when prompted.
4. **Peer Discovery (ZeroConf / mDNS):**
   - Both devices will automatically broadcast and discover each other via NSD (`_localchat._tcp.`).
   - Tap **Search Peers** or tap the discovered peer card to connect the TCP signaling channel.
5. **Real-Time Encrypted Chat:**
   - Type a message and tap send. Both devices will display the message, and SQLite (Room) will save it permanently.
6. **Voice Intercom / Walkie-Talkie:**
   - Tap the **Call (📞)** button to initiate a voice call.
   - The other phone will ring with a high-priority heads-up notification.
   - Tap **Accept** to open the low-latency 16kHz audio stream.
   - Toggle the **Speaker / Earpiece** button to switch audio routing, or bring the phone to your ear to trigger the proximity sensor.








