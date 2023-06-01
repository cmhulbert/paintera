package org.janelia.saalfeldlab.paintera.control.modes

import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.event.EventHandler
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import javafx.scene.layout.GridPane
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.control.mcu.MCUButtonControl
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.ActionSet.Companion.installActionSet
import org.janelia.saalfeldlab.fx.actions.ActionSet.Companion.removeActionSet
import org.janelia.saalfeldlab.fx.actions.painteraActionSet
import org.janelia.saalfeldlab.fx.actions.painteraMidiActionSet
import org.janelia.saalfeldlab.fx.extensions.createNullableValueBinding
import org.janelia.saalfeldlab.fx.extensions.nullableVal
import org.janelia.saalfeldlab.fx.midi.MidiToggleEvent
import org.janelia.saalfeldlab.fx.midi.ToggleAction
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.fx.ui.StyleableImageView
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.DeviceManager
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.ENTER_SHAPE_INTERPOLATION_MODE
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController
import org.janelia.saalfeldlab.paintera.control.actions.AllowedActions
import org.janelia.saalfeldlab.paintera.control.actions.LabelActionType
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.tools.Tool
import org.janelia.saalfeldlab.paintera.control.tools.paint.*
import org.janelia.saalfeldlab.paintera.control.tools.paint.PaintTool.Companion.createPaintStateContext
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts


object PaintLabelMode : AbstractToolMode() {

	private val activeSourceToSourceStateContextBinding = activeSourceStateProperty.createNullableValueBinding { binding -> createPaintStateContext(binding) }
	internal val statePaintContext by activeSourceToSourceStateContextBinding.nullableVal()

	private val paintBrushTool = PaintBrushTool(activeSourceStateProperty, this)
	private val samTool = SamTool(activeSourceStateProperty, this)
	private val fill2DTool = Fill2DTool(activeSourceStateProperty, this)
	private val fill3DTool = Fill3DTool(activeSourceStateProperty, this)
	private val intersectTool = IntersectPaintWithUnderlyingLabelTool(activeSourceStateProperty, this)

	override val defaultTool = NavigationTool

	override val tools: ObservableList<Tool> by lazy {
		FXCollections.observableArrayList(
			NavigationTool,
			paintBrushTool,
			fill2DTool,
            fill3DTool,
            intersectTool,
            samTool
        )
	}

	override val modeActions: List<ActionSet> by lazy {
		listOf(
			escapeToDefault(),
			*getToolTriggers().toTypedArray(),
			getSelectNextIdActions(),
			getResetMaskAction(),
		)
	}

	override val allowedActions = AllowedActions.PAINT

	private val moveModeActionsToActiveViewer = ChangeListener<OrthogonalViews.ViewerAndTransforms?> { _, old, new ->
		/* remove the mode actions from the deactivated viewer, add to the activated viewer */
		modeActions.forEach { actionSet ->
			old?.viewer()?.removeActionSet(actionSet)
			new?.viewer()?.installActionSet(actionSet)
		}
	}

	override fun createToolBar(): GridPane {
		val toolBarGrid = super.createToolBar()
		/* Add tool to switch to interpolation mode */
		toolBarGrid.add(Button().also { siButton ->
			siButton.styleClass += "toolbar-button"
			siButton.graphic = StyleableImageView().also { it.styleClass += listOf("toolbar-tool", "enter-shape-interpolation") }
			siButton.onAction = EventHandler {
				/* remove the current tool */
				switchTool(null)
				/* Indicate a viewer selection is required */
				selectViewerBefore {
					newShapeInterpolationModeForSource(activeSourceStateProperty.get())?.let { shapeInterpMode ->
						paintera.baseView.changeMode(shapeInterpMode)
					}
				}
			}
		}, toolBarGrid.columnCount, 0)
		return toolBarGrid
	}

	override fun enter() {
		activeViewerProperty.addListener(moveModeActionsToActiveViewer)
		super.enter()
	}

