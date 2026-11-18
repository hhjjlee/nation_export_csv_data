# JNI(whisper_jni.cpp)가 이름으로 찾는 클래스/메서드
-keep class com.kosub.player.asr.WhisperNative { *; }
# 설정 화면에서 쓰는 클래스
-keep class com.kosub.player.ui.SettingsActivity$SettingsFragment { *; }
-dontwarn com.google.auto.value.AutoValue
