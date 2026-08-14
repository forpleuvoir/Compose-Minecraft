package androidx.compose.ui.viewinterop

/**
 * 原 Desktop 平台为 AWT Component;Minecraft 平台第一版不嵌入任何原生视图,
 * 统一用 [Any] 作为占位类型。
 */
internal typealias InteropView = Any
