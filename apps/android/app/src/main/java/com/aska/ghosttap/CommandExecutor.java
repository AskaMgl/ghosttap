package com.aska.ghosttap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

/**
 * 指令执行器 (v3.12)
 * 
 * 职责：
 * 1. 执行云端下发的动作指令
 * 2. 坐标转换（百分比 → 绝对坐标）
 * 3. 使用无障碍 API 执行操作
 * 4. 实现 input 动作的三层防线（v3.12）
 * 5. 支持 launch_app 和 wait 动作（v3.12）
 */
public class CommandExecutor {
    
    private static final String TAG = Config.LOG_TAG + ".Executor";
    
    // 默认滑动持续时间（毫秒）
    private static final long DEFAULT_SWIPE_DURATION = 300L;
    
    // 默认滑动距离（屏幕百分比）
    private static final float DEFAULT_SWIPE_DISTANCE = 30f;
    
    // 点击持续时间（毫秒）
    private static final long CLICK_DURATION = 100L;
    
    private final AccessibilityService service;
    private final Context context;
    
    public CommandExecutor(AccessibilityService service) {
        this.service = service;
        this.context = service.getApplicationContext();
    }
    
    /**
     * 在指定坐标点击（百分比坐标）
     * 
     * @param centerX 百分比 X 坐标 [0-100]
     * @param centerY 百分比 Y 坐标 [0-100]
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 是否成功
     */
    public boolean clickAtPercent(float centerX, float centerY, int screenWidth, int screenHeight) {
        int x = (int) (centerX / 100 * screenWidth);
        int y = (int) (centerY / 100 * screenHeight);
        return clickAt(x, y);
    }
    
