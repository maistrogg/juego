package com.interprete.espanol;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 7;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService net = Executors.newFixedThreadPool(3);

    private SpeechRecognizer rec;
    private boolean listening = false;
    private String lang = "ja";
    private String pendingLang = null;
    private int errorStreak = 0;

    private TextToSpeech tts;
    private boolean ttsReady = false;

    private boolean pageReady = false;
    private String pendingText = null;

    private final Runnable restart = new Runnable() {
        @Override public void run() { if (listening) listenOnce(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        if (!night && Build.VERSION.SDK_INT >= 27) {
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }

        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setTextZoom(100);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = true;
                if (pendingText != null) { deliverText(pendingText); pendingText = null; }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException ignored) {}
                return true;
            }
        });
        web.loadUrl("file:///android_asset/index.html");

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(new Locale("es", "MX"));
                if (r < 0) r = tts.setLanguage(new Locale("es", "US"));
                if (r < 0) r = tts.setLanguage(new Locale("es"));
                ttsReady = r >= 0;
            }
        });

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent == null) return;
        CharSequence text = null;
        if (Intent.ACTION_PROCESS_TEXT.equals(intent.getAction())) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        }
        if (text == null || text.toString().trim().isEmpty()) return;
        String t = text.toString();
        if (pageReady) deliverText(t); else pendingText = t;
    }

    private void deliverText(String t) {
        js("App.addExternalText(" + JSONObject.quote(t) + ")");
    }

    private void js(final String code) {
        ui.post(() -> { if (web != null) web.evaluateJavascript("try{" + code + "}catch(e){}", null); });
    }

    /* ---------------- Speech recognition ---------------- */

    private static String speechTag(String code) {
        switch (code) {
            case "en": return "en-US";
            case "ja": return "ja-JP";
            case "ko": return "ko-KR";
            case "zh": return "zh-CN";
            case "ru": return "ru-RU";
            case "de": return "de-DE";
            case "fr": return "fr-FR";
            case "pt": return "pt-BR";
            case "it": return "it-IT";
            default: return code;
        }
    }

    private void state(String st, String reason) {
        js("App.onState(" + JSONObject.quote(st) + "," + (reason == null ? "null" : JSONObject.quote(reason)) + ")");
    }

    private void startRec(String l) {
        lang = l;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = l;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            state("idle", "no_service");
            return;
        }
        if (rec == null) {
            rec = SpeechRecognizer.createSpeechRecognizer(this);
            rec.setRecognitionListener(listener);
        } else {
            try { rec.cancel(); } catch (Exception ignored) {}
        }
        listening = true;
        errorStreak = 0;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        state("starting", null);
        listenOnce();
    }

    private void listenOnce() {
        if (rec == null) return;
        String tag = speechTag(lang);
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
        try {
            rec.startListening(i);
        } catch (Exception e) {
            ui.postDelayed(restart, 500);
        }
    }

    private void stopRec(String reason) {
        boolean was = listening;
        listening = false;
        ui.removeCallbacks(restart);
        if (rec != null) { try { rec.cancel(); } catch (Exception ignored) {} }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (was || reason != null) state("idle", reason);
    }

    private static String first(Bundle b) {
        if (b == null) return "";
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return (r == null || r.isEmpty() || r.get(0) == null) ? "" : r.get(0);
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override public void onReadyForSpeech(Bundle params) { if (listening) state("listening", null); }
        @Override public void onBeginningOfSpeech() {}
        @Override public void onRmsChanged(float rmsdB) {}
        @Override public void onBufferReceived(byte[] buffer) {}
        @Override public void onEndOfSpeech() {}
        @Override public void onEvent(int eventType, Bundle params) {}

        @Override public void onPartialResults(Bundle b) {
            String t = first(b);
            if (!t.isEmpty()) { errorStreak = 0; js("App.onPartial(" + JSONObject.quote(t) + ")"); }
        }

        @Override public void onResults(Bundle b) {
            String t = first(b);
            errorStreak = 0;
            js("App.onFinal(" + JSONObject.quote(t) + ")");
            if (listening) ui.postDelayed(restart, 80);
        }

        @Override public void onError(int error) {
            if (!listening) return;
            switch (error) {
                case SpeechRecognizer.ERROR_NO_MATCH:
                case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                    js("App.onFinal(\"\")");
                    ui.postDelayed(restart, 80);
                    return;
                case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                    stopRec("not_granted");
                    return;
                case SpeechRecognizer.ERROR_AUDIO:
                    stopRec("audio");
                    return;
                case 12: // ERROR_LANGUAGE_NOT_SUPPORTED (API 31)
                case 13: // ERROR_LANGUAGE_UNAVAILABLE (API 31)
                    stopRec("unavailable");
                    return;
                case SpeechRecognizer.ERROR_NETWORK:
                case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                    if (++errorStreak > 4) { stopRec("network"); return; }
                    ui.postDelayed(restart, 1000L * errorStreak);
                    return;
                default: // busy, client, server, disconnected...
                    if (++errorStreak > 8) { stopRec("error"); return; }
                    try { rec.cancel(); } catch (Exception ignored) {}
                    ui.postDelayed(restart, 300);
            }
        }
    };

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_MIC) return;
        String l = pendingLang;
        pendingLang = null;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && l != null) {
            startRec(l);
        } else {
            state("idle", "not_granted");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (listening) stopRec("hidden");
    }

    @Override
    protected void onDestroy() {
        listening = false;
        ui.removeCallbacksAndMessages(null);
        if (rec != null) { try { rec.destroy(); } catch (Exception ignored) {} rec = null; }
        if (tts != null) { tts.shutdown(); tts = null; }
        net.shutdownNow();
        if (web != null) { web.destroy(); web = null; }
        super.onDestroy();
    }

    /* ---------------- Translation ---------------- */

    private static String gtCode(String c) {
        if (c == null || c.isEmpty()) return "auto";
        if (c.equals("zh")) return "zh-CN";
        return c;
    }

    private static String httpPost(String url, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36");
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
            byte[] data = body.getBytes("UTF-8");
            try (OutputStream os = c.getOutputStream()) { os.write(data); }
            int code = c.getResponseCode();
            if (code == 429) throw new Exception("rate");
            if (code != 200) throw new Exception("http" + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return bo.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }

    private static JSONObject translateChunk(String text, String src) throws Exception {
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=" + gtCode(src) + "&tl=es&dt=t&dt=rm";
        String body = httpPost(url, "q=" + URLEncoder.encode(text, "UTF-8"));
        JSONArray root = new JSONArray(body);
        JSONArray sents = root.optJSONArray(0);
        StringBuilder tr = new StringBuilder();
        StringBuilder rd = new StringBuilder();
        if (sents != null) {
            for (int i = 0; i < sents.length(); i++) {
                JSONArray s = sents.optJSONArray(i);
                if (s == null) continue;
                Object a = s.opt(0);
                if (a instanceof String) {
                    tr.append((String) a);
                } else if (s.length() > 3 && s.opt(3) instanceof String) {
                    if (rd.length() > 0) rd.append(' ');
                    rd.append(s.optString(3));
                }
            }
        }
        JSONObject out = new JSONObject();
        out.put("translation", tr.toString().trim());
        out.put("reading", rd.toString().trim());
        out.put("detected", root.optString(2, ""));
        return out;
    }

    private void doTranslate(final String id, final String text, final String src) {
        net.execute(() -> {
            try {
                JSONObject res;
                if (text.length() <= 4500) {
                    res = translateChunk(text, src);
                } else {
                    StringBuilder tr = new StringBuilder();
                    String det = "";
                    String[] parts = text.split("(?<=[\\n。.!?！？])");
                    StringBuilder chunk = new StringBuilder();
                    ArrayList<String> chunks = new ArrayList<>();
                    for (String p : parts) {
                        if (chunk.length() + p.length() > 4000 && chunk.length() > 0) { chunks.add(chunk.toString()); chunk.setLength(0); }
                        chunk.append(p);
                    }
                    if (chunk.length() > 0) chunks.add(chunk.toString());
                    for (String ch : chunks) {
                        JSONObject r = translateChunk(ch, src);
                        tr.append(r.optString("translation")).append(' ');
                        if (det.isEmpty()) det = r.optString("detected");
                    }
                    res = new JSONObject();
                    res.put("translation", tr.toString().trim());
                    res.put("reading", "");
                    res.put("detected", det);
                }
                if (res.optString("translation").isEmpty()) throw new Exception("empty");
                js("App.onTranslated(" + JSONObject.quote(id) + "," + res.toString() + ")");
            } catch (Exception e) {
                String m = e.getMessage() == null ? "" : e.getMessage();
                String code = m.equals("rate") ? "rate" : (e instanceof java.io.IOException ? "network" : "other");
                js("App.onTranslateError(" + JSONObject.quote(id) + "," + JSONObject.quote(code) + ")");
            }
        });
    }

    /* ---------------- JS bridge ---------------- */

    private class Bridge {
        @JavascriptInterface public boolean speechAvailable() {
            return SpeechRecognizer.isRecognitionAvailable(MainActivity.this);
        }
        @JavascriptInterface public void startListening(final String l) {
            ui.post(() -> startRec(l == null ? "ja" : l));
        }
        @JavascriptInterface public void stopListening() {
            ui.post(() -> stopRec("stopped"));
        }
        @JavascriptInterface public void translate(String id, String text, String src) {
            if (id == null || text == null) return;
            doTranslate(id, text, src);
        }
        @JavascriptInterface public boolean speak(final String text) {
            if (!ttsReady || tts == null || text == null) return false;
            ui.post(() -> { if (tts != null) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tr"); });
            return true;
        }
        @JavascriptInterface public void stopSpeaking() {
            ui.post(() -> { if (tts != null) tts.stop(); });
        }
        @JavascriptInterface public void copy(final String text) {
            ui.post(() -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Traducción", text));
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(MainActivity.this, "Copiado", Toast.LENGTH_SHORT).show();
            });
        }
        @JavascriptInterface public void openTtsSettings() {
            ui.post(() -> {
                try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); } catch (Exception ignored) {}
            });
        }
    }
}
