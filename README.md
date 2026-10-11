# Android Face Recognition App

A complete Android application that demonstrates real-time face recognition using ML Kit and CameraX.

## ✅ **Fixed Issues**

The following issues have been resolved:

1. **Accessibility Issue**: Made `currentFace` property public in `FaceRecognizer`
2. **Naming Conflict**: Fixed `recognizedFaces` property naming to avoid conflicts
3. **Build Configuration**: Fixed typo in `build.gradle` (`targerSdkVersion` → `targetSdkVersion`)

## 🚀 **How to Run**

### **Option 1: Android Studio**
1. Open Android Studio
2. Select "Open an existing project"
3. Navigate to the `android-face-recognition-app` folder
4. Click "OK"
5. Wait for Gradle sync to complete
6. Connect an Android device with camera
7. Click the "Run" button (green play icon)

### **Option 2: Command Line**
```bash
# Navigate to project directory
cd android-face-recognition-app

# Build the project
./gradlew assembleDebug

# Install on connected device
./gradlew installDebug
```

## 📱 **App Features**

- **Real-time Face Detection**: Uses ML Kit to detect faces
- **Face Recognition**: Implements cosine similarity algorithm
- **Face Tracking**: Tracks faces across frames
- **Visual Feedback**: Colored bounding boxes for recognized/unknown faces
- **Add Person**: Register new faces to the database
- **Toggle Detection**: Start/stop face detection

## 🔧 **Troubleshooting Common Errors**

### **Build Errors**
1. **Gradle Sync Failed**: 
   - File → Invalidate Caches / Restart
   - Try "Sync Project with Gradle Files"

2. **Missing Dependencies**:
   - Ensure internet connection
   - Check `build.gradle` has all required dependencies

3. **Permission Errors**:
   - Grant camera permission when prompted
   - Go to Settings → Apps → Face Recognition App → Permissions

### **Runtime Errors**
1. **Camera Not Working**:
   - Check device has camera
   - Grant camera permission
   - Restart app

2. **Face Detection Not Working**:
   - Ensure good lighting
   - Face should be clearly visible
   - Check ML Kit dependencies are downloaded

## 📁 **Project Structure**

```
android-face-recognition-app/
├── app/
│   ├── src/main/
│   │   ├── java/com/example/facerecognition/
│   │   │   ├── MainActivity.kt          # Main UI and camera logic
│   │   │   ├── FaceRecognizer.kt       # Face recognition engine
│   │   │   └── FaceOverlayView.kt      # Visual overlay for faces
│   │   ├── res/
│   │   │   ├── layout/
│   │   │   │   ├── activity_main.xml
│   │   │   │   └── dialog_register_face.xml
│   │   │   └── values/
│   │   │       ├── strings.xml
│   │   │       └── colors.xml
│   │   └── AndroidManifest.xml
│   └── build.gradle
└── README.md
```

## 🔬 **Technical Details**

### **Face Recognition Algorithm**
- **Feature Extraction**: Face contours, angles, bounding box, attributes
- **Similarity Calculation**: Cosine similarity between feature vectors
- **Recognition Threshold**: 0.7 (70% similarity required)

### **Dependencies**
- **CameraX**: Camera functionality
- **ML Kit Face Detection**: Face detection and tracking
- **Kotlin Coroutines**: Asynchronous operations
- **View Binding**: Type-safe view access
- **AndroidX Lifecycle ViewModel**: detection state that survives rotation

## ⚠️ **Limitations**

**This app detects faces; it does not verify identity.** Be specific about this, because
the UI shows a name next to a face and a reader will otherwise assume identity checking.

ML Kit's `face-detection` library returns geometry (box, head angles, landmarks, contours,
expression probabilities) and a frame-local tracking id. It does **not** return an identity
embedding, and none can be derived from what it gives you. `FaceRecognizer` therefore
computes a 16-value descriptor of head pose, expression probabilities and landmark geometry
normalised by inter-ocular distance. A "Possible match: Alex" label means *this face is in a
similar pose and has similar facial proportions to the enrolled sample* - nothing more.

What was wrong before, and is fixed: the previous descriptor included **absolute bounding-box
and contour pixel coordinates** divided by a literal `1000f`, so cosine similarity was
dominated by *how far and where* a face sat in the frame. Two different people at the same
distance and pose matched each other; one person stopped matching themselves after leaning
closer. Because that version asserted identity in the UI, it failed in the dangerous
direction - confidently wrong, not visibly broken. The current descriptor is deliberately
translation- and scale-invariant, the threshold is strict (`MATCH_THRESHOLD = 0.95`), and the
copy says "possible match".

Other remaining limitations:
- No liveness check - a photograph satisfies the current matcher.
- No authentication, encryption-at-rest, or anti-spoofing. **Never gate access to anything
  with this.**
- No on-device delete UI for enrolled templates (see below).

### Enrolled data and where it lives
Templates persist across restarts in app-private storage
(`context.filesDir/face_templates.json`), with `android:allowBackup="false"` so face data is
not included in cloud or `adb` backup. Writes are atomic and version-stamped: templates
written by an incompatible descriptor definition are discarded rather than matched against.
Clearing app data or uninstalling removes them. To wipe from a connected device:

```bash
adb shell pm clear com.example.facerecognition
```

Face data never leaves the device: frames are analysed in memory and are not written to disk
and no network permission is declared.

## 🎯 **Next Steps**

For genuine recognition:
- Add a real embedding model (MobileFaceNet/ArcFace via TFLite, or a cloud face API) fed an
  *aligned, cropped* face image. This is the only way to obtain an identity descriptor. It
  requires downloading model weights, which was not in scope here.
- Add liveness detection (blink/turn challenge or depth-based).
- Encrypt templates at rest and gate enrolment behind device authentication.
- Surface an "Manage registrations" screen (list, delete individual entries).

## 🧪 **Testing status**

`app/src/androidTest/.../FaceTemplateStoreTest.kt` covers persistence and matching: restart
survival, duplicate-free re-registration, entry cap, descriptor-version rejection, corrupt-store
tolerance, mismatch rejection, and deletion. All 7 pass:

```bash
adb shell am instrument -w \
  -e class com.example.facerecognition.FaceTemplateStoreTest \
  "com.example.facerecognition.test/androidx.test.runner.AndroidJUnitRunner"
```

Two caveats about how much an emulator can prove:
- `./gradlew connectedDebugAndroidTest` reports *"Failed to receive the UTP test results"* on
  this AGP 8.1.4 / Android 16 combination. That is Gradle's result transport failing, not a
  test failing - the same tests report `OK (7 tests)` through `am instrument` above.
- **The AVD's synthetic camera never contains a face**, so end-to-end face detection,
  registration through the UI, and overlay box alignment could not be observed on the
  emulator. What *was* verified there: camera binding and fallbacks, inference actually running
  (`FaceDetectorV2Jni` call counts), the detection toggle surviving rotation, the
  use-after-free and rotation-crash fixes, and that the R8-minified release build still detects
  faces. **Overlay geometry (the `FILL_CENTER` scale/offset maths) and real match behaviour
  need a physical device with a real camera to confirm.**

## 📄 **License**

This project is for educational purposes. Ensure compliance with local privacy laws when using face recognition technology. 