package org.janelia.saalfeldlab.paintera.control.actions.navigation

import javafx.beans.property.BooleanProperty
import javafx.beans.property.LongProperty
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleLongProperty
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.*
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.Paintera
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts
import org.janelia.saalfeldlab.paintera.ui.PositiveLongTextFormatter
import org.janelia.saalfeldlab.paintera.ui.hGrow
import org.janelia.saalfeldlab.paintera.ui.hvGrow


class GoToLabelUI(val model: Model) : VBox(5.0) {

	interface Model {
		val labelProperty: LongProperty
		val activateLabelProperty: BooleanProperty

		fun getDialog(title: String = "Go To Label"): Alert {
			return PainteraAlerts.confirmation("Go", "Cancel", true, null).apply {
				this.title = title
				headerText = "Go to Label"
				dialogPane.content = GoToLabelUI(this@Model)
			}
		}
	}

	class Default : Model {
		override val labelProperty = SimpleLongProperty()
		override val activateLabelProperty = SimpleBooleanProperty(true)
	}

	init {
		hvGrow()
		padding = Insets(5.0)
		alignment = Pos.CENTER_RIGHT
		children += HBox(5.0).apply {
			alignment = Pos.BOTTOM_RIGHT
			children += Label("Label ID:")
			children += TextField().hGrow {
				textFormatter = PositiveLongTextFormatter().apply {
					value = model.labelProperty.value
					model.labelProperty.bind(valueProperty())
				}
			}
		}
		children += HBox(5.0).apply {
			alignment = Pos.BOTTOM_RIGHT
			children += Label("Activate Label? ")
			children += CheckBox().apply {
				selectedProperty().bindBidirectional(model.activateLabelProperty)
			}
		}

	}
}


fun main() {
	InvokeOnJavaFXApplicationThread {

		val model = GoToLabelUI.Default().apply {
			labelProperty.set(1234)
		}

		val dialog = model.getDialog().apply {
			val reloadButton = ButtonType("Reload", ButtonBar.ButtonData.LEFT)
			dialogPane.buttonTypes += reloadButton
			(dialogPane.lookupButton(reloadButton) as? Button)?.addEventFilter(ActionEvent.ACTION) {
				dialogPane.content = GoToLabelUI(model)
				it.consume()
			}
		}
		dialog.show()
	}
}

