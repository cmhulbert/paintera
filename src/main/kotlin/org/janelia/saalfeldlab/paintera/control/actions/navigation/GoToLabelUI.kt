package org.janelia.saalfeldlab.paintera.control.actions.navigation

import javafx.beans.property.LongProperty
import javafx.beans.property.SimpleLongProperty
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.ui.PositiveLongTextFormatter
import org.janelia.saalfeldlab.paintera.ui.hGrow
import org.janelia.saalfeldlab.paintera.ui.hvGrow


interface GoToLabelUIState {
	val labelProperty: LongProperty
}

class GoToLabelUI(val state: GoToLabelUIState) : HBox() {

	init {
		hvGrow()
		spacing = 5.0
		padding = Insets(5.0)
		alignment = Pos.CENTER_RIGHT
		children += Label("Label ID:")
		children += TextField().hGrow {
			textFormatter = PositiveLongTextFormatter().apply {
				value = state.labelProperty.value
				state.labelProperty.bind(valueProperty())
			}
		}
	}
}


fun main() {
	InvokeOnJavaFXApplicationThread {

		val state = object : GoToLabelUIState {
			override val labelProperty = SimpleLongProperty(1234L)
		}

		val root = VBox()
		root.apply {
			children += Button("Reload").apply {
				onAction = EventHandler {
					root.children.removeIf { it is GoToLabelUI }
					root.children.add(GoToLabelUI(state))
				}
			}
			children += GoToLabelUI(state)
		}

		val scene = Scene(root)
		val stage = Stage()
		stage.scene = scene
		stage.show()
	}
}

