#include <jni.h>
#include <vector>
#include <array>
#include <cmath>
#include <algorithm>
#include <mutex>
#include <cstring>

static constexpr int SAMPLE_RATE = 48000;
static constexpr int MAX_DELAY =
        SAMPLE_RATE * 2;

static constexpr int SPECTRUM_SIZE = 32;
static constexpr int FFT_SIZE = 256;

static float delayAmount = 0.0f;
static float echoAmount = 0.0f;
static float chorusAmount = 0.0f;
static float reverbAmount = 0.0f;

static bool bypass = false;
static bool mute = false;

static std::vector<float> delayL;
static std::vector<float> delayR;

static std::vector<float> echoL;
static std::vector<float> echoR;

static int delayPosL = 0;
static int delayPosR = 0;

static int echoPosL = 0;
static int echoPosR = 0;

static std::vector<float> playerBuffer;

static size_t playerRead = 0;

static std::mutex playerMutex;

static float spectrum[SPECTRUM_SIZE]{};

static float spectrumSamples[FFT_SIZE]{};

static int spectrumWrite = 0;

static std::mutex spectrumMutex;


/* =========================================================
 * RESET
 * ========================================================= */

static void clearBuffers() {

    std::fill(
            delayL.begin(),
            delayL.end(),
            0.0f
    );

    std::fill(
            delayR.begin(),
            delayR.end(),
            0.0f
    );

    std::fill(
            echoL.begin(),
            echoL.end(),
            0.0f
    );

    std::fill(
            echoR.begin(),
            echoR.end(),
            0.0f
    );

    delayPosL = 0;
    delayPosR = 0;

    echoPosL = 0;
    echoPosR = 0;

    std::fill(
            spectrum,
            spectrum + SPECTRUM_SIZE,
            0.0f
    );

    std::fill(
            spectrumSamples,
            spectrumSamples + FFT_SIZE,
            0.0f
    );

    spectrumWrite = 0;
}


/* =========================================================
 * JNI INIT
 * ========================================================= */

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_dtac_fx_MainActivity_nativeInit(
        JNIEnv* env,
        jobject thiz,
        jint sampleRate) {

    delayL.assign(
            MAX_DELAY,
            0.0f
    );

    delayR.assign(
            MAX_DELAY,
            0.0f
    );

    echoL.assign(
            MAX_DELAY,
            0.0f
    );

    echoR.assign(
            MAX_DELAY,
            0.0f
    );

    {
        std::lock_guard<std::mutex> lock(
                playerMutex
        );

        playerBuffer.clear();

        playerBuffer.reserve(
                SAMPLE_RATE * 10
        );

        playerRead = 0;
    }

    clearBuffers();

    delayAmount = 0.0f;
    echoAmount = 0.0f;
    chorusAmount = 0.0f;
    reverbAmount = 0.0f;

    bypass = false;
    mute = false;

    return JNI_TRUE;
}


/* =========================================================
 * RELEASE
 * ========================================================= */

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeRelease(
        JNIEnv* env,
        jobject thiz) {

    std::lock_guard<std::mutex> lock(
            playerMutex
    );

    playerBuffer.clear();
    playerRead = 0;

    delayL.clear();
    delayR.clear();
    echoL.clear();
    echoR.clear();
}


/* =========================================================
 * FX CONTROL
 * ========================================================= */

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetDelay(
        JNIEnv* env,
        jobject thiz,
        jfloat value) {

    delayAmount =
            std::clamp(
                    value,
                    0.0f,
                    1.0f
            );
}


extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetEcho(
        JNIEnv* env,
        jobject thiz,
        jfloat value) {

    echoAmount =
            std::clamp(
                    value,
                    0.0f,
                    1.0f
            );
}


extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetChorus(
        JNIEnv* env,
        jobject thiz,
        jfloat value) {

    chorusAmount =
            std::clamp(
                    value,
                    0.0f,
                    1.0f
            );
}


extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetReverb(
        JNIEnv* env,
        jobject thiz,
        jfloat value) {

    reverbAmount =
            std::clamp(
                    value,
                    0.0f,
                    1.0f
            );
}


extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetBypass(
        JNIEnv* env,
        jobject thiz,
        jboolean value) {

    bypass =
            value == JNI_TRUE;
}


extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetMute(
        JNIEnv* env,
        jobject thiz,
        jboolean value) {

    mute =
            value == JNI_TRUE;
}


