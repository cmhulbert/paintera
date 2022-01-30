package org.janelia.saalfeldlab.paintera.state

import net.imglib2.converter.Converter
import net.imglib2.type.logic.BoolType
import net.imglib2.type.numeric.IntegerType
import org.janelia.saalfeldlab.paintera.control.assignment.FragmentSegmentAssignment
import org.janelia.saalfeldlab.paintera.control.selection.SelectedIds
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import java.util.function.BooleanSupplier
import java.util.function.Consumer
import java.util.function.LongFunction

@Deprecated("Prefer using Mode/Tool/Action system", ReplaceWith("PaintMode"))
class LabelSourceStatePaintHandler<T : IntegerType<T>?>(
    private val source: MaskedSource<T, *>,
    private val fragmentSegmentAssignment: FragmentSegmentAssignment,
    private val isVisible: BooleanSupplier,
    private val floodFillStateUpdate: Consumer<FloodFillState>,
    private val selectedIds: SelectedIds,
    private val getMaskForLabel: LongFunction<Converter<T, BoolType>>
) {
//
//    private val handlers = HashMap<ViewerPanelFX, EventHandler<Event>>()
//    private val painters = HashMap<ViewerPanelFX, PaintActions2D>()
//    val brushProperties = BrushProperties()
//    fun viewerHandler(paintera: PainteraBaseView, keyTracker: KeyTracker): EventHandler<Event> {
//        return EventHandler { event ->
//            (event.target as? Node)?.let { node ->
//                LOG.trace("Handling event $event in target $node")
//                // kind of hacky way to accompilsh this:
//                var whileNode: Node? = node
//                while (whileNode != null) {
//                    (whileNode as? ViewerPanelFX)?.let { viewer ->
//                        handlers.computeIfAbsent(viewer) { makeHandler(it) }.handle(event)
//                    }
//                    whileNode = whileNode.parent
//                }
//            }
//        }
//    }
//
//    fun viewerFilter(paintera: PainteraBaseView?, keyTracker: KeyTracker?): EventHandler<Event> {
//        return EventHandler { event: Event ->
//            val target = event.target
//            if (MouseEvent.MOUSE_EXITED == event.eventType && target is ViewerPanelFX) Optional.ofNullable(painters[target]).ifPresent { obj: PaintActions2D -> obj.hideBrushOverlay() }
//        }
//    }
//
//
//    private fun makeHandler(viewer: ViewerPanelFX): EventHandler<Event> {
//        val baseView = paintera.baseView
//        val keyTracker = paintera.keyTracker
//
//        LOG.debug("Making handler with PainterBaseView {} key Tracker {} and ViewerPanelFX {}", baseView, keyTracker, viewer)
//        val paint2D = PaintActions2D(viewer, baseView.manager()).apply {
//            brushRadiusProperty().bindBidirectional(brushProperties.brushRadiusProperty)
//            brushRadiusScaleProperty().bindBidirectional(brushProperties.brushRadiusScaleProperty)
//            brushDepthProperty().bindBidirectional(brushProperties.brushDepthProperty)
//        }
//        painters[viewer] = paint2D
//
//        val paintSelection = {
//            selectedIds.lastSelection.let {
//                LOG.debug("Last selection is {}", it)
//                if (Label.regular(it)) it else null
//            }
//        }
//
//        val fill = FloodFill(viewer, source, fragmentSegmentAssignment, { baseView.orthogonalViews().requestRepaint() }, isVisible, floodFillStateUpdate)
//        val fillOverlay = FillOverlay(viewer)
//
//        val fill2D = FloodFill2D(viewer, source, fragmentSegmentAssignment, { baseView.orthogonalViews().requestRepaint() }, isVisible).apply {
//            fillDepthProperty().bindBidirectional(brushProperties.brushDepthProperty)
//        }
//        val fill2DOverlay = Fill2DOverlay(viewer).apply {
//            brushDepthProperty().bindBidirectional(brushProperties.brushDepthProperty)
//        }
//
//        return DelegateEventHandlers.handleAny().apply {
//            // brush
//            addOnKeyPressed(showBrushOverlay(paint2D))
//            addOnKeyReleased(hideBrushOverlay(paint2D))
//            addOnScroll(changeBrushSize(paint2D))
//            addOnScroll(changeBrushDepth(paint2D))
//            addOnKeyPressed(showFill2DOverlay(fill2DOverlay, fillOverlay))
//            addOnKeyReleased(removeFill2DOverlay(fill2DOverlay))
//            addOnKeyPressed(showFillOverlay(fillOverlay, fill2DOverlay))
//            addOnKeyReleased(removeFillOverlay(fillOverlay))
//
//            // paint
//            addEventHandler(MouseEvent.ANY, paintOnDrag(viewer, paintSelection))
//
//            // erase
//            addEventHandler(MouseEvent.ANY, eraseOnDrag(viewer))
//
//            // background
//            addEventHandler(MouseEvent.ANY, paintBackgroundOnDrag(viewer))
//
//            // advanced paint stuff
//            addOnMousePressed(fillPaintOverlay(fill, paintSelection))
//            addOnMousePressed(fill2DPaintOverlay(fill2D, paintSelection))
//            addOnMousePressed(restrictPaintTo(viewer))
//        }
//    }
//
//    private fun restrictPaintTo(viewer: ViewerPanelFX): EventFX<MouseEvent> {
//        val sourceInfo = paintera.baseView.sourceInfo()
//        val restrictor = RestrictPainting(viewer, sourceInfo, paintera.baseView.orthogonalViews()::requestRepaint, getMaskForLabel as LongFunction<Converter<*, BoolType>>)
//        return EventFX.MOUSE_PRESSED(
//            "restrict",
//            { event: MouseEvent -> restrictor.restrictTo(event.x, event.y) },
//            { verifyAction(PaintActionType.Restrict).withOnlyTheseKeysDown(KeyCode.SHIFT, KeyCode.R) && it.isPrimaryButtonDown }
//        )
//    }
//
//    private fun fill2DPaintOverlay(fill2D: FloodFill2D<T>, paintSelection: () -> Long?) = EventFX.MOUSE_PRESSED(
//        "fill 2D",
//        { event: MouseEvent -> fill2D.fillAt(event.x, event.y, paintSelection) },
//        { verifyAction(PaintActionType.Fill).withOnlyTheseKeysDown(KeyCode.F) && it.isPrimaryButtonDown }
//    )
//
//    private fun fillPaintOverlay(fill: FloodFill<T>, paintSelection: () -> Long?) = EventFX.MOUSE_PRESSED(
//        "fill",
//        { event: MouseEvent -> fill.fillAt(event.x, event.y, paintSelection) },
//        { verifyAction(PaintActionType.Fill).withOnlyTheseKeysDown(KeyCode.SHIFT, KeyCode.F) && it.isPrimaryButtonDown }
//    )
//
//    private fun paintBackgroundOnDrag(viewer: ViewerPanelFX) = PaintClickOrDrag(
//        paintera.baseView,
//        viewer,
//        { Label.BACKGROUND }, { brushProperties.brushRadius }, { brushProperties.brushDepth },
//        { verifyAction(PaintActionType.Background).withOnlyTheseKeysDown(KeyCode.SPACE, KeyCode.SHIFT) && it.isSecondaryButtonDown }
//    ).singleEventHandler()
//
//    private fun eraseOnDrag(viewer: ViewerPanelFX) = PaintClickOrDrag(
//        paintera.baseView,
//        viewer,
//        { Label.TRANSPARENT },
//        { brushProperties.brushRadius },
//        { brushProperties.brushDepth },
//        { verifyAction(PaintActionType.Erase).withOnlyTheseKeysDown(KeyCode.SPACE) && it.isSecondaryButtonDown }
//    ).singleEventHandler()
//
//    private fun paintOnDrag(viewer: ViewerPanelFX, paintSelection: () -> Long?): EventHandler<MouseEvent> {
//        return PaintClickOrDrag(
//            paintera.baseView,
//            viewer,
//            paintSelection,
//            { brushProperties.brushRadius },
//            { brushProperties.brushDepth },
//            { verifyAction(PaintActionType.Paint).withOnlyTheseKeysDown(KeyCode.SPACE) && it.isPrimaryButtonDown }
//        ).singleEventHandler()
//    }
//
//    private fun showBrushOverlay(paint2D: PaintActions2D) = EventFX.KEY_PRESSED(
//        "show brush overlay",
//        { paint2D.showBrushOverlay().also { LOG.trace("Showing Brush Overlay!") } },
//        { verifyAction(PaintActionType.Paint).withKeysDown(KeyCode.SPACE) })
//
//    private fun hideBrushOverlay(paint2D: PaintActions2D) = EventFX.KEY_RELEASED(
//        "hide brush overlay",
//        /* Sometimes the UI is disabled (i.e. UI is blocked due to painting) but the user releases the SPACE key.
//         *  When done being busy, we want to no longer have the brush overlay (unless they press space again).
//         *  To the end, always allow the brush overlay to be hidden, regardless of PAINT being allowed or not. */
//        { paint2D.hideBrushOverlay().also { LOG.trace("Hiding brush overlay!") } },
//        { it.code == KeyCode.SPACE && !paintera.keyTracker.areKeysDown(KeyCode.SPACE) }
//    )
//
//    private fun changeBrushSize(paint2D: PaintActions2D) = EventFX.SCROLL(
//        "change brush size",
//        { paint2D.changeBrushRadius(it.deltaY) },
//        { verifyAction(PaintActionType.SetBrush).withOnlyTheseKeysDown(KeyCode.SPACE) }
//    )
//
//    private fun removeFillOverlay(fillOverlay: FillOverlay) = EventFX.KEY_RELEASED(
//        "show fill overlay",
//        { fillOverlay.setVisible(false) },
//        { event: KeyEvent ->
//            paintera.baseView.isActionAllowed(PaintActionType.Fill) &&
//                (event.code == KeyCode.F && paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.SHIFT) ||
//                    event.code == KeyCode.SHIFT && paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.F))
//        }
//    )
//
//    private fun showFillOverlay(fillOverlay: FillOverlay, fill2DOverlay: Fill2DOverlay) = EventFX.KEY_PRESSED(
//        "show fill overlay",
//        {
//            fillOverlay.setVisible(true)
//            fill2DOverlay.setVisible(false)
//        },
//        { verifyAction(PaintActionType.Fill).withOnlyTheseKeysDown(KeyCode.F, KeyCode.SHIFT) })
//
//    private fun changeBrushDepth(paint2D: PaintActions2D) = EventFX.SCROLL(
//        "change brush depth",
//        { event: ScrollEvent? -> paint2D.changeBrushDepth(-ControlUtils.getBiggestScroll(event)) },
//        {
//            paintera.baseView.isActionAllowed(PaintActionType.SetBrush) &&
//                (paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.SPACE, KeyCode.SHIFT) ||
//                    paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.F) ||
//                    paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.SHIFT, KeyCode.F))
//        }
//    )
//
//    private fun removeFill2DOverlay(fill2DOverlay: Fill2DOverlay) = EventFX.KEY_RELEASED(
//        "show fill 2D overlay",
//        { fill2DOverlay.setVisible(false) },
//        { verifyAction(PaintActionType.Fill).andNoKeysActive() && it.code == KeyCode.F }
//    )
//
//    private fun showFill2DOverlay(fill2DOverlay: Fill2DOverlay, fillOverlay: FillOverlay) = EventFX.KEY_PRESSED(
//        "show fill 2D overlay",
//        {
//            fill2DOverlay.setVisible(true)
//            fillOverlay.setVisible(false)
//        },
//        { paintera.baseView.isActionAllowed(PaintActionType.Fill) && paintera.keyTracker.areOnlyTheseKeysDown(KeyCode.F) })
//
//    companion object {
//        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())
//    }
}
