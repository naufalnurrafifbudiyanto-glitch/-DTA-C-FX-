#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>
#include <vector>

#define LOG_TAG "DTA-C-FX"

#define LOGI(...) \
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static constexpr int CHANNELS = 2;
static constexpr int MAX_DELAY = 96000;
static constexpr int SPECTRUM_SIZE = 32;
static constexpr int FFT_SAMPLES = 256;

static int sampleRate = 48000;

static float delayAmount = 0.0f;
static float echoAmount = 0.0f;
static float chorusAmount = 0.0f;
static float reverbAmount = 0.0f;

static bool bypass = false;
static bool mute = false;

static std::vector<float> delayBuffer;
static std::vector<float> echoBuffer;
static std::vector<float> reverbBuffer;

static size_t delayPos = 0;
static size_t echoPos = 0;
static size_t reverbPos = 0;

static std::vector<float> playerBuffer;
static size_t playerRead = 0;

static std::mutex playerMutex;

static float spectrum[SPECTRUM_SIZE] = {};
static float spectrumInput[FFT_SAMPLES] = {};
static int spectrumPos = 0;

static float chorusPhase = 0.0f;

static float previousLeft = 0.0f;
static float previousRight = 0.0f;


// ============================================================
// Utility
// ============================================================

static inline float clampFloat(
        float x,
        float minValue,
        float maxValue
) {
    return std::max(
            minValue,
            std::min(
                    maxValue,
                    x
            )
    );
}


// ============================================================
// Noise Gate
// ============================================================

static inline float noiseGate(
        float x
) {
    const float threshold = 0.008f;

    float a = std::fabs(x);

    if (a < threshold) {

        // Soft attenuation instead of hard cut.
        return x * 0.08f;
    }

    return x;
}


// ============================================================
// Compressor
// ============================================================

static inline float compressor(
        float x
) {
    const float threshold = 0.55f;
    const float ratio = 4.0f;

    float sign =
            x >= 0.0f
                    ? 1.0f
                    : -1.0f;

    float a = std::fabs(x);

    if (a <= threshold)
        return x;

    float compressed =
            threshold +
            (a - threshold) / ratio;

    return sign * compressed;
}


// ============================================================
// Limiter
// ============================================================

static inline float limiter(
        float x
) {
    const float limit = 0.92f;

    return clampFloat(
            x,
            -limit,
            limit
    );
}


// ============================================================
// Spectrum
// ============================================================

static void updateSpectrum(
        float left,
        float right
) {

    float mono =
            (left + right) * 0.5f;

    spectrumInput[spectrumPos++] =
            mono;

    if (spectrumPos < FFT_SAMPLES)
        return;

    spectrumPos = 0;

    for (int band = 0;
         band < SPECTRUM_SIZE;
         band++) {

        float real = 0.0f;
        float imag = 0.0f;

        int start =
                band * 2 + 1;

        int end =
                start + 2;

        if (end >= FFT_SAMPLES / 2)
            end = FFT_SAMPLES / 2 - 1;

        for (int k = start;
             k <= end;
             k++) {

            float frequency =
                    static_cast<float>(k);

            float r = 0.0f;
            float im = 0.0f;

            for (int n = 0;
                 n < FFT_SAMPLES;
                 n++) {

                float angle =
                        2.0f *
                        static_cast<float>(M_PI) *
                        frequency *
                        static_cast<float>(n) /
                        static_cast<float>(FFT_SAMPLES);

                r +=
                        spectrumInput[n] *
                        std::cos(angle);

                im +=
                        spectrumInput[n] *
                        std::sin(angle);
            }

            real += r;
            imag += im;
        }

        float magnitude =
                std::sqrt(
                        real * real +
                        imag * imag
                );

        magnitude /=
                static_cast<float>(
                        std::max(
                                1,
                                end - start + 1
                        )
                );

        spectrum[band] =
                spectrum[band] * 0.75f +
                magnitude * 0.25f;
    }
}


