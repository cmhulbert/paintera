package org.janelia.saalfeldlab.paintera.control.modes

import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.collections.ObservableList
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.PainteraActionSet
import org.janelia.saalfeldlab.fx.actions.installActionSet
import org.janelia.saalfeldlab.fx.actions.removeActionSet
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.ENTER_SHAPE_INTERPOLATION_MODE
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController
import org.janelia.saalfeldlab.paintera.control.actions.AllowedActions
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.tools.Tool
import org.janelia.saalfeldlab.paintera.control.tools.paint.Fill2DTool
import org.janelia.saalfeldlab.paintera.control.tools.paint.Fill3DTool
import org.janelia.saalfeldlab.paintera.control.tools.paint.PaintBrushTool
import org.janelia.saalfeldlab.paintera.control.tools.paint.RestrictPaintToLabelTool
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.state.LabelSourceState
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState


object PaintLabelMode : AbstractToolMode() {

    private val paintBrushTool = PaintBrushTool(activeSourceStateProperty)
    private val fill2DTool = Fill2DTool(activeSourceStateProperty)
    private val fill3DTool = Fill3DTool(activeSourceStateProperty)
    private val restrictTool = RestrictPaintToLabelTool(activeSourceStateProperty)

    override val toolBarTools: ObservableList<Tool> by lazy {
        FXCollections.observableArrayList(
            NavigationTool,
            paintBrushTool,
            fill2DTool,
            fill3DTool,
            restrictTool
        )
    }

    override val toolTriggers: List<ActionSet> by lazy { getToolTriggerActions() }

    override val allowedActions = AllowedActions.PAINT

    private val moveToolTriggersToActiveViewer = ChangeListener<OrthogonalViews.ViewerAndTransforms?> { _, old, new ->
        /* remove the tool triggers from old, add to new */
        toolTriggers.forEach { actionSet ->
            old?.viewer()?.removeActionSet(actionSet)
            new?.viewer()?.installActionSet(actionSet)
        }

        /* set the currently activeTool for this viewer */
        switchTool(activeTool ?: NavigationTool)
    }

    override fun enter() {
        activeViewerProperty.addListener(moveToolTriggersToActiveViewer)
        super.enter()
    }

    override fun exit() {
        activeViewerProperty.removeListener(moveToolTriggersToActiveViewer)
        activeViewerProperty.get()?.let {
            toolTriggers.forEach { actionSet ->
                it.viewer()?.removeActionSet(actionSet)
            }
        }
        super.exit()
    }


    val togglePaintBrush = PainteraActionSet(PaintActionType.Paint, "toggle paint tool") {
        KEY_PRESSED(KeyCode.SPACE) {
            keysExclusive = false
            consume = false
            verify { activeSourceStateProperty.get()?.dataSource is MaskedSource<*, *> }
            verify { activeTool !is PaintBrushTool }
            onAction {
                switchTool(paintBrushTool)
                if (it.isShiftDown) {
                    paintBrushTool.selectBackground()
                } else {
                    paintBrushTool.deselectBackground()
                }
            }
        }
        KEY_PRESSED(KeyCode.SPACE) {
            /* swallow SPACE down events while painting*/
            filter = true
            consume = true
            verify { activeTool is PaintBrushTool }
        }
        KEY_RELEASED {
            keysReleased(KeyCode.SPACE)
            verify { activeTool is PaintBrushTool }
            onAction { switchTool(NavigationTool) }
        }
    }

    val toggleFill2D = PainteraActionSet(PaintActionType.Fill, "toggle fill 2D overlay") {
        KEY_PRESSED(KeyCode.F) {
            verify { activeTool !is Fill2DTool }
            onAction { switchTool(fill2DTool) }
        }

        KEY_PRESSED(KeyCode.F) {
            /* swallow F down events while Filling*/
            filter = true
            consume = true
            verify { activeTool is Fill2DTool }
        }

        KEY_RELEASED {
            keysReleased(KeyCode.F)
            verify { activeTool is Fill2DTool }
            onAction { switchTool(NavigationTool) }
        }
    }

