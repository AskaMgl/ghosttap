package com.aska.ghosttap;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import android.view.accessibility.AccessibilityManager;
import android.os.Handler;

/**
 * GhostTap 主界面 (v3.14)
 * 
 * 设计文档 §3.1.6: 单页面清爽设计，纯白背景，大量留白
 * - 圆角卡片突出核心状态（连接状态）
 * - 绿色状态点 ● 表示正常，红色 ● 表示异常
 * - 权限状态一行一个，清晰明了
 * - 底部统计信息小字显示
 * - 只有一个主操作按钮（根据状态切换"启动/停止"）
 */
public class MainActivity extends Activity {
    
    private static final String TAG = Config.LOG_TAG + ".MainActivity";
    
    // 权限请求码
    private static final int REQ_ACCESSIBILITY = 1001;
    private static final int REQ_OVERLAY = 1002;
    
    // 颜色定义
    private static final int COLOR_WHITE = 0xFFFFFFFF;
    private static final int COLOR_GREEN = 0xFF00AA00;
    private static final int COLOR_RED = 0xFFFF4444;
    private static final int COLOR_GRAY = 0xFF888888;
    private static final int COLOR_LIGHT_GRAY = 0xFFF5F5F5;
    private static final int COLOR_TEXT_PRIMARY = 0xFF333333;
    private static final int COLOR_TEXT_SECONDARY = 0xFF666666;
    