// ============================================================
// Native Init
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeInit(
        JNIEnv* env,
        jobject thiz,
        jint rate
) {

    sampleRate =
            std::max(
                    8000,
                    static_cast<int>(rate)
            );

    delayBuffer.assign(
            MAX_DELAY * CHANNELS,
            0.0f
    );

    echoBuffer.assign(
            MAX_DELAY * CHANNELS,
            0.0f
    );

    reverbBuffer.assign(
            MAX_DELAY * CHANNELS,
            0.0f
    );

    delayPos = 0;
    echoPos = 0;
    reverbPos = 0;

    chorusPhase = 0.0f;

    previousLeft = 0.0f;
    previousRight = 0.0f;

    std::fill(
            spectrum,
            spectrum + SPECTRUM_SIZE,
            0.0f
    );

    std::fill(
            spectrumInput,
            spectrumInput + FFT_SAMPLES,
            0.0f
    );

    spectrumPos = 0;

    {
        std::lock_guard<std::mutex> lock(
                playerMutex
        );

        playerBuffer.clear();
        playerRead = 0;
    }

    LOGI(
            "nativeInit sampleRate=%d",
            sampleRate
    );
}


// ============================================================
// Native Release
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeRelease(
        JNIEnv* env,
        jobject thiz
) {

    delayBuffer.clear();
    echoBuffer.clear();
    reverbBuffer.clear();

    {
        std::lock_guard<std::mutex> lock(
                playerMutex
        );

        playerBuffer.clear();
        playerRead = 0;
    }
}


// ============================================================
// Player PCM
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativePushPlayerPCM(
        JNIEnv* env,
        jobject thiz,
        jfloatArray pcm,
        jint frames,
        jint inputSampleRate
) {

    if (pcm == nullptr)
        return;

    jsize length =
            env->GetArrayLength(
                    pcm
            );

    if (length <= 0)
        return;

    jfloat* data =
            env->GetFloatArrayElements(
                    pcm,
                    nullptr
            );

    if (!data)
        return;

    int availableFrames =
            length / CHANNELS;

    int count =
            std::min(
                    availableFrames,
                    static_cast<int>(frames)
            );

    if (count <= 0) {

        env->ReleaseFloatArrayElements(
                pcm,
                data,
                JNI_ABORT
        );

        return;
    }

    std::lock_guard<std::mutex> lock(
            playerMutex
    );

    // Simple resampling to 48 kHz.
    if (inputSampleRate <= 0 ||
        inputSampleRate == sampleRate) {

        size_t oldSize =
                playerBuffer.size();

        playerBuffer.resize(
                oldSize +
                count * CHANNELS
        );

        std::memcpy(
                playerBuffer.data() + oldSize,
                data,
                count *
                CHANNELS *
                sizeof(float)
        );

    } else {

        double ratio =
                static_cast<double>(
                        inputSampleRate
                ) /
                static_cast<double>(
                        sampleRate
                );

        int outputFrames =
                static_cast<int>(
                        static_cast<double>(
                                count
                        ) / ratio
                );

        if (outputFrames < 1)
            outputFrames = 1;

        size_t oldSize =
                playerBuffer.size();

        playerBuffer.resize(
                oldSize +
                outputFrames * CHANNELS
        );

        for (int i = 0;
             i < outputFrames;
             i++) {

            double sourcePosition =
                    static_cast<double>(i) *
                    ratio;

            int index =
                    static_cast<int>(
                            sourcePosition
                    );

            if (index >= count)
                index = count - 1;

            int next =
                    std::min(
                            index + 1,
                            count - 1
                    );

            float frac =
                    static_cast<float>(
                            sourcePosition -
                            static_cast<double>(index)
                    );

            for (int ch = 0;
                 ch < CHANNELS;
                 ch++) {

                float a =
                        data[
                                index *
                                CHANNELS +
                                ch
                        ];

                float b =
                        data[
                                next *
                                CHANNELS +
                                ch
                        ];

                playerBuffer[
                        oldSize +
                        i * CHANNELS +
                        ch
                ] =
                        a +
                        (b - a) * frac;
            }
        }
    }

    // Prevent unlimited memory growth.
    const size_t maxBuffer =
            static_cast<size_t>(
                    sampleRate *
                    CHANNELS *
                    3
            );

    if (playerBuffer.size() >
            maxBuffer) {

        size_t remove =
                playerBuffer.size() -
                maxBuffer;

        if (playerRead > remove) {
            playerRead -= remove;
        } else {
            playerRead = 0;
        }

        playerBuffer.erase(
                playerBuffer.begin(),
                playerBuffer.begin() + remove
        );
    }

    env->ReleaseFloatArrayElements(
            pcm,
            data,
            JNI_ABORT
    );
}


