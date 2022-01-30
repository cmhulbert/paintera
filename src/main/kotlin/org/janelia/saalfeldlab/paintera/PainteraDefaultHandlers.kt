package org.janelia.saalfeldlab.paintera

import bdv.fx.viewer.ViewerPanelFX
import bdv.fx.viewer.multibox.MultiBoxOverlayConfig
import bdv.fx.viewer.multibox.MultiBoxOverlayRendererFX
import bdv.fx.viewer.scalebar.ScaleBarOverlayRenderer
import bdv.viewer.Interpolation
import bdv.viewer.Source
import javafx.beans.InvalidationListener
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ChangeListener
import javafx.beans.value.ObservableObjectValue
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.event.Event
import javafx.event.EventHandler
import javafx.scene.Node
import javafx.scene.control.ContextMenu
import javafx.scene.input.*
import javafx.scene.transform.Affine
import net.imglib2.FinalRealInterval
import net.imglib2.Interval
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.util.Intervals
import org.janelia.saalfeldlab.fx.actions.ActionSet
import org.janelia.saalfeldlab.fx.actions.installActionSet
import org.janelia.saalfeldlab.fx.actions.removeActionSet
import org.janelia.saalfeldlab.fx.event.EventFX
import org.janelia.saalfeldlab.fx.ortho.GridConstraintsManager
import org.janelia.saalfeldlab.fx.ortho.GridConstraintsManager.MaximizedColumn
import org.janelia.saalfeldlab.fx.ortho.GridConstraintsManager.MaximizedRow
import org.janelia.saalfeldlab.fx.ortho.GridResizer
import org.janelia.saalfeldlab.fx.ortho.OnEnterOnExit
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews
import org.janelia.saalfeldlab.fx.ortho.OrthogonalViews.ViewerAndTransforms
import org.janelia.saalfeldlab.fx.ui.Exceptions
import org.janelia.saalfeldlab.paintera.config.BookmarkConfig
import org.janelia.saalfeldlab.paintera.config.BookmarkSelectionDialog
import org.janelia.saalfeldlab.paintera.control.FitToInterval
import org.janelia.saalfeldlab.paintera.control.OrthoViewCoordinateDisplayListener
import org.janelia.saalfeldlab.paintera.control.OrthogonalViewsValueDisplayListener
import org.janelia.saalfeldlab.paintera.control.RunWhenFirstElementIsAdded
import org.janelia.saalfeldlab.paintera.control.actions.MenuActionType
import org.janelia.saalfeldlab.paintera.control.actions.NavigationActionType
import org.janelia.saalfeldlab.paintera.control.modes.ControlMode
import org.janelia.saalfeldlab.paintera.control.navigation.DisplayTransformUpdateOnResize
import org.janelia.saalfeldlab.paintera.data.mask.MaskedSource
import org.janelia.saalfeldlab.paintera.ui.ToggleMaximize
import org.janelia.saalfeldlab.paintera.ui.dialogs.opendialog.menu.OpenDialogMenu
import org.slf4j.LoggerFactory
import java.lang.invoke.MethodHandles
import java.util.Arrays
import java.util.function.Consumer
import java.util.function.Supplier
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

class PainteraDefaultHandlers(private val paintera: PainteraMainWindow, paneWithStatus: BorderPaneWithStatusBars) {

    private val baseView = paintera.baseView

    private val keyTracker = paintera.keyTracker

    private val mouseTracker = paintera.mouseTracker

    private val projectDirectory = Supplier { paintera.projectDirectory.actualDirectory.absolutePath }

    private val properties = paintera.properties

    private val orthogonalViews = baseView.orthogonalViews()

    private val viewersTopLeftTopRightBottomLeft = arrayOf(
        orthogonalViews.topLeft,
        orthogonalViews.topRight,
        orthogonalViews.bottomLeft
    )

    internal val globalActionHandlers = FXCollections.observableArrayList<ActionSet>().apply {
        addListener(ListChangeListener { change ->
            while (change.next()) {
                change.removed.forEach { paneWithStatus.pane.removeActionSet(it) }
                change.addedSubList.forEach { paneWithStatus.pane.installActionSet(it) }
            }
        })
    }

    private val mouseInsidePropertiesTopLeftTropRightBottomLeft = viewersTopLeftTopRightBottomLeft
        .map { it.viewer().isMouseInsideProperty }
        .toTypedArray()

    private val sourceInfo = baseView.sourceInfo()

