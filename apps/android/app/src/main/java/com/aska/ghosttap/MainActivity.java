package com.aska.ghosttap;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.view.accessibility.AccessibilityManager;

/**
 * GhostTap 主界面
 * 
 * 职责：
 * - 服务状态显示
 * - 无障碍权限引导
 * - 悬浮窗权限引导
 * - 服务器配置
 * - 连接状态显示
 */
public class MainActivity extends Activity {
    
    private static final String TAG = Config.LOG_TAG + ".MainActivity";
    
    // 权限请求码
    private static final int REQ_ACCESSIBILITY = 1001;
    private static final int REQ_OVERLAY = 1002;
    
    // UI 组件
    private TextView tvStatus;
    private TextView tvConnectionStatus;
    private Button btnAccessibility;
    private Button btnOverlay;
    private Button btnStart;
    private Button btnStop;
    private EditText etServerUrl;
    private EditText etUserId;
    
    // 状态检查线程
    private Runnable statusCheckRunnable;
    private final Handler handler = new Handler();
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.i(TAG, "MainActivity created");
        
        // 创建界面
        createUI();
        
        // 加载保存的配置
        loadConfig();
        
        // 开始状态检查
        startStatusCheck();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        updateUIState();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (statusCheckRunnable != null) {
            handler.removeCallbacks(statusCheckRunnable);
        }
    }
    
    /**
     * 创建界面布局
     */
    private void createUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        
        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("GhostTap");
        tvTitle.setTextSize(28);
        tvTitle.setTextColor(0xFF00AA00);
        tvTitle.setPadding(0, 0, 0, 20);
        layout.addView(tvTitle);
        
        // 副标题
        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("AI 远程控制您的 Android 设备");
        tvSubtitle.setTextSize(14);
        tvSubtitle.setTextColor(0xFF666666);
        tvSubtitle.setPadding(0, 0, 0, 40);
        layout.addView(tvSubtitle);
        
        // 状态卡片
        layout.addView(createCard("服务状态", createStatusSection()));
        layout.addView(createCard("权限设置", createPermissionSection()));
        layout.addView(createCard("连接设置", createConnectionSection()));
        layout.addView(createCard("操作", createActionSection()));
        
        // 说明文字
        TextView tvHelp = new TextView(this);
        tvHelp.setText("使用说明:\n" +
               "1. 开启无障碍服务权限\n" +
               "2. 开启悬浮窗权限（用于显示状态）\n" +
               "3. 配置服务器地址\n" +
               "4. 点击启动服务\n\n" +
               "授权后，AI 将自动完成您指定的任务。");
        tvHelp.setTextSize(12);
        tvHelp.setTextColor(0xFF888888);
        tvHelp.setPadding(0, 20, 0, 0);
        layout.addView(tvHelp);
        
        scrollView.addView(layout);
        setContentView(scrollView);
    }
    
    /**
     * 创建卡片布局
     */
    private View createCard(String title, View content) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(30, 25, 30, 25);
        card.setBackgroundColor(0xFFF5F5F5);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, 20);
        card.setLayoutParams(params);
        
        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(16);
        tvTitle.setTextColor(0xFF333333);
        tvTitle.setPadding(0, 0, 0, 15);
        card.addView(tvTitle);
        
        // 内容
        card.addView(content);
        
        return card;
    }
    
    /**
     * 创建状态显示区域
     */
    private View createStatusSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        // 服务状态
        tvStatus = new TextView(this);
        tvStatus.setText("服务: 未运行");
        tvStatus.setTextSize(14);
        layout.addView(tvStatus);
        
        // 连接状态
        tvConnectionStatus = new TextView(this);
        tvConnectionStatus.setText("连接: 未连接");
        tvConnectionStatus.setTextSize(14);
        tvConnectionStatus.setPadding(0, 10, 0, 0);
        layout.addView(tvConnectionStatus);
        
        return layout;
    }
    
    /**
     * 创建权限设置区域
     */
    private View createPermissionSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        // 无障碍权限按钮
        btnAccessibility = new Button(this);
        btnAccessibility.setText("开启无障碍服务");
        btnAccessibility.setOnClickListener(v -> requestAccessibilityPermission());
        layout.addView(btnAccessibility);
        
        // 悬浮窗权限按钮
        btnOverlay = new Button(this);
        btnOverlay.setText("开启悬浮窗权限");
        btnOverlay.setOnClickListener(v -> requestOverlayPermission());
        layout.addView(btnOverlay);
        
        return layout;
    }
    
    /**
     * 创建连接设置区域
     */
    private View createConnectionSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        // 服务器地址
        TextView tvUrlLabel = new TextView(this);
        tvUrlLabel.setText("服务器地址:");
        tvUrlLabel.setTextSize(14);
        tvUrlLabel.setPadding(0, 0, 0, 10);
        layout.addView(tvUrlLabel);
        
        etServerUrl = new EditText(this);
        etServerUrl.setHint("wss://your-server.com/ws");
        etServerUrl.setTextSize(14);
        layout.addView(etServerUrl);
        
        // 用户 ID
        TextView tvIdLabel = new TextView(this);
        tvIdLabel.setText("用户 ID（可选）:");
        tvIdLabel.setTextSize(14);
        tvIdLabel.setPadding(0, 20, 0, 10);
        layout.addView(tvIdLabel);
        
        etUserId = new EditText(this);
        etUserId.setHint("留空自动生成");
        etUserId.setTextSize(14);
        layout.addView(etUserId);
        
        // 保存按钮
        Button btnSave = new Button(this);
        btnSave.setText("保存设置");
        btnSave.setOnClickListener(v -> saveConfig());
        layout.addView(btnSave);
        
        return layout;
    }
    
    /**
     * 创建操作按钮区域
     */
    private View createActionSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        
        // 启动按钮
        btnStart = new Button(this);
        btnStart.setText("启动服务");
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        startParams.setMarginEnd(10);
        btnStart.setLayoutParams(startParams);
        btnStart.setOnClickListener(v -> startService());
        layout.addView(btnStart);
        
        // 停止按钮
        btnStop = new Button(this);
        btnStop.setText("停止服务");
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        );
        stopParams.setMarginStart(10);
        btnStop.setLayoutParams(stopParams);
        btnStop.setOnClickListener(v -> stopService());
        btnStop.setEnabled(false);
        layout.addView(btnStop);
        
        return layout;
    }
    
    /**
     * 更新 UI 状态
     */
    private void updateUIState() {
        boolean isServiceRunning = GhostTapService.isRunning();
        boolean hasAccessibility = isAccessibilityEnabled();
        boolean hasOverlay = canDrawOverlays();
        
        // 更新服务状态文本
        if (isServiceRunning) {
            tvStatus.setText("服务: 运行中");
            tvStatus.setTextColor(0xFF00AA00);
        } else {
            tvStatus.setText("服务: 未运行");
            tvStatus.setTextColor(0xFFFF4444);
        }
        
        // 更新连接状态
        GhostTapService instance = GhostTapService.getInstance();
        boolean isConnected = instance != null && instance.getCurrentStatus() != GhostTapService.TaskStatus.IDLE;
        if (isConnected) {
            tvConnectionStatus.setText("连接: 已连接");
        } else {
            tvConnectionStatus.setText("连接: 未连接");
        }
        
        // 更新权限按钮
        if (hasAccessibility) {
            btnAccessibility.setText("无障碍服务: 已开启");
        } else {
            btnAccessibility.setText("开启无障碍服务");
        }
        btnAccessibility.setEnabled(!hasAccessibility);
        
        if (hasOverlay) {
            btnOverlay.setText("悬浮窗权限: 已开启");
        } else {
            btnOverlay.setText("开启悬浮窗权限");
        }
        btnOverlay.setEnabled(!hasOverlay);
        
        // 更新操作按钮
        btnStart.setEnabled(hasAccessibility && !isServiceRunning);
        btnStop.setEnabled(isServiceRunning);
    }
    
    /**
     * 开始状态检查循环
     */
    private void startStatusCheck() {
        statusCheckRunnable = new Runnable() {
            @Override
            public void run() {
                updateUIState();
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statusCheckRunnable);
    }
    
    /**
     * 请求无障碍权限
     */
    private void requestAccessibilityPermission() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityForResult(intent, REQ_ACCESSIBILITY);
        
        Toast.makeText(this, "请在设置中找到 GhostTap 并开启无障碍服务", Toast.LENGTH_LONG).show();
    }
    
    /**
     * 请求悬浮窗权限
     */
    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())
            );
            startActivityForResult(intent, REQ_OVERLAY);
        }
    }
    
    /**
     * 检查无障碍服务是否启用
     */
    private boolean isAccessibilityEnabled() {
        String enabledServices = Settings.Secure.getString(
            getContentResolver(),
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) return false;
        
        return enabledServices.contains(getPackageName());
    }
    
    /**
     * 检查悬浮窗权限
     */
    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(this);
        }
        return true;
    }
    
    /**
     * 启动服务
     */
    private void startService() {
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (!canDrawOverlays()) {
            Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 保存配置
        saveConfig();
        
        // 启动服务
        Intent intent = new Intent(this, GhostTapService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        
        Toast.makeText(this, "服务已启动", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 停止服务
     */
    private void stopService() {
        GhostTapService instance = GhostTapService.getInstance();
        if (instance != null) {
            instance.stopCurrentTask();
        }
        
        Intent intent = new Intent(this, GhostTapService.class);
        stopService(intent);
        
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 加载配置
     */
    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        
        String serverUrl = prefs.getString(Config.PREF_SERVER_URL, Config.SERVER_URL);
        String userId = prefs.getString(Config.PREF_USER_ID, "");
        
        etServerUrl.setText(serverUrl);
        etUserId.setText(userId);
    }
    
    /**
     * 保存配置
     */
    private void saveConfig() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        String url = etServerUrl.getText().toString();
        editor.putString(Config.PREF_SERVER_URL, url.isEmpty() ? Config.SERVER_URL : url);
        
        String userId = etUserId.getText().toString();
        if (!userId.isEmpty()) {
            editor.putString(Config.PREF_USER_ID, userId);
        }
        
        editor.apply();
        
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 权限设置返回后刷新状态
        handler.postDelayed(this::updateUIState, 500);
    }
}
