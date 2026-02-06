package com.aska.ghosttap;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

/**
 * 指令执行器
 * 
 * 职责：
 * 1. 执行云端下发的动作指令
 * 2. 坐标转换（百分比 → 绝对坐标）
 * 3. 使用无障碍 API 执行操作
 */
public class CommandExecutor {
    
    private static final String TAG = Config.LOG_TAG + ".Executor";
    
    // 滑动持续时间（毫秒）
    private static final long SWIPE_DURATION = 300L;
    
    // 点击持续时间（毫秒）
    private static final long CLICK_DURATION = 100L;
    
    private final AccessibilityService service;
    
    public CommandExecutor(AccessibilityService service) {
        this.service = service;
    }
    
    /**
     * 点击指定节点
     * 
     * @param node 目标节点
     * @return 是否成功
     */
    public boolean click(AccessibilityNodeInfo node) {
        // 尝试使用 ACTION_CLICK
        if (node.isClickable()) {
            boolean result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            if (result) return true;
        }
        
        // 如果节点本身不可点击，尝试点击其父节点
        AccessibilityNodeInfo parent = node.getParent();
        int attempts = 0;
        while (parent != null && attempts < 3) {
            if (parent.isClickable()) {
                boolean result = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                parent.recycle();
                if (result) return true;
            }
            AccessibilityNodeInfo grandParent = parent.getParent();
            parent.recycle();
            parent = grandParent;
            attempts++;
        }
        
        // 如果还是失败，使用手势点击节点中心
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        int x = bounds.centerX();
        int y = bounds.centerY();
        
        return clickAt(x, y);
    }
    
    /**
     * 在指定坐标点击
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
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, CLICK_DURATION))
            .build();
        
        return service.dispatchGesture(gesture, null, null);
    }
    
    /**
     * 长按指定节点
     * 
     * @param node 目标节点
     * @param durationMs 长按持续时间（毫秒）
     * @return 是否成功
     */
    public boolean longClick(AccessibilityNodeInfo node, long durationMs) {
        // 尝试使用 ACTION_LONG_CLICK
        if (node.isLongClickable()) {
            boolean result = node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK);
            if (result) return true;
        }
        
        // 使用手势长按
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        
        return longClickAt(bounds.centerX(), bounds.centerY(), durationMs);
    }
    
    /**
     * 在指定坐标长按
     * 
     * @param x 屏幕 X 坐标
     * @param y 屏幕 Y 坐标
     * @param durationMs 长按持续时间（毫秒）
     * @return 是否成功
     */
    public boolean longClickAt(int x, int y, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Long click requires Android N+");
            return false;
        }
        
        Path path = new Path();
        path.moveTo(x, y);
        
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
            .build();
        
        return service.dispatchGesture(gesture, null, null);
    }
    
    /**
     * 输入文本
     * 
     * @param node 输入框节点
     * @param text 要输入的文本
     * @return 是否成功
     */
    public boolean inputText(AccessibilityNodeInfo node, String text) {
        // 首先聚焦输入框
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        
        // Android O+ 使用 ARGUMENT_SET_TEXT_CHARSEQUENCE
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        
        boolean result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "Input text result: " + result);
        }
        
        return result;
    }
    
    /**
     * 滑动操作
     * 
     * @param startX 起始 X 坐标
     * @param startY 起始 Y 坐标
     * @param endX 结束 X 坐标
     * @param endY 结束 Y 坐标
     * @param durationMs 滑动持续时间（毫秒）
     * @return 是否成功
     */
    public boolean swipe(int startX, int startY, int endX, int endY, long durationMs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w(TAG, "Swipe requires Android N+");
            return false;
        }
        
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        
        GestureDescription gesture = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
            .build();
        
        return service.dispatchGesture(gesture, null, null);
    }
    
    /**
     * 向上滑动
     */
    public boolean swipeUp(int screenWidth, int screenHeight) {
        int startX = screenWidth / 2;
        int startY = screenHeight * 3 / 4;
        int endY = screenHeight / 4;
        return swipe(startX, startY, startX, endY, SWIPE_DURATION);
    }
    
    /**
     * 向下滑动
     */
    public boolean swipeDown(int screenWidth, int screenHeight) {
        int startX = screenWidth / 2;
        int startY = screenHeight / 4;
        int endY = screenHeight * 3 / 4;
        return swipe(startX, startY, startX, endY, SWIPE_DURATION);
    }
    
    /**
     * 向左滑动
     */
    public boolean swipeLeft(int screenWidth, int screenHeight) {
        int startX = screenWidth * 3 / 4;
        int endX = screenWidth / 4;
        int y = screenHeight / 2;
        return swipe(startX, y, endX, y, SWIPE_DURATION);
    }
    
    /**
     * 向右滑动
     */
    public boolean swipeRight(int screenWidth, int screenHeight) {
        int startX = screenWidth / 4;
        int endX = screenWidth * 3 / 4;
        int y = screenHeight / 2;
        return swipe(startX, y, endX, y, SWIPE_DURATION);
    }
    
    /**
     * 滚动节点
     * 
     * @param node 可滚动节点
     * @param forward true 向前滚动，false 向后滚动
     * @return 是否成功
     */
    public boolean scroll(AccessibilityNodeInfo node, boolean forward) {
        int action = forward ?
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD :
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
        
        return node.performAction(action);
    }
    
    /**
     * 选择节点（用于复选框、单选按钮等）
     * 
     * @param node 目标节点
     * @param selected 是否选中
     * @return 是否成功
     */
    public boolean select(AccessibilityNodeInfo node, boolean selected) {
        Bundle args = new Bundle();
        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_BOOLEAN, selected);
        
        return node.performAction(AccessibilityNodeInfo.ACTION_SELECT, args);
    }
    
    /**
     * 清除输入框内容
     * 
     * @param node 输入框节点
     * @return 是否成功
     */
    public boolean clear(AccessibilityNodeInfo node) {
        return inputText(node, "");
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
     * 执行最近任务操作
     */
    public boolean recentApps() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS);
    }
    
    /**
     * 执行电源菜单操作
     */
    public boolean powerDialog() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG);
    }
    
    /**
     * 执行通知栏操作
     */
    public boolean notifications() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
    }
    
    /**
     * 执行快捷设置操作
     */
    public boolean quickSettings() {
        return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS);
    }
}