    private val toggleMaximizeTopLeft: ToggleMaximize
    private val toggleMaximizeTopRight: ToggleMaximize
    private val toggleMaximizeBottomLeft: ToggleMaximize

    private val multiBoxes: Array<MultiBoxOverlayRendererFX>
    private val multiBoxVisibilities = mouseInsidePropertiesTopLeftTropRightBottomLeft
        .map { mouseInside ->
            Bindings.createBooleanBinding(
                {
                    when (properties.multiBoxOverlayConfig.visibility) {
                        MultiBoxOverlayConfig.Visibility.ON -> true
                        MultiBoxOverlayConfig.Visibility.OFF -> false
                        MultiBoxOverlayConfig.Visibility.ONLY_IN_FOCUSED_VIEWER -> mouseInside.value
                    }
                },
                mouseInside,
                properties.multiBoxOverlayConfig.visibilityProperty()
            )
        }
        .toTypedArray()
        .also {
            it.forEachIndexed { index, isVisible -> isVisible.addListener { _, _, _ -> viewersTopLeftTopRightBottomLeft[index].viewer().display.drawOverlays() } }
        }

    private val resizer: GridResizer

    private val globalInterpolationProperty = SimpleObjectProperty<Interpolation>()

    private val scaleBarOverlays = listOf(
        ScaleBarOverlayRenderer(properties.scaleBarOverlayConfig),
        ScaleBarOverlayRenderer(properties.scaleBarOverlayConfig),
        ScaleBarOverlayRenderer(properties.scaleBarOverlayConfig)
    )

    private val viewerToTransforms = HashMap<ViewerPanelFX, ViewerAndTransforms>()

