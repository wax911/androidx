/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.glance.appwidget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.neverEqualPolicy
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.datastore.preferences.core.emptyPreferences
import androidx.glance.EmittableWithChildren
import androidx.glance.GlanceComposable
import androidx.glance.GlanceId
import androidx.glance.LocalContext
import androidx.glance.LocalGlanceId
import androidx.glance.LocalState
import androidx.glance.action.LambdaAction
import androidx.glance.session.Session
import androidx.glance.state.ConfigManager
import androidx.glance.state.GlanceState
import androidx.glance.state.PreferencesGlanceStateDefinition
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * A session that composes UI for a single app widget.
 *
 * This class represents the lifecycle of composition for an app widget. This is started by
 * [GlanceAppWidget] in response to APPWIDGET_UPDATE broadcasts. The session is run in
 * [androidx.glance.session.SessionWorker] on a background thread (WorkManager). While it is active,
 * the session will continue to recompose in response to composition state changes or external
 * events (e.g. [AppWidgetSession.updateGlance]). If a session is already running, GlanceAppWidget
 * will trigger events on the session instead of starting a new one.
 *
 * @property widget the GlanceAppWidget that contains the composable for this session.
 * @property id identifies which widget will be updated when the UI is ready.
 * @property initialOptions options to be provided to the composition and determine sizing.
 * @property configManager used by the session to retrieve configuration state.
 */
internal class AppWidgetSession(
    private val widget: GlanceAppWidget,
    private val id: AppWidgetId,
    private val initialOptions: Bundle? = null,
    private val configManager: ConfigManager = GlanceState,
) : Session(id.toSessionKey()) {

    private companion object {
        const val TAG = "AppWidgetSession"
        const val DEBUG = false
    }

    private val glanceState = mutableStateOf(emptyPreferences(), neverEqualPolicy())
    private val options = mutableStateOf(Bundle(), neverEqualPolicy())
    private var lambdas = mapOf<String, List<LambdaAction>>()
    @VisibleForTesting
    internal var lastRemoteViews: RemoteViews? = null
        private set

    override fun createRootEmittable() = RemoteViewsRoot(MaxComposeTreeDepth)

    override fun provideGlance(context: Context): @Composable @GlanceComposable () -> Unit = {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalGlanceId provides id,
            LocalAppWidgetOptions provides options.value,
            LocalState provides glanceState.value,
        ) {
            val manager = remember { context.appWidgetManager }
            val minSize = remember {
                appWidgetMinSize(
                    context.resources.displayMetrics,
                    manager,
                    id.appWidgetId
                )
            }
            val configIsReady by produceState(false) {
                options.value = initialOptions ?: manager.getAppWidgetOptions(id.appWidgetId)
                glanceState.value =
                    configManager.getValue(context, PreferencesGlanceStateDefinition, key)
                value = true
            }
            remember { widget.runGlance(context, id) }
                .collectAsState(null)
                .takeIf { configIsReady }
                ?.value?.let { ForEachSize(widget.sizeMode, minSize, it) }
                ?: IgnoreResult()
            // The following line ensures that when glanceState is updated, it increases the
            // Recomposer.changeCount and triggers processEmittableTree.
            SideEffect { glanceState.value }
        }
    }

    override suspend fun processEmittableTree(
        context: Context,
        root: EmittableWithChildren
    ) {
        root as RemoteViewsRoot
        if (root.shouldIgnoreResult()) return
        val layoutConfig = LayoutConfiguration.load(context, id.appWidgetId)
        val appWidgetManager = context.appWidgetManager
        try {
            val receiver = requireNotNull(appWidgetManager.getAppWidgetInfo(id.appWidgetId)) {
                "No app widget info for ${id.appWidgetId}"
            }.provider
            normalizeCompositionTree(root)
            lambdas = root.updateLambdaActionKeys()
            val rv = translateComposition(
                context,
                id.appWidgetId,
                root,
                layoutConfig,
                layoutConfig.addLayout(root),
                DpSize.Unspecified,
                receiver
            )
            appWidgetManager.updateAppWidget(id.appWidgetId, rv)
            lastRemoteViews = rv
        } catch (ex: CancellationException) {
            // Nothing to do
        } catch (throwable: Throwable) {
            if (widget.errorUiLayout == 0) {
                throw throwable
            }
            logException(throwable)
            val rv = RemoteViews(context.packageName, widget.errorUiLayout)
            appWidgetManager.updateAppWidget(id.appWidgetId, rv)
            lastRemoteViews = rv
        } finally {
            layoutConfig.save()
            Tracing.endGlanceAppWidgetUpdate()
        }
    }

    override suspend fun processEvent(context: Context, event: Any) {
        when (event) {
            is UpdateGlanceState -> {
                if (DEBUG) Log.i(TAG, "Received UpdateGlanceState event for session($key)")
                glanceState.value =
                    configManager.getValue(context, PreferencesGlanceStateDefinition, key)
            }
            is UpdateAppWidgetOptions -> {
                if (DEBUG) {
                    Log.i(
                        TAG,
                        "Received UpdateAppWidgetOptions(${event.newOptions}) event" +
                            "for session($key)"
                    )
                }
                options.value = event.newOptions
            }
            is RunLambda -> {
                Log.i(TAG, "Received RunLambda(${event.key}) action for session($key)")
                lambdas[event.key]?.map { it.block() }
                    ?: Log.w(
                        TAG,
                        "Triggering Action(${event.key}) for session($key) failed"
                    )
            }
            else -> {
                throw IllegalArgumentException(
                    "Sent unrecognized event type ${event.javaClass} to AppWidgetSession"
                )
            }
        }
    }

    suspend fun updateGlance() {
        sendEvent(UpdateGlanceState)
    }

    suspend fun updateAppWidgetOptions(newOptions: Bundle) {
        sendEvent(UpdateAppWidgetOptions(newOptions))
    }

    suspend fun runLambda(key: String) {
        sendEvent(RunLambda(key))
    }

    // Action types that this session supports.
    @VisibleForTesting
    internal object UpdateGlanceState
    @VisibleForTesting
    internal class UpdateAppWidgetOptions(val newOptions: Bundle)
    @VisibleForTesting
    internal class RunLambda(val key: String)

    private val Context.appWidgetManager: AppWidgetManager
        get() = this.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager
}

