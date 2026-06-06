package com.linghui.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** AI 引擎：LLM 对话 + TTS 语音合成 + ASR 语音识别占位 */
public class AiEngine {

    private static final String TAG = "AiEngine";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // 角色人格 — 灵绘的系统提示词
    private static final String CHARACTER_PROMPT =
        "你是「灵绘」，一个生活在手机屏幕里的虚拟精灵。" +
        "你性格温柔活泼，好奇心强，喜欢帮助主人。" +
        "你说话自然亲切，偶尔会加一点可爱的语气词。" +
        "你可以帮主人查天气、定闹钟、打开应用、回答问题。" +
        "每次回答控制在 2-3 句话，保持简洁亲切。";

    // 离线兜底回复（无网络时用）
    private static final java.util.Map<String, String[]> FALLBACK = new java.util.LinkedHashMap<>();
    static {
        FALLBACK.put("你好|嗨|哈喽|hi|hello", new String[]{
            "嗨！灵绘在此～有什么需要帮忙的吗？✨",
            "主人好呀！今天想让我帮你做什么？",
            "你好你好！灵绘元气满满地上线了！",
        });
        FALLBACK.put("你是谁|介绍|叫什么", new String[]{
            "我叫灵绘，是你手机里的桌面精灵！温柔又靠谱的那种～",
            "我是灵绘呀，你的虚拟桌面助手！",
        });
        FALLBACK.put("笑话|段子|搞笑", new String[]{
            "为什么程序员总在万圣节上班？因为他们怕 return 0！👻",
            "电脑和空调的区别：空调插电就凉快，电脑插电就发烧 🔥",
        });
        FALLBACK.put("谢谢|多谢|感谢", new String[]{
            "不客气！这是我应该做的～💕",
            "主人开心就好！有什么需要随时叫我哦～",
        });
        FALLBACK.put("天气|温度", new String[]{
            "我现在还没接天气 API 呢，不过可以帮你用浏览器搜一下！",
            "天气的话…建议拉开窗帘亲自看看 😄",
        });
    }

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final Handler mainHandler;
    private final Context appContext;
    private SpeechRecognizer speechRecognizer;
    private boolean isListening;
    private boolean isDetectingWakeWord;
    private String wakeWord = "灵绘";
    private WakeWordCallback wakeWordCb;
    private TextToSpeech tts;
    private boolean ttsReady;

    // 对话历史（最近 10 轮）
    private final List<JsonObject> chatHistory;
    private static final int MAX_HISTORY = 10;

    // LLM 后端配置
    private String llmBaseUrl = "http://localhost:8800";
    private String llmModel = "codex";
    private String openAiKey = "";
    private boolean useOpenAi = false;

    public AiEngine(Context context) {
        this.appContext = context.getApplicationContext();
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();
        this.gson = new Gson();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.chatHistory = new ArrayList<>();
        initTts(context);
    }

