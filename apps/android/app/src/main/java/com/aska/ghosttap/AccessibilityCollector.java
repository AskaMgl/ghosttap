package com.aska.ghosttap;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * UI 采集器 (v3.12)
 * 
 * 职责：
 * 1. 从无障碍根节点遍历 UI 树
 * 2. 预过滤可交互元素
 * 3. 百分比坐标转换
 * 4. 软键盘检测（v3.12 新增）
 * 5. 生成 UiEventMessage
 */
public class AccessibilityCollector {
    
    private static final String TAG = Config.LOG_TAG + ".Collector";
    
    // 节点 ID 映射缓存（用于后续查找）
    private int nextElementId = 0;
    
    // 屏幕尺寸缓存
    private int screenWidth = 1080;
    private int screenHeight = 2400;
    
    /**
     * 键盘状态信息
     */
    public static class KeyboardState {
        public final boolean visible;
        public final float heightPercent;
        
        public KeyboardState(boolean visible, float heightPercent) {
            this.visible = visible;
            this.heightPercent = heightPercent;
        }
    }
    
    /**
     * 采集 UI 信息
     * 
     * @param service AccessibilityService 用于获取窗口信息
     * @param rootNode 无障碍根节点
     * @param sessionId 会话 ID
     * @param packageName 当前应用包名
     * @param activity 当前 Activity 类名
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return UiEventMessage UI 事件消息
     */
    public UiEventMessage collect(
            AccessibilityService service,
            AccessibilityNodeInfo rootNode,
            String sessionId,
            String packageName,
            String activity,
            int screenWidth,
            int screenHeight) {
        
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        
        // 重置元素 ID 计数
        nextElementId = 0;
        
        // v3.12: 检测软键盘状态
        KeyboardState keyboardState = detectKeyboard(service);
        
        // 遍历并收集可交互元素
        List<UiElement> elements = new ArrayList<>();
        int[] originalNodeCount = new int[]{0};
        
        traverseNode(rootNode, elements, originalNodeCount, screenWidth, screenHeight);
        
        // v3.12: 检查是否被截断
        boolean truncated = elements.size() >= Config.MAX_UI_ELEMENTS;
        
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "Collected " + elements.size() + " elements from " + 
                  originalNodeCount[0] + " nodes, keyboard=" + keyboardState.visible);
        }
        
        String orientation = screenHeight > screenWidth ? "portrait" : "landscape";
        
        // v3.12: 创建包含键盘信息的 ScreenInfo
        ScreenInfo screenInfo = new ScreenInfo(
            screenWidth, 
            screenHeight, 
            orientation,
            keyboardState.visible,
            keyboardState.heightPercent
        );
        
        return new UiEventMessage(
            System.currentTimeMillis(),
            sessionId,
            packageName,
            activity,
            screenInfo,
            elements,
            new UiStats(originalNodeCount[0], elements.size(), truncated)
        );
    }
    
    /**
     * v3.12: 检测软键盘状态
     * 
     * 通过 AccessibilityWindowInfo 检测输入法窗口
     * 需要在 accessibility_service_config.xml 中设置:
     * android:accessibilityFlags="flagRetrieveInteractiveWindows"
     */
    public KeyboardState detectKeyboard(AccessibilityService service) {
        if (service == null) {
            return new KeyboardState(false, 0f);
        }
        
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null || windows.isEmpty()) {
                return new KeyboardState(false, 0f);
            }
            
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect rect = new Rect();
                    window.getBoundsInScreen(rect);
                    float keyboardHeightPercent = (float) rect.height() / screenHeight * 100;
                    
                    if (Config.DEBUG_MODE) {
                        Log.d(TAG, "Keyboard detected: height=" + rect.height() + 
                              "px (" + keyboardHeightPercent + "%)");
                    }
                    
                    return new KeyboardState(true, keyboardHeightPercent);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to detect keyboard", e);
        }
        
        return new KeyboardState(false, 0f);
    }
    
    /**
     * v3.13: 获取应用程序窗口的根节点（过滤系统覆盖层）
     * 
     * 设计文档要求: 优先采集 TYPE_APPLICATION 类型窗口，
     * 忽略 TYPE_ACCESSIBILITY_OVERLAY 类型窗口，避免其他 APP 悬浮窗干扰
     * 
     * @param service AccessibilityService
     * @return 应用程序窗口的根节点，如果没有则返回 null
     */
    public AccessibilityNodeInfo getApplicationWindowRoot(AccessibilityService service) {
        if (service == null) {
            return null;
        }
        
        try {
            List<AccessibilityWindowInfo> windows = service.getWindows();
            if (windows == null || windows.isEmpty()) {
                return service.getRootInActiveWindow();
            }
            
            // 优先查找 TYPE_APPLICATION 窗口
            AccessibilityWindowInfo appWindow = null;
            AccessibilityWindowInfo activeWindow = null;
            
            for (AccessibilityWindowInfo window : windows) {
                int type = window.getType();
                
                // 忽略系统覆盖层和其他应用的悬浮窗
                if (type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    if (Config.DEBUG_MODE) {
                        Log.d(TAG, "Skipping TYPE_ACCESSIBILITY_OVERLAY window");
                    }
                    continue;
                }
                
                // 记录应用程序窗口
                if (type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    appWindow = window;
                    // 如果是活动窗口，优先使用
                    if (window.isActive()) {
                        if (Config.DEBUG_MODE) {
                            Log.d(TAG, "Found active TYPE_APPLICATION window");
                        }
                        return window.getRoot();
                    }
                }
                
                // 记录活动窗口（备用）
                if (window.isActive()) {
                    activeWindow = window;
                }
            }
            
            // 返回找到的应用程序窗口，或活动窗口，或默认根节点
            if (appWindow != null) {
                if (Config.DEBUG_MODE) {
                    Log.d(TAG, "Using TYPE_APPLICATION window");
                }
                return appWindow.getRoot();
            }
            
            if (activeWindow != null) {
                if (Config.DEBUG_MODE) {
                    Log.d(TAG, "Using active window");
                }
                return activeWindow.getRoot();
            }
            
        } catch (Exception e) {
            Log.w(TAG, "Failed to get application window", e);
        }
        
        // 兜底：返回默认根节点
        return service.getRootInActiveWindow();
    }
    
    /**
     * 递归遍历节点树
     */
    private void traverseNode(
            AccessibilityNodeInfo node,
            List<UiElement> elements,
            int[] originalCount,
            int screenWidth,
            int screenHeight) {
        
        originalCount[0]++;
        
        // 检查是否应该包含此节点
        if (shouldIncludeNode(node)) {
            UiElement element = nodeToElement(node, nextElementId++, screenWidth, screenHeight);
            if (element != null) {
                elements.add(element);
                
                // 限制最大元素数量
                if (elements.size() >= Config.MAX_UI_ELEMENTS) {
                    return;
                }
            }
        }
        
        // 递归处理子节点
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            traverseNode(child, elements, originalCount, screenWidth, screenHeight);
            child.recycle();
            
            // 检查是否已达到最大数量
            if (elements.size() >= Config.MAX_UI_ELEMENTS) {
                return;
            }
        }
    }
    
    /**
     * 判断节点是否应该被包含
     * 
     * 保留条件：
     * - 可点击、可聚焦、可编辑
     * - 有文本内容或描述
     * - 可见
     */
    private boolean shouldIncludeNode(AccessibilityNodeInfo node) {
        // 必须可见
        if (!node.isVisibleToUser()) {
            return false;
        }
        
        // 获取节点边界
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        
        // 检查尺寸（防止过小元素）
        float widthPercent = (bounds.width() * 100f / screenWidth);
        float heightPercent = (bounds.height() * 100f / screenHeight);
        
        if (widthPercent < Config.MIN_ELEMENT_SIZE || heightPercent < Config.MIN_ELEMENT_SIZE) {
            return false;
        }
        
        // 可交互检查
        boolean isClickable = node.isClickable();
        boolean isFocusable = node.isFocusable();
        boolean isEditable = node.isEditable();
        
        // 有内容检查
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();
        boolean hasText = nodeText != null && !nodeText.toString().trim().isEmpty();
        boolean hasDesc = nodeDesc != null && !nodeDesc.toString().trim().isEmpty();
        
        // 如果是纯布局容器且不可点击，则排除
        CharSequence classNameSeq = node.getClassName();
        String className = classNameSeq != null ? classNameSeq.toString() : "";
        boolean isLayout = className.contains("Layout") || 
                           className.contains("ViewGroup") ||
                           className.contains("RecyclerView") ||
                           className.contains("ListView") ||
                           className.contains("ScrollView");
        
        if (isLayout && !isClickable && !isFocusable) {
            return false;
        }
        
        // 满足可交互或有内容之一即可
        return isClickable || isFocusable || isEditable || hasText || hasDesc;
    }
    
    /**
     * 将节点转换为 UI 元素
     */
    private UiElement nodeToElement(
            AccessibilityNodeInfo node,
            int elementId,
            int screenWidth,
            int screenHeight) {
        
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        
        // 计算百分比坐标
        float x1Percent = (bounds.left * 100f / screenWidth);
        float y1Percent = (bounds.top * 100f / screenHeight);
        float x2Percent = (bounds.right * 100f / screenWidth);
        float y2Percent = (bounds.bottom * 100f / screenHeight);
        
        float centerX = (x1Percent + x2Percent) / 2;
        float centerY = (y1Percent + y2Percent) / 2;
        
        // 确定元素类型
        String type;
        if (node.isEditable()) {
            type = "input";
        } else if (node.isClickable()) {
            type = "btn";
        } else {
            CharSequence text = node.getText();
            if (text != null && !text.toString().trim().isEmpty()) {
                type = "text";
            } else {
                type = "other";
            }
        }
        
        // 确定可用动作
        List<String> actions = new ArrayList<>();
        if (node.isClickable() || node.isFocusable()) actions.add("click");
        if (node.isEditable()) actions.add("input");
        
        // 构建位置列表
        List<Float> pos = new ArrayList<>();
        pos.add(x1Percent);
        pos.add(y1Percent);
        pos.add(x2Percent);
        pos.add(y2Percent);
        
        // 构建中心点列表
        List<Float> center = new ArrayList<>();
        center.add(centerX);
        center.add(centerY);
        
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();
        
        return new UiElement(
            elementId,
            type,
            nodeText != null ? nodeText.toString() : null,
            nodeDesc != null ? nodeDesc.toString() : null,
            pos,
            center,
            actions
        );
    }
    
    /**
     * v3.12: 通过坐标查找最深层的可编辑节点
     * 
     * 用于 input 动作的三层防线之一
     * 
     * @param rootNode 根节点
     * @param x 屏幕 X 坐标
     * @param y 屏幕 Y 坐标
     * @return 可编辑节点，未找到返回 null
     */
    public AccessibilityNodeInfo findEditableNodeAtPosition(
            AccessibilityNodeInfo rootNode, int x, int y) {
        if (rootNode == null) return null;
        return findDeepestEditable(rootNode, x, y);
    }
    
    /**
     * 递归查找最深层的可编辑节点（深度优先）
     */
    private AccessibilityNodeInfo findDeepestEditable(AccessibilityNodeInfo node, int x, int y) {
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
            
            AccessibilityNodeInfo result = findDeepestEditable(child, x, y);
            if (result != null) {
                // 注意：如果 result == child，不能 recycle
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
}
