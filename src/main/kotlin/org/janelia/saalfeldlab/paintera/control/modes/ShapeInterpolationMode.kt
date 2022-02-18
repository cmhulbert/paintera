package org.janelia.saalfeldlab.paintera.control.modes

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.beans.value.ChangeListener
import javafx.collections.FXCollections
import javafx.scene.Node
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent.KEY_PRESSED
import javafx.scene.input.KeyEvent.KEY_RELEASED
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent.*
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.fx.actions.*
import org.janelia.saalfeldlab.fx.extensions.createValueBinding
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.labels.Label
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.EXIT_SHAPE_INTERPOLATION_MODE
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_APPLY_MASK
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_FIRST_SELECTION
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_LAST_SELECTION
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_NEXT_SELECTION
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_EDIT_PREVIOUS_SELECTION
import org.janelia.saalfeldlab.paintera.LabelSourceStateKeys.SHAPE_INTERPOLATION_TOGGLE_PREVIEW
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController
import org.janelia.saalfeldlab.paintera.control.ShapeInterpolationController.ModeState.Interpolate
import org.janelia.saalfeldlab.paintera.control.actions.AllowedActions
import org.janelia.saalfeldlab.paintera.control.actions.MenuActionType
import org.janelia.saalfeldlab.paintera.control.actions.NavigationActionType
import org.janelia.saalfeldlab.paintera.control.actions.PaintActionType
import org.janelia.saalfeldlab.paintera.control.navigation.TranslateWithinPlane
import org.janelia.saalfeldlab.paintera.control.paint.PaintClickOrDragController
import org.janelia.saalfeldlab.paintera.control.tools.ViewerTool
import org.janelia.saalfeldlab.paintera.control.tools.paint.PaintBrushTool
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.paintera
import org.janelia.saalfeldlab.paintera.util.IntervalHelpers.Companion.smallestContainingInterval
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
    private var previousFillLabel: Long? = null

    internal val paintBrushTool = PaintBrushTool(activeSourceStateProperty)

    override val modeActions by lazy { modeActions() }

    override val allowedActions = AllowedActions.AllowedActionsBuilder()
        .add(PaintActionType.ShapeInterpolation, PaintActionType.Paint, PaintActionType.Erase, PaintActionType.SetBrushSize)
        .add(MenuActionType.ToggleMaximizeViewer)
        .add(NavigationActionType.Pan, NavigationActionType.Slice, NavigationActionType.Zoom)
        .create()

    private val toolTriggerListener = ChangeListener<OrthogonalViews.ViewerAndTransforms?> { _, old, new ->
        new?.viewer()?.apply { modeActions.forEach { installActionSet(it) } }
        old?.viewer()?.apply { modeActions.forEach { removeActionSet(it) } }
    }

    override fun enter() {
        activeViewerProperty.addListener(toolTriggerListener)
        paintera.baseView.disabledPropertyBindings[controller] = Bindings.createBooleanBinding({ controller.isBusy }, controller.isBusyProperty)
        super.enter()
        /* Try to initialize the tool, if state is valid. If not, change back to previous mode. */
        activeViewerProperty.get()?.viewer()?.let {
            shapeInterpolationToolProperty.get()?.let { shapeInterpolationTool ->
                controller.apply {
                    if (!isModeOn && source.currentMask == null && source.isApplyingMaskProperty.not().get()) {
                        modifyFragmentAlpha()
                        switchTool(shapeInterpolationTool)
                        enterMode(it)
                    }
                }
            } ?: paintera.baseView.changeMode(previousMode)
        } ?: paintera.baseView.changeMode(previousMode)
    }

    override fun exit() {
        super.exit()
        paintera.baseView.disabledPropertyBindings.remove(controller)
        controller.resetFragmentAlpha()
        activeViewerProperty.removeListener(toolTriggerListener)
    }

    private fun ShapeInterpolationController<*>.modifyFragmentAlpha() {
        /* set the converter fragment alpha to be the same as the segment. We do this so we can use the fragment alpha for the
        * selected objects during shape interpolation flood fill. This needs to be un-done in the #deactivate */
        converter.activeFragmentAlphaProperty().apply {
            activeSelectionAlpha = get().toDouble() / 255.0
            set(converter.activeSegmentAlphaProperty().get())
        }
    }

    private fun ShapeInterpolationController<*>.resetFragmentAlpha() {
        /* Add the activeFragmentAlpha back when we are done */
        apply {
            converter.activeFragmentAlphaProperty().set((activeSelectionAlpha * 255).toInt())
        }
    }

    private fun modeActions(): List<ActionSet> {
        return FXCollections.observableArrayList(
            PainteraActionSet(PaintActionType.Paint, "paint during shape interpolation") {
                KEY_PRESSED(KeyCode.SPACE) {
                    name = "switch to paint tool"
                    verify { activeSourceStateProperty.get()?.dataSource is MaskedSource<*, *> }
                    verify { activeTool !is PaintBrushTool }
                    onAction {
                        /* Don't allow painting with depth during shape interpolation */
                        paintBrushTool.brushProperties?.brushDepth = 1.0
                        switchTool(paintBrushTool)
                    }
                }

                MOUSE_PRESSED {
                    name = "provide shape interpolation mask to paint brush"
                    filter = true
                    consume = false
                    verify { activeTool is PaintBrushTool }
                    onAction {
                        /* On click, generate a new mask, */
                        (activeSourceStateProperty.get()?.dataSource as? MaskedSource<*, *>)?.let { source ->
                            paintBrushTool.paintClickOrDrag!!.let { paintController ->
                                source.resetMasks(false)
                                controller.currentViewerMask = controller.sectionAtCurrentDepth?.mask ?: controller.generateMask(paintController)
                                paintController.provideMask(controller.currentViewerMask!!)
                            }
                        }
                    }
                }

                MOUSE_PRESSED(MouseButton.PRIMARY) {
                    name = "set mask value to label"
                    filter = true
                    consume = false
                    verify { activeTool is PaintBrushTool }
                    onAction {
                        paintBrushTool.paintClickOrDrag?.apply {
                            paintBrushTool.paintClickOrDrag!!.fillLabelProperty.apply {
                                resetFillLabel()
                                bindFillLabel()
                            }
                        }
                    }
                }

                MOUSE_PRESSED(MouseButton.SECONDARY) {
                    name = "set mask value to label"
                    filter = true
                    consume = false
                    verify { activeTool is PaintBrushTool }
                    onAction {
                        paintBrushTool.paintClickOrDrag!!.apply {
                            removeFillLabelBinding()
                            setFillLabel(Label.TRANSPARENT)
                        }
                    }
                }

                MOUSE_RELEASED {
                    name = "set mask value to label"
                    filter = true
                    consume = false
                    verify { activeTool is PaintBrushTool }
                    onAction {
                        paintBrushTool.paintClickOrDrag?.let {
                            it.removeFillLabelBinding()
                            controller.paint(it.sourceInterval!!.smallestContainingInterval, it.viewerInterval)
                        }
                    }
                }

                KEY_RELEASED {
                    name = "switch back to shape interpolation tool"
                    filter = true
                    keysReleased(KeyCode.SPACE)
                    verify { activeTool is PaintBrushTool }
                    onAction {
                        paintBrushTool.apply {
                            paintClickOrDrag!!.release()
                        }
                        paintBrushTool.paintClickOrDrag!!.fillLabelProperty.unbind()
                        switchTool(shapeInterpolationToolProperty.get())
                    }
                }
            }
        )
    }

    private fun PaintClickOrDragController.bindFillLabel() {
        fillLabelProperty.bindBidirectional(this@ShapeInterpolationMode.controller.currentFillValuePropery)
    }

    private fun PaintClickOrDragController.removeFillLabelBinding() {
        fillLabelProperty.unbindBidirectional(controller.currentFillValuePropery)
    }
}


