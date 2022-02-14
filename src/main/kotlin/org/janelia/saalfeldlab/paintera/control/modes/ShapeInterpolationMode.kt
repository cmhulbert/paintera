package org.janelia.saalfeldlab.paintera.control.modes

import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableValue
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent.MOUSE_CLICKED
import javafx.scene.input.ScrollEvent
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.fx.actions.*
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.EXIT_SHAPE_INTERPOLATION_MODE
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_APPLY_MASK
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_APPLY_MASK_INPLACE
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_NEXT_SELECTION
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_PREVIOUS_SELECTION
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_SELECTION_1
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_SELECTION_2
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController.ModeState
import org.janelia.saalfeldlab.paintera.control.actions.AllowedActions
import org.janelia.saalfeldlab.paintera.control.actions.MenuActionType
import org.janelia.saalfeldlab.paintera.control.actions.NavigationActionType
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.navigation.TranslateWithinPlane
import org.janelia.saalfeldlab.paintera.control.tools.ViewerTool
import org.janelia.saalfeldlab.paintera.control.tools.paint.PaintBrushTool
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles

class ShapeInterpolationMode<D : IntegerType<D>>(val controller: ShapeInterpolationController<D>, val previousMode: ControlMode) : AbstractToolMode() {

    private inner class ShapeIntepolationToolProperty : SimpleObjectProperty<ShapeInterpolationTool?>() {

        private val keyAndMouseBindingsProperty = activeSourceStateProperty.createValueBinding {
            it?.let {
                paintera.baseView.keyAndMouseBindings.getConfigFor(it)
            }
        }

        init {
            bind(keyAndMouseBindingsProperty.createValueBinding {
                it?.let {
                    ShapeInterpolationTool(controller, it.keyCombinations, previousMode)
                }
            })
        }
    }

    private val shapeInterpolationToolProperty = ShapeIntepolationToolProperty()

    internal val paintBrushTool = PaintBrushTool(activeSourceStateProperty)

    override val toolTriggers by lazy { getToolTriggerActions() }

    override val allowedActions = AllowedActions.AllowedActionsBuilder()
        .add(PaintActionType.ShapeInterpolation, PaintActionType.Paint, PaintActionType.Erase, PaintActionType.SetBrush)
        .add(MenuActionType.ToggleMaximizeViewer)
        .add(NavigationActionType.Drag, NavigationActionType.Zoom, NavigationActionType.Scroll)
        .create()

    private val toolTriggerListener = ChangeListener<OrthogonalViews.ViewerAndTransforms?> { _, old, new ->
        new?.viewer()?.apply { toolTriggers.forEach { installActionSet(it) } }
        old?.viewer()?.apply { toolTriggers.forEach { removeActionSet(it) } }
    }

    override fun enter() {
        activeViewerProperty.addListener(toolTriggerListener)
        super.enter()

        /* Try to initialize the tool, if state is valid. If not, change back to previous mode. */
        activeViewerProperty.get()?.viewer()?.let {
            shapeInterpolationToolProperty.get()?.let { shapeInterpolationTool ->
                controller.apply {
                    if (!isModeOn && source.currentMask == null && source.isApplyingMaskProperty.not().get()) {
                        enterMode(it)
                        switchTool(shapeInterpolationTool)
                    }
                }
            } ?: paintera.baseView.changeMode(previousMode)
        } ?: paintera.baseView.changeMode(previousMode)
    }

    override fun exit() {
        super.exit()

        paintBrushTool.brushProperties.unbindBidirectional()
        activeViewerProperty.removeListener(toolTriggerListener)
    }

    private fun getToolTriggerActions(): List<ActionSet> {
        return FXCollections.observableArrayList(
            PainteraActionSet(PaintActionType.Paint, "paint during shape interpolation") {
                KEY_PRESSED(KeyCode.SPACE) {
                    name = "switch to paint tool"
                    verify { activeSourceStateProperty.get()?.dataSource is MaskedSource<*, *> }
                    verify { activeTool !is PaintBrushTool }
                    onAction {
                        switchTool(paintBrushTool)
                    }
                }
                KEY_RELEASED {
                    name = "switch back to shape interpolation tool"
                    keysReleased(KeyCode.SPACE)
                    onAction {
                        switchTool(shapeInterpolationToolProperty.get())

                        controller.selectObject(paintera.mouseTracker.x, paintera.mouseTracker.y, false)
                    }
                }
            }
        )
    }
}