    init {
        sourceInfo.currentState().addListener { _, _, newState -> paintera.baseView.changeMode(newState.defaultMode) }
        sourceInfo.currentSourceProperty().addListener { _, oldSource, newSource ->
            (oldSource as? MaskedSource<*, *>)?.apply {
                paintera.baseView.isDisabledProperty.unbind()
            }
            (newSource as? MaskedSource<*, *>)?.apply {
                paintera.baseView.isDisabledProperty.bind(isBusyProperty)
            }
        }

        grabFocusOnMouseOver(
            baseView.orthogonalViews().topLeft.viewer(),
            baseView.orthogonalViews().topRight.viewer(),
            baseView.orthogonalViews().bottomLeft.viewer()
        )

        globalActionHandlers + addOpenDatasetContextMenuAction(paneWithStatus.pane, KeyCode.CONTROL, KeyCode.O)

        this.toggleMaximizeTopLeft = toggleMaximizeNode(orthogonalViews, properties.gridConstraints, 0, 0)
        this.toggleMaximizeTopRight = toggleMaximizeNode(orthogonalViews, properties.gridConstraints, 1, 0)
        this.toggleMaximizeBottomLeft = toggleMaximizeNode(orthogonalViews, properties.gridConstraints, 0, 1)

        viewerToTransforms[orthogonalViews.topLeft.viewer()] = orthogonalViews.topLeft
        viewerToTransforms[orthogonalViews.topRight.viewer()] = orthogonalViews.topRight
        viewerToTransforms[orthogonalViews.bottomLeft.viewer()] = orthogonalViews.bottomLeft

        multiBoxes = arrayOf(
            MultiBoxOverlayRendererFX(
                { baseView.orthogonalViews().topLeft.viewer().state },
                sourceInfo.trackSources(),
                sourceInfo.trackVisibleSources()
            ),
            MultiBoxOverlayRendererFX(
                { baseView.orthogonalViews().topRight.viewer().state },
                sourceInfo.trackSources(),
                sourceInfo.trackVisibleSources()
            ),
            MultiBoxOverlayRendererFX(
                { baseView.orthogonalViews().bottomLeft.viewer().state },
                sourceInfo.trackSources(),
                sourceInfo.trackVisibleSources()
            )
        ).also { m -> m.forEachIndexed { idx, mb -> mb.isVisibleProperty.bind(multiBoxVisibilities[idx]) } }

        orthogonalViews.topLeft.viewer().display.addOverlayRenderer(multiBoxes[0])
        orthogonalViews.topRight.viewer().display.addOverlayRenderer(multiBoxes[1])
        orthogonalViews.bottomLeft.viewer().display.addOverlayRenderer(multiBoxes[2])

        updateDisplayTransformOnResize(baseView.orthogonalViews(), baseView.manager())

        val borderPane = paneWithStatus.pane

        baseView.allowedActionsProperty().addListener { _, _, new ->
            val disableSidePanel = new.isAllowed(MenuActionType.SidePanel).not()
            paneWithStatus.scrollPane.disableProperty().set(disableSidePanel)
        }

        sourceInfo.trackSources().addListener(createSourcesInterpolationListener())

        val keyCombinations = ControlMode.keyAndMouseBindings.keyCombinations
        EventFX.KEY_PRESSED(
            PainteraBaseKeys.CYCLE_INTERPOLATION_MODES,
            { toggleInterpolation() },
            { keyCombinations.matches(PainteraBaseKeys.CYCLE_INTERPOLATION_MODES, it) }
        ).installInto(borderPane)

        this.resizer = GridResizer(properties.gridConstraints, 5.0, baseView.pane, keyTracker).apply { installInto(baseView.pane) }

        val currentSource = sourceInfo.currentSourceProperty()

        val vdl = paneWithStatus.run {
            OrthogonalViewsValueDisplayListener(
                ::setCurrentStatus,
                currentSource,
                { sourceInfo.getState(it).interpolationProperty().get() }).apply {
                bindActiveViewer(currentFocusHolder())
            }
        }

        val cdl = paneWithStatus.run {
            OrthoViewCoordinateDisplayListener(::setViewerCoordinateStatus, ::setWorldCoorinateStatus).apply {
                bindActiveViewer(currentFocusHolder())
            }
        }

        sourceInfo.trackSources().addListener(
            FitToInterval.fitToIntervalWhenSourceAddedListener(baseView.manager()) { baseView.orthogonalViews().topLeft.viewer().widthProperty().get() }
        )
        sourceInfo.trackSources().addListener(RunWhenFirstElementIsAdded {
            baseView.viewer3D().setInitialTransformToInterval(sourceIntervalInWorldSpace(it.addedSubList[0]))
        })

        mapOf(
            toggleMaximizeTopLeft to orthogonalViews.topLeft,
            toggleMaximizeTopRight to orthogonalViews.topRight,
            toggleMaximizeBottomLeft to orthogonalViews.bottomLeft
        ).forEach { (toggle, view) ->
            /* Toggle Maxmizing one pane*/
            EventFX.KEY_PRESSED(
                PainteraBaseKeys.MAXIMIZE_VIEWER,
                { toggle.toggleMaximizeViewer() },
                { baseView.isActionAllowed(MenuActionType.ToggleMaximizeViewer) && keyCombinations.matches(PainteraBaseKeys.MAXIMIZE_VIEWER, it) }
            ).installInto(view.viewer())


            /* Toggle Maxmizing the Viewer and an OrthoSlice*/
            EventFX.KEY_PRESSED(
                PainteraBaseKeys.MAXIMIZE_VIEWER_AND_3D,
                { toggle.toggleMaximizeViewerAndOrthoslice() },
                { baseView.isActionAllowed(MenuActionType.ToggleMaximizeViewer) && keyCombinations.matches(PainteraBaseKeys.MAXIMIZE_VIEWER_AND_3D, it) }
            ).installInto(view.viewer())
        }

        val contextMenuFactory = MeshesGroupContextMenu(baseView.manager())
        val contextMenuProperty = SimpleObjectProperty<ContextMenu>()
        val hideContextMenu = {
            if (contextMenuProperty.get() != null) {
                contextMenuProperty.get().hide()
                contextMenuProperty.set(null)
            }
        }
        baseView.viewer3D().meshesGroup().addEventHandler(
            MouseEvent.MOUSE_CLICKED
        ) {
            LOG.debug("Handling event {}", it)
            if (baseView.isActionAllowed(MenuActionType.OrthoslicesContextMenu) &&
                MouseButton.SECONDARY == it.button &&
                it.clickCount == 1 &&
                !mouseTracker.isDragging
            ) {
                LOG.debug("Check passed for event {}", it)
                it.consume()
                val pickResult = it.pickResult
                if (pickResult.intersectedNode != null) {
                    val pt = pickResult.intersectedPoint
                    val menu = contextMenuFactory.createMenu(doubleArrayOf(pt.x, pt.y, pt.z))
                    menu.show(baseView.viewer3D(), it.screenX, it.screenY)
                    contextMenuProperty.set(menu)
                } else {
                    hideContextMenu()
                }
            } else {
                hideContextMenu()
            }
        }
        // hide the context menu when clicked outside the meshes
        baseView.viewer3D().addEventHandler(
            MouseEvent.MOUSE_CLICKED
        ) { hideContextMenu() }

        this.baseView.orthogonalViews().topLeft.viewer().addTransformListener(scaleBarOverlays[0])
        this.baseView.orthogonalViews().topLeft.viewer().display.addOverlayRenderer(scaleBarOverlays[0])
        this.baseView.orthogonalViews().topRight.viewer().addTransformListener(scaleBarOverlays[1])
        this.baseView.orthogonalViews().topRight.viewer().display.addOverlayRenderer(scaleBarOverlays[1])
        this.baseView.orthogonalViews().bottomLeft.viewer().addTransformListener(scaleBarOverlays[2])
        this.baseView.orthogonalViews().bottomLeft.viewer().display.addOverlayRenderer(scaleBarOverlays[2])
        properties.scaleBarOverlayConfig.change.addListener { this.baseView.orthogonalViews().applyToAll { vp -> vp.display.drawOverlays() } }

        val addBookmarkKeyCode = KeyCodeCombination(KeyCode.B)
        val addBookmarkWithCommentKeyCode = KeyCodeCombination(KeyCode.B, KeyCombination.SHIFT_DOWN)
        val applyBookmarkKeyCode = KeyCodeCombination(KeyCode.B, KeyCombination.CONTROL_DOWN)
        //TODO Caleb: Add as global action?
        paneWithStatus.pane.addEventHandler(KeyEvent.KEY_PRESSED) {
            if (baseView.isActionAllowed(NavigationActionType.Bookmark)) {
                when {
                    addBookmarkKeyCode.match(it) -> {
                        it.consume()
                        val globalTransform = AffineTransform3D()
                        baseView.manager().getTransform(globalTransform)
                        val viewer3DTransform = Affine()
                        baseView.viewer3D().getAffine(viewer3DTransform)
                        properties.bookmarkConfig.addBookmark(BookmarkConfig.Bookmark(globalTransform, viewer3DTransform, null))
                    }
                    addBookmarkWithCommentKeyCode.match(it) -> {
                        it.consume()
                        val globalTransform = AffineTransform3D()
                        baseView.manager().getTransform(globalTransform)
                        val viewer3DTransform = Affine()
                        baseView.viewer3D().getAffine(viewer3DTransform)
                        paneWithStatus.bookmarkConfigNode().requestAddNewBookmark(globalTransform, viewer3DTransform)
                    }
                    applyBookmarkKeyCode.match(it) -> {
                        it.consume()
                        BookmarkSelectionDialog(properties.bookmarkConfig.unmodifiableBookmarks)
                            .showAndWaitForBookmark()
                            .ifPresent { bm ->
                                baseView.manager().setTransform(bm.globalTransformCopy, properties.bookmarkConfig.getTransitionTime())
                                baseView.viewer3D().setAffine(bm.viewer3DTransformCopy, properties.bookmarkConfig.getTransitionTime())
                            }
                    }
                }
            }
        }

    }