// ============================================================
// DSP Process
// ============================================================

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_dtac_fx_MainActivity_nativeProcess(
        JNIEnv* env,
        jobject thiz,
        jfloatArray input,
        jint frames,
        jint source
) {

    if (input == nullptr)
        return nullptr;

    const int samples =
            frames * CHANNELS;

    jsize inputLength =
            env->GetArrayLength(
                    input
            );

    if (inputLength < samples)
        return nullptr;

    jfloat* in =
            env->GetFloatArrayElements(
                    input,
                    nullptr
            );

    if (!in)
        return nullptr;

    jfloatArray result =
            env->NewFloatArray(
                    samples
            );

    if (!result) {

        env->ReleaseFloatArrayElements(
                input,
                in,
                JNI_ABORT
        );

        return nullptr;
    }

    std::vector<float> output(
            samples,
            0.0f
    );

    for (int i = 0;
         i < frames;
         i++) {

        float left =
                in[i * 2];

        float right =
                in[i * 2 + 1];

        // ----------------------------------------------------
        // PLAYER
        // ----------------------------------------------------

        if (source == 1) {

            std::lock_guard<std::mutex> lock(
                    playerMutex
            );

            if (playerRead + 1 <
                    playerBuffer.size()) {

                left =
                        playerBuffer[
                                playerRead
                        ];

                right =
                        playerBuffer[
                                playerRead + 1
                        ];

                playerRead += 2;

            } else {

                left = 0.0f;
                right = 0.0f;

                // Clear consumed player buffer.
                if (playerRead >=
                        playerBuffer.size()) {

                    playerBuffer.clear();
                    playerRead = 0;
                }
            }
        }

        // ----------------------------------------------------
        // BYPASS
        // ----------------------------------------------------

        if (bypass) {

            output[i * 2] = left;
            output[i * 2 + 1] = right;

            updateSpectrum(
                    left,
                    right
            );

            continue;
        }

        // ----------------------------------------------------
        // NOISE GATE
        // ----------------------------------------------------

        left =
                noiseGate(left);

        right =
                noiseGate(right);

        // ----------------------------------------------------
        // COMPRESSOR
        // ----------------------------------------------------

        left =
                compressor(left);

        right =
                compressor(right);

        // ----------------------------------------------------
        // DELAY
        // ----------------------------------------------------

        int delaySamples =
                static_cast<int>(
                        delayAmount *
                        static_cast<float>(
                                sampleRate
                        ) *
                        0.65f
                );

        delaySamples =
                std::max(
                        1,
                        std::min(
                                delaySamples,
                                MAX_DELAY - 1
                        )
                );

        size_t delayRead =
                (
                        delayPos +
                        MAX_DELAY -
                        delaySamples
                ) %
                MAX_DELAY;

        float delayedLeft =
                delayBuffer[
                        delayRead * 2
                ];

        float delayedRight =
                delayBuffer[
                        delayRead * 2 + 1
                ];

        delayBuffer[
                delayPos * 2
        ] = left;

        delayBuffer[
                delayPos * 2 + 1
        ] = right;

        left +=
                delayedLeft *
                delayAmount *
                0.55f;

        right +=
                delayedRight *
                delayAmount *
                0.55f;

        // ----------------------------------------------------
        // ECHO
        // ----------------------------------------------------

        float echoLeft =
                echoBuffer[
                        echoPos * 2
                ];

        float echoRight =
                echoBuffer[
                        echoPos * 2 + 1
                ];

        echoBuffer[
                echoPos * 2
        ] =
                left +
                echoLeft *
                echoAmount *
                0.45f;

        echoBuffer[
                echoPos * 2 + 1
        ] =
                right +
                echoRight *
                echoAmount *
                0.45f;

        left +=
                echoLeft *
                echoAmount *
                0.55f;

        right +=
                echoRight *
                echoAmount *
                0.55f;

        // ----------------------------------------------------
        // CHORUS
        // ----------------------------------------------------

        chorusPhase +=
                2.0f *
                static_cast<float>(M_PI) *
                0.25f /
                static_cast<float>(sampleRate);

        if (chorusPhase >
                2.0f *
                static_cast<float>(M_PI)) {

            chorusPhase -=
                    2.0f *
                    static_cast<float>(M_PI);
        }

        float modulation =
                std::sin(
                        chorusPhase
                );

        int chorusDelay =
                static_cast<int>(
                        0.012f *
                        sampleRate +
                        modulation *
                        0.004f *
                        sampleRate *
                        chorusAmount
                );

        chorusDelay =
                std::max(
                        1,
                        std::min(
                                chorusDelay,
                                MAX_DELAY - 1
                        )
                );

        size_t chorusRead =
                (
                        delayPos +
                        MAX_DELAY -
                        chorusDelay
                ) %
                MAX_DELAY;

        float chorusLeft =
                delayBuffer[
                        chorusRead * 2
                ];

        float chorusRight =
                delayBuffer[
                        chorusRead * 2 + 1
                ];

        left =
                left *
                (1.0f - chorusAmount * 0.45f) +
                chorusLeft *
                chorusAmount *
                0.45f;

        right =
                right *
                (1.0f - chorusAmount * 0.45f) +
                chorusRight *
                chorusAmount *
                0.45f;

        // ----------------------------------------------------
        // REVERB
        // ----------------------------------------------------

        int reverbDelay =
                static_cast<int>(
                        0.075f *
                        sampleRate
                );

        reverbDelay =
                std::max(
                        1,
                        std::min(
                                reverbDelay,
                                MAX_DELAY - 1
                        )
                );

        size_t reverbRead =
                (
                        reverbPos +
                        MAX_DELAY -
                        reverbDelay
                ) %
                MAX_DELAY;

        float rvLeft =
                reverbBuffer[
                        reverbRead * 2
                ];

        float rvRight =
                reverbBuffer[
                        reverbRead * 2 + 1
                ];

        reverbBuffer[
                reverbPos * 2
        ] =
                left +
                rvLeft *
                0.72f;

        reverbBuffer[
                reverbPos * 2 + 1
        ] =
                right +
                rvRight *
                0.72f;

        left +=
                rvLeft *
                reverbAmount *
                0.42f;

        right +=
                rvRight *
                reverbAmount *
                0.42f;

        // ----------------------------------------------------
        // LIMITER
        // ----------------------------------------------------

        left =
                limiter(left);

        right =
                limiter(right);

        // ----------------------------------------------------
        // MUTE
        // ----------------------------------------------------

        if (mute) {

            left = 0.0f;
            right = 0.0f;
        }

        output[
                i * 2
        ] = left;

        output[
                i * 2 + 1
        ] = right;

        updateSpectrum(
                left,
                right
        );

        delayPos++;

        if (delayPos >= MAX_DELAY)
            delayPos = 0;

        echoPos++;

        if (echoPos >= MAX_DELAY)
            echoPos = 0;

        reverbPos++;

        if (reverbPos >= MAX_DELAY)
            reverbPos = 0;
    }

    env->ReleaseFloatArrayElements(
            input,
            in,
            JNI_ABORT
    );

    env->SetFloatArrayRegion(
            result,
            0,
            samples,
            output.data()
    );

    return result;
}