    val toggleFill3D = PainteraActionSet(PaintActionType.Fill, "toggle fill 3D overlay") {
        KEY_PRESSED(KeyCode.F, KeyCode.SHIFT) {
            verify { activeTool !is Fill3DTool }
            onAction { switchTool(fill3DTool) }
        }
        KEY_PRESSED {
            /* swallow F down events while Filling*/
            filter = true
            consume = true
            verify { it.code in listOf(KeyCode.F, KeyCode.SHIFT) && activeTool is Fill3DTool }
        }

        KEY_RELEASED {
            keysReleased(KeyCode.F, KeyCode.SHIFT)
            verify { activeTool is Fill3DTool }
            onAction {
                when (it.code) {
                    KeyCode.F -> switchTool(NavigationTool)
                    KeyCode.SHIFT -> switchTool(fill2DTool)
                    else -> return@onAction
                }
            }
        }

        KEY_PRESSED {
            ignoreKeys()
            consume = true
            name = LabelSourceStateKeys.CANCEL_3D_FLOODFILL
            /* Don't use `keyMatchesBinding` because we want to dynamically grabthe `keyBindingsProperty` each time, incase it changes */
            verify { keyBindingsProperty.get()?.matches(LabelSourceStateKeys.CANCEL_3D_FLOODFILL, it) ?: false }


        }
    }

    val restrictPaintToLabel = PainteraActionSet(PaintActionType.Restrict, "toggle restrict paint") {
        KEY_PRESSED(KeyCode.SHIFT, KeyCode.R) {
            verify { activeTool !is RestrictPaintToLabelTool }
            onAction { switchTool(restrictTool) }
        }
        KEY_PRESSED {
            /* swallow F down events while Filling*/
            filter = true
            consume = true
            verify { it.code in listOf(KeyCode.R, KeyCode.SHIFT) && activeTool is Fill3DTool }
        }

        KEY_RELEASED {
            keysReleased(KeyCode.SHIFT, KeyCode.R)
            verify { activeTool is RestrictPaintToLabelTool }
            onAction { switchTool(NavigationTool) }
        }
    }

    val enterShapeInterpolationMode = PainteraActionSet(PaintActionType.ShapeInterpolation, ENTER_SHAPE_INTERPOLATION_MODE) {
        KEY_PRESSED(KeyCode.S) {
            verify {
                when (activeSourceStateProperty.get()) {
                    is LabelSourceState<*, *> -> true
                    is ConnectomicsLabelState<*, *> -> true
                    else -> false
                }
            }
            verify { activeSourceStateProperty.get()?.dataSource as? MaskedSource<out IntegerType<*>, *> != null }
            onAction {
                newShapeInterpolationModeForSource(activeSourceStateProperty.get())?.let {
                    it.paintBrushTool.brushProperties.bindBidirectional(paintBrushTool.brushProperties)
                    paintera.baseView.changeMode(it)
                }
            }
        }
    }


    private fun getToolTriggerActions() = listOf(
        togglePaintBrush,
        toggleFill2D,
        toggleFill3D,
        restrictPaintToLabel,
        enterShapeInterpolationMode
    )

    private fun newShapeInterpolationModeForSource(sourceState: SourceState<*, *>?): ShapeInterpolationMode<*>? {
        return sourceState?.let { state ->
            (state.dataSource as? MaskedSource<out IntegerType<*>, *>)?.let { maskedSource ->
                when (state) {
                    is ConnectomicsLabelState<*, *> -> {
                        with(state) {
                            ShapeInterpolationController(
                                maskedSource,
                                ::refreshMeshes,
                                selectedIds,
                                idService,
                                converter(),
                                fragmentSegmentAssignment,
                            )
                        }
                    }
                    is LabelSourceState<*, *> -> {
                        with(state) {
                            ShapeInterpolationController(
                                maskedSource,
                                ::refreshMeshes,
                                selectedIds(),
                                idService(),
                                converter(),
                                assignment()
                            )
                        }
                    }
                    else -> null
                }?.let {
                    ShapeInterpolationMode(it, this)
                }
            }
        }
    }

}