    private fun toggleInterpolation() {
        if (globalInterpolationProperty.get() != null) {
            globalInterpolationProperty.set(if (globalInterpolationProperty.get() == Interpolation.NLINEAR) Interpolation.NEARESTNEIGHBOR else Interpolation.NLINEAR)
            baseView.orthogonalViews().requestRepaint()
        }
    }

    private fun createSourcesInterpolationListener(): InvalidationListener {
        return InvalidationListener {
            if (globalInterpolationProperty.get() == null && !sourceInfo.trackSources().isEmpty()) {
                // initially set the global interpolation state based on source interpolation
                val source = sourceInfo.trackSources().iterator().next()
                val sourceState = sourceInfo.getState(source)
                globalInterpolationProperty.set(sourceState.interpolationProperty().get())
            }

            // bind all source interpolation states to the global state
            for (source in sourceInfo.trackSources()) {
                val sourceState = sourceInfo.getState(source)
                sourceState.interpolationProperty().bind(globalInterpolationProperty)
            }
        }
    }

    //TODO Caleb: Make some `GlobalAction` Service?
    fun addOpenDatasetContextMenuAction(target: Node, vararg keyTrigger: KeyCode): ActionSet {

        assert(keyTrigger.isNotEmpty())

        val handleExcpetion: (Exception) -> Unit = { exception -> Exceptions.exceptionAlert(Constants.NAME, "Unable to show open dataset menu", exception, owner = baseView.viewer3D().scene?.window) }
        val actionSet = OpenDialogMenu.keyPressedAction(paintera.gateway, target, handleExcpetion, baseView, projectDirectory, mouseTracker, *keyTrigger)
        target.installActionSet(actionSet)
        return actionSet
    }

