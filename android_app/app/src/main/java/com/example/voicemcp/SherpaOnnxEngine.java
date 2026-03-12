package com.example.voicemcp;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SherpaOnnxEngine implements SttEngine {

    private static final String TAG = "SherpaOnnxEngine";
    private static final int SAMPLE_RATE = 16000;
    private static final String ASSET_MODEL_DIR = "sherpa-onnx-model";
    private static final String MODEL_VERSION = "kroko-2025-08-06";

    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private volatile boolean ready = false;
    private Listener listener;
    private String lastText = "";

    @Override
    public String displayName() { return "Sherpa-ONNX"; }

    @Override
    public String key() { return "sherpa_onnx"; }

    @Override
    public void initialize(Context context, Listener listener) {
        this.listener = listener;
        new Thread(() -> {
            try {
                copyAssetDir(context.getAssets(), ASSET_MODEL_DIR,
                        new File(context.getFilesDir(), ASSET_MODEL_DIR));

                String modelRoot = new File(context.getFilesDir(), ASSET_MODEL_DIR).getAbsolutePath();

                OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
                transducer.setEncoder(ASSET_MODEL_DIR + "/encoder.onnx");
                transducer.setDecoder(ASSET_MODEL_DIR + "/decoder.onnx");
                transducer.setJoiner(ASSET_MODEL_DIR + "/joiner.onnx");

                OnlineModelConfig modelConfig = new OnlineModelConfig();
                modelConfig.setTransducer(transducer);
                modelConfig.setTokens(ASSET_MODEL_DIR + "/tokens.txt");
                modelConfig.setModelType("zipformer2");
                modelConfig.setNumThreads(2);

                OnlineRecognizerConfig config = new OnlineRecognizerConfig();
                config.setModelConfig(modelConfig);

                recognizer = new OnlineRecognizer(context.getAssets(), config);
                stream = recognizer.createStream("");
                ready = true;
                listener.onReady();
                Log.i(TAG, "Sherpa-ONNX ready");
            } catch (Exception e) {
                ready = false;
                String msg = "Sherpa-ONNX model load failed. Put model in assets/" + ASSET_MODEL_DIR;
                listener.onError(msg);
                Log.e(TAG, msg, e);
            }
        }).start();
    }

    @Override
    public boolean isReady() { return ready; }

    @Override
    public void feedAudio(byte[] pcm, int length) {
        if (!ready || recognizer == null || stream == null) return;

        // Convert 16-bit PCM bytes to float samples normalized to [-1, 1]
        int numSamples = length / 2;
        float[] samples = new float[numSamples];
        for (int i = 0; i < numSamples; i++) {
            short s = (short) ((pcm[i * 2] & 0xFF) | (pcm[i * 2 + 1] << 8));
            samples[i] = s / 32768.0f;
        }

        stream.acceptWaveform(samples, SAMPLE_RATE);

        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }

        boolean isEndpoint = recognizer.isEndpoint(stream);
        String text = recognizer.getResult(stream).getText().trim();

        if (isEndpoint && !text.isEmpty()) {
            // Flush with tail padding for best results
            float[] tailPadding = new float[(int) (0.8 * SAMPLE_RATE)];
            stream.acceptWaveform(tailPadding, SAMPLE_RATE);
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            text = recognizer.getResult(stream).getText().trim();
            recognizer.reset(stream);
            if (!text.isEmpty() && listener != null) {
                listener.onFinalResult(text);
            }
            lastText = "";
        } else if (!text.isEmpty() && !text.equals(lastText)) {
            lastText = text;
            if (listener != null) {
                listener.onPartialResult(text);
            }
        }
    }

    @Override
    public void flush() {
        if (!ready || recognizer == null || stream == null) return;
        float[] tailPadding = new float[(int) (0.8 * SAMPLE_RATE)];
        stream.acceptWaveform(tailPadding, SAMPLE_RATE);
        while (recognizer.isReady(stream)) {
            recognizer.decode(stream);
        }
        String text = recognizer.getResult(stream).getText().trim();
        recognizer.reset(stream);
        if (!text.isEmpty() && listener != null) {
            listener.onFinalResult(text);
        }
        lastText = "";
    }

    @Override
    public void close() {
        ready = false;
        if (stream != null) { stream.release(); stream = null; }
        if (recognizer != null) { recognizer.release(); recognizer = null; }
    }

    // ---- asset copy helpers ----

    private void copyAssetDir(AssetManager assets, String assetDir, File outDir) throws IOException {
        File versionFile = new File(outDir, ".version");
        if (outDir.exists() && versionFile.exists()) {
            try (InputStream vis = new java.io.FileInputStream(versionFile)) {
                byte[] buf = new byte[256];
                int n = vis.read(buf);
                String existing = new String(buf, 0, n).trim();
                if (MODEL_VERSION.equals(existing)) {
                    return; // already copied correct version
                }
            } catch (Exception ignore) {}
            // Wrong version — delete and re-copy
            deleteDir(outDir);
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("Could not create directory: " + outDir);
        }
        String[] files = assets.list(assetDir);
        if (files == null || files.length == 0) {
            throw new IOException("Missing asset folder: " + assetDir);
        }
        for (String file : files) {
            String assetPath = assetDir + "/" + file;
            File outFile = new File(outDir, file);
            String[] children = assets.list(assetPath);
            if (children != null && children.length > 0) {
                copyAssetDir(assets, assetPath, outFile);
            } else {
                try (InputStream in = assets.open(assetPath);
                     OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[4096];
                    int read;
                    while ((read = in.read(buf)) != -1) {
                        out.write(buf, 0, read);
                    }
                    out.flush();
                }
            }
        }
        // Write version marker
        try (OutputStream vos = new FileOutputStream(new File(outDir, ".version"))) {
            vos.write(MODEL_VERSION.getBytes());
            vos.flush();
        }
    }

    private void deleteDir(File dir) {
        if (dir.isDirectory()) {
            File[] children = dir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDir(child);
                }
            }
        }
        dir.delete();
    }
}
