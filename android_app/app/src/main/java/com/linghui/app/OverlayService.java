package com.linghui.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.audiofx.Equalizer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Vibrator;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;
import android.view.animation.AlphaAnimation;
import android.view.animation.Animation;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.linghui.app.comm.AiCommHandler;

/** 悬浮窗前台服务：小球 + 气泡菜单（音乐/语音/对话） */
public class OverlayService extends Service {

    private static OverlayService instance;
    public static OverlayService getInstance() { return instance; }

    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "linghui_overlay";
    private static final int NOTIFY_ID = 1001;

    private WindowManager windowManager;
    private LinearLayout overlayRoot;
    private ICharacterView charView;
    private LinearLayout menuBubble;
    private TextView menuTitle;
    private Button btnMusic, btnVoice, btnChat;
    private AiEngine aiEngine;
    private AiCommHandler commHandler;
    private MediaPlayer mediaPlayer;
    private boolean musicPlaying;
    private boolean mediaPlayerPrepared;
    private Equalizer equalizer;

    // 拖拽
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging;
    private static final int DRAG_THRESHOLD = 10;
    private boolean bubbleVisible;

    // 漫游
    private Handler roamHandler;
    private WindowManager.LayoutParams wmParams;
    private float roamTime;
    private float roamSpeedX = 0.8f, roamSpeedY = 1.1f;
    private float roamAmpX = 60f, roamAmpY = 50f;
    private boolean isRoaming = true;
    private boolean wakeWordActive;
    private boolean overlayVisible = true;
    private BroadcastReceiver notifReceiver;
    // 音乐可视化：7 个彩色小球
    private View[] vizBalls;
    private Handler vizHandler;
    private boolean vizActive;
    private static final int[] VIZ_COLORS = {
        0xFFE53935, 0xFFFB8C00, 0xFFFFEB3B,  // 红橙黄
        0xFF43A047, 0xFF00ACC1, 0xFF1E88E5,  // 绿青蓝
        0xFF8E24AA                               // 紫
    };
    private float[] vizPhases = {0f, 0.6f, 1.2f, 1.8f, 2.4f, 3.0f, 3.6f};
    private int screenW, screenH, charSize;


    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        Log.i(TAG, "灵绘悬浮球启动");

