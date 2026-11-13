package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.ui.GlideDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import com.droiddeck.launcher.R
import com.droiddeck.launcher.input.PC_KEYS
import com.droiddeck.launcher.input.PC_KEY_ROWS
import com.droiddeck.launcher.input.PcKey
import com.droiddeck.launcher.input.pcKeyLabel
import com.droiddeck.launcher.input.GamepadTouchCodes
import com.droiddeck.launcher.input.ScreenKeyActions

private enum class DroidDeckPickerTab { KEYBOARD, GAMEPAD, MOUSE }

@Composable
fun ScreenKeyPicker(
    onDismiss: () -> Unit,
    onAdd: (List<Int>) -> Unit,
    initial: List<Int> = emptyList(),
    sequence: Boolean = false,
    sequenceLimit: Int = 4,
    slots: List<String> = emptyList(),
    onAddAction: (String) -> Unit = {},
    allowActions: Boolean = true,
    allowEmpty: Boolean = false,
) {
    var picked by remember { mutableStateOf(initial) }
    val context = LocalContext.current
    var pickerTab by remember { mutableStateOf(DroidDeckPickerTab.KEYBOARD) }
    var pickerQuery by remember { mutableStateOf("") }
    val p = LocalPalette.current
    val toggle: (Int) -> Unit = { code ->
        picked = if (sequence) {
            if (code in picked) picked - code else (picked + code).take(sequenceLimit)
        } else {
            if (code in picked) picked - code else picked + code
        }
    }
    val rows = remember {
        val functionRow = PC_KEY_ROWS[0]
        val nav = listOfNotNull(PC_KEYS[110], PC_KEYS[111], PC_KEYS[102], PC_KEYS[107], PC_KEYS[104], PC_KEYS[109]) +
            listOfNotNull(PC_KEYS[103], PC_KEYS[105], PC_KEYS[108], PC_KEYS[106])
        listOf(functionRow.take(13), nav) + PC_KEY_ROWS.drop(1)
    }
    val unit = remember { rows.maxOf { row -> row.fold(0f) { sum, key -> sum + key.weight } } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(10.dp), contentAlignment = Alignment.Center) {
            Surface(
                Modifier.widthIn(max = 820.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(20.dp),
                color = p.background, contentColor = p.onBackground, border = BorderStroke(1.dp, p.line2),
            ) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.screen_keys_add), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Icon(Icons.Default.Close, null) }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf(
                            DroidDeckPickerTab.KEYBOARD to "Keyboard",
                            DroidDeckPickerTab.GAMEPAD to "Gamepad",
                            DroidDeckPickerTab.MOUSE to "Mouse",
                        ).forEach { (tab, title) ->
                            val selected = pickerTab == tab
                            Surface(
                                Modifier.weight(1f).height(36.dp).clickable {
                                    pickerTab = tab
                                    pickerQuery = ""
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (selected) p.primary.copy(alpha = 0.17f) else p.line2.copy(alpha = 0.18f),
                                border = BorderStroke(1.dp, if (selected) p.primary else p.line2),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                                        color = if (selected) p.primary else p.onBackground.copy(alpha = 0.72f),
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(40.dp)
                            .background(p.line2.copy(alpha = 0.20f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = p.onBackground.copy(alpha = 0.5f))
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (pickerQuery.isEmpty()) {
                                Text("Search controls", color = p.onBackground.copy(alpha = 0.42f), style = MaterialTheme.typography.bodySmall)
                            }
                            BasicTextField(
                                value = pickerQuery,
                                onValueChange = { pickerQuery = it.take(40) },
                                singleLine = true,
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = p.onBackground),
                                cursorBrush = androidx.compose.ui.graphics.SolidColor(p.primary),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (pickerQuery.isNotEmpty()) {
                            IconButton(onClick = { pickerQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 26.dp).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (sequence && slots.size >= sequenceLimit) {
                            slots.take(sequenceLimit).forEachIndexed { index, arrow ->
                                val code = picked.getOrNull(index)
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (code == null) p.line2.copy(alpha = 0.2f) else p.primary.copy(alpha = 0.16f),
                                    modifier = if (code == null) Modifier else Modifier.clickable { picked = picked - code },
                                ) {
                                    Text(
                                        if (code == null) arrow else "$arrow ${GamepadTouchCodes.label(code) ?: pcKeyLabel(LocalContext.current, code)}",
                                        Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        } else {
                            picked.forEach { code ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp), color = p.primary.copy(alpha = 0.16f),
                                    modifier = Modifier.clickable { picked = picked - code },
                                ) {
                                    Text(GamepadTouchCodes.label(code) ?: pcKeyLabel(LocalContext.current, code), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                    if (allowActions && pickerTab == DroidDeckPickerTab.MOUSE) {
                    Text("MOUSE CONTROLS", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = p.onBackground.copy(alpha = 0.65f))
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            ScreenKeyActions.MOUSE_LEFT to "Left click", ScreenKeyActions.MOUSE_RIGHT to "Right click",
                            ScreenKeyActions.MOUSE_MIDDLE to "Middle click", ScreenKeyActions.MOUSE_REGION_ABSOLUTE to "Mouse area",
                            ScreenKeyActions.MOUSE_REGION_RELATIVE to "Relative mouse",
                            ScreenKeyActions.SCROLL_UP to "Scroll up once", ScreenKeyActions.SCROLL_DOWN to "Scroll down once",
                            ScreenKeyActions.SCROLL_UP_CONTINUOUS to "Scroll up while held",
                            ScreenKeyActions.SCROLL_DOWN_CONTINUOUS to "Scroll down while held",
                        ).filter { it.second.contains(pickerQuery, ignoreCase = true) }.forEach { (action, title) ->
                            Surface(shape = RoundedCornerShape(8.dp), color = p.surface, border = BorderStroke(1.dp, p.line), modifier = Modifier.height(34.dp).clickable { onAddAction(action) }) {
                                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1) }
                            }
                        }
                    }
                    }
                    if (pickerTab == DroidDeckPickerTab.GAMEPAD) {
                    Text("GAMEPAD", Modifier.padding(start = 16.dp, top = 8.dp), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = p.onBackground.copy(alpha = 0.65f))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (GamepadTouchCodes.buttons + GamepadTouchCodes.dpad + listOf(GamepadTouchCodes.LEFT_ANALOG, GamepadTouchCodes.RIGHT_ANALOG) + GamepadTouchCodes.leftStick + GamepadTouchCodes.rightStick).distinct().filter { (GamepadTouchCodes.label(it) ?: "").contains(pickerQuery, ignoreCase = true) }.forEach { code ->
                            val selected = code in picked
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (selected) p.primary.copy(alpha = 0.2f) else p.surface,
                                border = BorderStroke(1.dp, if (selected) p.primary else p.line),
                                modifier = Modifier.height(34.dp).clickable { toggle(code) },
                            ) {
                                Box(Modifier.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                                    Text(GamepadTouchCodes.label(code).orEmpty(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                            }
                        }
                    }
                    }
                    if (pickerTab == DroidDeckPickerTab.KEYBOARD) {
                    LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(rows.size) { index ->
                            val row = rows[index]
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                row.filter { pickerQuery.isBlank() || pcKeyLabel(context, it.code).contains(pickerQuery, ignoreCase = true) }
                                    .forEach { key -> KeyCell(key, key.code in picked, Modifier.weight(key.weight)) { toggle(key.code) } }
                                if (row.sumOf { it.weight.toDouble() } < unit) {
                                    Spacer(Modifier.weight(unit - row.fold(0f) { sum, key -> sum + key.weight }))
                                }
                            }
                        }
                    }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).focusGlide(CircleShape)) { Text("Cancel") }
                        Button(
                            onClick = { onAdd(picked) },
                            enabled = picked.isNotEmpty() || allowEmpty,
                            modifier = Modifier.weight(1f).focusGlide(CircleShape),
                        ) {
                            Text(if (picked.isEmpty() && allowEmpty) "Clear direction" else stringResource(R.string.screen_keys_add))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCell(key: PcKey, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    val label = pcKeyLabel(LocalContext.current, key.code)
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) p.primary.copy(alpha = 0.2f) else p.surface,
        border = BorderStroke(1.dp, if (selected) p.primary else p.line),
        modifier = modifier.height(36.dp).focusGlide(RoundedCornerShape(8.dp)).clickable(onClick = onClick),
    ) {
        Box(Modifier.fillMaxSize().padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
            Text(
                label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = if (label.length > 4) 10.sp else 12.sp,
            )
        }
    }
}
