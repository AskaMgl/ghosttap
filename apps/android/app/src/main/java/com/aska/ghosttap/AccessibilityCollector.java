package com.aska.ghosttap;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * UI 采集器
 * 
 * 职责：
 * 1. 从无障碍根节点遍历 UI 树
 * 2. 预过滤可交互元素
 * 3. 百分比坐标转换
 * 4. 生成 UiEventMessage
 */
public class AccessibilityCollector {
    
    private static final String TAG = Config.LOG_TAG + ".Collector";
    
    // 节点 ID 映射缓存（用于后续查找）
    private final Map<Integer, AccessibilityNodeInfo> nodeIdMap = new HashMap<>();
    private int nextElementId = 0;
    
    /**
     * 采集 UI 信息
     * 
     * @param rootNode 无障碍根节点
     * @param userId 用户 ID
     * @param sessionId 会话 ID
     * @param packageName 当前应用包名
     * @param activity 当前 Activity 类名
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     * @return UiEventMessage UI 事件消息
     */
    public UiEventMessage collect(
            AccessibilityNodeInfo rootNode,
            String userId,
            String sessionId,
            String packageName,
            String activity,
            int screenWidth,
            int screenHeight) {
        
        // 清空之前的节点映射
        nodeIdMap.clear();
        nextElementId = 0;
        
        // 遍历并收集可交互元素
        List<UiElement> elements = new ArrayList<>();
        int[] originalNodeCount = new int[]{0};
        
        traverseNode(rootNode, elements, originalNodeCount, screenWidth, screenHeight);
        
        if (Config.DEBUG_MODE) {
            Log.d(TAG, "Collected " + elements.size() + " elements from " + originalNodeCount[0] + " nodes");
        }
        
        String orientation = screenHeight > screenWidth ? "portrait" : "landscape";
        
        return new UiEventMessage(
            System.currentTimeMillis(),
            userId,
            sessionId,
            packageName,
            activity,
            new ScreenInfo(screenWidth, screenHeight, orientation),
            elements,
            new UiStats(originalNodeCount[0], elements.size())
        );
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
            UiElement element = nodeToElement(node, elements.size(), screenWidth, screenHeight);
            if (element != null) {
                elements.add(element);
                
                // 缓存节点引用（使用虚拟 ID）
                nodeIdMap.put(element.id, node);
                
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
     * - 可点击、可聚焦、可编辑或可滚动
     * - 有文本内容或描述
     * - 可见
     * 
     * 排除条件：
     * - 纯布局容器（如 LinearLayout, FrameLayout）且不可点击
     * - 尺寸过小
     * - 不可见
     */
    private boolean shouldIncludeNode(AccessibilityNodeInfo node) {
        // 必须可见
        if (!node.isVisibleToUser()) {
            return false;
        }
        
        // 获取节点边界
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        
        // 检查尺寸
        float widthPercent = (bounds.width() * 100f / 1080);
        float heightPercent = (bounds.height() * 100f / 2400);
        
        if (widthPercent < Config.MIN_ELEMENT_SIZE || heightPercent < Config.MIN_ELEMENT_SIZE) {
            return false;
        }
        
        // 可交互检查
        boolean isClickable = node.isClickable();
        boolean isFocusable = node.isFocusable();
        boolean isEditable = node.isEditable();
        boolean isScrollable = node.isScrollable();
        
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
        
        if (isLayout && !isClickable && !isFocusable && !isScrollable) {
            return false;
        }
        
        // 满足可交互或有内容之一即可
        return isClickable || isFocusable || isEditable || isScrollable || hasText || hasDesc;
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
        } else if (node.isScrollable()) {
            type = "scroll";
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
        if (node.isScrollable()) actions.add("swipe");
        
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
     * 根据虚拟 ID 查找节点
     */
    public AccessibilityNodeInfo findNodeByVirtualId(AccessibilityNodeInfo rootNode, int elementId) {
        return nodeIdMap.get(elementId);
    }
}
