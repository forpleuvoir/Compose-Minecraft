/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.ui.platform

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.InternalComposeUiApi
import androidx.savedstate.compose.LocalSavedStateRegistryOwner

/**
 * The CompositionLocal that provides information about Screen Reader state associated with
 * the current scene.
 */
@InternalComposeUiApi
val LocalPlatformScreenReader = staticCompositionLocalOf<PlatformScreenReader> {
    error("CompositionLocal LocalPlatformScreenReader not present")
}

// TODO: Remove as part of https://youtrack.jetbrains.com/issue/CMP-9379
/**
 * The CompositionLocal that provides information about window insets associated with current
 * scene.
 */
@InternalComposeUiApi
val LocalPlatformWindowInsets = staticCompositionLocalOf<PlatformWindowInsets> {
    error("CompositionLocal LocalPlatformWindowInsets not present")
}

// ─────────────────────────────────────────────────────────────────────────────
// Scene 组合局部(移植自 CMP ui-desktop 的 CompositionLocals.skiko.kt)
// ─────────────────────────────────────────────────────────────────────────────

private val PlatformArchitectureComponentsOwner.values: Array<ProvidedValue<*>>
    get() {
        val providedValues = mutableListOf<ProvidedValue<*>>(
            androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
            LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
        )
        return providedValues.toTypedArray()
    }

@Composable
internal fun ProvidePlatformCompositionLocals(
    vararg values: ProvidedValue<*>,
    platformContext: PlatformContext,
    content: @Composable () -> Unit,
) {
    // 与上游一致:由 id + owner 工厂创建(内部用 SavedStateRegistry 保存/恢复)
    val saveableStateRegistry = remember(platformContext) {
        DisposableSaveableStateRegistry(
            id = "ComposeContainer",
            savedStateRegistryOwner = platformContext.architectureComponentsOwner.savedStateRegistryOwner,
        )
    }
    DisposableEffect(platformContext) {
        onDispose { saveableStateRegistry.dispose() }
    }

    // TODO: CMP-9752 完整实现 HostDefaultProvider 后再对齐
    val hostDefaultProvider = remember(platformContext) {
        object : HostDefaultProvider {
            @Suppress("UNCHECKED_CAST")
            override fun <T> getHostDefault(key: HostDefaultKey<T>): T {
                return platformContext.architectureComponentsOwner as T
            }
        }
    }

    CompositionLocalProvider(
        *values,
        LocalPlatformScreenReader provides platformContext.screenReader,
        LocalPlatformWindowInsets provides platformContext.windowInsets,
        *platformContext.architectureComponentsOwner.values,
        LocalSaveableStateRegistry provides saveableStateRegistry,
        LocalHostDefaultProvider provides hostDefaultProvider,
        content = content,
    )
}
