package nl.collegewhispr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import nl.collegewhispr.data.Lecture
import nl.collegewhispr.data.LectureStatus
import kotlin.math.roundToInt

// iOS-style building blocks shared by the screens.

/** Width of grouped content on a tablet, like an iPad inset list. */
val ContentWidth = 720.dp

/** Top bar: back button with label on the left, a small centered title, actions on the right. */
@Composable
fun NavBar(
    title: String? = null,
    onBack: (() -> Unit)? = null,
    backLabel: String = "Terug",
    actions: @Composable RowScope.() -> Unit = {},
) {
    val ios = LocalIos.current
    Box(
        Modifier.fillMaxWidth().background(ios.background)
            .windowInsetsPadding(WindowInsets.statusBars).height(48.dp).padding(horizontal = 4.dp),
    ) {
        if (onBack != null) {
            Row(
                Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable(onClick = onBack)
                    .padding(end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, null, tint = ios.accent, modifier = Modifier.size(32.dp))
                Text(backLabel, style = IosType.body, color = ios.accent)
            }
        }
        if (title != null) {
            Text(
                title, style = IosType.headline, color = ios.label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).widthIn(max = 420.dp),
            )
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, style = IosType.largeTitle, color = LocalIos.current.label,
        modifier = modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
    )
}

/** Inset grouped section: optional small-caps header, rounded card, optional footer. */
@Composable
fun Group(
    header: String? = null,
    footer: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val ios = LocalIos.current
    Column(modifier.widthIn(max = ContentWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (header != null) {
            Text(
                header.uppercase(), style = IosType.footnote, color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(ios.card), content = content)
        if (footer != null) {
            Text(
                footer, style = IosType.footnote, color = ios.secondaryLabel,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
    }
}

/** Hairline between rows, inset from the left like iOS. */
@Composable
fun Separator(inset: Dp = 16.dp) {
    val hairline = with(LocalDensity.current) { 1.toDp() }
    Box(Modifier.padding(start = inset).fillMaxWidth().height(hairline).background(LocalIos.current.separator))
}

@Composable
fun IosRow(
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 48.dp,
    content: @Composable RowScope.() -> Unit,
) {
    val base = Modifier.fillMaxWidth().heightIn(min = minHeight)
    Row(
        (if (onClick != null) base.clickable(onClick = onClick) else base).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** Choice in a list: a checkmark marks the selected one. */
@Composable
fun CheckRow(label: String, selected: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    val ios = LocalIos.current
    IosRow(onClick) {
        Column(Modifier.weight(1f)) {
            Text(label, style = IosType.body, color = ios.label)
            if (subtitle != null) Text(subtitle, style = IosType.footnote, color = ios.secondaryLabel)
        }
        if (selected) Icon(Icons.Filled.Check, "Gekozen", tint = ios.accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, subtitle: String? = null, onChange: (Boolean) -> Unit) {
    val ios = LocalIos.current
    IosRow({ onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(label, style = IosType.body, color = ios.label)
            if (subtitle != null) Text(subtitle, style = IosType.footnote, color = ios.secondaryLabel)
        }
        Spacer(Modifier.width(12.dp))
        IosSwitch(checked, onChange)
    }
}

/** Green iOS switch with a full-size white thumb in both states. */
@Composable
fun IosSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val ios = LocalIos.current
    Switch(
        checked = checked,
        onCheckedChange = onChange,
        thumbContent = { Spacer(Modifier.size(SwitchDefaults.IconSize)) },
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color.White,
            checkedTrackColor = ios.green,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = Color.White,
            uncheckedTrackColor = ios.fill,
            uncheckedBorderColor = Color.Transparent,
        ),
    )
}

/** Row that opens something: chevron on the right. */
@Composable
fun NavRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val ios = LocalIos.current
    IosRow(onClick, minHeight = 56.dp) {
        if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, style = IosType.headline, color = ios.label, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = IosType.subhead, color = ios.secondaryLabel)
            below?.invoke(this)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = ios.tertiaryLabel)
    }
}

/** Tappable text row in the accent color (or red for destructive actions). */
@Composable
fun ActionRow(label: String, onClick: () -> Unit, color: Color? = null, icon: ImageVector? = null, enabled: Boolean = true) {
    val ios = LocalIos.current
    val c = if (enabled) color ?: ios.accent else ios.tertiaryLabel
    IosRow(if (enabled) onClick else null) {
        Text(label, style = IosType.body, color = c, modifier = Modifier.weight(1f))
        if (icon != null) Icon(icon, null, tint = c, modifier = Modifier.size(20.dp))
    }
}

/** Capsule button: filled for the main action, tinted otherwise. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    filled: Boolean = true,
    color: Color? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    val ios = LocalIos.current
    val c = if (enabled) color ?: ios.accent else ios.tertiaryLabel
    val base = Modifier.clip(CircleShape).background(if (filled) c else c.copy(alpha = 0.14f))
    Row(
        (if (enabled) base.clickable(onClick = onClick) else base).padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (filled) Color.White else c
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = IosType.subhead.copy(fontWeight = FontWeight.SemiBold), color = fg)
    }
}

/** Selectable capsule, e.g. to switch between transcript versions. */
@Composable
fun Capsule(text: String, selected: Boolean, onClick: () -> Unit) {
    val ios = LocalIos.current
    Text(
        text, style = IosType.subhead.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
        color = if (selected) Color.White else ios.label, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (selected) ios.accent else ios.fill)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** Borderless text field for use inside a [Group] row. */
@Composable
fun IosTextField(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    val ios = LocalIos.current
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier,
        textStyle = IosType.body.copy(color = ios.label),
        cursorBrush = SolidColor(ios.accent),
        singleLine = singleLine,
        minLines = minLines,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, style = IosType.body, color = ios.tertiaryLabel)
                inner()
            }
        },
    )
}

