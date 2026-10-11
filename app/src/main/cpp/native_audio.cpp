#include <jni.h>

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dtac_fx_MainActivity_nativeStartAudio(
        JNIEnv* env,
        jobject thiz) {
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeStopAudio(
        JNIEnv* env,
        jobject thiz) {
}