        // 第一步：前台服务（必须立即完成，否则 ANR）
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createNotificationChannel();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFY_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFY_ID, buildNotification());
        }

        // 第二步：创建悬浮窗（轻量视觉初始化）
        initOverlay();

        // 第三步：延迟初始化 AI 引擎和通信模块（避免 onCreate 阻塞）
        new Handler().postDelayed(this::delayedInit, 500);
    }

    private void delayedInit() {
        // 服务可能已被销毁，检查窗口是否还在
        if (overlayRoot == null || !overlayRoot.isAttachedToWindow()) {
            Log.w(TAG, "服务已销毁，跳过延迟初始化");
            return;
        }
        aiEngine = new AiEngine(this);
        commHandler = new AiCommHandler();

        // AI 渲染回调
        commHandler.setRenderCallback(new AiCommHandler.RenderCallback() {
            @Override public void onMood(String mood) {
                if (charView != null) charView.onInteract();
            }
            @Override public void onTalking(boolean talking, float intensity) {
                if (charView != null) {
                    if (talking) charView.onInteract(); else charView.onIdle();
                }
            }
            @Override public void onReply(String text) {
                showBubble(text);
                if (aiEngine != null) aiEngine.speak(text);
                if (charView != null) charView.onReplyReceived();
            }
        });

        commHandler.setAiCallback(new AiCommHandler.AiCallback() {
            @Override public void onUserTap() { onOverlayClick(); }
            @Override public void onUserLongPress() {
                showBubble(getString(R.string.long_press));
            }
        });

        commHandler.setActionCallback((action, params) -> {
            String resultMsg = executeAction(action, null);
            commHandler.onActionResult(action, resultMsg != null, resultMsg != null ? resultMsg : getString(R.string.action_unknown));
        });

        commHandler.setActionResultCallback((action, success, message) -> {
            showBubble(success ? getString(R.string.action_success_prefix) + message : getString(R.string.action_fail_prefix) + action + getString(R.string.action_fail_suffix) + message);
        });

        commHandler.startPolling();
        initMediaPlayer();
        startWakeWordIfPermitted();
        registerNotifReceiver();
        Log.i(TAG, "AI引擎和通信模块初始化完成");
    }

    // ═══ MediaPlayer ═══
    private void initMediaPlayer() {
        try {
            mediaPlayer = new MediaPlayer();
            // 播放内置音乐
            android.content.res.AssetFileDescriptor afd = getAssets().openFd("linghui/music/SoundHelix-Song-8.mp3");
            mediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(mp -> {
                mediaPlayerPrepared = true;
                try {
                    equalizer = new Equalizer(0, mp.getAudioSessionId());
                    equalizer.setEnabled(true);
                    Log.i(TAG, "均衡器初始化成功, bands=" + equalizer.getNumberOfBands());
                } catch (Exception e) {
                    Log.w(TAG, "均衡器不可用: " + e.getMessage());
                }
                Log.i(TAG, "音乐准备就绪");
            });
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.w(TAG, "音乐初始化失败: " + e.getMessage());
        }
    }

    private void toggleMusic() {
        if (mediaPlayer == null || !mediaPlayerPrepared) {
            showBubble(getString(R.string.music_not_ready));
            return;
        }
        try {
            if (musicPlaying) {
                if (equalizer != null) try { equalizer.setEnabled(false); } catch (Exception e) {}
                mediaPlayer.pause();
                musicPlaying = false;
                btnMusic.setText(getString(R.string.btn_music));
                showBubble(getString(R.string.music_paused));
                stopVisualizer();
            } else {
                mediaPlayer.start();
                if (equalizer != null) try { equalizer.setEnabled(true); } catch (Exception e) {}
                musicPlaying = true;
                btnMusic.setText(getString(R.string.btn_pause));
                showBubble(getString(R.string.music_playing));
                startVisualizer();
            }
        } catch (Exception e) {
            showBubble(getString(R.string.music_play_failed) + e.getMessage());
        }
    }

    // ═══ 悬浮窗初始化 ═══
    private void initOverlay() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenW = metrics.widthPixels;
        screenH = metrics.heightPixels;
        charSize = dpToPx(180); // 球体扩大50%

        overlayRoot = new LinearLayout(this);
        overlayRoot.setOrientation(LinearLayout.VERTICAL);

        // 小球（OpenGL）
        charView = new LingHuiGLView(this);
        LinearLayout.LayoutParams charParams = new LinearLayout.LayoutParams(charSize, charSize);
        charParams.gravity = Gravity.CENTER;
        overlayRoot.addView((View) charView, charParams);

        // 音乐可视化小球
        initVisualizer();
        // 气泡菜单嵌入 overlayRoot（非独立窗口）
        createMenuBubble();

        wmParams = new WindowManager.LayoutParams(
            charSize, WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        wmParams.gravity = Gravity.TOP | Gravity.START;
        wmParams.x = (screenW - charSize) / 2;
        wmParams.y = screenH / 3;
        wmParams.windowAnimations = 0;

        overlayRoot.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                // 菜单显示时，触摸菜单区域交给按钮处理
                float localY = event.getY();
                if (bubbleVisible && localY > charSize + dpToPx(4)) {
                    return false;
                }
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = wmParams.x;
                        initialY = wmParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        isRoaming = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dx = event.getRawX() - initialTouchX;
                        float dy = event.getRawY() - initialTouchY;
                        if (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            wmParams.x = initialX + (int) dx;
                            wmParams.y = initialY + (int) dy;
                            windowManager.updateViewLayout(overlayRoot, wmParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 用总位移判断：< 30px 视为点击，否则为拖拽
                        float totalDx = Math.abs(event.getRawX() - initialTouchX);
                        float totalDy = Math.abs(event.getRawY() - initialTouchY);
                        if (totalDx < 30 && totalDy < 30) {
                            onOverlayClick();
                        } else {
                            // 贴边
                            if (!bubbleVisible) {
                                int cx = wmParams.x + charSize / 2;
                                wmParams.x = cx < screenW / 2 ? 0 : screenW - charSize;
                                windowManager.updateViewLayout(overlayRoot, wmParams);
                                isRoaming = true;
                                roamTime = 0;
                                startRoaming();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(overlayRoot, wmParams);
        startRoaming();
    }

    // ═══ 气泡菜单 ═══
    // ═══ 音乐可视化 ═══

    private void initVisualizer() {
        vizBalls = new View[7];
        LinearLayout vizRow = new LinearLayout(this);
        vizRow.setGravity(Gravity.CENTER);
        vizRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(36));
        rowParams.topMargin = dpToPx(4);
        vizRow.setLayoutParams(rowParams);
        vizRow.setVisibility(View.GONE);

        for (int i = 0; i < 7; i++) {
            View ball = new View(this);
            int size = dpToPx(12);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(size, size);
            bp.setMargins(dpToPx(4), 0, dpToPx(4), 0);
            bp.gravity = Gravity.BOTTOM;
            ball.setLayoutParams(bp);
            GradientDrawable gd = new GradientDrawable();
            gd.setShape(GradientDrawable.OVAL);
            gd.setColor(VIZ_COLORS[i]);
            ball.setBackground(gd);
            ball.setAlpha(0.85f);
            vizRow.addView(ball);
            vizBalls[i] = ball;
        }
        overlayRoot.addView(vizRow, 1); // 插在 GL 球和气泡菜单之间
        vizHandler = new Handler();
    }

    private void startVisualizer() {
        if (vizBalls == null) return;
        for (View b : vizBalls) b.getParent().requestLayout();
        ((View) vizBalls[0].getParent()).setVisibility(View.VISIBLE);
        vizActive = true;
        animateVisualizer();
    }

    private void stopVisualizer() {
        vizActive = false;
        if (vizHandler != null) vizHandler.removeCallbacksAndMessages(null);
        if (vizBalls != null && vizBalls.length > 0 && vizBalls[0].getParent() != null) {
            ((View) vizBalls[0].getParent()).setVisibility(View.GONE);
        }
    }

    private void animateVisualizer() {
        if (!vizActive || vizBalls == null) return;
        long t = System.currentTimeMillis();
        for (int i = 0; i < 7; i++) {
            // 每个球不同的频率和相位，模拟音谱跳动
            float phase = vizPhases[i];
            float freq = 8f + i * 1.5f;  // 不同频率
            float baseAmp = 0.3f + i * 0.08f;
            // 混合两个正弦波产生不规则跳动感
            float raw = (float) (Math.sin(t * 0.001 * freq + phase) * 0.6
                               + Math.sin(t * 0.0013 * freq * 1.7 + phase * 2) * 0.4);
            float scale = baseAmp + Math.abs(raw) * 1.8f;
            scale = Math.max(0.25f, Math.min(scale, 3.0f));

            View ball = vizBalls[i];
            int baseSize = dpToPx(12);
            int newSize = (int) (baseSize * scale);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) ball.getLayoutParams();
            lp.width = newSize;
            lp.height = newSize;
            // 微调透明度
            ball.setAlpha(0.5f + Math.abs(raw) * 0.5f);
            ball.setLayoutParams(lp);
        }
        vizBalls[0].getParent().requestLayout();
        vizHandler.postDelayed(this::animateVisualizer, 60); // ~16fps
    }

    private void createMenuBubble() {
        menuBubble = new LinearLayout(this);
        menuBubble.setOrientation(LinearLayout.VERTICAL);
        menuBubble.setGravity(Gravity.CENTER);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1a1a2e);
        bg.setCornerRadius(dpToPx(16));
        bg.setStroke(dpToPx(1), 0x55ff4400);
        menuBubble.setBackground(bg);
        menuBubble.setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12));
        menuBubble.setElevation(dpToPx(8));

        menuTitle = new TextView(this);
        menuTitle.setText(getString(R.string.app_name));
        menuTitle.setTextColor(0xFFff6600);
        menuTitle.setTextSize(14);
        menuTitle.setGravity(Gravity.CENTER);
        menuTitle.setPadding(0, 0, 0, dpToPx(4));
        menuBubble.addView(menuTitle);

        // 关闭按钮
        Button btnClose = new Button(this);
        btnClose.setText("✕");
        btnClose.setTextSize(16);
        btnClose.setTextColor(0xFFff4444);
        btnClose.setAllCaps(false);
        btnClose.setBackgroundColor(0x00000000);
        btnClose.setPadding(dpToPx(8), dpToPx(2), dpToPx(8), dpToPx(2));
        btnClose.setOnClickListener(v -> hideBubble());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.gravity = Gravity.END;
        btnClose.setLayoutParams(closeLp);
        menuBubble.addView(btnClose);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.VERTICAL);
        btnRow.setGravity(Gravity.CENTER);

        btnMusic = createMenuBtn(getString(R.string.btn_music));
        btnVoice = createMenuBtn(getString(R.string.btn_voice));
        btnChat = createMenuBtn(getString(R.string.btn_chat));

        btnRow.addView(btnMusic);
        btnRow.addView(btnVoice);
        btnRow.addView(btnChat);
        menuBubble.addView(btnRow);

        btnMusic.setOnClickListener(v -> toggleMusic());
        btnVoice.setOnClickListener(v -> startVoiceConversation());
        btnChat.setOnClickListener(v -> openWebView());

        menuBubble.setVisibility(View.GONE);

        // 嵌入 overlayRoot，小球下方
        LinearLayout.LayoutParams menuLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        menuLp.gravity = Gravity.CENTER;
        menuLp.topMargin = dpToPx(8);
        overlayRoot.addView(menuBubble, menuLp);
    }

    private Button createMenuBtn(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(12);
        btn.setTextColor(0xFFFFFFFF);
        btn.setAllCaps(false);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x44ff4400);
        bg.setCornerRadius(dpToPx(10));
        btn.setBackground(bg);
        btn.setPadding(dpToPx(10), dpToPx(6), dpToPx(10), dpToPx(6));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            dpToPx(120), LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dpToPx(6), 0, dpToPx(6));
        btn.setLayoutParams(lp);
        return btn;
    }

    // ═══ 交互 ═══
    private void onOverlayClick() {
        if (bubbleVisible) {
            hideBubble();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        isRoaming = false; // 点击停飘
        // 立即取消所有漫游回调，避免最后一帧位移造成抖动
        if (roamHandler != null) roamHandler.removeCallbacksAndMessages(null);
        menuBubble.setVisibility(View.VISIBLE);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(250);
        menuBubble.startAnimation(fadeIn);
        bubbleVisible = true;
        overlayRoot.postDelayed(this::hideBubble, 20000);
    }

    private void showBubble(String text) {
        // 复用菜单标题显示消息
        menuTitle.setText(text);
        menuBubble.setVisibility(View.VISIBLE);
        AlphaAnimation fadeIn = new AlphaAnimation(0f, 1f);
        fadeIn.setDuration(250);
        menuBubble.startAnimation(fadeIn);
        bubbleVisible = true;
        overlayRoot.postDelayed(this::hideBubble, 4000);
    }

    private void hideBubble() {
        // 取消 showMenu 的 20s 自动关闭定时器
        overlayRoot.removeCallbacks(null);
        AlphaAnimation fadeOut = new AlphaAnimation(1f, 0f);
        fadeOut.setDuration(200);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override public void onAnimationStart(Animation a) {}
            @Override public void onAnimationEnd(Animation a) {
                menuBubble.setVisibility(View.GONE);
                menuTitle.setText(getString(R.string.app_name));
            }
            @Override public void onAnimationRepeat(Animation a) {}
        });
        menuBubble.startAnimation(fadeOut);
        bubbleVisible = false;
        charView.onIdle();
        isRoaming = true;
        roamTime = 0;
        startRoaming();
    }

    // ═══ 语音对话 ═══
    private void startVoiceConversation() {
        // 请求录音权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            showBubble(getString(R.string.perm_mic_needed));
            return;
        }

        showBubble(getString(R.string.listening));
        charView.onInteract();

        aiEngine.startListening(new AiEngine.ListenCallback() {
            @Override public void onReady() {
                showBubble(getString(R.string.im_listening));
            }
            @Override public void onResult(String transcript) {
                showBubble(getString(R.string.you_said) + transcript);
                aiEngine.chat(transcript, new AiEngine.ChatCallback() {
                    @Override public void onReply(String reply) {
                        showBubble(reply);
                        aiEngine.speak(reply);
                        if (charView != null) charView.onReplyReceived();
                        resumeWakeWord();
                    }
                    @Override public void onError(String error) {
                        showBubble(getString(R.string.network_issue));
                    }
                });
            }
            @Override public void onError(String error) {
                showBubble(getString(R.string.didnt_catch));
                charView.onIdle();
                resumeWakeWord();
            }
        });
    }

    // ═══ 唤醒词检测 ═══

    /** 如果有录音权限，启动后台唤醒词检测 */
    private void startWakeWordIfPermitted() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "无录音权限，跳过唤醒词检测");
            return;
        }
        if (wakeWordActive) return;
        wakeWordActive = true;
        Log.i(TAG, "唤醒词检测启动: 「灵绘」");

        aiEngine.startWakeWordDetection(new AiEngine.WakeWordCallback() {
            @Override
            public void onWakeWordDetected(String transcript) {
                Log.i(TAG, "🎯 唤醒词命中: " + transcript);
                // 暂停唤醒词检测，进入对话模式
                wakeWordActive = false;
                showBubble(getString(R.string.wake_greeting));
                charView.onInteract();
                // 延迟一秒后开始听指令，避免把唤醒词当指令
                new Handler().postDelayed(() -> startVoiceConversation(), 1000);
            }
        });
    }

    /** 对话结束后恢复唤醒词检测 */
    private void resumeWakeWord() {
        if (!wakeWordActive) {
            new Handler().postDelayed(() -> {
                startWakeWordIfPermitted();
                if (wakeWordActive) {
                    Log.i(TAG, "唤醒词检测已恢复");
                }
            }, 2000);
        }
    }

    // ═══ 打开全功能页 ═══
    private void openWebView() {
        hideBubble();
        Intent intent = new Intent(this, WebViewActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    // ═══ 通知 ═══
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(getString(R.string.channel_description));
            NotificationManager nm = getSystemService(NotificationManager.class);
            nm.createNotificationChannel(channel);
        }
    }

    // ── 通知栏控制面板（含 3 个操作按钮）──
    private static final String ACTION_PAUSE_ROAM = "com.linghui.PAUSE_ROAM";
    private static final String ACTION_TOGGLE_VISIBLE = "com.linghui.TOGGLE_VISIBLE";
    private static final String ACTION_OPEN_WEBVIEW = "com.linghui.OPEN_WEBVIEW";

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        PendingIntent pausePi = PendingIntent.getBroadcast(this, 1,
            new Intent(ACTION_PAUSE_ROAM), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent togglePi = PendingIntent.getBroadcast(this, 2,
            new Intent(ACTION_TOGGLE_VISIBLE), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent webPi = PendingIntent.getBroadcast(this, 3,
            new Intent(ACTION_OPEN_WEBVIEW), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String roamLabel = isRoaming ? getString(R.string.btn_pause) : getString(R.string.btn_roam);
        String visibleLabel = overlayVisible ? getString(R.string.btn_hide) : getString(R.string.btn_show);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(isRoaming ? getString(R.string.roaming_status) : getString(R.string.paused_status))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, roamLabel, pausePi)
            .addAction(android.R.drawable.ic_menu_view, visibleLabel, togglePi)
            .addAction(android.R.drawable.ic_menu_gallery, getString(R.string.btn_open_linghui), webPi)

            .build();
    }

    /** 注册通知栏按钮的广播接收器 */
    private void registerNotifReceiver() {
        notifReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_PAUSE_ROAM.equals(action)) {
                    if (isRoaming) {
                        isRoaming = false;
                        showBubble(getString(R.string.roam_paused));
                    } else {
                        isRoaming = true;
                        roamTime = 0;
                        startRoaming();
                        showBubble(getString(R.string.roam_resumed));
                    }
                    updateNotification();
                } else if (ACTION_TOGGLE_VISIBLE.equals(action)) {
                    overlayVisible = !overlayVisible;
                    overlayRoot.setVisibility(overlayVisible ? View.VISIBLE : View.INVISIBLE);
                    showBubble(overlayVisible ? getString(R.string.im_back) : getString(R.string.see_you));
                    updateNotification();
                } else if (ACTION_OPEN_WEBVIEW.equals(action)) {
                    openWebView();
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PAUSE_ROAM);
        filter.addAction(ACTION_TOGGLE_VISIBLE);
        filter.addAction(ACTION_OPEN_WEBVIEW);
        registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    /** 刷新通知栏按钮文字 */
    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIFY_ID, buildNotification());
    }

    // ═══ 漫游 ═══
    private void startRoaming() {
        if (roamHandler == null) roamHandler = new Handler();
        roamHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isRoaming) return;
                roamTime += 0.1f;
                int cx = screenW / 2;
                int cy = screenH / 2;
                int nx = (int) (cx + Math.sin(roamTime * roamSpeedX) * roamAmpX - charSize / 2);
                int ny = (int) (cy + Math.cos(roamTime * roamSpeedY) * roamAmpY - charSize / 2);

                // 边缘碰撞反弹：碰到屏幕边缘时反转方向 + 缩小振幅模拟弹跳
                boolean hitEdge = false;
                if (nx <= 0) { nx = 0; roamSpeedX = -roamSpeedX; roamAmpX *= 0.9f; hitEdge = true; }
                if (nx >= screenW - charSize) { nx = screenW - charSize; roamSpeedX = -roamSpeedX; roamAmpX *= 0.9f; hitEdge = true; }
                if (ny <= 0) { ny = 0; roamSpeedY = -roamSpeedY; roamAmpY *= 0.9f; hitEdge = true; }
                if (ny >= screenH - charSize) { ny = screenH - charSize; roamSpeedY = -roamSpeedY; roamAmpY *= 0.9f; hitEdge = true; }

                // 弹跳后逐渐恢复振幅
                if (hitEdge) {
                    roamAmpX = Math.min(roamAmpX + 10f, 60f);
                    roamAmpY = Math.min(roamAmpY + 10f, 50f);
                }

                wmParams.x = nx;
                wmParams.y = ny;
                try { windowManager.updateViewLayout(overlayRoot, wmParams); } catch (Exception e) { isRoaming = false; Log.w(TAG, "漫游更新失败，停止漫游: " + e.getMessage()); }
                roamHandler.postDelayed(this, 100);
            }
        }, 50);
    }

    @Override
    public void onDestroy() {
        isRoaming = false;
        wakeWordActive = false;
        if (aiEngine != null) aiEngine.stopWakeWordDetection();
        if (roamHandler != null) roamHandler.removeCallbacksAndMessages(null);
        vizActive = false;
        if (vizHandler != null) vizHandler.removeCallbacksAndMessages(null);
        if (equalizer != null) { equalizer.release(); equalizer = null; }
        if (mediaPlayer != null) { mediaPlayer.release(); mediaPlayer = null; }
        if (charView != null) charView.onIdle();
        if (overlayRoot != null) try { windowManager.removeView(overlayRoot); } catch (Exception e) {}
        if (notifReceiver != null) try { unregisterReceiver(notifReceiver); } catch (Exception e) {}
        if (commHandler != null) commHandler.stopPolling();
        if (aiEngine != null) aiEngine.shutdown();
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ═══ 均衡器 ═══

    // 预设值：0=关闭, 1=重低音, 2=流行, 3=古典, 4=摇滚, 5=爵士, 6=人声
    private static final short[][] EQ_PRESETS = {
        null,  // 0=关闭
        {900,900,500,300,200,0,0,0,0,300,500,700,800,800,800},  // 重低音
        {0,300,600,900,600,300,0,-100,-100,0,0,0,0,0,0},         // 流行
        {0,0,0,0,0,0,-200,-200,-200,-300,-300,-400,-400,-400,-500},  // 古典
        {500,400,300,100,0,-100,-100,0,200,400,500,500,500,400,300},  // 摇滚
        {0,200,300,100,0,0,-100,-100,0,0,0,0,0,0,0},              // 爵士
        {-200,-100,-100,0,200,400,500,500,500,400,200,0,-100,-100,-100},  // 人声
    };

    public void setEqualizer(int preset) {
        if (equalizer == null) return;
        try {
            if (preset == 0) {
                equalizer.setEnabled(false);
            } else if (preset > 0 && preset < EQ_PRESETS.length) {
                equalizer.setEnabled(true);
                short[] bands = EQ_PRESETS[preset];
                short numBands = equalizer.getNumberOfBands();
                for (short i = 0; i < numBands && i < bands.length; i++) {
                    equalizer.setBandLevel(i, bands[i]);
                }
            }
            Log.i(TAG, "均衡器预设: " + preset);
        } catch (Exception e) {
            Log.w(TAG, "均衡器设置失败: " + e.getMessage());
        }
    }

    // ═══ 工具 ═══
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    // ═══ 自动化操作 — 完整实现 ═══
    private String executeAction(String action, java.util.Map<String, String> params) {
        if (action == null) return null;
        try {
            switch (action) {
                case "open_app": {
                    String pkg = params != null ? params.get("package") : null;
                    if (pkg == null) return getString(R.string.no_package);
                    Intent launch = getPackageManager().getLaunchIntentForPackage(pkg);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(launch);
                        return getString(R.string.app_opened);
                    }
                    return getString(R.string.action_app_not_found) + pkg;
                }
                case "open_url": {
                    String url = params != null ? params.get("url") : null;
                    if (url == null) return getString(R.string.no_url);
                    if (!url.startsWith("http")) url = "https://" + url;
                    Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(browser);
                    return getString(R.string.link_opened);
                }
                case "search": {
                    String query = params != null ? params.get("query") : null;
                    if (query == null) return getString(R.string.no_query);
                    String searchUrl = "https://www.google.com/search?q=" + Uri.encode(query);
                    Intent search = new Intent(Intent.ACTION_VIEW, Uri.parse(searchUrl));
                    search.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(search);
                    return getString(R.string.searched) + query;
                }
                case "notify": {
                    String title = params != null ? params.get("title") : "灵绘";
                    String content = params != null ? params.get("content") : "";
                    NotificationManager nm = getSystemService(NotificationManager.class);
                    Notification notif = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle(title != null ? title : "灵绘")
                        .setContentText(content)
                        .setSmallIcon(R.mipmap.ic_launcher)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setAutoCancel(true)
                        .build();
                    nm.notify((int) System.currentTimeMillis(), notif);
                    return getString(R.string.notif_sent);
                }
                case "clipboard": {
                    String text = params != null ? params.get("text") : null;
                    if (text == null) return getString(R.string.no_clipboard_text);
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("linghui", text));
                    return getString(R.string.clipboard_copied);
                }
                case "volume_up": {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
                    return getString(R.string.vol_up);
                }
                case "volume_down": {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
                    return getString(R.string.vol_down);
                }
                case "vibrate": {
                    int dur = 200;
                    if (params != null && params.containsKey("duration_ms")) {
                        try { dur = Integer.parseInt(params.get("duration_ms")); } catch (NumberFormatException e) {}
                    }
                    Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (vib != null && vib.hasVibrator()) {
                        vib.vibrate(dur);
                        return getString(R.string.action_vibrated);
                    }
                    return getString(R.string.vibrate_unsupported);
                }
                default: return null;
            }
        } catch (Exception e) {
            Log.w(TAG, "executeAction 异常: " + action + ", " + e.getMessage());
            return getString(R.string.action_failed) + e.getMessage();
        }
    }
}