class ShapeInterpolationTool(val controller: ShapeInterpolationController<*>, keyCombinations: NamedKeyCombination.CombinationMap, val previousMode: ControlMode) : ViewerTool() {
    override val graphicProperty: SimpleObjectProperty<Node>
        get() = TODO("Not yet implemented")

    private val baseView = paintera.baseView

    private var inRestrictedNavigation = false

    override val actionSets: List<ActionSet> = mutableListOf(
        shapeInterpolationActions(keyCombinations),
        fixSelectionBeforeRestrictedNavigation(),
        *NavigationTool.actionSets.toTypedArray()
    )

    override fun activate() {
        super.activate()
        /* set the converter fragment alpha to be the same as the segment. We do this so we can use the fragment alpha for the
        * selected objects during shape interpolation flood fill. This needs to be un-done in the #deactivate */
        controller.converter.activeFragmentAlphaProperty().apply {
            controller.activeSelectionAlpha = get() / 255
            set(controller.converter.activeSegmentAlphaProperty().get())
        }
        inRestrictedNavigation = false
        /* This action set allows us to translate through the unfocused viewers */
        paintera.baseView.orthogonalViews().viewerAndTransforms()
            .filter { !it.viewer().isFocusable }
            .forEach { disabledViewerAndTransform ->
                val translateWhileDisabled = disabledViewerTranslateOnlyMap.computeIfAbsent(disabledViewerAndTransform, disabledViewerTranslateOnly)
                /* remove if already present, to avoid adding twice */
                disabledViewerAndTransform.viewer().removeActionSet(translateWhileDisabled)
                disabledViewerAndTransform.viewer().installActionSet(translateWhileDisabled)
            }
    }

    override fun deactivate() {
        /* Add the activeFragmentAlpha back when we are done */
        controller.apply {
            converter.activeFragmentAlphaProperty().set(activeSelectionAlpha)
        }
        disabledViewerTranslateOnlyMap.forEach { (vat, actionSet) -> vat.viewer().removeActionSet(actionSet) }
        disabledViewerTranslateOnlyMap.clear()
        super.deactivate()
    }

    override val statusProperty = SimpleStringProperty().apply {
        val statusBinding = controller.modeStateProperty().createValueBinding(controller.activeSectionProperty) {
            when (it) {
                ModeState.Select -> "Select # ${controller.activeSectionProperty.get() + 1}"
                ModeState.Interpolate -> "Interpolating..."
                ModeState.Preview -> "Preview"
                else -> ""
            }
        }
        bind(statusBinding)
    }

    val disabledViewerTranslateOnlyMap = mutableMapOf<OrthogonalViews.ViewerAndTransforms, PainteraDragActionSet>()

    private val disabledViewerTranslateOnly = { vat: OrthogonalViews.ViewerAndTransforms ->
        val translator = vat.run {
            val globalTransformManager = paintera.baseView.manager()
            TranslateWithinPlane(globalTransformManager, displayTransform(), globalToViewerTransform())
        }
        PainteraDragActionSet(NavigationActionType.Drag, "translate xy") {
            verify { it.isSecondaryButtonDown }
            verify {
                controller.run {
                    (modeState == ModeState.Select)
                        && ((!selectedObjects.isEmpty && numSections() < 2) || selectedObjects.isEmpty) // Either selecting the second, or editing
                }
            }
            handleDragDetected {
                if (!inRestrictedNavigation) {
                    controller.apply {
                        if (fixCurrentSelection()) {
                            transitionToNextSelection()
                        }
                    }
                    inRestrictedNavigation = true
                }
                translator.init()
            }
            handleDrag { translator.translate(it.x - startX, it.y - startY) }
        }
    }


    private fun fixSelectionBeforeRestrictedNavigation(): ActionSet {
        return PainteraActionSet(NavigationActionType.Scroll, "restricted navigation toggle") {
            ScrollEvent.SCROLL {
                filter = true
                consume = false
                ignoreKeys()
                verify {
                    with(controller) {
                        return@with !inRestrictedNavigation // Not already navigating
                            && (modeState == ModeState.Select) // in select mode
                            && ((!selectedObjects.isEmpty && numSections() < 2) || selectedObjects.isEmpty) // Either selecting the second, or editing
                    }
                }
                onAction {
                    with(controller) {
                        if (fixCurrentSelection()) {
                            transitionToNextSelection()
                        }
                    }
                    inRestrictedNavigation = true
                    /* Bind the  NavigationTool properties required for navigation.
                     *  This is only half of the logic required to use NavigationTool in another tool.
                     *  The other important step is to add the NavigationTool.actionSets to this tool's actions
                     *
                     *  Note: We are still restricted by the ActionType permissions, as desired.  */
                    NavigationTool.activate()
                }
            }
        }
    }