    /**
     * 在指定坐标点击（绝对坐标）
     * 
     * @param x 屏幕 X 坐标
     * @param y 屏幕 Y 坐标
     * @return 是否成功
     */
    public boolean clickAt(int x, int y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Click at coordinates requires Android N+");
            return false;
        }
        
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "Clicking at (" + x + ", " + y + ")");
        }
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, CLICK_DURATION))
            .build();
        
        return service.dispatchGesture(gesture, null, null);
    }
    
    /**
     * v3.12: 输入文本（三层防线）
     * 
     * 防线1: 通过坐标命中查找可编辑节点 → ACTION_SET_TEXT
     * 防线2: 点击坐标聚焦 → findFocus(FOCUS_INPUT) → ACTION_SET_TEXT
     * 防线3: 剪贴板粘贴兜底
     * 
     * @param centerX 百分比 X 坐标 [0-100]
     * @param centerY 百分比 Y 坐标 [0-100]
     * @param text 要输入的文本
     * @param rootNode 当前根节点（用于防线1查找）
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 是否成功
     */
    public boolean inputText(float centerX, float centerY, String text, 
                             AccessibilityNodeInfo rootNode, 
                             int screenWidth, int screenHeight) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        
        int x = (int) (centerX / 100 * screenWidth);
        int y = (int) (centerY / 100 * screenHeight);
        
        Log.i(TAG, "Input text at (" + x + ", " + y + "): " + text.substring(0, Math.min(20, text.length())) + "...");
        
        // ========== 防线1: 坐标命中查找可编辑节点 ==========
        if (rootNode != null) {
            AccessibilityNodeInfo targetNode = findEditableNodeAtPosition(rootNode, x, y);
            if (targetNode != null) {
                boolean success = performSetText(targetNode, text);
                if (success) {
                    Log.d(TAG, "Input success via method 1: direct editable node");
                    return true;
                }
            }
        }
        
        // ========== 防线2: 点击聚焦后获取焦点节点 ==========
        // 先点击坐标聚焦
        clickAt(x, y);
        
        // 等待聚焦完成
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // 获取当前聚焦的输入框
        AccessibilityNodeInfo focusedNode = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusedNode != null && focusedNode.isEditable()) {
            boolean success = performSetText(focusedNode, text);
            if (success) {
                Log.d(TAG, "Input success via method 2: focus node");
                return true;
            }
        }
        
        // ========== 防线3: 剪贴板粘贴 ==========
        Log.w(TAG, "Falling back to clipboard paste");
        boolean success = clipboardPaste(text);
        if (success) {
            Log.d(TAG, "Input success via method 3: clipboard paste");
        }
        return success;
    }
    
    /**
     * 执行 ACTION_SET_TEXT
     */
    private boolean performSetText(AccessibilityNodeInfo node, String text) {
        // 先聚焦
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        
        // 设置文本
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        
        boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "ACTION_SET_TEXT result: " + result);
        }
        
        return result;
    }
    
    /**
     * 通过坐标查找可编辑节点（深度优先）
     */
    private AccessibilityNodeInfo findEditableNodeAtPosition(AccessibilityNodeInfo node, int x, int y) {
        if (node == null) return null;
        
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        
        // 检查坐标是否在节点范围内
        if (!rect.contains(x, y)) {
            return null;
        }
        
        // 深度优先：优先返回更深层（更具体）的匹配
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            
            AccessibilityNodeInfo result = findEditableNodeAtPosition(child, x, y);
            if (result != null) {
                // 注意：如果 result == child（child 自身 isEditable），不能 recycle
                if (result != child) {
                    child.recycle();
                }
                return result;
            }
            child.recycle();
        }
        
        // 当前节点包含坐标且可编辑
        if (node.isEditable()) {
            return node;
        }
        
        return null;
    }
    
    /**
     * 剪贴板粘贴（防线3兜底）
     */
    private boolean clipboardPaste(String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                Log.e(TAG, "ClipboardManager not available");
                return false;
            }
            
            ClipData clip = ClipData.newPlainText("GhostTap", text);
            clipboard.setPrimaryClip(clip);
            
            // 粘贴操作
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (focused != null) {
                    focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                    return true;
                }
            }
            
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Clipboard paste failed", e);
            return false;
        }
    }
    
    /**
     * v3.12: 滑动操作（支持可选参数）
     * 
     * @param direction 方向: "up", "down", "left", "right"
     * @param centerX 起点百分比 X 坐标 [0-100]，null 表示屏幕中心
     * @param centerY 起点百分比 Y 坐标 [0-100]，null 表示屏幕中心
     * @param distance 滑动距离（屏幕百分比），null 使用默认 30%
     * @param durationMs 滑动时长（毫秒），null 使用默认 300ms
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return 是否成功
     */
    public boolean swipe(String direction, Float centerX, Float centerY, 
                         Float distance, Integer durationMs,
                         int screenWidth, int screenHeight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Swipe requires Android N+");
            return false;
        }
        
        // 默认值
        float startXPercent = centerX != null ? centerX : 50f;
        float startYPercent = centerY != null ? centerY : 50f;
        float swipeDistancePercent = distance != null ? distance : DEFAULT_SWIPE_DISTANCE;
        long swipeDuration = durationMs != null ? durationMs : DEFAULT_SWIPE_DURATION;
        
        // 计算起点坐标
        int startX = (int) (startXPercent / 100 * screenWidth);
        int startY = (int) (startYPercent / 100 * screenHeight);
        
        // 根据方向计算终点坐标
        int endX = startX;
        int endY = startY;
        
        switch (direction.toLowerCase()) {
            case "up":
                endY = (int) (startY - swipeDistancePercent / 100 * screenHeight);
                break;
            case "down":
                endY = (int) (startY + swipeDistancePercent / 100 * screenHeight);
                break;
            case "left":
                endX = (int) (startX - swipeDistancePercent / 100 * screenWidth);
                break;
            case "right":
                endX = (int) (startX + swipeDistancePercent / 100 * screenWidth);
                break;
            default:
                Log.w(TAG, "Unknown swipe direction: " + direction);
                return false;
        }
        
        // 边界检查
        endX = Math.max(0, Math.min(endX, screenWidth));
        endY = Math.max(0, Math.min(endY, screenHeight));
        
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "Swipe " + direction + " from (" + startX + ", " + startY + 
                  ") to (" + endX + ", " + endY + ") duration=" + swipeDuration + "ms");
        }
        
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, swipeDuration))
            .build();
        
        return service.dispatchGesture(gesture, null, null);
    }
    
    /**
     * 执行返回操作
     */
    public boolean back() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
    }
    
    /**
     * 执行 Home 操作
     */
    public boolean home() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
    }
    
    /**
     * v3.12: 启动指定 APP
     * 
     * @param packageName APP 包名
     * @return 是否成功
     */
    public boolean launchApp(String packageName) {
        if (packageName == null || packageName.isEmpty()) {
            return false;
        }
        
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                Log.i(TAG, "Launched app: " + packageName);
                return true;
            } else {
                Log.e(TAG, "Package not found: " + packageName);
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch app: " + packageName, e);
            return false;
        }
    }
    
    /**
     * v3.12: 等待指定时间
     * 
     * @param waitMs 等待毫秒数
     */
    public void waitMs(long waitMs) {
        if (waitMs <= 0) return;
        
        try {
            Thread.sleep(waitMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
}
