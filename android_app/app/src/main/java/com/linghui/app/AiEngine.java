package com.linghui.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final Handler mainHandler;
    private TextToSpeech tts;
    private boolean ttsReady;

    // 对话历史（最近 10 轮）
    private final List<JsonObject> chatHistory;
    private static final int MAX_HISTORY = 10;

    // LLM 后端配置
    private String llmBaseUrl = "http://localhost:11434";
    private String llmModel = "qwen2.5:3b";
    private String openAiKey = "";
    private boolean useOpenAi = false;

    public AiEngine(Context context) {
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

        Request request = new Request.Builder()
            .url(endpoint)
            .post(RequestBody.create(gson.toJson(requestBody), JSON))
            .header("Content-Type", "application/json")
            .apply(b -> {
                if (useOpenAi && !openAiKey.isEmpty()) {
                    b.header("Authorization", "Bearer " + openAiKey);
                }
            })
            .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(Call call, IOException e) {
                mainHandler.post(() -> callback.onError("LLM 请求失败: " + e.getMessage()));
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

    // ---------- ASR 占位 ----------
    public void startListening(ListenCallback callback) {
        callback.onReady();
    }

    // ---------- 配置 ----------
    public void setLlmBackend(String url, String model, boolean openAi) {
        this.llmBaseUrl = url;
        this.llmModel = model;
        this.useOpenAi = openAi;
    }

    public void setOpenAiKey(String key) { this.openAiKey = key; }

    public void shutdown() {
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
}