	override fun exit() {
		activeViewerProperty.removeListener(moveModeActionsToActiveViewer)
		activeViewerProperty.get()?.let {
			modeActions.forEach { actionSet ->
				it.viewer()?.removeActionSet(actionSet)
			}
		}
		super.exit()
	}

	private val toggleFill3D = painteraActionSet("toggle fill 3D overlay", PaintActionType.Fill) {
		KEY_PRESSED(KeyCode.F, KeyCode.SHIFT) {
			onAction { switchTool(fill3DTool) }
		}
		KEY_PRESSED {
			/* swallow F down events while filling*/
			filter = true
			consume = true
			verifyEventNotNull()
			verify { it!!.code in listOf(KeyCode.F, KeyCode.SHIFT) && activeTool is Fill3DTool }
		}

		KEY_RELEASED {
			verifyEventNotNull()
			keysReleased(KeyCode.F, KeyCode.SHIFT)
			verify { activeTool is Fill3DTool }
			onAction {
				when (it!!.code) {
					KeyCode.F -> switchTool(NavigationTool)
					KeyCode.SHIFT -> switchTool(fill2DTool)
					else -> return@onAction
				}
			}
		}
	}

	private val enterShapeInterpolationMode = painteraActionSet(ENTER_SHAPE_INTERPOLATION_MODE, PaintActionType.ShapeInterpolation) {
		KEY_PRESSED(KeyCode.S) {
			verify { activeSourceStateProperty.get() is ConnectomicsLabelState<*, *> }
			verify {
				@Suppress("UNCHECKED_CAST")
				activeSourceStateProperty.get()?.dataSource as? MaskedSource<out IntegerType<*>, *> != null
			}
			onAction {
				newShapeInterpolationModeForSource(activeSourceStateProperty.get())?.let {
					paintera.baseView.changeMode(it)
				}
			}
		}
	}

    private val activeSamTool = painteraActionSet("Sam Mode", PaintActionType.Paint) {
        KEY_PRESSED(*samTool.keyTrigger.toTypedArray()) {
            verify { activeSourceStateProperty.get() is ConnectomicsLabelState<*, *> }
            verify { activeTool !is SamTool }
            verify {
                @Suppress("UNCHECKED_CAST")
                activeSourceStateProperty.get()?.dataSource as? MaskedSource<out IntegerType<*>, *> != null
            }
            onAction {
                disableUnfocusedViewers()
                switchTool(samTool)
            }
        }
        KEY_PRESSED(*samTool.keyTrigger.toTypedArray()) {
            verify { activeSourceStateProperty.get() is ConnectomicsLabelState<*, *> }
            verify { activeTool is SamTool }
            onAction {
                enableAllViewers()
                switchTool(defaultTool)
            }
        }
        KEY_PRESSED(KeyCode.ESCAPE) {
            verify { activeSourceStateProperty.get() is ConnectomicsLabelState<*, *> }
            verify { activeTool is SamTool }
            filter = true
            consume = false
            onAction {
                enableAllViewers()
                switchTool(defaultTool)
            }
        }
    }


	private fun getToolTriggers() = listOf(
		paintBrushTool.createTriggers(this, PaintActionType.Paint),
		fill2DTool.createTriggers(this, PaintActionType.Fill, ignoreDisable = false),
		toggleFill3D,
		intersectTool.createTriggers(this, PaintActionType.Intersect),
		enterShapeInterpolationMode,
        activeSamTool

	)

	private fun getSelectNextIdActions() = painteraActionSet("Create New Segment", LabelActionType.CreateNew) {
		KEY_PRESSED(keyBindings!!, LabelSourceStateKeys.NEXT_ID) {
			name = "create new segment"
			verify { activeTool?.let { it !is PaintTool || !it.isPainting } ?: true }
			onAction {
				statePaintContext?.nextId(activate = true)
			}
		}
	}

