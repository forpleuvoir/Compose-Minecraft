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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.HostDefaultKey
import androidx.compose.runtime.HostDefaultProvider
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.LocalHostDefaultProvider
import androidx.compose.runtime.ProvidedValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.InternalComposeUiApi
import androidx.navigationevent.NavigationEventDispatcherOwner
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
//
// LocalInternalNavigationEventDispatcherOwner / LocalCompatNavigationEventDispatcherOwner
// 定义在 DefaultNavigationEventDispatcherOwner.kt(platform 包内),本文件不再重复。
// ─────────────────────────────────────────────────────────────────────────────

private val PlatformArchitectureComponentsOwner.values: Array<ProvidedValue<*>>
    get() {
        val providedValues = mutableListOf(
            androidx.lifecycle.compose.LocalLifecycleOwner provides lifecycleOwner,
            LocalInternalNavigationEventDispatcherOwner provides navigationEventDispatcherOwner,
            LocalCompatNavigationEventDispatcherOwner provides navigationEventDispatcherOwner,
            LocalSavedStateRegistryOwner provides savedStateRegistryOwner,
        )
        viewModelStoreOwner?.let { providedValues.add(LocalInternalViewModelStoreOwner provides it) }
        return providedValues.toTypedArray()
    }

@OptIn(InternalComposeApi::class)
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
        val registry = saveableStateRegistry
        onDispose { registry.dispose() }
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
