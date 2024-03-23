package me.bmax.apatch.ui.component
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Parcelable
import android.text.Layout
import android.text.method.LinkMovementMethod
import android.util.Log
import android.view.SurfaceControl
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.SecureFlagPolicy
import io.noties.markwon.Markwon
import io.noties.markwon.utils.NoCopySpannableFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import me.bmax.apatch.ui.webui.WebViewInterface
import me.bmax.apatch.ui.webui.showSystemUI
import me.bmax.apatch.util.APDialogBlurBehindUtils.Companion.setupWindowBlurListener
import java.lang.reflect.Method
import java.util.function.Consumer
import kotlin.coroutines.resume

private const val TAG = "DialogComponent"

interface ConfirmDialogVisuals : Parcelable {
    val title: String
    val content: String
    val isMarkdown: Boolean
    val confirm: String?
    val dismiss: String?
}

@Parcelize
private data class ConfirmDialogVisualsImpl(
    override val title: String,
    override val content: String,
    override val isMarkdown: Boolean,
    override val confirm: String?,
    override val dismiss: String?,
) : ConfirmDialogVisuals {
    companion object {
        val Empty: ConfirmDialogVisuals = ConfirmDialogVisualsImpl("", "", false, null, null)
    }
}

interface DialogHandle {
    val isShown: Boolean
    val dialogType: String
    fun show()
    fun hide()
}

interface LoadingDialogHandle : DialogHandle {
    suspend fun <R> withLoading(block: suspend () -> R): R
    fun showLoading()
}

sealed interface ConfirmResult {
    data object Confirmed : ConfirmResult
    data object Canceled : ConfirmResult
}

interface ConfirmDialogHandle : DialogHandle {
    val visuals: ConfirmDialogVisuals

    fun showConfirm(
        title: String,
        content: String,
        markdown: Boolean = false,
        confirm: String? = null,
        dismiss: String? = null
    )

    suspend fun awaitConfirm(
        title: String,
        content: String,
        markdown: Boolean = false,
        confirm: String? = null,
        dismiss: String? = null
    ): ConfirmResult
}

private abstract class DialogHandleBase(
    protected val visible: MutableState<Boolean>,
    protected val coroutineScope: CoroutineScope
) : DialogHandle {
    override val isShown: Boolean
        get() = visible.value

    override fun show() {
        coroutineScope.launch {
            visible.value = true
        }
    }

    final override fun hide() {
        coroutineScope.launch {
            visible.value = false
        }
    }

    override fun toString(): String {
        return dialogType
    }
}

private class LoadingDialogHandleImpl(
    visible: MutableState<Boolean>,
    coroutineScope: CoroutineScope
) : LoadingDialogHandle, DialogHandleBase(visible, coroutineScope) {
    override suspend fun <R> withLoading(block: suspend () -> R): R {
        return coroutineScope.async {
            try {
                visible.value = true
                block()
            } finally {
                visible.value = false
            }
        }.await()
    }

    override fun showLoading() {
        show()
    }

    override val dialogType: String get() = "LoadingDialog"
}

typealias NullableCallback = (() -> Unit)?

interface ConfirmCallback {

    val onConfirm: NullableCallback

    val onDismiss: NullableCallback

    val isEmpty: Boolean get() = onConfirm == null && onDismiss == null

    companion object {
        operator fun invoke(onConfirmProvider: () -> NullableCallback, onDismissProvider: () -> NullableCallback): ConfirmCallback {
            return object : ConfirmCallback {
                override val onConfirm: NullableCallback
                    get() = onConfirmProvider()
                override val onDismiss: NullableCallback
                    get() = onDismissProvider()
            }
        }
    }
}