    companion object {

        private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

        private val DEFAULT_HANDLER = EventHandler<Event> { LOG.trace("Default event handler: Use if no source is present") }

        fun updateDisplayTransformOnResize(
            views: OrthogonalViews<*>,
            lock: Any,
        ): Array<DisplayTransformUpdateOnResize> {
            return arrayOf(
                updateDisplayTransformOnResize(views.topLeft, lock),
                updateDisplayTransformOnResize(views.topRight, lock),
                updateDisplayTransformOnResize(views.bottomLeft, lock)
            )
        }

        fun updateDisplayTransformOnResize(vat: ViewerAndTransforms, lock: Any): DisplayTransformUpdateOnResize {
            val viewer = vat.viewer()
            val displayTransform = vat.displayTransform()
            val updater = DisplayTransformUpdateOnResize(
                displayTransform,
                viewer.widthProperty(),
                viewer.heightProperty(),
                lock
            )
            updater.listen()
            return updater
        }

        fun OrthogonalViews<*>.currentFocusHolder(): ObservableObjectValue<ViewerAndTransforms?> {
            return Bindings.createObjectBinding(
                { viewerAndTransforms().firstOrNull { it.viewer().focusedProperty().get() } },
                *views().map { it.focusedProperty() }.toTypedArray()
            )

        }

        fun createOnEnterOnExit(currentFocusHolder: ObservableObjectValue<ViewerAndTransforms?>): Consumer<OnEnterOnExit> {
            val onEnterOnExits = ArrayList<OnEnterOnExit>()

            val onEnterOnExit = ChangeListener<ViewerAndTransforms?> { _, oldv, newv ->
                oldv?.apply {
                    onEnterOnExits.stream().map { it.onExit() }.forEach { it.accept(viewer()) }
                }
                newv?.apply {
                    onEnterOnExits.stream().map { it.onEnter() }.forEach { it.accept(viewer()) }
                }
            }

            currentFocusHolder.addListener(onEnterOnExit)

            return Consumer { onEnterOnExits.add(it) }
        }

        fun grabFocusOnMouseOver(vararg nodes: Node) {
            grabFocusOnMouseOver(listOf(*nodes))
        }

        fun grabFocusOnMouseOver(nodes: Collection<Node>) {
            nodes.forEach({ grabFocusOnMouseOver(it) })
        }

        fun grabFocusOnMouseOver(node: Node) {
            node.addEventFilter(MouseEvent.MOUSE_ENTERED) {
                if (!node.focusedProperty().get()) {
                    node.requestFocus()
                }
            }
        }

        fun sourceIntervalInWorldSpace(source: Source<*>): Interval {
            val min = Arrays.stream(
                Intervals.minAsLongArray(
                    source.getSource(
                        0,
                        0
                    )
                )
            ).asDoubleStream().toArray()
            val max = Arrays.stream(
                Intervals.maxAsLongArray(
                    source.getSource(
                        0,
                        0
                    )
                )
            ).asDoubleStream().toArray()
            val tf = AffineTransform3D()
            source.getSourceTransform(0, 0, tf)
            tf.apply(min, min)
            tf.apply(max, max)
            return Intervals.smallestContainingInterval(FinalRealInterval(min, max))
        }

        fun setFocusTraversable(
            view: OrthogonalViews<*>,
            isTraversable: Boolean,
        ) {
            view.topLeft.viewer().isFocusTraversable = isTraversable
            view.topRight.viewer().isFocusTraversable = isTraversable
            view.bottomLeft.viewer().isFocusTraversable = isTraversable
            view.grid().bottomRight.isFocusTraversable = isTraversable
        }

        fun toggleMaximizeNode(
            orthogonalViews: OrthogonalViews<out Node>,
            manager: GridConstraintsManager,
            column: Int,
            row: Int,
        ): ToggleMaximize {
            return ToggleMaximize(
                orthogonalViews,
                manager,
                MaximizedColumn.fromIndex(column),
                MaximizedRow.fromIndex(row)
            )
        }
    }
}
