package com.dtac.fx;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends Activity {

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAMES = 512;

    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final int REQUEST_AUDIO_FILE = 2001;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    private Thread audioThread;
    private Thread playerThread;

    private volatile boolean running = false;
    private volatile boolean playerRunning = false;

    private volatile int source = 0; // 0 = MIC, 1 = PLAYER

    private SpectrumView spectrumView;
    private TextView txtSource;

    private SeekBar seekDelay;
    private SeekBar seekEcho;
    private SeekBar seekChorus;
    private SeekBar seekReverb;

    private TextView txtDelayValue;
    private TextView txtEchoValue;
    private TextView txtChorusValue;
    private TextView txtReverbValue;

    private Button btnBypass;
    private Button btnMute;

    private boolean bypass = false;
    private boolean mute = false;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private boolean nativeLoaded = false;

    static {
        // Library loading is done safely below.
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Landscape tanpa membuat folder layout-land.
        setRequestedOrientation(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        );

        try {
            System.loadLibrary("dtac_fx");
            nativeLoaded = true;
        } catch (UnsatisfiedLinkError e) {
            nativeLoaded = false;
        }

        setContentView(R.layout.activity_main);

        spectrumView = findViewById(R.id.spectrumView);
        txtSource = findViewById(R.id.txtSource);

        setupControls();

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    REQUEST_RECORD_AUDIO
            );
        }
    }

    private void setupControls() {

        Button btnMic = findViewById(R.id.btnMic);
        Button btnPlayer = findViewById(R.id.btnPlayer);

        seekDelay = findViewById(R.id.seekDelay);
        seekEcho = findViewById(R.id.seekEcho);
        seekChorus = findViewById(R.id.seekChorus);
        seekReverb = findViewById(R.id.seekReverb);

        txtDelayValue = findViewById(R.id.txtDelayValue);
        txtEchoValue = findViewById(R.id.txtEchoValue);
        txtChorusValue = findViewById(R.id.txtChorusValue);
        txtReverbValue = findViewById(R.id.txtReverbValue);

        btnBypass = findViewById(R.id.btnBypass);
        btnMute = findViewById(R.id.btnMute);

        btnMic.setOnClickListener(v -> {

            source = 0;

            txtSource.setText("SOURCE : MIC");

            startAudio();

        });

        btnPlayer.setOnClickListener(v -> {

            source = 1;

            txtSource.setText("SOURCE : PLAYER");

            startAudio();

            openAudioFile();
        });

        setupSeekBar(
                seekDelay,
                txtDelayValue,
                0
        );

        setupSeekBar(
                seekEcho,
                txtEchoValue,
                1
        );

        setupSeekBar(
                seekChorus,
                txtChorusValue,
                2
        );

        setupSeekBar(
                seekReverb,
                txtReverbValue,
                3
        );

        btnBypass.setOnClickListener(v -> {

            bypass = !bypass;

            btnBypass.setText(
                    bypass
                            ? "BYPASS ON"
                            : "BYPASS OFF"
            );

            if (nativeLoaded) {
                nativeSetBypass(bypass);
            }
        });

        btnMute.setOnClickListener(v -> {

            mute = !mute;

            btnMute.setText(
                    mute
                            ? "MUTE ON"
                            : "MUTE OFF"
            );

            if (nativeLoaded) {
                nativeSetMute(mute);
            }
        });
    }

    private void setupSeekBar(
            SeekBar bar,
            TextView value,
            int type
    ) {

        bar.setMax(100);

        bar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {

                        value.setText(
                                progress + "%"
                        );

                        if (!nativeLoaded)
                            return;

                        float amount =
                                progress / 100.0f;

                        switch (type) {

                            case 0:
                                nativeSetDelay(amount);
                                break;

                            case 1:
                                nativeSetEcho(amount);
                                break;

                            case 2:
                                nativeSetChorus(amount);
                                break;

                            case 3:
                                nativeSetReverb(amount);
                                break;
                        }
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );
    }

    private void startAudio() {

        if (running)
            return;

        if (!nativeLoaded)
            return;

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
        ) != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        int minRecord =
                AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

        int minTrack =
                AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT
                );

        if (minRecord <= 0)
            minRecord = FRAMES * 4;

        if (minTrack <= 0)
            minTrack = FRAMES * 8;

        try {

            audioRecord =
                    new AudioRecord(
                            MediaRecorder.AudioSource.DEFAULT,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(
                                    minRecord,
                                    FRAMES * 8
                            )
                    );

            audioTrack =
                    new AudioTrack(
                            android.media.AudioManager.STREAM_MUSIC,
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_OUT_STEREO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(
                                    minTrack,
                                    FRAMES * 8
                            ),
                            AudioTrack.MODE_STREAM
                    );

            nativeInit(SAMPLE_RATE);

            nativeSetDelay(
                    seekDelay.getProgress() / 100.0f
            );

            nativeSetEcho(
                    seekEcho.getProgress() / 100.0f
            );

            nativeSetChorus(
                    seekChorus.getProgress() / 100.0f
            );

            nativeSetReverb(
                    seekReverb.getProgress() / 100.0f
            );

            nativeSetBypass(bypass);
            nativeSetMute(mute);

            running = true;

            audioRecord.startRecording();
            audioTrack.play();

            audioThread =
                    new Thread(
                            this::audioLoop,
                            "DTA-C-FX-Audio"
                    );

            audioThread.start();

            startSpectrum();

        } catch (Exception e) {

            stopAudio();
        }
    }

    private void audioLoop() {

        short[] micBuffer =
                new short[FRAMES];

        float[] input =
                new float[FRAMES * 2];

        short[] output =
                new short[FRAMES * 2];

        while (running) {

            if (source == 0) {

                int read =
                        audioRecord.read(
                                micBuffer,
                                0,
                                FRAMES
                        );

                if (read <= 0)
                    continue;

                for (int i = 0; i < read; i++) {

                    float sample =
                            micBuffer[i] / 32768.0f;

                    input[i * 2] = sample;
                    input[i * 2 + 1] = sample;
                }

                if (read < FRAMES) {

                    for (int i = read; i < FRAMES; i++) {

                        input[i * 2] = 0;
                        input[i * 2 + 1] = 0;
                    }
                }

            } else {

                // Player PCM masuk melalui nativePushPlayerPCM().
                for (int i = 0; i < FRAMES * 2; i++) {
                    input[i] = 0;
                }
            }

            float[] processed =
                    nativeProcess(
                            input,
                            FRAMES,
                            source
                    );

            if (processed == null)
                continue;

            int count =
                    Math.min(
                            processed.length,
                            output.length
                    );

            for (int i = 0; i < count; i++) {

                float s =
                        Math.max(
                                -1.0f,
                                Math.min(
                                        1.0f,
                                        processed[i]
                                )
                        );

                output[i] =
                        (short) (
                                s * 32767.0f
                        );
            }

            audioTrack.write(
                    output,
                    0,
                    count
            );
        }
    }

    private void startSpectrum() {

        handler.postDelayed(
                new Runnable() {

                    @Override
                    public void run() {

                        if (!running)
                            return;

                        if (nativeLoaded) {

                            float[] spectrum =
                                    nativeGetSpectrum();

                            if (spectrum != null &&
                                    spectrumView != null) {

                                spectrumView.setSpectrum(
                                        spectrum
                                );
                            }
                        }

                        handler.postDelayed(
                                this,
                                50
                        );
                    }
                },
                50
        );
    }

    private void openAudioFile() {

        Intent intent =
                new Intent(
                        Intent.ACTION_OPEN_DOCUMENT
                );

        intent.addCategory(
                Intent.CATEGORY_OPENABLE
        );

        intent.setType("audio/*");

        startActivityForResult(
                intent,
                REQUEST_AUDIO_FILE
        );
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data
    ) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode != REQUEST_AUDIO_FILE)
            return;

        if (resultCode != RESULT_OK)
            return;

        if (data == null ||
                data.getData() == null)
            return;

        final android.net.Uri uri =
                data.getData();

        playerRunning = true;

        playerThread =
                new Thread(
                        () -> decodePlayer(uri),
                        "DTA-C-FX-Player"
                );

        playerThread.start();
    }

    private void decodePlayer(
            android.net.Uri uri
    ) {

        MediaExtractor extractor =
                new MediaExtractor();

        MediaCodec decoder = null;

        try {

            extractor.setDataSource(
                    this,
                    uri,
                    null
            );

            int trackIndex = -1;

            for (int i = 0;
                 i < extractor.getTrackCount();
                 i++) {

                MediaFormat format =
                        extractor.getTrackFormat(i);

                String mime =
                        format.getString(
                                MediaFormat.KEY_MIME
                        );

                if (mime != null &&
                        mime.startsWith("audio/")) {

                    trackIndex = i;
                    break;
                }
            }

            if (trackIndex < 0)
                return;

            MediaFormat format =
                    extractor.getTrackFormat(
                            trackIndex
                    );

            extractor.selectTrack(
                    trackIndex
            );

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            if (mime == null)
                return;

            decoder =
                    MediaCodec.createDecoderByType(
                            mime
                    );

            decoder.configure(
                    format,
                    null,
                    null,
                    0
            );

            decoder.start();

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            boolean inputEOS = false;
            boolean outputEOS = false;

            while (playerRunning &&
                    !outputEOS) {

                if (!inputEOS) {

                    int inputIndex =
                            decoder.dequeueInputBuffer(
                                    10000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer buffer =
                                decoder.getInputBuffer(
                                        inputIndex
                                );

                        if (buffer != null) {

                            int sampleSize =
                                    extractor.readSampleData(
                                            buffer,
                                            0
                                    );

                            if (sampleSize < 0) {

                                decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        0,
                                        0,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                );

                                inputEOS = true;

                            } else {

                                long presentationTime =
                                        extractor.getSampleTime();

                                decoder.queueInputBuffer(
                                        inputIndex,
                                        0,
                                        sampleSize,
                                        presentationTime,
                                        0
                                );

                                extractor.advance();
                            }
                        }
                    }
                }

                int outputIndex =
                        decoder.dequeueOutputBuffer(
                                info,
                                10000
                        );

                if (outputIndex >= 0) {

                    ByteBuffer outputBuffer =
                            decoder.getOutputBuffer(
                                    outputIndex
                            );

                    if (outputBuffer != null &&
                            info.size > 0) {

                        outputBuffer.position(
                                info.offset
                        );

                        outputBuffer.limit(
                                info.offset +
                                        info.size
                        );

                        ByteBuffer copy =
                                outputBuffer.slice()
                                        .order(
                                                ByteOrder.LITTLE_ENDIAN
                                        );

                        short[] pcm =
                                new short[
                                        info.size / 2
                                ];

                        copy.asShortBuffer()
                                .get(pcm);

                        sendPlayerPCM(
                                pcm,
                                format
                        );
                    }

                    decoder.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if ((info.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        outputEOS = true;
                    }
                }
            }

        } catch (Exception ignored) {

        } finally {

            playerRunning = false;

            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Exception ignored) {
            }

            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    private void sendPlayerPCM(
            short[] pcm,
            MediaFormat format
    ) {

        if (!nativeLoaded)
            return;

        int channels = 2;

        if (format.containsKey(
                MediaFormat.KEY_CHANNEL_COUNT
        )) {

            channels =
                    format.getInteger(
                            MediaFormat.KEY_CHANNEL_COUNT
                    );
        }

        int sampleRate = SAMPLE_RATE;

        if (format.containsKey(
                MediaFormat.KEY_SAMPLE_RATE
        )) {

            sampleRate =
                    format.getInteger(
                            MediaFormat.KEY_SAMPLE_RATE
                    );
        }

        float[] stereo =
                new float[
                        pcm.length /
                                Math.max(
                                        1,
                                        channels
                                ) * 2
                ];

        if (channels <= 0)
            channels = 1;

        int frames =
                pcm.length / channels;

        if (stereo.length <
                frames * 2) {

            stereo =
                    new float[
                            frames * 2
                    ];
        }

        for (int i = 0; i < frames; i++) {

            float left;
            float right;

            if (channels == 1) {

                float s =
                        pcm[i] / 32768.0f;

                left = s;
                right = s;

            } else {

                int p = i * channels;

                left =
                        pcm[p] / 32768.0f;

                right =
                        pcm[p + 1] / 32768.0f;
            }

            stereo[i * 2] = left;
            stereo[i * 2 + 1] = right;
        }

        // Native melakukan resampling bila diperlukan.
        nativePushPlayerPCM(
                stereo,
                frames,
                sampleRate
        );
    }

    private void stopAudio() {

        running = false;
        playerRunning = false;

        try {
            if (audioRecord != null) {
                audioRecord.stop();
                audioRecord.release();
            }
        } catch (Exception ignored) {
        }

        try {
            if (audioTrack != null) {
                audioTrack.stop();
                audioTrack.release();
            }
        } catch (Exception ignored) {
        }

        audioRecord = null;
        audioTrack = null;

        if (nativeLoaded) {
            nativeRelease();
        }
    }

    @Override
    protected void onDestroy() {

        stopAudio();

        super.onDestroy();
    }

    // ============================================================
    // NATIVE
    // ============================================================

    private native void nativeInit(
            int sampleRate
    );

    private native void nativeRelease();

    private native float[] nativeProcess(
            float[] input,
            int frames,
            int source
    );

    private native void nativePushPlayerPCM(
            float[] pcm,
            int frames,
            int sampleRate
    );

    private native void nativeSetDelay(
            float amount
    );

    private native void nativeSetEcho(
            float amount
    );

    private native void nativeSetChorus(
            float amount
    );

    private native void nativeSetReverb(
            float amount
    );

    private native void nativeSetBypass(
            boolean enabled
    );

    private native void nativeSetMute(
            boolean enabled
    );

    private native float[] nativeGetSpectrum();
}