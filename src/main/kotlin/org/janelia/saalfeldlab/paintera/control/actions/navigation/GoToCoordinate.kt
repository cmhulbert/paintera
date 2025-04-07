package org.janelia.saalfeldlab.paintera.control.actions.navigation

import javafx.event.EventHandler
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.layout.VBox
import javafx.stage.Stage
import org.janelia.saalfeldlab.fx.actions.onAction
import org.janelia.saalfeldlab.fx.actions.verifyPermission
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.control.actions.MenuAction
import org.janelia.saalfeldlab.paintera.control.actions.NavigationActionType
import kotlin.jvm.optionals.getOrNull


object GoToCoordinate : MenuAction("_Go to Coordinate...") {

	init {
		verifyPermission(NavigationActionType.Pan)
		onAction<GoToCoordinateState> {
			initializeWithCurrentCoordinates()
			val buttonType = getDialog("Go To Source Coordinate").showAndWait().getOrNull()
			when (buttonType) {
				ButtonType.OK -> translateToCoordinate(xProperty.value, yProperty.value, zProperty.value)
			}
		}
	}
}

internal

fun main() {
	InvokeOnJavaFXApplicationThread {

		val state = GoToCoordinateUI.Default()
		val root = VBox()
		root.apply {
			children += Button("Reload").apply {
				onAction = EventHandler {
					root.children.removeIf { it is GoToCoordinateUI }
					root.children.add(GoToCoordinateUI(state))
				}
			}
			children += GoToCoordinateUI(state)
		}

		val scene = Scene(root)
		val stage = Stage()
		stage.scene = scene
		stage.show()
	}
}

