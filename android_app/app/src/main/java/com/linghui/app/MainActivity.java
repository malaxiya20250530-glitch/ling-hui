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
                checkBatteryOptimization();
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
                checkBatteryOptimization();
            } else {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_overlay_title))
                    .setMessage(getString(R.string.overlay_permission_needed) + "\n\n" + getRomPermissionHint())
                    .setPositiveButton(getString(R.string.perm_go_settings), (d, w) -> requestOverlayPermission())
                    .setNegativeButton(getString(R.string.perm_cancel), null)
                    .show();
            }
        }
    }

    /** HyperOS/MIUI: 请求忽略电池优化，防止杀后台 */
    private void checkBatteryOptimization() {
        String rom = detectRom();
        if (rom.equals("HyperOS") || rom.equals("MIUI")) {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.perm_battery_title))
                    .setMessage(rom + getString(R.string.battery_warning))
                    .setPositiveButton(getString(R.string.perm_go_settings), (d, w) -> {
                        try {
                            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        } catch (Exception e) {
                            // 部分 ROM 不支持此 Intent，直接启动服务
                        }
                    })
                    .setNegativeButton(getString(R.string.perm_battery_skip), (d, w) -> doStartService())
                    .setOnDismissListener(d -> doStartService())
                    .show();
                return;
            }
        }
        doStartService();
    }

    private void doStartService() {
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

    /** ROM 检测（区分 MIUI / HyperOS / Android 版本） */
    private String detectRom() {
        String brand = Build.BRAND != null ? Build.BRAND.toLowerCase() : "";
        String display = Build.DISPLAY != null ? Build.DISPLAY.toLowerCase() : "";
        if (brand.contains("xiaomi") || brand.contains("redmi")) {
            if (display.contains("os") && !display.contains("oos")) return "HyperOS";
            if (Build.VERSION.SDK_INT >= 34) return "HyperOS";
            return "MIUI";
        }
        if (brand.contains("huawei") || brand.contains("honor")) return "EMUI";
        if (brand.contains("oppo") || brand.contains("realme")) return "ColorOS";
        if (brand.contains("vivo") || brand.contains("iqoo")) return "FuntouchOS";
        if (brand.contains("samsung")) return "OneUI";
        if (brand.contains("oneplus")) return "OxygenOS";
        return Build.MANUFACTURER;
    }

    private String getRomPermissionHint() {
        String rom = detectRom();
        switch (rom) {
            case "HyperOS":
                return "HyperOS 需开启 3 项权限:\n"
                    + "1. [设置-应用设置-灵绘-悬浮窗] 开启\n"
                    + "2. [设置-应用设置-灵绘-后台弹出界面] 开启\n"
                    + "3. [设置-应用设置-灵绘-省电策略-无限制]";
            case "MIUI":
                return "MIUI 需开启 3 项权限:\n"
                    + "1. [设置-应用管理-灵绘-悬浮窗] 开启\n"
                    + "2. [设置-应用管理-灵绘-后台弹出界面] 开启\n"
                    + "3. [安全中心-应用管理-灵绘-省电策略-无限制]";
            case "EMUI":
                return "请开启 [在其他应用上层显示] 和 [关联启动]";
            default:
                return "请在系统设置中开启悬浮窗权限";
        }
    }
}