    // ---------- TTS 初始化 ----------
    private void initTts(Context context) {
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
                tts.setSpeechRate(1.0f);
                ttsReady = true;
                Log.i(TAG, "TTS 初始化成功");
            } else {
                Log.e(TAG, "TTS 初始化失败: " + status);
            }
        });
    }

    // ---------- LLM 对话 ----------
    public void chat(String userMessage, ChatCallback callback) {
        JsonObject requestBody = useOpenAi
            ? buildOpenAiBody(userMessage)
            : buildOllamaBody(userMessage);

        String endpoint = useOpenAi
            ? llmBaseUrl + "/v1/chat/completions"
            : llmBaseUrl + "/api/chat";

        Request.Builder builder = new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(gson.toJson(requestBody), JSON))
            .header("Content-Type", "application/json");
        if (useOpenAi && !openAiKey.isEmpty()) {
            builder.header("Authorization", "Bearer " + openAiKey);
        }
        Request request = builder.build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                String fallback = getFallbackReply(userMessage);
                if (fallback != null) {
                    addToHistory("user", userMessage);
                    addToHistory("assistant", fallback);
                    mainHandler.post(() -> callback.onReply(fallback));
                } else {
                    mainHandler.post(() -> callback.onError("LLM request failed: " + e.getMessage()));
                }
            }
            @Override public void onResponse(Call call, Response response) throws IOException {
                String reply = parseReply(response.body().string());
                addToHistory("user", userMessage);
                addToHistory("assistant", reply);
                mainHandler.post(() -> callback.onReply(reply));
            }
        });
    }

    private JsonObject buildOllamaBody(String userMessage) {
        JsonObject body = new JsonObject();
        body.addProperty("model", llmModel);
        body.addProperty("stream", false);
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", CHARACTER_PROMPT);
        msgs.add(sys);
        for (JsonObject h : chatHistory) msgs.add(h);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        msgs.add(user);
        body.add("messages", msgs);
        return body;
    }

    private JsonObject buildOpenAiBody(String userMessage) {
        JsonObject body = new JsonObject();
        body.addProperty("model", llmModel);
        JsonArray msgs = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", CHARACTER_PROMPT);
        msgs.add(sys);
        for (JsonObject h : chatHistory) msgs.add(h);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", userMessage);
        msgs.add(user);
        body.add("messages", msgs);
        return body;
    }

    private String parseReply(String responseBody) {
        try {
            JsonObject resp = gson.fromJson(responseBody, JsonObject.class);
            if (useOpenAi && resp.has("choices")) {
                return resp.getAsJsonArray("choices").get(0)
                    .getAsJsonObject().getAsJsonObject("message")
                    .get("content").getAsString();
            }
            if (resp.has("message")) {
                return resp.getAsJsonObject("message")
                    .get("content").getAsString();
            }
        } catch (Exception e) {
            Log.w(TAG, "解析回复失败: " + e.getMessage());
        }
        return "（唔…灵绘好像有点走神了，再说一次好吗？）";
    }

    private String getFallbackReply(String input) {
        for (java.util.Map.Entry<String, String[]> e : FALLBACK.entrySet()) {
            if (java.util.regex.Pattern.compile(e.getKey()).matcher(input).find()) {
                String[] replies = e.getValue();
                return replies[new java.util.Random().nextInt(replies.length)];
            }
        }
        return null;
    }

    private void addToHistory(String role, String content) {
        JsonObject msg = new JsonObject();
        msg.addProperty("role", role);
        msg.addProperty("content", content);
        chatHistory.add(msg);
        while (chatHistory.size() > MAX_HISTORY) chatHistory.remove(0);
    }

    // ---------- TTS 播报 ----------
    public void speak(String text) {
        if (ttsReady && tts != null) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "linghui_tts_" + System.currentTimeMillis());
        }
    }

    // ---------- ASR 语音识别 ----------
    public void startListening(ListenCallback callback) {
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            callback.onError("Speech recognition unavailable");
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {
                isListening = true;
                mainHandler.post(() -> callback.onReady());
            }
            @Override public void onBeginningOfSpeech() {
                Log.d(TAG, "用户开始说话");
            }
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {
                isListening = false;
                Log.d(TAG, "用户停止说话");
            }
            @Override public void onError(int error) {
                isListening = false;
                String msg;
                switch (error) {
                    case SpeechRecognizer.ERROR_NETWORK:
                        msg = "Network error, check connection"; break;
                    case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                        msg = "Network timeout"; break;
                    case SpeechRecognizer.ERROR_NO_MATCH:
                        msg = "Didn't catch that, try again?"; break;
                    case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                        msg = "Speaking too long"; break;
                    case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                        msg = "Speech engine busy"; break;
                    case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                        msg = "Microphone permission missing"; break;
                    default:
                        msg = "Speech recognition error (" + error + ")"; break;
                }
                Log.w(TAG, "ASR 错误: " + msg);
                mainHandler.post(() -> callback.onError(msg));
            }
            @Override public void onResults(Bundle results) {
                isListening = false;
                java.util.ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && !matches.isEmpty()) {
                    String transcript = matches.get(0);
                    Log.i(TAG, "识别结果: " + transcript);
                    mainHandler.post(() -> callback.onResult(transcript));
                } else {
                    mainHandler.post(() -> callback.onError("未能识别语音"));
                }
            }
            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        speechRecognizer.startListening(intent);
    }

    public void stopListening() {
        if (speechRecognizer != null && isListening) {
            speechRecognizer.stopListening();
            isListening = false;
        }
    }

    public boolean isListening() {
        return isListening;
    }

    // ---------- 唤醒词检测 ----------

    public void setWakeWord(String word) {
        this.wakeWord = word;
    }

    /**
     * 启动唤醒词连续检测 — 后台持续监听，命中唤醒词后回调
     *
     * 原理：循环调用 SpeechRecognizer，每次识别结果检查是否含唤醒词。
     * 命中后自动停止循环，适合低功耗后台值守。
     */
    public void startWakeWordDetection(WakeWordCallback callback) {
        if (isDetectingWakeWord) return;
        isDetectingWakeWord = true;
        this.wakeWordCb = callback;
        Log.i(TAG, "唤醒词检测启动: \"" + wakeWord + "\"");
        runWakeWordLoop();
    }

    public void stopWakeWordDetection() {
        isDetectingWakeWord = false;
        stopListening();
        Log.i(TAG, "唤醒词检测停止");
    }

    public boolean isDetectingWakeWord() {
        return isDetectingWakeWord;
    }

    private void runWakeWordLoop() {
        if (!isDetectingWakeWord) return;

        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            // 不可用时延迟重试
            mainHandler.postDelayed(this::runWakeWordLoop, 3000);
            return;
        }

        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(appContext);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}

            @Override public void onError(int error) {
                // 出错后延迟重试
                if (isDetectingWakeWord) {
                    mainHandler.postDelayed(() -> runWakeWordLoop(), 1500);
                }
            }

            @Override public void onResults(Bundle results) {
                java.util.ArrayList<String> matches =
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null) {
                    for (String text : matches) {
                        if (text != null && text.contains(wakeWord)) {
                            Log.i(TAG, "🎯 检测到唤醒词: " + text);
                            isDetectingWakeWord = false;
                            if (wakeWordCb != null) {
                                mainHandler.post(() -> wakeWordCb.onWakeWordDetected(text));
                            }
                            return;
                        }
                    }
                }
                // 未命中，继续循环
                if (isDetectingWakeWord) {
                    mainHandler.postDelayed(() -> runWakeWordLoop(), 800);
                }
            }

            @Override public void onPartialResults(Bundle partialResults) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        });

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);

        speechRecognizer.startListening(intent);
    }

    // ---------- 配置 ----------
    public void setLlmBackend(String url, String model, boolean openAi) {
        this.llmBaseUrl = url;
        this.llmModel = model;
        this.useOpenAi = openAi;
    }

    public void setOpenAiKey(String key) { this.openAiKey = key; }

    public void shutdown() {
        isDetectingWakeWord = false;
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
            speechRecognizer = null;
        }
        if (tts != null) { tts.stop(); tts.shutdown(); }
    }

    // ---------- 回调接口 ----------
    public interface ChatCallback {
        void onReply(String reply);
        void onError(String error);
    }

    public interface ListenCallback {
        void onReady();
        void onResult(String transcript);
        void onError(String error);
    }

    public interface WakeWordCallback {
        void onWakeWordDetected(String transcript);
    }
}
