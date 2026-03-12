package com.example.voicemcp;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class VoskEngine implements SttEngine {

    private static final int SAMPLE_RATE = 16000;
    private static final String ASSET_MODEL_DIR = "model-en-us";

    private Model model;
    private Recognizer recognizer;
    private volatile boolean ready = false;
    private Listener listener;

    @Override
    public String displayName() { return "Vosk"; }

    @Override
    public String key() { return "vosk"; }

    @Override
    public void initialize(Context context, Listener listener) {
        this.listener = listener;
        new Thread(() -> {
            try {
                File modelDir = copyModelToInternalStorage(context, ASSET_MODEL_DIR);
                model = new Model(modelDir.getAbsolutePath());
                recognizer = new Recognizer(model, SAMPLE_RATE);
                ready = true;
                listener.onReady();
            } catch (Exception e) {
                ready = false;
                listener.onError("Vosk model load failed. Put model in assets/" + ASSET_MODEL_DIR);
                android.util.Log.e("VoskEngine", "Failed to initialize Vosk", e);
            }
        }).start();
    }

    @Override
    public boolean isReady() { return ready; }

    @Override
    public void feedAudio(byte[] pcm, int length) {
        if (!ready || recognizer == null) return;
        boolean isFinal = recognizer.acceptWaveForm(pcm, length);
        String json = isFinal ? recognizer.getResult() : recognizer.getPartialResult();
        emitResult(json, isFinal);
    }

    @Override
    public void flush() {
        if (!ready || recognizer == null) return;
        emitResult(recognizer.getFinalResult(), true);
    }

    @Override
    public void close() {
        if (recognizer != null) { recognizer.close(); recognizer = null; }
        if (model != null) { model.close(); model = null; }
        ready = false;
    }

    private void emitResult(String json, boolean isFinal) {
        try {
            JSONObject result = new JSONObject(json);
            String text = isFinal ? result.optString("text", "") : result.optString("partial", "");
            text = text.trim();
            if (text.isEmpty() || listener == null) return;
            if (isFinal) {
                listener.onFinalResult(text);
            } else {
                listener.onPartialResult(text);
            }
        } catch (Exception e) {
            if (listener != null) listener.onError("Failed parsing Vosk output");
        }
    }

    // ---- asset copy helpers (unchanged from original) ----

    private File copyModelToInternalStorage(Context context, String assetModelDir) throws Exception {
        File targetDir = new File(context.getFilesDir(), assetModelDir);
        if (targetDir.exists() && targetDir.isDirectory() && targetDir.list() != null && targetDir.list().length > 0) {
            return targetDir;
        }
        copyAssetFolder(context.getAssets(), assetModelDir, targetDir.getAbsolutePath());
        return targetDir;
    }

    private void copyAssetFolder(AssetManager assetManager, String fromAssetPath, String toPath) throws Exception {
        String[] files = assetManager.list(fromAssetPath);
        if (files == null || files.length == 0) {
            throw new IllegalStateException("Missing asset folder: " + fromAssetPath);
        }
        File dir = new File(toPath);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create directory: " + toPath);
        }
        for (String file : files) {
            String sourcePath = fromAssetPath + "/" + file;
            String[] children = assetManager.list(sourcePath);
            if (children != null && children.length > 0) {
                copyAssetFolder(assetManager, sourcePath, toPath + "/" + file);
            } else {
                copyAsset(assetManager, sourcePath, toPath + "/" + file);
            }
        }
    }

    private void copyAsset(AssetManager assetManager, String fromAssetPath, String toPath) throws Exception {
        try (InputStream in = assetManager.open(fromAssetPath);
             FileOutputStream out = new FileOutputStream(toPath)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            out.flush();
        }
    }
}
