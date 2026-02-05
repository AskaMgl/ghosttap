# Protocol Definitions

共享协议定义，用于 Android 和 Server。

## TypeScript

```typescript
// types.ts
export interface UiEvent {
  type: 'ui_event';
  user_id: string;
  session_id: string;
  package: string;
  activity: string;
  screen: {
    width: number;
    height: number;
    orientation: 'portrait' | 'landscape';
  };
  elements: Element[];
}

export interface ActionCommand {
  type: 'action';
  action: 'click' | 'input' | 'swipe' | 'back' | 'pause' | 'done';
  target?: {
    element_id?: number;
    center: [number, number];
  };
  text?: string;
  direction?: 'up' | 'down' | 'left' | 'right';
  reason?: string;
}
```

## Kotlin

```kotlin
// types.kt
data class UiEvent(
    val type: String = "ui_event",
    val user_id: String,
    val session_id: String,
    val package: String,
    val activity: String,
    val screen: Screen,
    val elements: List<Element>
)

data class ActionCommand(
    val type: String = "action",
    val action: String,
    val target: Target?,
    val text: String?,
    val direction: String?,
    val reason: String?
)
```
