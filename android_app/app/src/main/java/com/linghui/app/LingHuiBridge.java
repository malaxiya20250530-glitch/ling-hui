package com.linghui.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Vibrator;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

/**
 * WebView JSBridge — 暴露原生功能给 HTML 前端调用
 *
 * HTML 端通过 window.LingHuiBridge.xxx() 调用
 */
public class LingHuiBridge {

    private static final String TAG = "LingHuiBridge";
    private static final String CHANNEL_ID = "linghui_webview";
    private final Context ctx;
    private TtsCallback ttsCb;

    public interface TtsCallback {
        void onSpeakRequest(String text);
    }

    public LingHuiBridge(Context context) {
        this.ctx = context.getApplicationContext();
    }

    public void setTtsCallback(TtsCallback cb) {
        this.ttsCb = cb;
    }

    // ═══ 系统操作 ═══

    /** 触发手机振动 */
    @JavascriptInterface
    public void vibrate(int durationMs) {
        Vibrator vib = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(Math.max(50, Math.min(durationMs, 5000)));
        }
    }

    /** 调节音量: stream = "music"|"ring"|"alarm"|"notification", delta = +1/-1 */
    @JavascriptInterface
    public void adjustVolume(String stream, int delta) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int streamType;
        switch (stream) {
            case "ring": streamType = AudioManager.STREAM_RING; break;
            case "alarm": streamType = AudioManager.STREAM_ALARM; break;
            case "notification": streamType = AudioManager.STREAM_NOTIFICATION; break;
            default: streamType = AudioManager.STREAM_MUSIC;
        }
        if (delta > 0) {
            am.adjustVolume(AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
        } else {
            am.adjustVolume(AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        }
    }

    /** 获取当前音乐音量 (0-100) */
    @JavascriptInterface
    public int getMusicVolume() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        return max > 0 ? (cur * 100 / max) : 50;
    }

    /** 设置音乐音量 (0-100) */
    @JavascriptInterface
    public void setMusicVolume(int percent) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int level = Math.max(0, Math.min(max, percent * max / 100));
        am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
    }

    // ═══ 剪贴板 ═══

    @JavascriptInterface
    public void copyToClipboard(String text) {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("linghui", text));
        showToast(ctx.getString(R.string.clipboard_copied));
    }

    @JavascriptInterface
    public String getClipboard() {
        ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
            CharSequence text = cm.getPrimaryClip().getItemAt(0).getText();
            return text != null ? text.toString() : "";
        }
        return "";
    }

    // ═══ 通知 ═══

    @JavascriptInterface
    public void showNotification(String title, String content) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);

        Intent intent = new Intent(ctx, WebViewActivity.class);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notif = new NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build();
        nm.notify((int) System.currentTimeMillis(), notif);
    }

    // ═══ 分享 ═══

    @JavascriptInterface
    public void shareText(String text) {
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_TEXT, text);
        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(Intent.createChooser(share, "Share to…"));
    }

    // ═══ 打开链接 ═══

    @JavascriptInterface
    public void openUrl(String url) {
        if (!url.startsWith("http")) url = "https://" + url;
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        browser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(browser);
    }

    // ═══ Toast ═══

    @JavascriptInterface
    public void showToast(String text) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show();
    }

    // ═══ TTS 语音合成 ═══

    @JavascriptInterface
    public void speak(String text) {
        if (ttsCb != null) {
            ttsCb.onSpeakRequest(text);
        }
    }

    // ═══ 设备信息 ═══

    // ═══ 均衡器 ═══

    @JavascriptInterface
    public void setEqualizer(int preset) {
        try {
            OverlayService svc = OverlayService.getInstance();
            if (svc != null) svc.setEqualizer(preset);
        } catch (Exception e) { /* OverlayService 未运行 */ }
    }

    @JavascriptInterface
    public String getDeviceInfo() {
        return "{\"brand\":\"" + Build.BRAND + "\",\"model\":\"" + Build.MODEL
            + "\",\"sdk\":" + Build.VERSION.SDK_INT + "}";
    }
}
