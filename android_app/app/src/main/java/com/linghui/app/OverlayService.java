package com.linghui.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

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
                CHANNEL_ID, "灵绘精灵", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("灵绘虚拟精灵运行中");
            getSystemService(NotificationManager.class).createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("灵绘")
            .setContentText("虚拟精灵正在陪伴你 ✨")
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

        // 对话气泡
        chatBubble = new LinearLayout(this);
        chatBubble.setOrientation(LinearLayout.VERTICAL);
        chatBubble.setBackgroundColor(0xE0000000);
        chatBubble.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        chatText = new TextView(this);
        chatText.setTextColor(0xFFFFFFFF);
        chatText.setTextSize(14);
        chatText.setMaxWidth(dpToPx(200));
        chatText.setText("你好呀~ 我是灵绘 ✨");
        chatBubble.addView(chatText);
        chatBubble.setVisibility(View.GONE);
        FrameLayout.LayoutParams bubbleParams = new FrameLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT);
        bubbleParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bubbleParams.topMargin = charSize + dpToPx(8);
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
        showBubble("我在呢~ 想聊什么？");
        charView.onInteract();

        aiEngine.chat("你好呀", new AiEngine.ChatCallback() {
            @Override public void onReply(String reply) {
                showBubble(reply);
                aiEngine.speak(reply);
                charView.onReplyReceived();
            }
            @Override public void onError(String error) {
                showBubble("（网络好像不太稳…等一下再试试？）");
                Log.w(TAG, error);
            }
        });
    }

    private void showBubble(String text) {
        chatText.setText(text);
        chatBubble.setVisibility(View.VISIBLE);
        bubbleVisible = true;
        overlayRoot.postDelayed(this::hideBubble, 5000);
    }

    private void hideBubble() {
        chatBubble.setVisibility(View.GONE);
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

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (useUnity && charView instanceof UnityPlayerView) {
            ((UnityPlayerView) charView).pause();
            ((UnityPlayerView) charView).destroy();
        }
        if (roamHandler != null) { roamHandler.removeCallbacksAndMessages(null); roamHandler = null; }
        if (overlayRoot != null) windowManager.removeView(overlayRoot);
        if (aiEngine != null) aiEngine.shutdown();
        Log.i(TAG, "灵绘悬浮窗服务已停止");
    }
}
