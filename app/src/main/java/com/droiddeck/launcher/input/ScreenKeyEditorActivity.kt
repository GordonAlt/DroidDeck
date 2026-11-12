package com.droiddeck.launcher.input

import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.ui.DroidDeckTheme
import com.droiddeck.launcher.ui.ScreenKeyEditor
import com.droiddeck.launcher.ui.ScreenKeyPicker

class ScreenKeyEditorActivity : ComponentActivity() {
    private lateinit var keys: ScreenKeys
    private var selected by mutableStateOf<ScreenKey?>(null)
    private var pickerOpen by mutableStateOf(false)
    private var binding by mutableStateOf(false)
    private var direction by mutableStateOf<Int?>(null)

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        com.droiddeck.launcher.core.AppLanguage.applyTo(this, newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        immersive()
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF0B0F14.toInt()) }
        keys = ScreenKeys(this, { _, _ -> })
        keys.onAdd = { binding = false; pickerOpen = true }
        keys.onSelect = { selected = keys.stateOf(it) }
        keys.onEditingChanged = { if (!it) finish() }
        root.addView(keys, FrameLayout.LayoutParams(-1, -1))
        keys.startEditing()
        val overlay = ComposeView(this).apply {
            isClickable = false
            setContent {
                DroidDeckTheme {
                    Box(Modifier.fillMaxSize()) {
                        if (selected == null) {
                            Row(
                                Modifier.align(Alignment.TopCenter).padding(10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(onClick = { selected = keys.addRadialGroup() }) { Text("Add radial group") }
                                Button(onClick = { keys.stopEditing() }) { Text("Save & close") }
                            }
                        }
                        if (pickerOpen) {
                            val target = selected
                            ScreenKeyPicker(
                                initial = if (binding) target?.codes.orEmpty().filter { it > 0 } else emptyList(),
                                onDismiss = { pickerOpen = false; binding = false; direction = null },
                                onAdd = { codes ->
                                    if (binding && target != null) {
                                        val dir = direction
                                        if (dir != null) keys.setDirection(target.codes, dir, codes)
                                        else if (codes.isNotEmpty()) keys.setCodes(target.codes, codes)
                                        selected = keys.stateOf(if (dir == null) codes else target.codes)
                                    } else if (codes.isNotEmpty()) {
                                        keys.add(codes)
                                        selected = keys.stateOf(codes)
                                    }
                                    pickerOpen = false
                                    binding = false
                                    direction = null
                                },
                                onAddAction = { action ->
                                    val code = ScreenKeyActions.code(action)
                                    if (code != null) {
                                        keys.addAction(action)
                                        selected = keys.stateOf(listOf(code))
                                    }
                                    pickerOpen = false
                                    binding = false
                                    direction = null
                                },
                                allowActions = !binding,
                                allowEmpty = direction != null,
                            )
                        }
                        selected?.let { key ->
                            ScreenKeyEditor(
                                codes = key.codes,
                                action = key.action,
                                directionBindings = key.directionBindings,
                                keyY = key.y,
                                size = key.size,
                                opacity = key.opacity,
                                shape = key.shape,
                                toggle = key.toggle,
                                labels = key.labels,
                                text = key.text,
                                hasImage = key.image.isNotEmpty(),
                                onResize = { keys.resize(key.codes, it); selected = keys.stateOf(key.codes) },
                                onOpacity = { keys.setOpacity(key.codes, it); selected = keys.stateOf(key.codes) },
                                onShape = { keys.setShape(key.codes, it); selected = keys.stateOf(key.codes) },
                                onToggle = { keys.setToggle(key.codes, it); selected = keys.stateOf(key.codes) },
                                onLabels = { keys.setLabels(key.codes, it); selected = keys.stateOf(key.codes) },
                                onDirection = { direction = it; binding = true; pickerOpen = true },
                                onText = { keys.setText(key.codes, it); selected = keys.stateOf(key.codes) },
                                onImage = { keys.setImage(key.codes, it); selected = keys.stateOf(key.codes) },
                                onClearImage = { keys.setImage(key.codes, ""); selected = keys.stateOf(key.codes) },
                                onBind = { direction = null; binding = true; pickerOpen = true },
                                onTextFocus = {},
                                onTextBlur = {},
                                onRemove = { keys.remove(key.codes); selected = null },
                                onDismiss = { selected = null; keys.deselect() },
                                width = key.width,
                                height = key.height,
                                fillColor = key.fillColor,
                                borderColor = key.borderColor,
                                textColor = key.textColor,
                                borderWidth = key.borderWidth,
                                cornerRadius = key.cornerRadius,
                                onWidth = { keys.setWidth(key.codes, it); selected = keys.stateOf(key.codes) },
                                onHeight = { keys.setHeight(key.codes, it); selected = keys.stateOf(key.codes) },
                                onFillColor = { keys.setFillColor(key.codes, it); selected = keys.stateOf(key.codes) },
                                onBorderColor = { keys.setBorderColor(key.codes, it); selected = keys.stateOf(key.codes) },
                                onTextColor = { keys.setTextColor(key.codes, it); selected = keys.stateOf(key.codes) },
                                onBorderWidth = { keys.setBorderWidth(key.codes, it); selected = keys.stateOf(key.codes) },
                                onCornerRadius = { keys.setCornerRadius(key.codes, it); selected = keys.stateOf(key.codes) },
                            )
                        }
                    }
                }
            }
        }
        root.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}