/* =========================================================
 * PLAYER BUFFER
 * ========================================================= */

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativePushPlayerPCM(
        JNIEnv* env,
        jobject thiz,
        jshortArray pcm,
        jint length) {

    if (!pcm || length <= 0)
        return;

    jsize arrayLength =
            env->GetArrayLength(pcm);

    if (length > arrayLength)
        length = arrayLength;

    jshort* data =
            env->GetShortArrayElements(
                    pcm,
                    nullptr
            );

    if (!data)
        return;

    {
        std::lock_guard<std::mutex> lock(
                playerMutex
        );

        /*
         * Hapus bagian yang sudah dibaca agar
         * vector tidak tumbuh terus.
         */
        if (playerRead > 65536) {

            playerBuffer.erase(
                    playerBuffer.begin(),
                    playerBuffer.begin() +
                            static_cast<long>(
                                    playerRead
                            )
            );

            playerRead = 0;
        }

        constexpr size_t MAX_BUFFER =
                SAMPLE_RATE * 10 * 2;

        for (int i = 0; i < length; i++) {

            if (playerBuffer.size()
                    < MAX_BUFFER) {

                playerBuffer.push_back(
                        static_cast<float>(
                                data[i]
                        ) / 32768.0f
                );
            }
        }
    }

    env->ReleaseShortArrayElements(
            pcm,
            data,
            JNI_ABORT
    );
}


/* =========================================================
 * PLAYER SAMPLE
 * ========================================================= */

static bool getPlayerStereo(
        float& left,
        float& right) {

    std::lock_guard<std::mutex> lock(
            playerMutex
    );

    if (playerRead + 1 >=
            playerBuffer.size()) {

        left = 0.0f;
        right = 0.0f;

        return false;
    }

    left =
            playerBuffer[playerRead++];

    right =
            playerBuffer[playerRead++];

    return true;
}


/* =========================================================
 * DSP
 * ========================================================= */

static float processChannel(
        float input,
        std::vector<float>& delayBuffer,
        std::vector<float>& echoBuffer,
        int& delayPosition,
        int& echoPosition) {

    if (bypass)
        return input;

    /*
     * DELAY
     *
     * 20 ms - 1000 ms
     */
    int delaySamples =
            960 +
            static_cast<int>(
                    delayAmount *
                    47040.0f
            );

    int delayRead =
            delayPosition -
            delaySamples;

    while (delayRead < 0)
        delayRead += MAX_DELAY;

    float delayed =
            delayBuffer[delayRead];

    delayBuffer[delayPosition] =
            input;

    delayPosition++;

    if (delayPosition >= MAX_DELAY)
        delayPosition = 0;


    /*
     * ECHO
     *
     * sekitar 250 ms
     */
    int echoSamples =
            12000;

    int echoRead =
            echoPosition -
            echoSamples;

    while (echoRead < 0)
        echoRead += MAX_DELAY;

    float oldEcho =
            echoBuffer[echoRead];

    echoBuffer[echoPosition] =
            input +
            oldEcho * 0.55f;

    echoPosition++;

    if (echoPosition >= MAX_DELAY)
        echoPosition = 0;


    /*
     * CHORUS
     *
     * delay kecil dengan modulasi.
     */
    float phase =
            static_cast<float>(
                    delayPosition
            ) /
            static_cast<float>(
                    SAMPLE_RATE
            );

    float lfo =
            std::sin(
                    phase *
                    2.0f *
                    static_cast<float>(M_PI) *
                    0.7f
            );

    int chorusSamples =
            960 +
            static_cast<int>(
                    (lfo + 1.0f) *
                    0.5f *
                    960.0f *
                    chorusAmount
            );

    int chorusRead =
            delayPosition -
            chorusSamples;

    while (chorusRead < 0)
        chorusRead += MAX_DELAY;

    float chorus =
            delayBuffer[chorusRead];


    /*
     * REVERB
     *
     * Gabungan beberapa tap delay.
     */
    int r1 =
            delayPosition -
            1800;

    int r2 =
            delayPosition -
            3100;

    int r3 =
            delayPosition -
            4700;

    while (r1 < 0)
        r1 += MAX_DELAY;

    while (r2 < 0)
        r2 += MAX_DELAY;

    while (r3 < 0)
        r3 += MAX_DELAY;

    float reverb =
            delayBuffer[r1] * 0.35f +
            delayBuffer[r2] * 0.25f +
            delayBuffer[r3] * 0.18f;


    /*
     * MIX
     */
    float output =
            input;

    output +=
            delayed *
            delayAmount *
            0.45f;

    output +=
            oldEcho *
            echoAmount *
            0.45f;

    output +=
            (chorus - input) *
            chorusAmount *
            0.40f;

    output +=
            reverb *
            reverbAmount *
            0.50f;

    /*
     * Soft saturation agar output
     * tidak terlalu kasar ketika clipping.
     */
    output =
            std::tanh(
                    output
            );

    return output;
}


