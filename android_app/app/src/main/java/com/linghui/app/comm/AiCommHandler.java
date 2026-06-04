package com.linghui.app.comm;

import android.os.Handler;
import android.os.Looper;
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

    private RenderCallback renderCb;
    private AiCallback aiCb;
    private ActionCallback actionCb;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public void setRenderCallback(RenderCallback cb) { this.renderCb = cb; }
    public void setAiCallback(AiCallback cb)         { this.aiCb = cb; }
    public void setActionCallback(ActionCallback cb) { this.actionCb = cb; }

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
     * 自动化操作完成 → 通知 AI 管线
     */
    public void onActionResult(String action, boolean success, String message) {
        // TODO: 回传给 Python AI 管线（HTTP/WebSocket）
    }
}
