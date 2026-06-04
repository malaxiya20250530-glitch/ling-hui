package com.linghui.app.comm;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 灵绘跨层通信协议 — AI 管线 ↔ 渲染层 ↔ 自动化层
 *
 * 消息格式：JSON，type 字段标识消息类型
 *
 * 方向：
 *   AI → 渲染:  mood, talking, reply
 *   渲染 → AI:  tap, long_press
 *   AI → 自动化: action
 *   自动化 → AI:  action_result
 */
public class LingHuiProtocol {

    // ── 消息类型 ────────────────────────────────────
    public static final String TYPE_MOOD          = "mood";
    public static final String TYPE_TALKING       = "talking";
    public static final String TYPE_REPLY         = "reply";
    public static final String TYPE_TAP           = "tap";
    public static final String TYPE_LONG_PRESS    = "long_press";
    public static final String TYPE_ACTION        = "action";
    public static final String TYPE_ACTION_RESULT = "action_result";

    // ── 情绪值 ──────────────────────────────────────
    public static final String MOOD_NEUTRAL   = "neutral";
    public static final String MOOD_HAPPY     = "happy";
    public static final String MOOD_CURIOUS   = "curious";
    public static final String MOOD_CONCERNED = "concerned";
    public static final String MOOD_TIRED     = "tired";
    public static final String MOOD_PLAYFUL   = "playful";

    /**
     * AI → 渲染层：情绪更新
     */
    public static JSONObject moodMessage(String mood) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", TYPE_MOOD);
            msg.put("mood", mood);
            return msg;
        } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * AI → 渲染层：说话状态
     */
    public static JSONObject talkingMessage(boolean talking, float intensity) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", TYPE_TALKING);
            msg.put("talking", talking);
            msg.put("talkIntensity", intensity);
            return msg;
        } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * AI → 渲染层：LLM 回复
     */
    public static JSONObject replyMessage(String text, String actionType, String actionResult) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", TYPE_REPLY);
            msg.put("replyText", text);
            msg.put("actionType", actionType != null ? actionType : "");
            msg.put("actionResult", actionResult != null ? actionResult : "");
            return msg;
        } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * 渲染层 → AI：用户点击交互
     */
    public static JSONObject tapMessage() {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", TYPE_TAP);
            return msg;
        } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * AI → 自动化层：执行操作
     */
    public static JSONObject actionMessage(String actionType, JSONObject params) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", TYPE_ACTION);
            msg.put("action", actionType);
            msg.put("params", params != null ? params : new JSONObject());
            return msg;
        } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * 自动化层 → AI：操作结果
     */
    public static JSONObject actionResultMessage(String action, boolean success, String message) {
        try {
            JSONObject msg = new JSONObject();
            msg.put("type", TYPE_ACTION_RESULT);
            msg.put("action", action);
            msg.put("success", success);
            msg.put("message", message);
            return msg;
        } catch (JSONException e) { return new JSONObject(); }
    }

    /**
     * 将 Python 情绪值映射到 Java 表情字符串
     */
    public static String pyMoodToExpression(String pyMood) {
        if (pyMood == null) return MOOD_NEUTRAL;
        switch (pyMood) {
            case "happy":     return MOOD_HAPPY;
            case "curious":   return MOOD_CURIOUS;
            case "concerned": return MOOD_CONCERNED;
            case "tired":     return MOOD_TIRED;
            case "playful":   return MOOD_PLAYFUL;
            default:          return MOOD_NEUTRAL;
        }
    }
}
