package org.janelia.saalfeldlab.paintera.ui

import javafx.scene.Node
import javafx.scene.control.TextField
import javafx.scene.control.TextFormatter
import javafx.scene.layout.HBox.setHgrow
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox.setVgrow
import javafx.util.StringConverter
import net.imglib2.algorithm.math.ImgMath.let
import org.jdom2.filter.Filters.text


class PositiveLongTextFormatter(initialValue: Long? = null) : TextFormatter<Long?>(
	object : StringConverter<Long?>() {
		override fun toString(`object`: Long?) = `object`?.takeIf { it >= 0 }?.let { "$it" }
		override fun fromString(string: String?) = string?.toLongOrNull()
	},
	initialValue,
	{ it.apply { if (controlNewText.runCatching{toLong()}.isFailure) text = "" } }
)

class PositiveDoubleTextFormatter(initialValue: Double? = null, format : String? = "%.3f") : TextFormatter<Double>(
	object : StringConverter<Double>() {
		override fun toString(`object`: Double?) = `object`?.takeIf { it >= 0 }?.let { format?.format(it) ?: "$it" }
		override fun fromString(string: String?) = string?.toDoubleOrNull()
	},
	initialValue,
	{ it.apply { if (controlNewText?.toDoubleOrNull() == null) text = "" } }
)

fun <T : Node> T.hGrow(apply: (T.() -> Unit)? = { }): T {
	setHgrow(this, Priority.ALWAYS)
	apply?.invoke(this)
	return this
}

fun <T : Node> T.vGrow(apply: (T.() -> Unit)? = { }): T {
	setVgrow(this, Priority.ALWAYS)
	apply?.invoke(this)
	return this
}

fun <T : Node> T.hvGrow(apply: (T.() -> Unit)? = { }): T {
	hGrow()
	vGrow()
	apply?.invoke(this)
	return this
}