    // UI 组件
    private TextView tvStatusTitle;
    private TextView tvStatusSubtitle;
    private View dotStatus;
    private TextView tvAccessibilityStatus;
    private TextView tvOverlayStatus;
    private TextView tvBackgroundStatus;
    private EditText etServerUrl;
    private EditText etUserId;
    private EditText etDeviceName;
    private Button btnMainAction;
    
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
     * 创建界面布局 (v3.14: 符合设计文档清爽风格)
     */
    private void createUI() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ));
        scrollView.setBackgroundColor(COLOR_WHITE);
        
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(60, 80, 60, 80);
        layout.setBackgroundColor(COLOR_WHITE);
        
        // ========== 头部图标和标题 ==========
        TextView tvIcon = new TextView(this);
        tvIcon.setText("🤖");
        tvIcon.setTextSize(48);
        tvIcon.setGravity(Gravity.CENTER);
        layout.addView(tvIcon);
        
        TextView tvTitle = new TextView(this);
        tvTitle.setText("OpenClaw");
        tvTitle.setTextSize(24);
        tvTitle.setTextColor(COLOR_TEXT_PRIMARY);
        tvTitle.setGravity(Gravity.CENTER);
        tvTitle.setPadding(0, 20, 0, 0);
        layout.addView(tvTitle);
        
        TextView tvSubtitle = new TextView(this);
        tvSubtitle.setText("远程控制助手");
        tvSubtitle.setTextSize(16);
        tvSubtitle.setTextColor(COLOR_TEXT_SECONDARY);
        tvSubtitle.setGravity(Gravity.CENTER);
        tvSubtitle.setPadding(0, 10, 0, 60);
        layout.addView(tvSubtitle);
        
        // ========== 状态卡片 ==========
        layout.addView(createStatusCard());
        
        // 分隔线
        layout.addView(createDivider());
        
        // ========== 权限状态 ==========
        layout.addView(createPermissionSection());
        
        // 分隔线
        layout.addView(createDivider());
        
        // ========== 连接设置 ==========
        layout.addView(createSettingsSection());
        
        // 分隔线
        layout.addView(createDivider());
        
        // ========== 主操作按钮 ==========
        btnMainAction = new Button(this);
        btnMainAction.setText("启动服务");
        btnMainAction.setTextSize(16);
        btnMainAction.setTextColor(COLOR_WHITE);
        btnMainAction.setPadding(40, 30, 40, 30);
        
        // 设置圆角背景
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(12);
        btnBg.setColor(COLOR_GREEN);
        btnMainAction.setBackground(btnBg);
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.setMargins(0, 40, 0, 40);
        btnMainAction.setLayoutParams(btnParams);
        btnMainAction.setOnClickListener(v -> onMainActionClick());
        layout.addView(btnMainAction);
        
        // ========== 底部统计 ==========
        TextView tvStats = new TextView(this);
        tvStats.setText("GhostTap v3.14");
        tvStats.setTextSize(12);
        tvStats.setTextColor(COLOR_GRAY);
        tvStats.setGravity(Gravity.CENTER);
        tvStats.setPadding(0, 20, 0, 0);
        layout.addView(tvStats);
        
        scrollView.addView(layout);
        setContentView(scrollView);
    }
    
    /**
     * 创建状态卡片（圆角卡片突出核心状态）
     */
    private View createStatusCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(50, 40, 50, 40);
        card.setGravity(Gravity.CENTER);
        
        // 圆角背景
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(20);
        bg.setColor(COLOR_LIGHT_GRAY);
        card.setBackground(bg);
        
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        card.setLayoutParams(params);
        
        // 状态点 + 标题
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setOrientation(LinearLayout.HORIZONTAL);
        statusRow.setGravity(Gravity.CENTER);
        
        dotStatus = new View(this);
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(24, 24);
        dotParams.setMargins(0, 0, 15, 0);
        dotStatus.setLayoutParams(dotParams);
        
        // 圆形状态点
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(COLOR_RED);
        dotStatus.setBackground(dotBg);
        statusRow.addView(dotStatus);
        
        tvStatusTitle = new TextView(this);
        tvStatusTitle.setText("未运行");
        tvStatusTitle.setTextSize(20);
        tvStatusTitle.setTextColor(COLOR_TEXT_PRIMARY);
        statusRow.addView(tvStatusTitle);
        
        card.addView(statusRow);
        
        // 副标题
        tvStatusSubtitle = new TextView(this);
        tvStatusSubtitle.setText("未连接云端");
        tvStatusSubtitle.setTextSize(14);
        tvStatusSubtitle.setTextColor(COLOR_TEXT_SECONDARY);
        tvStatusSubtitle.setGravity(Gravity.CENTER);
        tvStatusSubtitle.setPadding(0, 15, 0, 0);
        card.addView(tvStatusSubtitle);
        
        return card;
    }
    
    /**
     * 创建分隔线
     */
    private View createDivider() {
        View divider = new View(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            2
        );
        params.setMargins(0, 40, 0, 40);
        divider.setLayoutParams(params);
        divider.setBackgroundColor(0xFFEEEEEE);
        return divider;
    }
    
    /**
     * 创建权限状态区域
     */
    private View createPermissionSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("权限状态");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(COLOR_GRAY);
        tvTitle.setPadding(0, 0, 0, 20);
        layout.addView(tvTitle);
        
        // 无障碍权限
        LinearLayout row1 = createPermissionRow("无障碍权限");
        tvAccessibilityStatus = (TextView) row1.getChildAt(1);
        row1.setOnClickListener(v -> requestAccessibilityPermission());
        layout.addView(row1);
        
        // 悬浮窗权限
        LinearLayout row2 = createPermissionRow("悬浮窗权限");
        tvOverlayStatus = (TextView) row2.getChildAt(1);
        row2.setOnClickListener(v -> requestOverlayPermission());
        layout.addView(row2);
        
        // 后台运行
        LinearLayout row3 = createPermissionRow("后台运行");
        tvBackgroundStatus = (TextView) row3.getChildAt(1);
        tvBackgroundStatus.setText("● 已允许");
        tvBackgroundStatus.setTextColor(COLOR_GREEN);
        layout.addView(row3);
        
        return layout;
    }
    
    /**
     * 创建权限状态行
     */
    private LinearLayout createPermissionRow(String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, 15, 0, 15);
        
        TextView tvLabel = new TextView(this);
        tvLabel.setText(label);
        tvLabel.setTextSize(16);
        tvLabel.setTextColor(COLOR_TEXT_PRIMARY);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
        ));
        row.addView(tvLabel);
        
        TextView tvStatus = new TextView(this);
        tvStatus.setText("● 未开启");
        tvStatus.setTextSize(14);
        tvStatus.setTextColor(COLOR_RED);
        row.addView(tvStatus);
        
        return row;
    }
    
    /**
     * 创建设置区域
     */
    private View createSettingsSection() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        
        // 标题
        TextView tvTitle = new TextView(this);
        tvTitle.setText("连接设置");
        tvTitle.setTextSize(14);
        tvTitle.setTextColor(COLOR_GRAY);
        tvTitle.setPadding(0, 0, 0, 20);
        layout.addView(tvTitle);
        
        // 服务器地址
        TextView tvUrlLabel = new TextView(this);
        tvUrlLabel.setText("服务器地址");
        tvUrlLabel.setTextSize(14);
        tvUrlLabel.setTextColor(COLOR_TEXT_SECONDARY);
        tvUrlLabel.setPadding(0, 10, 0, 10);
        layout.addView(tvUrlLabel);
        
        etServerUrl = createEditText("wss://your-server.com/ws");
        layout.addView(etServerUrl);
        
        // 用户 ID
        TextView tvIdLabel = new TextView(this);
        tvIdLabel.setText("用户 ID");
        tvIdLabel.setTextSize(14);
        tvIdLabel.setTextColor(COLOR_TEXT_SECONDARY);
        tvIdLabel.setPadding(0, 25, 0, 10);
        layout.addView(tvIdLabel);
        
        etUserId = createEditText("留空自动生成");
        layout.addView(etUserId);
        
        // 设备名称
        TextView tvDeviceLabel = new TextView(this);
        tvDeviceLabel.setText("设备名称");
        tvDeviceLabel.setTextSize(14);
        tvDeviceLabel.setTextColor(COLOR_TEXT_SECONDARY);
        tvDeviceLabel.setPadding(0, 25, 0, 10);
        layout.addView(tvDeviceLabel);
        
        // v3.14: 默认显示系统设备名称（如 "Xiaomi 14"）
        etDeviceName = createEditText(Config.getDefaultDeviceName());
        layout.addView(etDeviceName);
        
        return layout;
    }
    
    /**
     * 创建输入框
     */
    private EditText createEditText(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setTextSize(14);
        editText.setPadding(20, 20, 20, 20);
        editText.setBackgroundColor(COLOR_LIGHT_GRAY);
        
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(8);
        bg.setColor(COLOR_LIGHT_GRAY);
        editText.setBackground(bg);
        
        return editText;
    }
    
    /**
     * 主按钮点击处理
     */
    private void onMainActionClick() {
        Object tag = btnMainAction.getTag();
        String action = tag != null ? tag.toString() : "start";
        
        switch (action) {
            case "stop":
                stopService();
                break;
            case "restart":
                // 重新连接：停止后用新配置重新启动
                restartService();
                break;
            case "start":
            default:
                // 检查必要权限
                if (!isAccessibilityEnabled()) {
                    Toast.makeText(this, "请先开启无障碍权限", Toast.LENGTH_LONG).show();
                    requestAccessibilityPermission();
                    return;
                }
                if (!canDrawOverlays()) {
                    Toast.makeText(this, "请先开启悬浮窗权限", Toast.LENGTH_LONG).show();
                    requestOverlayPermission();
                    return;
                }
                startService();
                break;
        }
    }
    
    /**
     * 重新启动服务（用新配置）
     */
    private void restartService() {
        // 保存新配置
        saveConfig();
        
        // 停止服务
        Intent stopIntent = new Intent(this, GhostTapService.class);
        stopService(stopIntent);
        
        // 等待服务完全停止后重新启动
        handler.postDelayed(() -> {
            // 检查服务器地址
            String serverUrl = etServerUrl.getText().toString().trim();
            if (serverUrl.isEmpty() || serverUrl.contains("your-server.com") || serverUrl.contains("example.com")) {
                Toast.makeText(this, "请先配置有效的服务器地址", Toast.LENGTH_LONG).show();
                etServerUrl.requestFocus();
                return;
            }
            
            // 启动服务
            Intent intent = new Intent(this, GhostTapService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
            Toast.makeText(this, "正在使用新配置连接...", Toast.LENGTH_SHORT).show();
        }, 800);
    }
    
    /**
     * 更新 UI 状态
     */
    private void updateUIState() {
        boolean isServiceRunning = GhostTapService.isRunning();
        boolean hasAccessibility = isAccessibilityEnabled();
        boolean hasOverlay = canDrawOverlays();
        
        // 检查无障碍权限是否真正开启
        if (!hasAccessibility) {
            isServiceRunning = false;
        }
        
        // 更新状态卡片
        if (isServiceRunning) {
            GhostTapService instance = GhostTapService.getInstance();
            boolean webSocketConnected = instance != null && instance.isWebSocketConnected();
            
            if (webSocketConnected) {
                // WebSocket 已连接
                tvStatusTitle.setText("运行中");
                tvStatusTitle.setTextColor(COLOR_GREEN);
                ((GradientDrawable) dotStatus.getBackground()).setColor(COLOR_GREEN);
                tvStatusSubtitle.setText("已连接云端");
            } else {
                // 服务运行但 WebSocket 未连接
                tvStatusTitle.setText("未就绪");
                tvStatusTitle.setTextColor(0xFFFF8800); // 橙色
                ((GradientDrawable) dotStatus.getBackground()).setColor(0xFFFF8800);
                tvStatusSubtitle.setText("连接失败，修改地址后点击重连");
            }
            
            btnMainAction.setText("重新连接");
            btnMainAction.setTag("restart");
            ((GradientDrawable) btnMainAction.getBackground()).setColor(0xFFFF8800); // 橙色
        } else {
            tvStatusTitle.setText("未运行");
            tvStatusTitle.setTextColor(COLOR_TEXT_PRIMARY);
            ((GradientDrawable) dotStatus.getBackground()).setColor(COLOR_RED);
            
            if (!hasAccessibility) {
                tvStatusSubtitle.setText("需要开启无障碍权限");
            } else {
                tvStatusSubtitle.setText("点击启动服务");
            }
            
            btnMainAction.setText("启动服务");
            ((GradientDrawable) btnMainAction.getBackground()).setColor(COLOR_GREEN);
        }
        
        // 更新权限状态
        updatePermissionStatus(tvAccessibilityStatus, hasAccessibility);
        updatePermissionStatus(tvOverlayStatus, hasOverlay);
        
        // 按钮始终可点击，但需要先检查权限
        // 不禁用按钮，而是在点击时提示用户
    }
    
    /**
     * 更新权限状态显示
     */
    private void updatePermissionStatus(TextView tvStatus, boolean granted) {
        if (granted) {
            tvStatus.setText("● 已开启");
            tvStatus.setTextColor(COLOR_GREEN);
        } else {
            tvStatus.setText("● 未开启");
            tvStatus.setTextColor(COLOR_RED);
        }
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
     * 注：权限检查已在 onMainActionClick() 中完成
     */
    private void startService() {
        // 检查服务器地址是否配置
        String serverUrl = etServerUrl.getText().toString().trim();
        if (serverUrl.isEmpty() || serverUrl.contains("your-server.com") || serverUrl.contains("example.com")) {
            Toast.makeText(this, "请先配置有效的服务器地址", Toast.LENGTH_LONG).show();
            etServerUrl.requestFocus();
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
        // 停止服务
        Intent intent = new Intent(this, GhostTapService.class);
        stopService(intent);
        
        // 立即更新 UI 状态
        handler.postDelayed(() -> updateUIState(), 500);
        
        Toast.makeText(this, "服务已停止", Toast.LENGTH_SHORT).show();
    }
    
    /**
     * 加载配置
     */
    private void loadConfig() {
        SharedPreferences prefs = getSharedPreferences(Config.PREFS_NAME, MODE_PRIVATE);
        
        String serverUrl = prefs.getString(Config.PREF_SERVER_URL, Config.SERVER_URL);
        String userId = prefs.getString(Config.PREF_USER_ID, "");
        
        // v3.14: 处理设备名称
        String savedDeviceName = prefs.getString(Config.PREF_DEVICE_NAME, null);
        String deviceName;
        if (savedDeviceName == null || savedDeviceName.equals("Android设夁")) {
            // 从未保存过，或保存的是旧版本默认值"安卓设备"，使用系统设备名
            deviceName = Config.getDefaultDeviceName();
            // 更新保存，避免下次重复检测
            prefs.edit().putString(Config.PREF_DEVICE_NAME, deviceName).apply();
        } else {
            deviceName = savedDeviceName;
        }
        
        etServerUrl.setText(serverUrl);
        etUserId.setText(userId);
        etDeviceName.setText(deviceName);
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
        
        String deviceName = etDeviceName.getText().toString();
        // v3.14: 如果用户清空，保存系统默认名
        editor.putString(Config.PREF_DEVICE_NAME, deviceName.isEmpty() ? Config.getDefaultDeviceName() : deviceName);
        
        editor.apply();
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        // 权限设置返回后刷新状态
        handler.postDelayed(this::updateUIState, 500);
    }
}
