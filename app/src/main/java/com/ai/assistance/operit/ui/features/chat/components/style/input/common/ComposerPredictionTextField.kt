package com.ai.assistance.operit.ui.features.chat.components.style.input.common

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation

private class PredictionEditorCoordinates {
    var editor: LayoutCoordinates? = null
    var trailing: LayoutCoordinates? = null

    fun trailingBounds() = editor?.takeIf { it.isAttached }?.let { root ->
        trailing?.takeIf { it.isAttached }?.let { root.localBoundingBoxOf(it) }
    }
}

/** Native editor and Material decoration, keeping adoption gestures separate from trailing controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerPredictionTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    prediction: String?,
    onAcceptPrediction: (String) -> Boolean,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    placeholder: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = if (outlined) OutlinedTextFieldDefaults.shape else TextFieldDefaults.shape,
    colors: TextFieldColors = if (outlined) OutlinedTextFieldDefaults.colors() else TextFieldDefaults.colors(),
) {
    val coordinates = remember { PredictionEditorCoordinates() }
    val ghost = visibleComposerPrediction(value, prediction)
    val focused by interactionSource.collectIsFocusedAsState()
    val textColor = textStyle.color.takeOrElse {
        when {
            !enabled -> colors.disabledTextColor
            focused -> colors.focusedTextColor
            else -> colors.unfocusedTextColor
        }
    }
    val mergedTextStyle = textStyle.merge(TextStyle(color = textColor))
    CompositionLocalProvider(LocalTextSelectionColors provides colors.textSelectionColors) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier
                .composerPredictionSemantics(value, prediction, enabled && !readOnly, onAcceptPrediction)
                .defaultMinSize(
                    minWidth = OutlinedTextFieldDefaults.MinWidth,
                    minHeight = OutlinedTextFieldDefaults.MinHeight,
                )
                .onGloballyPositioned { coordinates.editor = it }
                .composerPredictionGesture(
                    value, prediction, enabled && !readOnly,
                    excludedBounds = { if (trailingIcon != null) coordinates.trailingBounds() else null },
                    onAccept = onAcceptPrediction,
                ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = mergedTextStyle,
            cursorBrush = SolidColor(colors.cursorColor),
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            singleLine = singleLine,
            maxLines = if (ghost == null) maxLines else Int.MAX_VALUE,
            minLines = minLines,
            interactionSource = interactionSource,
            decorationBox = { inner ->
                val editor: @Composable () -> Unit = {
                    ComposerPredictionViewport(value, prediction, mergedTextStyle, maxLines,
                        inner)
                }
                val trailing: (@Composable () -> Unit)? = trailingIcon?.let { content ->
                    { Box(Modifier.onGloballyPositioned { coordinates.trailing = it }) { content() } }
                }
                if (outlined) {
                    OutlinedTextFieldDefaults.DecorationBox(
                        value = value.text,
                        innerTextField = editor,
                        enabled = enabled,
                        singleLine = singleLine,
                        visualTransformation = visualTransformation,
                        interactionSource = interactionSource,
                        placeholder = placeholder,
                        trailingIcon = trailing,
                        colors = colors,
                        container = {
                            OutlinedTextFieldDefaults.Container(
                                enabled = enabled,
                                isError = false,
                                interactionSource = interactionSource,
                                colors = colors,
                                shape = shape,
                            )
                        },
                    )
                } else {
                    TextFieldDefaults.DecorationBox(
                        value = value.text,
                        innerTextField = editor,
                        enabled = enabled,
                        singleLine = singleLine,
                        visualTransformation = visualTransformation,
                        interactionSource = interactionSource,
                        placeholder = placeholder,
                        trailingIcon = trailing,
                        colors = colors,
                        container = {
                            TextFieldDefaults.Container(
                                enabled = enabled,
                                isError = false,
                                interactionSource = interactionSource,
                                colors = colors,
                                shape = shape,
                            )
                        },
                    )
                }
            },
        )
    }
}