private class ConfirmDialogHandleImpl(
    visible: MutableState<Boolean>,
    coroutineScope: CoroutineScope,
    callback: ConfirmCallback,
    override var visuals: ConfirmDialogVisuals = ConfirmDialogVisualsImpl.Empty,
    private val resultFlow: ReceiveChannel<ConfirmResult>
) : ConfirmDialogHandle, DialogHandleBase(visible, coroutineScope) {
    private class ResultCollector(
        private val callback: ConfirmCallback
    ) : FlowCollector<ConfirmResult> {
        fun handleResult(result: ConfirmResult) {
            Log.d(TAG, "handleResult: ${result.javaClass.simpleName}")
            when (result) {
                ConfirmResult.Confirmed -> onConfirm()
                ConfirmResult.Canceled -> onDismiss()
            }
        }

        fun onConfirm() {
            callback.onConfirm?.invoke()
        }

        fun onDismiss() {
            callback.onDismiss?.invoke()
        }

        override suspend fun emit(value: ConfirmResult) {
            handleResult(value)
        }
    }

    private val resultCollector = ResultCollector(callback)

    private var awaitContinuation: CancellableContinuation<ConfirmResult>? = null

    private val isCallbackEmpty = callback.isEmpty

    init {
        coroutineScope.launch {
            resultFlow
                .consumeAsFlow()
                .onEach { result ->
                    awaitContinuation?.let {
                        awaitContinuation = null
                        if (it.isActive) {
                            it.resume(result)
                        }
                    }
                }
                .onEach { hide() }
                .collect(resultCollector)
        }
    }

    private suspend fun awaitResult(): ConfirmResult {
        return suspendCancellableCoroutine {
            awaitContinuation = it.apply {
                if (isCallbackEmpty) {
                    invokeOnCancellation {
                        visible.value = false
                    }
                }
            }
        }
    }

    fun updateVisuals(visuals: ConfirmDialogVisuals) {
        this.visuals = visuals
    }

    override fun show() {
        if (visuals !== ConfirmDialogVisualsImpl.Empty) {
            super.show()
        } else {
            throw UnsupportedOperationException("can't show confirm dialog with the Empty visuals")
        }
    }

    override fun showConfirm(
        title: String,
        content: String,
        markdown: Boolean,
        confirm: String?,
        dismiss: String?
    ) {
        coroutineScope.launch {
            updateVisuals(ConfirmDialogVisualsImpl(title, content, markdown, confirm, dismiss))
            show()
        }
    }

    override suspend fun awaitConfirm(
        title: String,
        content: String,
        markdown: Boolean,
        confirm: String?,
        dismiss: String?
    ): ConfirmResult {
        coroutineScope.launch {
            updateVisuals(ConfirmDialogVisualsImpl(title, content, markdown, confirm, dismiss))
            show()
        }
        return awaitResult()
    }

    override val dialogType: String get() = "ConfirmDialog"

    override fun toString(): String {
        return "${super.toString()}(visuals: $visuals)"
    }

    companion object {
        fun Saver(
            visible: MutableState<Boolean>,
            coroutineScope: CoroutineScope,
            callback: ConfirmCallback,
            resultChannel: ReceiveChannel<ConfirmResult>
        ) = Saver<ConfirmDialogHandle, ConfirmDialogVisuals>(
            save = {
                it.visuals
            },
            restore = {
                Log.d(TAG, "ConfirmDialog restore, visuals: $it")
                ConfirmDialogHandleImpl(visible, coroutineScope, callback, it, resultChannel)
            }
        )
    }
}

private class CustomDialogHandleImpl(
    visible: MutableState<Boolean>,
    coroutineScope: CoroutineScope
) : DialogHandleBase(visible, coroutineScope) {
    override val dialogType: String get() = "CustomDialog"
}

