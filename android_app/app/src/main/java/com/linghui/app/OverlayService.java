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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

/** 悬浮窗前台服务：管理桌面精灵的显示、拖拽和交互 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "linghui_overlay";
    private static final int NOTIFY_ID = 1001;

    private WindowManager windowManager;
    private FrameLayout overlayRoot;
    private LingHuiGLView glView;
    private LinearLayout chatBubble;
    private TextView chatText;
    private AiEngine aiEngine;

    // 拖拽相关
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging;
    private static final int DRAG_THRESHOLD = 10;

    // 对话气泡
    private boolean bubbleVisible;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "灵绘悬浮窗服务启动");

        aiEngine = new AiEngine(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);

        createNotificationChannel();
        startForeground(NOTIFY_ID, buildNotification());

        createOverlay();
    }

    // ---------- 通知栏 ----------
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "灵绘精灵", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("灵绘虚拟精灵运行中");
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
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

    // ---------- 悬浮窗创建 ----------
    private void createOverlay() {
        overlayRoot = new FrameLayout(this);

        // OpenGL 3D 角色视图
        glView = new LingHuiGLView(this);
        int size = dpToPx(120);
        FrameLayout.LayoutParams glParams = new FrameLayout.LayoutParams(size, size);
        glParams.gravity = Gravity.CENTER;
        overlayRoot.addView(glView, glParams);

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
        bubbleParams.topMargin = size + dpToPx(8);
        overlayRoot.addView(chatBubble, bubbleParams);

        // 窗口参数
        WindowManager.LayoutParams wmParams = new WindowManager.LayoutParams(
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
        wmParams.x = dm.widthPixels / 2 - size / 2;
        wmParams.y = dm.heightPixels / 3;

        overlayRoot.setOnTouchListener(new OverlayTouchListener(wmParams));
        overlayRoot.setOnClickListener(v -> onOverlayClick());

        windowManager.addView(overlayRoot, wmParams);
    }

    // ---------- 拖拽处理 ----------
    private class OverlayTouchListener implements View.OnTouchListener {
        private final WindowManager.LayoutParams params;

        OverlayTouchListener(WindowManager.LayoutParams p) { this.params = p; }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    isDragging = false;
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
                    if (!isDragging) {
                        v.performClick();
                    }
                    return true;
            }
            return false;
        }
    }

    // ---------- 点击交互 ----------
    private void onOverlayClick() {
        if (bubbleVisible) {
            hideBubble();
        } else {
            showBubble("我在呢~ 想聊什么？");
            glView.onInteract();

            // 调用 AI 引擎
            aiEngine.chat("你好呀", new AiEngine.ChatCallback() {
                @Override public void onReply(String reply) {
                    showBubble(reply);
                    aiEngine.speak(reply);
                    glView.onReplyReceived();
                }
                @Override public void onError(String error) {
                    showBubble("（网络好像不太稳…等一下再试试？）");
                    Log.w(TAG, error);
                }
            });
        }
    }

    private void showBubble(String text) {
        chatText.setText(text);
        chatBubble.setVisibility(View.VISIBLE);
        bubbleVisible = true;
        // 3 秒后自动隐藏
        overlayRoot.postDelayed(this::hideBubble, 5000);
    }

    private void hideBubble() {
        chatBubble.setVisibility(View.GONE);
        bubbleVisible = false;
        glView.onIdle();
    }

    // ---------- 工具 ----------
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (overlayRoot != null) windowManager.removeView(overlayRoot);
        if (aiEngine != null) aiEngine.shutdown();
        Log.i(TAG, "灵绘悬浮窗服务已停止");
    }
}
