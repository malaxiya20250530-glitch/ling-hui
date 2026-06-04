package com.linghui.app;

import android.content.Context;
import android.view.Gravity;
import android.widget.FrameLayout;

/**
 * Unity 3D 角色渲染视图 —— 封装 UnityPlayer
 * 与 LingHuiGLView 接口一致，OverlayService 可无缝切换
 */
public class UnityPlayerView extends FrameLayout implements ICharacterView {

    private Object unityPlayer;  // com.unity3d.player.UnityPlayer（运行时反射，避免编译依赖）

    public UnityPlayerView(Context context) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));
        setClipChildren(false);

        // 尝试通过反射初始化 UnityPlayer（仅在 unityLibrary 已集成时生效）
        try {
            Class<?> unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
            unityPlayer = unityPlayerClass.getConstructor(Context.class).newInstance(context);
            addView((android.view.View) unityPlayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        } catch (Exception e) {
            // Unity 未集成时静默降级 —— OverlayService 会自动切回 GLView
        }
    }

    /** 用户点击互动 → Unity 角色跳一跳 */
    public void onInteract() {
        sendToUnity("AiBridge", "OnUserInteract", "");
        sendToUnity("AiBridge", "OnMoodChanged", "excited");
    }

    /** AI 回复到达 → Unity 角色开心 */
    public void onReplyReceived() {
        sendToUnity("AiBridge", "OnAiReply", "reply");
        sendToUnity("AiBridge", "OnMoodChanged", "happy");
    }

    /** 空闲恢复 */
    public void onIdle() {
        sendToUnity("AiBridge", "OnIdle", "");
    }

    /** 检测 Unity 是否可用 */
    public boolean isUnityAvailable() {
        return unityPlayer != null;
    }

    /** 暂停 Unity */
    public void pause() {
        try { unityPlayer.getClass().getMethod("pause").invoke(unityPlayer); } catch (Exception ignored) {}
    }

    /** 恢复 Unity */
    public void resume() {
        try { unityPlayer.getClass().getMethod("resume").invoke(unityPlayer); } catch (Exception ignored) {}
    }

    /** 销毁 Unity */
    public void destroy() {
        try {
            unityPlayer.getClass().getMethod("quit").invoke(unityPlayer);
            removeAllViews();
        } catch (Exception ignored) {}
    }

    // ── 内部工具 ──
    private void sendToUnity(String gameObject, String method, String message) {
        if (unityPlayer == null) return;
        try {
            Class<?> unityPlayerClass = Class.forName("com.unity3d.player.UnityPlayer");
            java.lang.reflect.Method unitySend = unityPlayerClass.getMethod(
                "UnitySendMessage", String.class, String.class, String.class);
            unitySend.invoke(null, gameObject, method, message);
        } catch (Exception ignored) {}
    }
}