class ShapeInterpolationTool(val controller: ShapeInterpolationController<*>, keyCombinations: NamedKeyCombination.CombinationMap, val previousMode: ControlMode) : ViewerTool() {
    override val graphicProperty: SimpleObjectProperty<Node>
        get() = TODO("Not yet implemented")

    private val baseView = paintera.baseView

    override val actionSets: List<ActionSet> = mutableListOf(
        shapeInterpolationActions(keyCombinations),
        *NavigationTool.actionSets.toTypedArray()
    )

    override fun activate() {
        super.activate()
        /* This action set allows us to translate through the unfocused viewers */
        paintera.baseView.orthogonalViews().viewerAndTransforms()
            .filter { !it.viewer().isFocusable }
            .forEach { disabledViewerAndTransform ->
                val translateWhileDisabled = disabledViewerTranslateOnlyMap.computeIfAbsent(disabledViewerAndTransform, disabledViewerTranslateOnly)
                /* remove if already present, to avoid adding twice */
                disabledViewerAndTransform.viewer().removeActionSet(translateWhileDisabled)
                disabledViewerAndTransform.viewer().installActionSet(translateWhileDisabled)
            }
        NavigationTool.activate()
    }

    override fun deactivate() {
        NavigationTool.deactivate()
        disabledViewerTranslateOnlyMap.forEach { (vat, actionSet) -> vat.viewer().removeActionSet(actionSet) }
        disabledViewerTranslateOnlyMap.clear()
        super.deactivate()
    }

    override val statusProperty = SimpleStringProperty().apply {

        val statusBinding = controller.modeStateProperty.createValueBinding(controller.sectionDepthProperty) {
            controller.getStatusText()
        }
        bind(statusBinding)
    }

