package space.nicart.watchbox.ui.webview

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.media3.common.util.UnstableApi
import okhttp3.Request
import space.nicart.watchbox.R
import space.nicart.watchbox.ui.components.WbEmptyState
import space.nicart.watchbox.ui.components.WbPillButton

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun rememberSourceWebView(onReturn: () -> Unit): (Request) -> Unit {
    val context = LocalContext.current
    val callback = rememberUpdatedState(onReturn)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) callback.value()
    }
    return { request -> launcher.launch(SourceWebViewActivity.newIntent(context, request)) }
}

/** The same recovery path on phones, TV, detail pages and stream-resolution errors. */
@Composable
fun SourceErrorState(
    title: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    body: String? = null,
    verificationRequest: Request? = null,
) {
    val openWebView = rememberSourceWebView(onReturn = onRetry)
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        WbEmptyState(
            title = title,
            body = body,
            actionLabel = stringResource(R.string.action_retry),
            onAction = onRetry,
        )
        if (verificationRequest != null) {
            WbPillButton(
                label = stringResource(R.string.source_open_webview),
                onClick = { openWebView(verificationRequest) },
            )
        }
    }
}