    private fun shapeInterpolationActions(keyCombinations: NamedKeyCombination.CombinationMap): ActionSet {
        return PainteraActionSet(PaintActionType.ShapeInterpolation, "shape interpolation") {
            with(controller) {
                verifyAll(KEY_PRESSED) { isModeOn }

                KEY_PRESSED {
                    keyMatchesBinding(keyCombinations, EXIT_SHAPE_INTERPOLATION_MODE)
                    onAction {
                        NavigationTool.deactivate()
                        exitMode(false)
                        baseView.changeMode(previousMode)
                    }
                }
                KEY_PRESSED {
                    keyMatchesBinding(keyCombinations, SHAPE_INTERPOLATION_APPLY_MASK)
                    onAction {
                        if (applyMask()) {
                            baseView.changeMode(previousMode)
                        }
                    }
                    handleException {
                        baseView.changeMode(previousMode)
                    }
                }
                KEY_PRESSED {
                    keyMatchesBinding(keyCombinations, SHAPE_INTERPOLATION_APPLY_MASK_INPLACE)
                    onAction {
                        val startWhenReady = object : ChangeListener<Boolean> {
                            override fun changed(observable: ObservableValue<out Boolean>, oldValue: Boolean, newValue: Boolean) {
                                if (!newValue) {
                                    InvokeOnJavaFXApplicationThread {
                                        restartFromLastSection()
                                        observable.removeListener(this)
                                    }
                                }
                            }
                        }
                        if (applyMask(false)) {
                            source.isMaskInUseBinding.addListener(startWhenReady)
                        }
                    }
                    handleException {
                        baseView.changeMode(previousMode)
                    }
                }
                keyPressEditSelectionAction({ 0 }, SHAPE_INTERPOLATION_EDIT_SELECTION_1, keyCombinations)
                keyPressEditSelectionAction({ 1 }, SHAPE_INTERPOLATION_EDIT_SELECTION_2, keyCombinations)
                keyPressEditSelectionAction({ activeSectionProperty.get() - 1 }, SHAPE_INTERPOLATION_EDIT_PREVIOUS_SELECTION, keyCombinations)
                keyPressEditSelectionAction({ activeSectionProperty.get() + 1 }, SHAPE_INTERPOLATION_EDIT_NEXT_SELECTION, keyCombinations)
                MOUSE_CLICKED {
                    name = "select object in current section"

                    verifyNoKeysDown()
                    verify { !paintera.mouseTracker.isDragging }
                    verify { it.button == MouseButton.PRIMARY } // respond to primary click
                    verify { modeState == ModeState.Select } // need to be in the select state
                    onAction {
                        inRestrictedNavigation = false
                        selectObject(it.x, it.y, true)
                    }
                }
                MOUSE_CLICKED {
                    name = "toggle object in current section"
                    verify { !paintera.mouseTracker.isDragging }
                    verify { modeState == ModeState.Select } // need to be in the select state
                    verify {
                        val triggerByRightClick = (it.button == MouseButton.SECONDARY) && keyTracker!!.noKeysActive()
                        val triggerByCtrlLeftClick = (it.button == MouseButton.PRIMARY) && keyTracker!!.areOnlyTheseKeysDown(KeyCode.CONTROL)
                        triggerByRightClick || triggerByCtrlLeftClick
                    }
                    onAction {
                        inRestrictedNavigation = false
                        selectObject(it.x, it.y, false)
                    }
                }
            }
        }
    }

    private fun ActionSet.keyPressEditSelectionAction(idx: () -> Int, keyName: String, keyCombinations: NamedKeyCombination.CombinationMap) = with(controller) {
        KEY_PRESSED {
            keyMatchesBinding(keyCombinations, keyName)
            verify { idx() < controller.numSections() && idx() >= 0 }
            onAction {
                inRestrictedNavigation = false
                editSelection(idx())
            }
            handleException {

            LOG.error("Error during shape interpolation edit selection ${idx() + 1}. Exiting Shape Interpolation.", it)
                exitMode(false)
                baseView.changeMode(previousMode)
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    }
}
