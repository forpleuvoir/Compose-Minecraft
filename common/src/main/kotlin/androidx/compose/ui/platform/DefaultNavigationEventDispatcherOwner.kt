/*
 * Copyright 2025 The Android Open Source Project
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
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigationevent.NavigationEventDispatcherOwner

/**
 * Internal helper to provide [NavigationEventDispatcherOwner] from Compose UI module.
 * In applications please use [androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner].
 *
 * @hide
 */
internal val LocalInternalNavigationEventDispatcherOwner =
    staticCompositionLocalOf<NavigationEventDispatcherOwner?> { null }

/**
 * 兼容导航事件分发器(上游位于 ui-backhandler 模块;本平台第一版不引入该模块,
 * 在 platform 包内提供等效内部局部)。
 */
internal val LocalCompatNavigationEventDispatcherOwner =
    staticCompositionLocalOf<NavigationEventDispatcherOwner?> { null }


@InternalComposeApi
@Composable
fun findDefaultNavigationEventDispatcherOwner(): NavigationEventDispatcherOwner? =
    LocalInternalNavigationEventDispatcherOwner.current
        ?: LocalCompatNavigationEventDispatcherOwner.current