package com.linghui.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/** 灵绘 - AI虚拟桌面精灵主界面 */
public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnWebView = findViewById(R.id.btn_open_webview);
        Button btnStart = findViewById(R.id.btn_start);
        Button btnStop = findViewById(R.id.btn_stop);

        btnWebView.setOnClickListener(v -> {
            startActivity(new Intent(this, WebViewActivity.class));
        });

        btnStart.setOnClickListener(v -> {
            if (checkOverlayPermission()) {
                startOverlayService();
            } else {
                requestOverlayPermission();
            }
        });

        btnStop.setOnClickListener(v -> stopOverlayService());
    }

    private boolean checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        startActivityForResult(intent, OVERLAY_PERMISSION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == OVERLAY_PERMISSION) {
            if (checkOverlayPermission()) {
                startOverlayService();
            } else {
                Toast.makeText(this, getString(R.string.overlay_permission_needed), Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startOverlayService() {
        Intent intent = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        Toast.makeText(this, getString(R.string.overlay_started), Toast.LENGTH_SHORT).show();
        finish();
    }

    private void stopOverlayService() {
        stopService(new Intent(this, OverlayService.class));
        Toast.makeText(this, getString(R.string.overlay_stopped), Toast.LENGTH_SHORT).show();
    }
}