/** iOS search bar: grey rounded field with a magnifier and a clear button. */
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String = "Zoeken") {
    val ios = LocalIos.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(ios.fill).padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, null, tint = ios.secondaryLabel, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        IosTextField(value, onChange, placeholder, Modifier.weight(1f))
        if (value.isNotEmpty()) {
            Icon(
                Icons.Filled.Cancel, "Wissen", tint = ios.tertiaryLabel,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { onChange("") },
            )
        }
    }
}

/** Alert in iOS style: centered title, rounded card, accent (or red) buttons. */
@Composable
fun IosAlert(
    title: String?,
    message: String? = null,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String? = "Annuleren",
    destructive: Boolean = false,
    body: (@Composable () -> Unit)? = null,
) {
    val ios = LocalIos.current
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = if (destructive) ios.red else ios.accent)
            }
        },
        dismissButton = if (dismiss != null) {
            { TextButton(onClick = onDismiss) { Text(dismiss, style = IosType.body, color = ios.accent) } }
        } else null,
        title = if (title != null) {
            { Text(title, style = IosType.headline, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
        } else null,
        text = if (message == null && body == null) null else {
            {
                Column {
                    if (message != null) {
                        Text(message, style = IosType.footnote, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                    body?.invoke()
                }
            }
        },
        shape = RoundedCornerShape(14.dp),
        containerColor = ios.card,
        titleContentColor = ios.label,
        textContentColor = ios.label,
        tonalElevation = 0.dp,
    )
}

/** Thin rounded progress bar. */
@Composable
fun ThinProgress(progress: Float, color: Color? = null, modifier: Modifier = Modifier) {
    val ios = LocalIos.current
    Box(modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(ios.fill)) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(color ?: ios.accent))
    }
}

/** iOS-style scrubber: thin track, white round thumb. Tap or drag to seek. */
@Composable
fun IosSlider(value: Float, max: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val ios = LocalIos.current
    var width by remember { mutableIntStateOf(1) }
    val onChangeNow by rememberUpdatedState(onChange)
    val maxNow by rememberUpdatedState(max)
    val frac = if (max > 0f) (value / max).coerceIn(0f, 1f) else 0f
    val thumb = 20.dp
    val thumbPx = with(LocalDensity.current) { thumb.toPx() }
    Box(
        modifier.height(32.dp).onSizeChanged { width = maxOf(1, it.width) }
            .pointerInput(Unit) { detectTapGestures { o -> onChangeNow((o.x / width).coerceIn(0f, 1f) * maxNow) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onChangeNow((change.position.x / width).coerceIn(0f, 1f) * maxNow) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(ios.fill))
        Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(ios.accent))
        Box(
            Modifier.offset { IntOffset((frac * (width - thumbPx)).roundToInt(), 0) }
                .size(thumb).shadow(2.dp, CircleShape).background(Color.White, CircleShape),
        )
    }
}

/** Status of a lecture, colored like iOS status text, with a progress bar while transcribing. */
@Composable
fun StatusLine(l: Lecture, chunkInfo: Pair<Int, Int>?) {
    val ios = LocalIos.current
    val color = when (l.status) {
        LectureStatus.DONE -> ios.green
        LectureStatus.TRANSCRIBING, LectureStatus.QUEUED, LectureStatus.IMPORTING -> ios.accent
        LectureStatus.WAITING -> ios.orange
        LectureStatus.ERROR, LectureStatus.RECORDING -> ios.red
        LectureStatus.RECORDED -> ios.secondaryLabel
    }
    val extra = if (l.status == LectureStatus.TRANSCRIBING && chunkInfo != null && chunkInfo.second > 0)
        " · deel ${chunkInfo.first} van ${chunkInfo.second} · ${(l.progress * 100).toInt()}%" else ""
    Text(l.status.label + extra, color = color, style = IosType.footnote.copy(fontWeight = FontWeight.Medium))
    if (l.status == LectureStatus.TRANSCRIBING) {
        Spacer(Modifier.height(6.dp))
        ThinProgress(l.progress)
    }
    l.message?.let {
        if (l.status != LectureStatus.DONE) Text(it, style = IosType.footnote, color = ios.secondaryLabel)
    }
}