    private fun ShapeInterpolationController<*>.getStatusText() = if (modeState == Interpolate) {
        "Interpolating..."
    } else {
        with(this) {
            if (numSections() == 0) {
                "Select or Paint ..."
            } else {
                val sectionIdx = sortedSectionDepths.indexOf(sectionDepthProperty.get())
                "Section: ${if (sectionIdx == -1) "N/A" else "${sectionIdx + 1}"} / ${numSections()}"
            }
        }
    }

    val disabledViewerTranslateOnlyMap = mutableMapOf<OrthogonalViews.ViewerAndTransforms, PainteraDragActionSet>()

    private val disabledViewerTranslateOnly = { vat: OrthogonalViews.ViewerAndTransforms ->
        val translator = vat.run {
            val globalTransformManager = paintera.baseView.manager()
            TranslateWithinPlane(globalTransformManager, displayTransform(), globalToViewerTransform())
        }
        PainteraDragActionSet(NavigationActionType.Pan, "translate xy") {
            verify { it.isSecondaryButtonDown }
            verify { controller.modeState != Interpolate }
            handleDragDetected {
                translator.init()
            }
            handleDrag { translator.translate(it.x - startX, it.y - startY) }
        }
    }

    private fun shapeInterpolationActions(keyCombinations: NamedKeyCombination.CombinationMap): ActionSet {
        return PainteraActionSet(PaintActionType.ShapeInterpolation, "shape interpolation") {
            with(controller) {
                verifyAll(KEY_PRESSED) { isModeOn }
                KEY_PRESSED {
                    keyMatchesBinding(keyCombinations, EXIT_SHAPE_INTERPOLATION_MODE)
                    onAction {
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
                    keyMatchesBinding(keyCombinations, SHAPE_INTERPOLATION_TOGGLE_PREVIEW)
                    onAction { controller.togglePreviewMode() }
                    handleException {
                        baseView.changeMode(previousMode)
                    }
                }

                listOf(KeyCode.DELETE, KeyCode.BACK_SPACE).forEach { key ->
                    KEY_PRESSED(key) {
                        name = "remove section"
                        filter = true
                        consume = false
                        verify {
                            sectionDepthProperty.get() in sortedSectionDepths
                        }
                        onAction { deleteCurrentSection() }
                    }
                }

                keyPressEditSelectionAction({ 0 }, SHAPE_INTERPOLATION_EDIT_FIRST_SELECTION, keyCombinations)
                keyPressEditSelectionAction({ controller.numSections() - 1 }, SHAPE_INTERPOLATION_EDIT_LAST_SELECTION, keyCombinations)
                keyPressEditSelectionAction({ controller.previousSectionDepthIdx }, SHAPE_INTERPOLATION_EDIT_PREVIOUS_SELECTION, keyCombinations)
                keyPressEditSelectionAction({ controller.nextSectionDepthIdx }, SHAPE_INTERPOLATION_EDIT_NEXT_SELECTION, keyCombinations)
                MOUSE_CLICKED {
                    name = "select object in current section"

                    verifyNoKeysDown()
                    verify { !paintera.mouseTracker.isDragging }
                    verify { it.button == MouseButton.PRIMARY } // respond to primary click
                    verify { modeState != Interpolate } // need to be in the select state
                    onAction {
                        selectObject(it.x, it.y, true)
                    }
                }
                MOUSE_CLICKED {
                    name = "toggle object in current section"
                    verify { !paintera.mouseTracker.isDragging }
                    verify { modeState != Interpolate } // need to be in the select state
                    verify {
                        val triggerByRightClick = (it.button == MouseButton.SECONDARY) && keyTracker!!.noKeysActive()
                        val triggerByCtrlLeftClick = (it.button == MouseButton.PRIMARY) && keyTracker!!.areOnlyTheseKeysDown(KeyCode.CONTROL)
                        triggerByRightClick || triggerByCtrlLeftClick
                    }
                    onAction {
                        selectObject(it.x, it.y, false)
                    }
                }
            }
        }
    }

    private fun ActionSet.keyPressEditSelectionAction(idxGetter: () -> Int, keyName: String, keyCombinations: NamedKeyCombination.CombinationMap) = with(controller) {
        KEY_PRESSED {
            keyMatchesBinding(keyCombinations, keyName)
            verify {
                val sectionIdx = idxGetter()
                sectionIdx < controller.numSections() && sectionIdx >= 0
            }
            onAction {
                editSelection(idxGetter())
            }
            handleException {

                LOG.error("Error during shape interpolation edit selection ${idxGetter() + 1}. Exiting Shape Interpolation.", it)
                exitMode(false)
                baseView.changeMode(previousMode)
            }
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
    }
}
