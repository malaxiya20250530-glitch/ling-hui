package com.linghui.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.core.app.NotificationCompat;
import com.linghui.app.comm.AiCommHandler;

/** 悬浮窗前台服务：管理模式(Unity/OpenGL)的显示、拖拽和交互 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "linghui_overlay";
    private static final int NOTIFY_ID = 1001;

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private ICharacterView charView;   // 统一接口 —— Unity 或 GL
    private boolean useUnity;          // 当前渲染模式
    private LinearLayout chatBubble;
    private TextView chatText;
    private AiEngine aiEngine;
    private AiCommHandler commHandler;

    // 拖拽
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging;
    private static final int DRAG_THRESHOLD = 10;

    // 对话气泡
    private boolean bubbleVisible;

    // 自动漫游
    private android.os.Handler roamHandler;
    private WindowManager.LayoutParams wmParams;
    private float roamTime;
    private float roamSpeedX = 0.8f, roamSpeedY = 1.1f;
    private float roamAmpX = 60f, roamAmpY = 50f;
    private boolean isRoaming = true;
    private int screenW, screenH, charSize;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "灵绘悬浮窗服务启动");

        aiEngine = new AiEngine(this);
        commHandler = new AiCommHandler();

        // 渲染回调 — 情绪/说话状态/回复
        commHandler.setRenderCallback(new AiCommHandler.RenderCallback() {
            @Override public void onMood(String mood) {
                Log.d(TAG, "情绪更新: " + mood);
                if (charView != null) charView.onInteract();
            }
            @Override public void onTalking(boolean talking, float intensity) {
                if (charView != null) {
                    if (talking) charView.onInteract(); else charView.onIdle();
                }
            }
            @Override public void onReply(String text) {
                showBubble(text);
                aiEngine.speak(text);
                if (charView != null) charView.onReplyReceived();
            }
        });

        // AI 回调 — 用户交互事件
        commHandler.setAiCallback(new AiCommHandler.AiCallback() {
            @Override public void onUserTap() {
                onOverlayClick();
            }
            @Override public void onUserLongPress() {
                showBubble("长按我干嘛啦~ 😆");
            }
        });

        // 操作回调 — 执行自动化操作
        commHandler.setActionCallback((action, params) -> {
            Log.i(TAG, "收到操作指令: " + action);
            String resultMsg = executeAction(action, params);
            commHandler.onActionResult(action, resultMsg != null, resultMsg != null ? resultMsg : getString(R.string.action_unknown));
        });

        // 操作结果回调
        commHandler.setActionResultCallback((action, success, message) -> {
            String bubbleText = success
                ? getString(R.string.action_success_prefix) + message
                : getString(R.string.action_fail_prefix) + action + getString(R.string.action_fail_suffix) + message;
            showBubble(bubbleText);
            Log.i(TAG, "操作结果: " + action + " success=" + success + " " + message);
        });

        // 启动轮询，从 Python 桥拉取消息
        commHandler.startPolling();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, buildNotification());
        }

        createOverlay();
    }

    // ---------- 通知栏 ----------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(getString(R.string.channel_description));
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pi)
            .setOngoing(true)
            .build();
    }

    // ---------- 悬浮窗创建（双模式）----------
    private void createOverlay() {
        overlayRoot = new FrameLayout(this);
        overlayRoot.setBackgroundColor(0x00000000);  // 透明

        // 优先尝试 Unity 渲染
        UnityPlayerView unityView = new UnityPlayerView(this);
        if (unityView.isUnityAvailable()) {
            charView = unityView;
            useUnity = true;
            Log.i(TAG, "🎮 使用 Unity 3D 渲染模式");
        } else {
            // 降级到 OpenGL 球体
            LingHuiGLView glView = new LingHuiGLView(this);
            charView = glView;
            useUnity = false;
            Log.i(TAG, "🔵 使用 OpenGL 渲染模式（Unity 未集成）");
        }

        charSize = dpToPx(100);
        FrameLayout.LayoutParams charParams = new FrameLayout.LayoutParams(charSize, charSize);
        charParams.gravity = Gravity.CENTER;
        overlayRoot.addView((View) charView, charParams);

        // 对话气泡 — 圆角渐变 + 阴影 + 淡入动画
        chatBubble = new LinearLayout(this);
        chatBubble.setOrientation(LinearLayout.VERTICAL);
        // 圆角渐变背景
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dpToPx(16));
        bg.setColors(new int[]{0xF07050A0, 0xF0404080}); // 紫→深紫
        bg.setStroke(dpToPx(1), 0x80FFFFFF); // 半透白边框
        chatBubble.setBackground(bg);
        chatBubble.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
        chatBubble.setElevation(dpToPx(6)); // 阴影
        chatText = new TextView(this);
        chatText.setTextColor(0xFFFFFFFF);
        chatText.setTextSize(13);
        chatText.setLineSpacing(dpToPx(2), 1f);
        chatText.setMaxWidth(dpToPx(220));
        chatText.setShadowLayer(1f, 0f, 1f, 0x40000000); // 文字阴影
        chatText.setText("✨ 你好呀~ 我是灵绘");
        chatBubble.addView(chatText);
        chatBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bubbleParams.topMargin = charSize + dpToPx(6);
        overlayRoot.addView(chatBubble, bubbleParams);

        // 窗口参数
        wmParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);

        wmParams.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics dm = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(dm);
        wmParams.x = dm.widthPixels / 2 - charSize / 2;
        wmParams.y = dm.heightPixels / 3;
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;

        overlayRoot.setOnTouchListener(new OverlayTouchListener(wmParams));
        overlayRoot.setOnClickListener(v -> onOverlayClick());

        windowManager.addView(overlayRoot, wmParams);
        startRoaming();
    }

    // ---------- 拖拽 ----------
    private class OverlayTouchListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams params;
        OverlayTouchListener(WindowManager.LayoutParams p) { this.params = p; }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x; initialY = params.y;
                    initialTouchX = event.getRawX(); initialTouchY = event.getRawY();
                    isDragging = false;
                    // 拖拽时暂停漫游
                    isRoaming = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - initialTouchX;
                    float dy = event.getRawY() - initialTouchY;
                    if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                        isDragging = true;
                    }
                    if (isDragging) {
                        params.x = initialX + (int) dx;
                        params.y = initialY + (int) dy;
                        windowManager.updateViewLayout(overlayRoot, params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!isDragging) v.performClick();
                    // 松手后恢复漫游
                    isRoaming = true;
                    return true;
            }
            return false;
        }
    }

    // ---------- 点击交互 ----------
    private void onOverlayClick() {
        if (bubbleVisible) { hideBubble(); return; }
        startVoiceConversation();
    }

    /**
     * 启动语音对话：ASR 监听 → LLM 思考 → TTS 播报
     */
    private void startVoiceConversation() {
        showBubble("正在听… 🎤");
        charView.onInteract();

        aiEngine.startListening(new AiEngine.ListenCallback() {
            @Override public void onReady() {
                showBubble("我在听呢~ 👂");
            }
            @Override public void onResult(String transcript) {
                showBubble("你说: " + transcript);
                charView.onReplyReceived();

                aiEngine.chat(transcript, new AiEngine.ChatCallback() {
                    @Override public void onReply(String reply) {
                        showBubble(reply);
                        aiEngine.speak(reply);
                        charView.onReplyReceived();
                    }
                    @Override public void onError(String error) {
                        showBubble(getString(R.string.chat_network_error));
                        Log.w(TAG, error);
                    }
                });
            }
            @Override public void onError(String error) {
                showBubble(error);
                charView.onIdle();
            }
        });
    }

    private void showBubble(String text) {
        chatText.setText("✨ " + text);
        chatBubble.setVisibility(View.VISIBLE);
        // 淡入动画
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(250);
        chatBubble.startAnimation(fadeIn);
        bubbleVisible = true;
        overlayRoot.postDelayed(this::hideBubble, 6000);
    }

    private void hideBubble() {
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                chatBubble.setVisibility(View.GONE);
            }
            @Override public void onAnimationRepeat(Animation a) {}
        });
        chatBubble.startAnimation(fadeOut);
        bubbleVisible = false;
        charView.onIdle();
    }

    // ---------- 工具 ----------
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- 不规则漫游 ----------
    private void startRoaming() {
        roamHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        roamTime = 0f;
        roamHandler.post(roamRunnable);
    }

    private Runnable roamRunnable = new Runnable() {
        @Override public void run() {
            if (!isRoaming || wmParams == null || overlayRoot == null) {
                if (roamHandler != null) roamHandler.postDelayed(this, 1000);
                return;
            }
            roamTime += 0.05f;
            // 叠加两个正弦波产生不规则运动
            float dx = (float)(Math.sin(roamTime * roamSpeedX) * roamAmpX
                             + Math.cos(roamTime * 0.37f) * roamAmpX * 0.5f);
            float dy = (float)(Math.cos(roamTime * roamSpeedY) * roamAmpY
                             + Math.sin(roamTime * 0.53f) * roamAmpY * 0.5f);

            int newX = Math.max(0, Math.min(screenW - charSize, wmParams.x + (int)dx));
            int newY = Math.max(0, Math.min(screenH - charSize, wmParams.y + (int)dy));
            wmParams.x = newX;
            wmParams.y = newY;

            try {
                windowManager.updateViewLayout(overlayRoot, wmParams);
            } catch (Exception ignored) {}

            if (roamHandler != null) roamHandler.postDelayed(this, 50); // 20fps
        }
    };

    @Override public IBinder onBind(Intent intent) { return null; }

    /**
     * 启动唤醒词检测 — 后台持续监听"灵绘"，命中后激活对话
     */
    private void startWakeWordDetection() {
        aiEngine.startWakeWordDetection(transcript -> {
            Log.i(TAG, "🎯 唤醒词命中: " + transcript);
            startVoiceConversation();
        });
    }

    /**
     * 执行自动化操作 — 调用 Android 系统 API
     */
    private String executeAction(String action, org.json.JSONObject params) {
        try {
            switch (action) {
                case "open_app": {
                    String appName = params != null ? params.optString("app_name", "") : "";
                    Intent launchIntent = getPackageManager()
                        .getLaunchIntentForPackage(resolvePackageName(appName));
                    if (launchIntent != null) {
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launchIntent);
                        return getString(R.string.action_app_opened) + appName;
                    }
                    // 回退：尝试用市场搜索
                    if (!appName.isEmpty()) {
                        Intent searchIntent = new Intent(Intent.ACTION_VIEW,
                            Uri.parse("market://search?q=" + appName));
                        searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try {
                            startActivity(searchIntent);
                            return getString(R.string.action_market_search) + appName;
                        } catch (Exception ignored) {}
                    }
                    return getString(R.string.action_app_not_found) + appName;
                }
                case "search": {
                    String query = params != null ? params.optString("query", "") : "";
                    Intent searchIntent = new Intent(Intent.ACTION_WEB_SEARCH);
                    searchIntent.putExtra("query", query);
                    searchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(searchIntent);
                    return getString(R.string.action_searching) + query;
                }
                case "notify": {
                    String title = params != null ? params.optString("title", getString(R.string.remind_default_title)) : getString(R.string.remind_default_title);
                    String content = params != null ? params.optString("content", "") : "";
                    sendLocalNotification(title, content);
                    return getString(R.string.action_remind_set) + content;
                }
                case "clipboard": {
                    String text = params != null ? params.optString("text", "") : "";
                    ClipboardManager clipboard = (ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
                    ClipData clip = ClipData.newPlainText("linghui", text);
                    clipboard.setPrimaryClip(clip);
                    return getString(R.string.action_clipboard_copied);
                }
                case "screenshot": {
                    // Android 14+ 截图需要 MediaProjection API
                    // 此处发出提示，完整实现需配合前台服务权限
                    showToast(getString(R.string.action_screenshot_permission));
                    return getString(R.string.action_screenshot_manual);
                }
                case "vibrate": {
                    int durationMs = params != null ? params.optInt("duration_ms", 200) : 200;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        VibratorManager vm = (VibratorManager)
                            getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                        vm.getDefaultVibrator().vibrate(
                            VibrationEffect.createOneShot(durationMs,
                                VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
                        v.vibrate(VibrationEffect.createOneShot(durationMs,
                            VibrationEffect.DEFAULT_AMPLITUDE));
                    }
                    return getString(R.string.action_vibrated) + durationMs + "ms";
                }
                case "brightness": {
                    int level = params != null ? params.optInt("level", 128) : 128;
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (Settings.System.canWrite(this)) {
                            Settings.System.putInt(getContentResolver(),
                                Settings.System.SCREEN_BRIGHTNESS,
                                Math.max(0, Math.min(255, level)));
                            return getString(R.string.action_brightness_set) + level;
                        }
                    }
                    showToast(getString(R.string.action_brightness_permission));
                    return "亮度调整需授权";
                }
                case "volume": {
                    int vol = params != null ? params.optInt("level", 10) : 10;
                    AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    int maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    int targetVol = Math.max(0, Math.min(maxVol, vol));
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0);
                    return getString(R.string.action_volume_set) + targetVol;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "操作执行失败: " + action + " - " + e.getMessage());
            return "执行失败: " + e.getMessage();
        }
        return null;
    }

    /**
     * 发送本地通知
     */
    private void sendLocalNotification(String title, String content) {
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build();
        nm.notify((int) System.currentTimeMillis(), notification);
    }

    /**
     * 常用应用名 → 包名映射
     */
    private String resolvePackageName(String appName) {
        if (appName == null || appName.isEmpty()) return "";
        switch (appName.toLowerCase()) {
            case "微信": case "wechat": return "com.tencent.mm";
            case "qq": return "com.tencent.mobileqq";
            case "支付宝": case "alipay": return "com.eg.android.AlipayGphone";
            case "抖音": case "tiktok": return "com.ss.android.ugc.aweme";
            case "淘宝": case "taobao": return "com.taobao.taobao";
            case "微博": case "weibo": return "com.sina.weibo";
            case "网易云音乐": case "music": return "com.netease.cloudmusic";
            case "哔哩哔哩": case "bilibili": return "tv.danmaku.bili";
            case "浏览器": case "browser": return "com.android.chrome";
            case "设置": case "settings": return "com.android.settings";
            case "相机": case "camera": return "com.android.camera";
            case "相册": case "gallery": return "com.android.gallery3d";
            case "计算器": case "calculator": return "com.android.calculator2";
            default: return appName;
        }
    }

    /**
     * 显示 Toast（非主线程安全）
     */
    private void showToast(String text) {
        new Handler(android.os.Looper.getMainLooper()).post(() ->
            Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (useUnity && charView instanceof UnityPlayerView) {
            ((UnityPlayerView) charView).pause();
            ((UnityPlayerView) charView).destroy();
        }
        if (roamHandler != null) { roamHandler.removeCallbacksAndMessages(null); roamHandler = null; }
        if (overlayRoot != null) windowManager.removeView(overlayRoot);
        if (aiEngine != null) aiEngine.stopWakeWordDetection();
        if (commHandler != null) commHandler.stopPolling();
        if (aiEngine != null) aiEngine.shutdown();
        Log.i(TAG, "灵绘悬浮窗服务已停止");
    }
}
