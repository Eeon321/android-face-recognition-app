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

## ⚠️ **Limitations**

This is a **simplified implementation** for demonstration:
- Uses basic geometric features (not deep learning)
- Limited accuracy compared to commercial systems
- No persistent storage
- No security measures

## 🎯 **Next Steps**

For production use, consider:
- Implementing proper face embedding models (FaceNet, ArcFace)
- Adding persistent storage for face data
- Implementing face liveness detection
- Adding security measures
- Improving UI/UX

## 📄 **License**

This project is for educational purposes. Ensure compliance with local privacy laws when using face recognition technology. 