/* =========================================================
 * SPECTRUM ANALYZER
 *
 * DFT 256 sample -> 32 band
 * ========================================================= */

static void updateSpectrum(
        float left,
        float right) {

    float sample =
            (left + right) * 0.5f;

    std::lock_guard<std::mutex> lock(
            spectrumMutex
    );

    spectrumSamples[spectrumWrite] =
            sample;

    spectrumWrite++;

    if (spectrumWrite < FFT_SIZE)
        return;

    for (int band = 0;
         band < SPECTRUM_SIZE;
         band++) {

        float real = 0.0f;
        float imag = 0.0f;

        int bin =
                1 +
                band * 3;

        for (int n = 0;
             n < FFT_SIZE;
             n++) {

            float angle =
                    2.0f *
                    static_cast<float>(M_PI) *
                    bin *
                    n /
                    FFT_SIZE;

            /*
             * Hann window
             */
            float window =
                    0.5f -
                    0.5f *
                    std::cos(
                            2.0f *
                            static_cast<float>(M_PI) *
                            n /
                            FFT_SIZE
                    );

            float sampleValue =
                    spectrumSamples[n] *
                    window;

            real +=
                    sampleValue *
                    std::cos(angle);

            imag -=
                    sampleValue *
                    std::sin(angle);
        }

        float magnitude =
                std::sqrt(
                        real * real +
                        imag * imag
                ) /
                FFT_SIZE;

        float level =
                std::min(
                        1.0f,
                        magnitude * 12.0f
                );

        spectrum[band] =
                spectrum[band] * 0.70f +
                level * 0.30f;
    }

    spectrumWrite = 0;
}


/* =========================================================
 * PROCESS
 * ========================================================= */

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeProcess(
        JNIEnv* env,
        jobject thiz,
        jshortArray inputArray,
        jshortArray outputArray,
        jint frames) {

    if (!inputArray ||
        !outputArray ||
        frames <= 0)
        return;

    jshort* input =
            env->GetShortArrayElements(
                    inputArray,
                    nullptr
            );

    jshort* output =
            env->GetShortArrayElements(
                    outputArray,
                    nullptr
            );

    if (!input || !output) {

        if (input)
            env->ReleaseShortArrayElements(
                    inputArray,
                    input,
                    JNI_ABORT
            );

        if (output)
            env->ReleaseShortArrayElements(
                    outputArray,
                    output,
                    0
            );

        return;
    }

    for (int i = 0;
         i < frames;
         i++) {

        float left;
        float right;

        bool playerHasData =
                getPlayerStereo(
                        left,
                        right
                );

        /*
         * Kalau PLAYER sedang aktif tetapi
         * buffer kosong, input dari Java
         * tetap digunakan.
         */
        if (!playerHasData) {

            left =
                    input[i * 2] /
                    32768.0f;

            right =
                    input[i * 2 + 1] /
                    32768.0f;
        }

        float outL =
                processChannel(
                        left,
                        delayL,
                        echoL,
                        delayPosL,
                        echoPosL
                );

        float outR =
                processChannel(
                        right,
                        delayR,
                        echoR,
                        delayPosR,
                        echoPosR
                );

        if (mute) {

            outL = 0.0f;
            outR = 0.0f;
        }

        outL =
                std::clamp(
                        outL,
                        -1.0f,
                        1.0f
                );

        outR =
                std::clamp(
                        outR,
                        -1.0f,
                        1.0f
                );

        output[i * 2] =
                static_cast<jshort>(
                        outL * 32767.0f
                );

        output[i * 2 + 1] =
                static_cast<jshort>(
                        outR * 32767.0f
                );

        updateSpectrum(
                outL,
                outR
        );
    }

    env->ReleaseShortArrayElements(
            inputArray,
            input,
            JNI_ABORT
    );

    env->ReleaseShortArrayElements(
            outputArray,
            output,
            0
    );
}


/* =========================================================
 * GET SPECTRUM
 * ========================================================= */

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_dtac_fx_MainActivity_nativeGetSpectrum(
        JNIEnv* env,
        jobject thiz) {

    jfloat resultData[
            SPECTRUM_SIZE
    ];

    {
        std::lock_guard<std::mutex> lock(
                spectrumMutex
        );

        std::memcpy(
                resultData,
                spectrum,
                sizeof(spectrum)
        );
    }

    jfloatArray result =
            env->NewFloatArray(
                    SPECTRUM_SIZE
            );

    if (!result)
        return nullptr;

    env->SetFloatArrayRegion(
            result,
            0,
            SPECTRUM_SIZE,
            resultData
    );

    return result;
}