	private fun getResetMaskAction() = painteraActionSet("Force Mask Reset", PaintActionType.Paint, ignoreDisable = true) {
		KEY_PRESSED(KeyCode.SHIFT, KeyCode.ESCAPE) {
			verify {
				statePaintContext?.let { state ->
					(state.dataSource as? MaskedSource<*, *>)?.let { maskedSource ->
						maskedSource.currentMask?.let { true } ?: false
					} ?: false
				} ?: false
			}
			onAction {
				InvokeOnJavaFXApplicationThread {
					PainteraAlerts.confirmation("Yes", "No", false, paintera.pane.scene.window).apply {
						headerText = "Force Reset the Active Mask?"
						contentText = """
                            This may result in loss of some of the most recent uncommitted label annotations. This usually is only necessary if the mask is stuck on "busy".

                            Only do this if you suspect an error has occured. You may consider waiting a bit to see if the mask releases on it's own.
                        """.trimIndent()
						val okButton = dialogPane.lookupButton(ButtonType.OK) as Button
						okButton.onAction = EventHandler {
							activeSourceStateProperty.get()?.let { state ->
								(state.dataSource as? MaskedSource<*, *>)?.resetMasks()
							}
						}
						showAndWait()
					}
				}
			}
		}
	}

	private fun newShapeInterpolationModeForSource(sourceState: SourceState<*, *>?): ShapeInterpolationMode<*>? {
		return sourceState?.let { state ->
			@Suppress("UNCHECKED_CAST")
            (state as? ConnectomicsLabelState<*, *>)?.run {
                (dataSource as? MaskedSource<out IntegerType<*>, *>)?.let { maskedSource ->
                    ShapeInterpolationController(
                        maskedSource,
                        ::refreshMeshes,
                        selectedIds,
                        idService,
                        converter(),
                        fragmentSegmentAssignment,
                    )
                }
            }?.let { ShapeInterpolationMode(it, this) }
		}
	}

    internal fun midiToolTogleActions() = DeviceManager.xTouchMini?.let { device ->
        activeViewerProperty.get()?.viewer()?.let { viewer ->
            painteraMidiActionSet("midi paint tool switch actions", device, viewer, PaintActionType.Paint) {
                val toggleToolActionMap = mutableMapOf<Tool, ToggleAction>()
                activeToolProperty.addListener { obs, old, new ->
                    toggleToolActionMap[old]?.updateControlSilently(MCUButtonControl.TOGGLE_OFF)
                    toggleToolActionMap[new]?.updateControlSilently(MCUButtonControl.TOGGLE_ON)
                }
                toggleToolActionMap[NavigationTool] = MidiToggleEvent.BUTTON_TOGGLE(0) {
                    name = "midi switch back to navigation tool"
                    filter = true
                    onAction {
                        InvokeOnJavaFXApplicationThread {
                            if (activeTool is Fill2DTool) {
                                fill2DTool.fill2D.release()
                            }
                            if (activeTool != NavigationTool)
                                switchTool(NavigationTool)
                            /* If triggered, ensure toggle is on. Only can be off when switching to another tool */
                            updateControlSilently(MCUButtonControl.TOGGLE_ON)
                        }
                    }
                }
                toggleToolActionMap[paintBrushTool] = MidiToggleEvent.BUTTON_TOGGLE(1) {
                    name = "midi switch to paint tool"
                    verify { activeSourceStateProperty.get()?.dataSource is MaskedSource<*, *> }
                    onAction {
                        InvokeOnJavaFXApplicationThread {
                            if (activeTool == paintBrushTool) {
                                switchTool(NavigationTool)
                            } else {
                                switchTool(paintBrushTool)
                                paintBrushTool.enteredWithoutKeyTrigger = true
                            }
                        }
                    }
                }
                toggleToolActionMap[fill2DTool] = MidiToggleEvent.BUTTON_TOGGLE(2) {
                    name = "midi switch to fill2d tool"
                    verify { activeSourceStateProperty.get()?.dataSource is MaskedSource<*, *> }
                    onAction {
                        InvokeOnJavaFXApplicationThread {
                            if (activeTool == fill2DTool) {
                                switchTool(NavigationTool)
                            } else {
                                switchTool(fill2DTool)
                            }
                        }
                    }
                }
//                toggleToolActionMap[]
            }
        }
    }

}


