package com.linghui.app.comm;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AI 管线通信处理器 — 双向消息路由
 *
 * 连接 Python AI 管线 ↔ Java 渲染层 ↔ Java 自动化层
 */
public class AiCommHandler {

    public interface RenderCallback {
        void onMood(String mood);
        void onTalking(boolean talking, float intensity);
        void onReply(String text);
    }

    public interface AiCallback {
        void onUserTap();
        void onUserLongPress();
    }

    public interface ActionCallback {
        void onAction(String actionType, JSONObject params);
    }

    public interface ActionResultCallback {
        void onActionResult(String action, boolean success, String message);
    }

    private RenderCallback renderCb;
    private AiCallback aiCb;
    private ActionCallback actionCb;
    private ActionResultCallback resultCb;
    private String pipelineUrl = "http://localhost:8800";
    private Thread pollingThread;
    private volatile boolean isPolling;
    private int pollingIntervalMs = 500;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void setRenderCallback(RenderCallback cb) { this.renderCb = cb; }
    public void setAiCallback(AiCallback cb)         { this.aiCb = cb; }
    public void setActionCallback(ActionCallback cb) { this.actionCb = cb; }
    public void setActionResultCallback(ActionResultCallback cb) { this.resultCb = cb; }
    public void setPipelineUrl(String url)           { this.pipelineUrl = url; }
    public void setPollingInterval(int intervalMs)  { this.pollingIntervalMs = intervalMs; }

    /**
     * 启动消息轮询 — 后台线程定时从 Python 桥拉取消息
     */
    public void startPolling() {
        if (isPolling) return;
        isPolling = true;
        pollingThread = new Thread(() -> {
            Log.i("AiCommHandler", "轮询线程启动, 间隔=" + pollingIntervalMs + "ms");
            while (isPolling) {
                try {
                    pollMessages();
                    Thread.sleep(pollingIntervalMs);
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    Log.w("AiCommHandler", "轮询异常: " + e.getMessage());
                    try { Thread.sleep(pollingIntervalMs * 2); }
                    catch (InterruptedException ignored) { break; }
                }
            }
            Log.i("AiCommHandler", "轮询线程停止");
        });
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    /**
     * 停止消息轮询
     */
    public void stopPolling() {
        isPolling = false;
        if (pollingThread != null) {
            pollingThread.interrupt();
            pollingThread = null;
        }
    }

    /**
     * 执行一次轮询：GET /messages → 逐条送入 onAiMessage
     */
    private void pollMessages() {
        try {
            java.net.URL url = new java.net.URL(pipelineUrl + "/messages");
            java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);

            int code = conn.getResponseCode();
            if (code != 200) return;

            java.io.InputStream is = conn.getInputStream();
            java.util.Scanner s = new java.util.Scanner(is, "UTF-8").useDelimiter("\\A");
            String body = s.hasNext() ? s.next() : "{}";
            s.close();
            conn.disconnect();

            JSONObject resp = new JSONObject(body);
            JSONArray msgs = resp.optJSONArray("messages");
            if (msgs == null || msgs.length() == 0) return;

            for (int i = 0; i < msgs.length(); i++) {
                onAiMessage(msgs.getJSONObject(i).toString());
            }
        } catch (java.net.SocketTimeoutException e) {
            // 超时正常，管道可能未就绪
        } catch (Exception e) {
            Log.w("AiCommHandler", "拉取消息失败: " + e.getMessage());
        }
    }

    /**
     * 接收来自 AI 管线的消息（Python 端通过 HTTP/WebSocket 发送）
     */
    public void onAiMessage(String json) {
        try {
            JSONObject msg = new JSONObject(json);
            String type = msg.optString("type", "");

            switch (type) {
                case LingHuiProtocol.TYPE_MOOD:
                    if (renderCb != null) {
                        String mood = msg.optString("mood", LingHuiProtocol.MOOD_NEUTRAL);
                        mainHandler.post(() -> renderCb.onMood(mood));
                    }
                    break;

                case LingHuiProtocol.TYPE_TALKING:
                    if (renderCb != null) {
                        boolean talking = msg.optBoolean("talking", false);
                        double intensity = msg.optDouble("talkIntensity", 0.5);
                        mainHandler.post(() -> renderCb.onTalking(talking, (float) intensity));
                    }
                    break;

                case LingHuiProtocol.TYPE_REPLY:
                    if (renderCb != null) {
                        String text = msg.optString("replyText", "");
                        mainHandler.post(() -> renderCb.onReply(text));
                    }
                    break;

                case LingHuiProtocol.TYPE_ACTION:
                    if (actionCb != null) {
                        String action = msg.optString("action", "");
                        JSONObject params = msg.optJSONObject("params");
                        mainHandler.post(() -> actionCb.onAction(action, params));
                    }
                    break;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 用户点击悬浮窗 → 通知 AI 管线
     */
    public void onUserTap() {
        if (aiCb != null) mainHandler.post(() -> aiCb.onUserTap());
    }

    /**
     * 用户长按悬浮窗 → 通知 AI 管线
     */
    public void onUserLongPress() {
        if (aiCb != null) mainHandler.post(() -> aiCb.onUserLongPress());
    }

    /**
     * 自动化操作完成 → 通知 AI 管线和本地监听器
     */
    public void onActionResult(String action, boolean success, String message) {
        // 1. 通知本地回调（OverlayService 等）
        if (resultCb != null) {
            mainHandler.post(() -> resultCb.onActionResult(action, success, message));
        }

        // 2. 回传给 Python AI 管线（HTTP POST）
        sendToPipeline(action, success, message);
    }

    /**
     * 将操作结果通过 HTTP POST 发送到 Python AI 管线
     */
    private void sendToPipeline(String action, boolean success, String message) {
        try {
            java.net.URL url = new java.net.URL(pipelineUrl + "/action_result");
            java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            JSONObject payload = LingHuiProtocol.actionResultMessage(action, success, message);
            java.io.OutputStream os = conn.getOutputStream();
            os.write(payload.toString().getBytes("UTF-8"));
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            if (code != 200) {
                android.util.Log.w("AiCommHandler",
                    "Pipeline non-200: " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            android.util.Log.w("AiCommHandler",
                "Pipeline send-back failed: " + e.getMessage());
        }
    }
}
