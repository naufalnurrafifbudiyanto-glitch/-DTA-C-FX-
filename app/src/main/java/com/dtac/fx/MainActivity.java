package com.dtac.fx;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.AudioManager;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class MainActivity extends Activity {

    static {
        System.loadLibrary("dtac_fx");
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int FRAMES = 512;
    private static final int PERMISSION_REQUEST = 100;
    private static final int PLAYER_REQUEST = 200;

    private AudioRecord audioRecord;
    private AudioTrack audioTrack;

    private Thread audioThread;
    private volatile boolean running = false;

    private int source = 0; // 0 = MIC, 1 = PLAYER

    private Button micButton;
    private Button playerButton;
    private Button bypassButton;
    private Button muteButton;

    private SeekBar delaySeek;
    private SeekBar echoSeek;
    private SeekBar chorusSeek;
    private SeekBar reverbSeek;

    private TextView delayValue;
    private TextView echoValue;
    private TextView chorusValue;
    private TextView reverbValue;
    private TextView sourceText;

    private SpectrumView spectrumView;

    private final Handler handler =
            new Handler(Looper.getMainLooper());

    private final Runnable spectrumRunnable = new Runnable() {
        @Override
        public void run() {

            if (!running)
                return;

            float[] spectrum = nativeGetSpectrum();

            if (spectrum != null && spectrumView != null) {
                spectrumView.setSpectrum(spectrum);
            }

            handler.postDelayed(this, 50);
        }
    };

    private native boolean nativeInit(int sampleRate);

    private native void nativeRelease();

    private native void nativeProcess(
            short[] input,
            short[] output,
            int frames
    );

    private native void nativeSetDelay(float value);

    private native void nativeSetEcho(float value);

    private native void nativeSetChorus(float value);

    private native void nativeSetReverb(float value);

    private native void nativeSetBypass(boolean value);

    private native void nativeSetMute(boolean value);

    private native float[] nativeGetSpectrum();

    private native void nativePushPlayerPCM(
            short[] pcm,
            int length
    );

    private boolean bypass = false;
    private boolean mute = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        bindViews();
        setupControls();

        if (checkSelfPermission(
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {

            requestPermissions(
                    new String[]{
                            Manifest.permission.RECORD_AUDIO
                    },
                    PERMISSION_REQUEST
            );
        } else {
            startAudio();
        }
    }

    private void bindViews() {

        micButton = findViewById(R.id.btnMic);
        playerButton = findViewById(R.id.btnPlayer);

        bypassButton = findViewById(R.id.btnBypass);
        muteButton = findViewById(R.id.btnMute);

        delaySeek = findViewById(R.id.seekDelay);
        echoSeek = findViewById(R.id.seekEcho);
        chorusSeek = findViewById(R.id.seekChorus);
        reverbSeek = findViewById(R.id.seekReverb);

        delayValue = findViewById(R.id.txtDelayValue);
        echoValue = findViewById(R.id.txtEchoValue);
        chorusValue = findViewById(R.id.txtChorusValue);
        reverbValue = findViewById(R.id.txtReverbValue);

        sourceText = findViewById(R.id.txtSource);

        spectrumView = findViewById(R.id.spectrumView);
    }

    private void setupControls() {

        micButton.setOnClickListener(v -> {

            source = 0;
            sourceText.setText("SOURCE : MIC");

            micButton.setAlpha(1.0f);
            playerButton.setAlpha(0.55f);
        });

        playerButton.setOnClickListener(v -> {

            Intent intent =
                    new Intent(Intent.ACTION_OPEN_DOCUMENT);

            intent.addCategory(
                    Intent.CATEGORY_OPENABLE);

            intent.setType("audio/*");

            startActivityForResult(
                    intent,
                    PLAYER_REQUEST
            );
        });

        setupSeek(
                delaySeek,
                delayValue,
                this::nativeSetDelay
        );

        setupSeek(
                echoSeek,
                echoValue,
                this::nativeSetEcho
        );

        setupSeek(
                chorusSeek,
                chorusValue,
                this::nativeSetChorus
        );

        setupSeek(
                reverbSeek,
                reverbValue,
                this::nativeSetReverb
        );

        bypassButton.setOnClickListener(v -> {

            bypass = !bypass;

            nativeSetBypass(bypass);

            bypassButton.setText(
                    bypass ? "BYPASS ON" : "BYPASS OFF"
            );

            bypassButton.setAlpha(
                    bypass ? 1.0f : 0.65f
            );
        });

        muteButton.setOnClickListener(v -> {

            mute = !mute;

            nativeSetMute(mute);

            muteButton.setText(
                    mute ? "MUTE ON" : "MUTE OFF"
            );

            muteButton.setAlpha(
                    mute ? 1.0f : 0.65f
            );
        });
    }

    private interface FXSetter {
        void set(float value);
    }

    private void setupSeek(
            SeekBar seek,
            TextView valueText,
            FXSetter setter) {

        seek.setMax(100);

        seek.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {

                    @Override
                    public void onProgressChanged(
                            SeekBar bar,
                            int progress,
                            boolean fromUser) {

                        float value =
                                progress / 100.0f;

                        setter.set(value);

                        valueText.setText(
                                progress + "%"
                        );
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar bar) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar bar) {
                    }
                }
        );
    }

    private void startAudio() {

        if (running)
            return;

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

        int recordBuffer =
                Math.max(minRecord * 2, FRAMES * 2);

        int trackBuffer =
                Math.max(minTrack * 2, FRAMES * 4);

        audioRecord =
                new AudioRecord(
                        android.media.MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        recordBuffer
                );

        audioTrack =
                new AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_STEREO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        trackBuffer,
                        AudioTrack.MODE_STREAM
                );

        if (!nativeInit(SAMPLE_RATE)) {
            return;
        }

        audioRecord.startRecording();
        audioTrack.play();

        running = true;

        audioThread =
                new Thread(
                        this::audioLoop,
                        "DTA-C-Audio"
                );

        audioThread.start();

        handler.post(spectrumRunnable);
    }

    private void audioLoop() {

        short[] mic =
                new short[FRAMES];

        short[] stereo =
                new short[FRAMES * 2];

        short[] output =
                new short[FRAMES * 2];

        while (running) {

            if (source == 0) {

                int read =
                        audioRecord.read(
                                mic,
                                0,
                                FRAMES
                        );

                if (read <= 0)
                    continue;

                for (int i = 0; i < read; i++) {

                    short s = mic[i];

                    stereo[i * 2] = s;
                    stereo[i * 2 + 1] = s;
                }

                nativeProcess(
                        stereo,
                        output,
                        read
                );

                audioTrack.write(
                        output,
                        0,
                        read * 2
                );

            } else {

                /*
                 * PLAYER PCM sudah dikirim ke native
                 * melalui nativePushPlayerPCM().
                 *
                 * Array kosong digunakan sebagai pemicu
                 * pemrosesan buffer PLAYER.
                 */

                java.util.Arrays.fill(
                        stereo,
                        (short) 0
                );

                nativeProcess(
                        stereo,
                        output,
                        FRAMES
                );

                audioTrack.write(
                        output,
                        0,
                        output.length
                );

                try {
                    Thread.sleep(3);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    @Override
    protected void onActivityResult(
            int requestCode,
            int resultCode,
            Intent data) {

        super.onActivityResult(
                requestCode,
                resultCode,
                data
        );

        if (requestCode != PLAYER_REQUEST)
            return;

        if (resultCode != RESULT_OK)
            return;

        if (data == null || data.getData() == null)
            return;

        Uri uri = data.getData();

        source = 1;

        sourceText.setText(
                "SOURCE : PLAYER"
        );

        micButton.setAlpha(0.55f);
        playerButton.setAlpha(1.0f);

        new Thread(
                () -> decodePlayer(uri),
                "DTA-C-Player"
        ).start();
    }

    private void decodePlayer(Uri uri) {

        MediaExtractor extractor =
                new MediaExtractor();

        MediaCodec codec = null;

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

            extractor.selectTrack(trackIndex);

            MediaFormat format =
                    extractor.getTrackFormat(
                            trackIndex
                    );

            String mime =
                    format.getString(
                            MediaFormat.KEY_MIME
                    );

            int inputRate =
                    format.containsKey(
                            MediaFormat.KEY_SAMPLE_RATE)
                            ? format.getInteger(
                            MediaFormat.KEY_SAMPLE_RATE)
                            : SAMPLE_RATE;

            int inputChannels =
                    format.containsKey(
                            MediaFormat.KEY_CHANNEL_COUNT)
                            ? format.getInteger(
                            MediaFormat.KEY_CHANNEL_COUNT)
                            : 2;

            codec =
                    MediaCodec.createDecoderByType(
                            mime
                    );

            codec.configure(
                    format,
                    null,
                    null,
                    0
            );

            codec.start();

            MediaCodec.BufferInfo info =
                    new MediaCodec.BufferInfo();

            boolean inputDone = false;
            boolean outputDone = false;

            while (!outputDone && running) {

                if (!inputDone) {

                    int inputIndex =
                            codec.dequeueInputBuffer(
                                    10000
                            );

                    if (inputIndex >= 0) {

                        ByteBuffer inputBuffer =
                                codec.getInputBuffer(
                                        inputIndex
                                );

                        if (inputBuffer == null)
                            continue;

                        int sampleSize =
                                extractor.readSampleData(
                                        inputBuffer,
                                        0
                                );

                        if (sampleSize < 0) {

                            codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    0,
                                    0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            );

                            inputDone = true;

                        } else {

                            long pts =
                                    extractor.getSampleTime();

                            codec.queueInputBuffer(
                                    inputIndex,
                                    0,
                                    sampleSize,
                                    pts,
                                    0
                            );

                            extractor.advance();
                        }
                    }
                }

                int outputIndex =
                        codec.dequeueOutputBuffer(
                                info,
                                10000
                        );

                if (outputIndex >= 0) {

                    ByteBuffer buffer =
                            codec.getOutputBuffer(
                                    outputIndex
                            );

                    if (buffer != null &&
                            info.size > 0) {

                        buffer.position(
                                info.offset
                        );

                        buffer.limit(
                                info.offset +
                                        info.size
                        );

                        ByteBuffer copy =
                                buffer.slice()
                                        .order(
                                                ByteOrder.LITTLE_ENDIAN
                                        );

                        int samples =
                                info.size / 2;

                        short[] pcm =
                                new short[samples];

                        copy.asShortBuffer()
                                .get(pcm);

                        short[] stereo =
                                convertTo48kStereo(
                                        pcm,
                                        inputChannels,
                                        inputRate
                                );

                        if (stereo.length > 0) {

                            nativePushPlayerPCM(
                                    stereo,
                                    stereo.length
                            );
                        }
                    }

                    codec.releaseOutputBuffer(
                            outputIndex,
                            false
                    );

                    if ((info.flags &
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            != 0) {

                        outputDone = true;
                    }
                }
            }

        } catch (Exception e) {

            e.printStackTrace();

        } finally {

            try {
                if (codec != null) {
                    codec.stop();
                    codec.release();
                }
            } catch (Exception ignored) {
            }

            try {
                extractor.release();
            } catch (Exception ignored) {
            }
        }
    }

    private short[] convertTo48kStereo(
            short[] input,
            int channels,
            int inputRate) {

        if (input.length == 0)
            return new short[0];

        int inFrames =
                input.length /
                        Math.max(channels, 1);

        if (inFrames <= 0)
            return new short[0];

        int outFrames =
                (int) Math.ceil(
                        inFrames *
                                (SAMPLE_RATE /
                                        (double) inputRate)
                );

        short[] output =
                new short[outFrames * 2];

        for (int i = 0; i < outFrames; i++) {

            double src =
                    i *
                            (inputRate /
                                    (double) SAMPLE_RATE);

            int p =
                    (int) src;

            double frac =
                    src - p;

            if (p >= inFrames)
                p = inFrames - 1;

            int p2 =
                    Math.min(
                            p + 1,
                            inFrames - 1
                    );

            float left1;
            float right1;
            float left2;
            float right2;

            if (channels == 1) {

                left1 =
                        input[p];

                right1 =
                        left1;

                left2 =
                        input[p2];

                right2 =
                        left2;

            } else {

                int a = p * channels;
                int b = p2 * channels;

                left1 =
                        input[a];

                right1 =
                        input[a + 1];

                left2 =
                        input[b];

                right2 =
                        input[b + 1];
            }

            float left =
                    left1 +
                            (left2 - left1) *
                                    (float) frac;

            float right =
                    right1 +
                            (right2 - right1) *
                                    (float) frac;

            output[i * 2] =
                    (short) left;

            output[i * 2 + 1] =
                    (short) right;
        }

        return output;
    }

    @Override
    protected void onDestroy() {

        running = false;

        handler.removeCallbacks(
                spectrumRunnable
        );

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

        nativeRelease();

        super.onDestroy();
    }

    /*
     * Spectrum Analyzer sederhana 32 band.
     * Tidak membutuhkan file Java tambahan.
     */

    public static class SpectrumView
            extends View {

        private final android.graphics.Paint paint =
                new android.graphics.Paint(
                        android.graphics.Paint.ANTI_ALIAS_FLAG
                );

        private float[] values =
                new float[32];

        public SpectrumView(
                android.content.Context context) {

            super(context);

            paint.setStrokeWidth(8);
            paint.setStrokeCap(
                    android.graphics.Paint.Cap.ROUND
            );
        }

        public void setSpectrum(float[] data) {

            if (data == null)
                return;

            int n =
                    Math.min(
                            data.length,
                            values.length
                    );

            System.arraycopy(
                    data,
                    0,
                    values,
                    0,
                    n
            );

            postInvalidate();
        }

        @Override
        protected void onDraw(
                android.graphics.Canvas canvas) {

            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();

            float gap = width / 32.0f;

            for (int i = 0; i < 32; i++) {

                float value =
                        Math.max(
                                0.02f,
                                Math.min(
                                        1.0f,
                                        values[i] * 5.0f
                                )
                        );

                float x =
                        gap * i +
                                gap * 0.5f;

                float barHeight =
                        value * height;

                paint.setColor(
                        android.graphics.Color.rgb(
                                0,
                                220,
                                255
                        )
                );

                canvas.drawLine(
                        x,
                        height,
                        x,
                        height - barHeight,
                        paint
                );
            }
        }
    }
}