@Composable
fun rememberLoadingDialog(): LoadingDialogHandle {
    val visible = remember {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()

    if (visible.value) {
        LoadingDialog()
    }

    return remember {
        LoadingDialogHandleImpl(visible, coroutineScope)
    }
}

@Composable
private fun rememberConfirmDialog(visuals: ConfirmDialogVisuals, callback: ConfirmCallback): ConfirmDialogHandle {
    val visible = rememberSaveable {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    val resultChannel = remember {
        Channel<ConfirmResult>()
    }

    val handle = rememberSaveable(
        saver = ConfirmDialogHandleImpl.Saver(visible, coroutineScope, callback, resultChannel),
        init = {
            ConfirmDialogHandleImpl(visible, coroutineScope, callback, visuals, resultChannel)
        }
    )

    if (visible.value) {
        ConfirmDialog(
            handle.visuals,
            confirm = { coroutineScope.launch { resultChannel.send(ConfirmResult.Confirmed) } },
            dismiss = { coroutineScope.launch { resultChannel.send(ConfirmResult.Canceled) } }
        )
    }

    return handle
}

@Composable
fun rememberConfirmCallback(onConfirm: NullableCallback, onDismiss: NullableCallback): ConfirmCallback {
    val currentOnConfirm by rememberUpdatedState(newValue = onConfirm)
    val currentOnDismiss by rememberUpdatedState(newValue = onDismiss)
    return remember {
        ConfirmCallback({ currentOnConfirm }, { currentOnDismiss })
    }
}

@Composable
fun rememberConfirmDialog(onConfirm: NullableCallback = null, onDismiss: NullableCallback = null): ConfirmDialogHandle {
    return rememberConfirmDialog(rememberConfirmCallback(onConfirm, onDismiss))
}

@Composable
fun rememberConfirmDialog(callback: ConfirmCallback): ConfirmDialogHandle {
    return rememberConfirmDialog(ConfirmDialogVisualsImpl.Empty, callback)
}

@Composable
fun rememberCustomDialog(composable: @Composable (dismiss: () -> Unit) -> Unit): DialogHandle {
    val visible = rememberSaveable {
        mutableStateOf(false)
    }
    val coroutineScope = rememberCoroutineScope()
    if (visible.value) {
        composable { visible.value = false }
    }
    return remember {
        CustomDialogHandleImpl(visible, coroutineScope)
    }
}

@Composable
private fun LoadingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false, usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.size(100.dp), shape = RoundedCornerShape(8.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
        setupWindowBlurListener(dialogWindowProvider.window)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConfirmDialog(visuals: ConfirmDialogVisuals, confirm: () -> Unit, dismiss: () -> Unit) {
    BasicAlertDialog(
        onDismissRequest = {
            dismiss()
        },
        properties = DialogProperties(decorFitsSystemWindows = true, usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOff)
    ) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .wrapContentHeight(),
            shape = RoundedCornerShape(20.dp),
            tonalElevation = AlertDialogDefaults.TonalElevation,
            color = AlertDialogDefaults.containerColor,
        ) {
            Column(modifier = Modifier.padding(PaddingValues(all = 24.dp))) {
                // Title
                Box(Modifier
                    .padding(PaddingValues(bottom = 16.dp))
                    .align(Alignment.Start)
                ) {
                    Text(text = visuals.title, style = MaterialTheme.typography.headlineSmall)
                }

                // Content
                Box(Modifier
                    .weight(weight = 1f, fill = false)
                    .padding(PaddingValues(bottom = 24.dp))
                    .align(Alignment.Start)) {

                    if (visuals.isMarkdown) {
                        MarkdownContent(content = visuals.content)
                    } else {
                        Text(text = visuals.content, style = MaterialTheme.typography.bodyMedium)
                    }
                }

                // Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = dismiss) {
                        Text(text = visuals.dismiss ?: stringResource(id = android.R.string.cancel))
                    }

                    TextButton(onClick = confirm) {
                        Text(text = visuals.confirm ?: stringResource(id = android.R.string.ok))
                    }
                }
            }
            val dialogWindowProvider = LocalView.current.parent as DialogWindowProvider
            setupWindowBlurListener(dialogWindowProvider.window)
        }
    }

}

@Composable
private fun MarkdownContent(content: String) {
    val contentColor = LocalContentColor.current

    AndroidView(
        factory = { context ->
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setSpannableFactory(NoCopySpannableFactory.getInstance())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
                    hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
                }

                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(),
        update = {
            Markwon.create(it.context).setMarkdown(it, content)
            it.setTextColor(contentColor.toArgb())
        }
    )
}