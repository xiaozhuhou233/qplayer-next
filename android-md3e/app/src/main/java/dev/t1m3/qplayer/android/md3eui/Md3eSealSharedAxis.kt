package dev.t1m3.qplayer.android.md3eui

/*
 * Copyright 2021 SOUP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

// Ported from the legacy MaterialSharedAxis.kt, itself imported from Seal/SOUP.
internal object Md3eSealSharedAxis {
    const val duration = 300
    private const val outgoingFraction = 0.35f
    private val Int.outgoing get() = (this * outgoingFraction).toInt()
    private val Int.incoming get() = this - outgoing

    fun x(enter: (Int) -> Int, exit: (Int) -> Int, durationMillis: Int = duration): ContentTransform =
        xIn(enter, durationMillis) togetherWith xOut(exit, durationMillis)

    fun xIn(offset: (Int) -> Int, durationMillis: Int = duration): EnterTransition =
        slideInHorizontally(tween(durationMillis, easing = FastOutSlowInEasing), initialOffsetX = offset) +
            fadeIn(tween(durationMillis.incoming, delayMillis = durationMillis.outgoing, easing = LinearOutSlowInEasing))

    fun xOut(offset: (Int) -> Int, durationMillis: Int = duration): ExitTransition =
        slideOutHorizontally(tween(durationMillis, easing = FastOutSlowInEasing), targetOffsetX = offset) +
            fadeOut(tween(durationMillis.outgoing, easing = FastOutLinearInEasing))

    fun y(enter: (Int) -> Int, exit: (Int) -> Int, durationMillis: Int = duration): ContentTransform =
        yIn(enter, durationMillis) togetherWith yOut(exit, durationMillis)

    fun yIn(offset: (Int) -> Int, durationMillis: Int = duration): EnterTransition =
        slideInVertically(tween(durationMillis, easing = FastOutSlowInEasing), initialOffsetY = offset) +
            fadeIn(tween(durationMillis.incoming, delayMillis = durationMillis.outgoing, easing = LinearOutSlowInEasing))

    fun yOut(offset: (Int) -> Int, durationMillis: Int = duration): ExitTransition =
        slideOutVertically(tween(durationMillis, easing = FastOutSlowInEasing), targetOffsetY = offset) +
            fadeOut(tween(durationMillis.outgoing, easing = FastOutLinearInEasing))

    fun z(forward: Boolean, durationMillis: Int = duration): ContentTransform =
        zIn(forward, durationMillis) togetherWith zOut(forward, durationMillis)

    fun zIn(forward: Boolean, durationMillis: Int = duration): EnterTransition =
        fadeIn(tween(durationMillis.incoming, delayMillis = durationMillis.outgoing, easing = LinearOutSlowInEasing)) +
            scaleIn(tween(durationMillis, easing = FastOutSlowInEasing), initialScale = if (forward) 0.8f else 1.1f)

    fun zOut(forward: Boolean, durationMillis: Int = duration): ExitTransition =
        fadeOut(tween(durationMillis.outgoing, easing = FastOutLinearInEasing)) +
            scaleOut(tween(durationMillis, easing = FastOutSlowInEasing), targetScale = if (forward) 1.1f else 0.8f)
}
