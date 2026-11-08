package dev.sushnt.journal.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset

/**
 * One set of timings for every animation in the app, so all movement feels
 * like it belongs together. (Material's "emphasized" easing: quick start, soft landing.)
 */
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 400

    fun <T> quick(): FiniteAnimationSpec<T> = tween(SHORT, easing = Emphasized)
    fun <T> standard(): FiniteAnimationSpec<T> = tween(MEDIUM, easing = Emphasized)

    /** Springy but calm, for things that follow a finger or a tap (press, selection). */
    fun <T> gentleSpring(): FiniteAnimationSpec<T> = spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

    private val slide = tween<IntOffset>(LONG, easing = Emphasized)

    /** Opening something (an entry, Settings): it slides in from the right over the old screen. */
    fun <S> AnimatedContentTransitionScope<S>.forward(): ContentTransform =
        (slideInHorizontally(slide) { it / 4 } + fadeIn(tween(MEDIUM, easing = Emphasized))) togetherWith
            (slideOutHorizontally(slide) { -it / 10 } + fadeOut(tween(SHORT)))

    /** Going back: the reverse. */
    fun <S> AnimatedContentTransitionScope<S>.backward(): ContentTransform =
        (slideInHorizontally(slide) { -it / 10 } + fadeIn(tween(MEDIUM, easing = Emphasized))) togetherWith
            (slideOutHorizontally(slide) { it / 4 } + fadeOut(tween(SHORT)))

    /** Switching tabs: a soft cross-fade with a tiny zoom, no sideways movement. */
    fun <S> AnimatedContentTransitionScope<S>.fadeThrough(): ContentTransform =
        (fadeIn(tween(MEDIUM, delayMillis = 60, easing = Emphasized)) + scaleIn(tween(MEDIUM, delayMillis = 60, easing = Emphasized), initialScale = 0.97f)) togetherWith
            fadeOut(tween(SHORT))
}