// ============================================================
// FX Controls
// ============================================================

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetDelay(
        JNIEnv* env,
        jobject thiz,
        jfloat amount
) {
    delayAmount =
            clampFloat(
                    amount,
                    0.0f,
                    1.0f
            );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetEcho(
        JNIEnv* env,
        jobject thiz,
        jfloat amount
) {
    echoAmount =
            clampFloat(
                    amount,
                    0.0f,
                    1.0f
            );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetChorus(
        JNIEnv* env,
        jobject thiz,
        jfloat amount
) {
    chorusAmount =
            clampFloat(
                    amount,
                    0.0f,
                    1.0f
            );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetReverb(
        JNIEnv* env,
        jobject thiz,
        jfloat amount
) {
    reverbAmount =
            clampFloat(
                    amount,
                    0.0f,
                    1.0f
            );
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetBypass(
        JNIEnv* env,
        jobject thiz,
        jboolean enabled
) {
    bypass =
            enabled == JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_dtac_fx_MainActivity_nativeSetMute(
        JNIEnv* env,
        jobject thiz,
        jboolean enabled
) {
    mute =
            enabled == JNI_TRUE;
}


// ============================================================
// Spectrum
// ============================================================

extern "C"
JNIEXPORT jfloatArray JNICALL
Java_com_dtac_fx_MainActivity_nativeGetSpectrum(
        JNIEnv* env,
        jobject thiz
) {

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
            spectrum
    );

    return result;
}