internal fun interface ContentReceiver : CoroutineContext.Element {
    /**
     * Provide [content] to the Glance session, suspending until the session is
     * shut down.
     *
     * If this function is called concurrently with itself, the previous call will throw
     * [CancellationException] and the new content will replace it.
     */
    suspend fun provideContent(
        content: @Composable @GlanceComposable () -> Unit
    ): Nothing

    override val key: CoroutineContext.Key<*> get() = Key

    companion object Key : CoroutineContext.Key<ContentReceiver>
}

internal fun GlanceAppWidget.runGlance(
    context: Context,
    id: GlanceId,
): Flow<(@GlanceComposable @Composable () -> Unit)?> = channelFlow {
    val contentCoroutine: AtomicReference<CancellableContinuation<Nothing>?> =
        AtomicReference(null)
    val receiver = ContentReceiver { content ->
        suspendCancellableCoroutine {
            it.invokeOnCancellation { trySend(null) }
            contentCoroutine.getAndSet(it)?.cancel()
            trySend(content)
        }
    }
    withContext(receiver) { provideGlance(context, id) }
}

internal val Context.appWidgetManager: AppWidgetManager
    get() = this.getSystemService(Context.APPWIDGET_SERVICE) as AppWidgetManager

internal fun createUniqueRemoteUiName(appWidgetId: Int) = "appWidget-$appWidgetId"

internal fun AppWidgetId.toSessionKey() = createUniqueRemoteUiName(appWidgetId)

/**
 * Maximum depth for a composition. Although there is no hard limit, this should avoid deep
 * recursions, which would create [RemoteViews] too large to be sent.
 */
private const val MaxComposeTreeDepth = 50
