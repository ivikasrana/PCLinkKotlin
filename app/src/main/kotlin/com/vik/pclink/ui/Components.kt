package com.vik.pclink.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val TileShape = RoundedCornerShape(22.dp)
private val ControlShape = RoundedCornerShape(12.dp)

@Composable
fun Backdrop(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val t = LocalTokens.current
    val glow: List<Pair<Offset, Pair<Color, Float>>> = if (t.dark) {
        listOf(
            Offset(0f, 0f) to (Color(0xFF0A84FF).copy(alpha = .24f) to 900f),
            Offset(1f, 0f) to (Color(0xFFBF5AF2).copy(alpha = .16f) to 800f),
            Offset(.7f, 1f) to (Color(0xFF30D158).copy(alpha = .08f) to 900f),
        )
    } else {
        listOf(
            Offset(0f, 0f) to (Color(0xFF5AC8FA).copy(alpha = .26f) to 900f),
            Offset(1f, 0f) to (Color(0xFFAF52DE).copy(alpha = .14f) to 800f),
            Offset(.7f, 1f) to (Color(0xFF0A84FF).copy(alpha = .14f) to 900f),
        )
    }
    Box(
        modifier
            .fillMaxSize()
            .background(t.bg)
            .drawBehind {
                glow.forEach { (at, spec) ->
                    val center = Offset(size.width * at.x, size.height * at.y)
                    drawCircle(
                        brush = Brush.radialGradient(listOf(spec.first, Color.Transparent), center = center, radius = spec.second),
                        radius = spec.second,
                        center = center,
                    )
                }
            },
    ) { content() }
}

@Composable
fun Tile(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), content: @Composable ColumnScope.() -> Unit) {
    val t = LocalTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .shadow(if (t.dark) 0.dp else 6.dp, TileShape, ambientColor = Color(0x14000000), spotColor = Color(0x14000000))
            .clip(TileShape)
            .background(t.material)
            .border(BorderStroke(.5.dp, t.separator), TileShape)
            .padding(padding),
        content = content,
    )
}

@Composable
fun Masthead(title: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Column(modifier.padding(vertical = 8.dp)) {
        Text(title, color = t.label, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.2).sp, lineHeight = 38.sp)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, color = LocalTokens.current.label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp, modifier = modifier.padding(bottom = 12.dp))
}

enum class Tone { Neutral, Green, Amber, Red }

@Composable
fun Pill(text: String, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    val (fg, bg) = when (tone) {
        Tone.Neutral -> t.secondary to t.fill
        Tone.Green -> t.green to t.green.copy(alpha = .16f)
        Tone.Amber -> t.amber to t.amber.copy(alpha = .16f)
        Tone.Red -> t.accent to t.accent.copy(alpha = .14f)
    }
    Text(
        text, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = modifier.clip(CircleShape).background(bg).padding(horizontal = 11.dp, vertical = 3.dp),
    )
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current
    Text(
        text, color = if (on) t.onTint else t.secondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
        modifier = Modifier.clip(CircleShape).background(if (on) t.tint else t.fill).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

enum class ButtonKind { Primary, Secondary, Ghost, Danger }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: ButtonKind = ButtonKind.Primary,
    enabled: Boolean = true,
    large: Boolean = false,
) {
    val t = LocalTokens.current
    val (fg, bg) = when (kind) {
        ButtonKind.Primary -> t.onTint to t.tint
        ButtonKind.Secondary -> t.tint to t.tint.copy(alpha = .14f)
        ButtonKind.Ghost -> t.label to t.fill
        ButtonKind.Danger -> t.accent to t.accent.copy(alpha = .12f)
    }
    Box(
        modifier
            .height(if (large) 52.dp else 44.dp)
            .alpha(if (enabled) 1f else .45f)
            .clip(ControlShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = if (large) 17.sp else 16.sp, fontWeight = if (large) FontWeight.SemiBold else FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
    invalid: Boolean = false,
) {
    val t = LocalTokens.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, color = t.secondary, fontSize = 14.sp)
        val ring = when {
            invalid -> t.accent
            focused -> t.tint
            else -> Color.Transparent
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(ControlShape)
                .background(if (focused) t.material else t.fill)
                .border(BorderStroke(2.dp, ring), ControlShape)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = t.label, fontSize = 16.sp),
                cursorBrush = SolidColor(t.tint),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

@Composable
fun TextArea(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    readOnly: Boolean = false,
    minHeight: Dp = 110.dp,
    maxLines: Int = 8,
) {
    val t = LocalTokens.current
    var focused by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Text(label, color = t.secondary, fontSize = 14.sp)
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(ControlShape)
                .background(if (focused) t.material else t.fill)
                .border(BorderStroke(2.dp, if (focused) t.tint else Color.Transparent), ControlShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                readOnly = readOnly,
                maxLines = maxLines,
                textStyle = TextStyle(color = t.label, fontSize = 16.sp),
                cursorBrush = SolidColor(t.tint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
    }
}

@Composable
fun Notice(text: String, error: Boolean = true, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    val t = LocalTokens.current
    Text(
        text, color = if (error) t.accent else t.secondary, fontSize = 14.sp,
        modifier = modifier.fillMaxWidth().clip(ControlShape).background(if (error) t.accent.copy(alpha = .1f) else t.fill).padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(t.fill).padding(2.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) t.segment else Color.Transparent)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(value) },
                contentAlignment = Alignment.Center,
            ) { Text(label, color = t.label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
        }
    }
}

@Composable
fun AddressPicker(addresses: List<String>, selected: String, onSelect: (String) -> Unit, onCopy: () -> Unit) {
    val t = LocalTokens.current
    var open by remember { mutableStateOf(false) }
    Column(Modifier.padding(bottom = 12.dp)) {
        Text("IP address", color = t.secondary, fontSize = 13.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (addresses.size > 1) {
                Box(Modifier.weight(1f)) {
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).clip(ControlShape).background(t.fill).clickable { open = true }.padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(selected, color = t.label, fontSize = 16.sp)
                        Text("▾", color = t.secondary, fontSize = 16.sp)
                    }
                    androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        addresses.forEach { ip ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(ip) }, onClick = { open = false; onSelect(ip) })
                        }
                    }
                }
            } else {
                Text(addresses.firstOrNull() ?: "No network", color = t.label, fontSize = 16.sp, modifier = Modifier.weight(1f))
            }
            if (addresses.isNotEmpty()) Chip("Copy", true, onCopy)
        }
    }
}

@Composable
fun Wire(label: String, value: String) {
    val t = LocalTokens.current
    Column(Modifier.padding(bottom = 10.dp)) {
        Text(label, color = t.secondary, fontSize = 13.sp)
        Text(value.ifBlank { "-" }, color = t.label, fontSize = 16.sp, overflow = TextOverflow.Clip)
    }
}
