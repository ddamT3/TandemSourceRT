package com.example.tandemapp.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun PumpIcon(
	modifier: Modifier = Modifier,
	size: Dp = 22.dp,
	tint: Color = Color(0xFF37474F)
) {
	Canvas(
		modifier = modifier.size(size)
	) {

		// 🔥 QUI il fix: usa size del DrawScope
		val w = this.size.width
		val h = this.size.height

		val bodyLeft = w * 0.20f
		val bodyTop = h * 0.16f
		val bodyWidth = w * 0.60f
		val bodyHeight = h * 0.68f

		// Corpo pompa
		drawRoundRect(
			color = tint,
			topLeft = Offset(bodyLeft, bodyTop),
			size = Size(bodyWidth, bodyHeight),
			cornerRadius = CornerRadius(w * 0.08f, w * 0.08f)
		)

		// Display
		drawRoundRect(
			color = Color.White,
			topLeft = Offset(w * 0.29f, h * 0.26f),
			size = Size(w * 0.32f, h * 0.16f),
			cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
		)

		// Pulsanti
		drawCircle(
			color = Color.White,
			radius = w * 0.035f,
			center = Offset(w * 0.34f, h * 0.58f)
		)
		drawCircle(
			color = Color.White,
			radius = w * 0.035f,
			center = Offset(w * 0.50f, h * 0.58f)
		)
		drawCircle(
			color = Color.White,
			radius = w * 0.035f,
			center = Offset(w * 0.66f, h * 0.58f)
		)

		// Tubo superiore
		drawLine(
			color = tint,
			start = Offset(w * 0.50f, h * 0.04f),
			end = Offset(w * 0.50f, h * 0.16f),
			strokeWidth = w * 0.08f,
			cap = StrokeCap.Round
		)

		// Connettore laterale
		drawLine(
			color = tint,
			start = Offset(w * 0.80f, h * 0.34f),
			end = Offset(w * 0.92f, h * 0.34f),
			strokeWidth = w * 0.07f,
			cap = StrokeCap.Round
		)
	}
}