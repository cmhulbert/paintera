package org.janelia.saalfeldlab.paintera.control

//private val allowedActionsProperty = paintera.baseView.allowedActionsProperty()
//
//object Navigation {
//
//    private val manager = paintera.baseView.manager()
//
//    internal val activeViewerProperty = SimpleObjectProperty<OrthogonalViews.ViewerAndTransforms?>().apply {
//        addListener { _, old, new ->
//            if (old != new) {
//                old?.removeNavigationHandlers()
//                new?.addNavigationHandlers()
//            }
//        }
//    }
//
//    private val globalTransform = AffineTransform3D().apply { manager.addListener { set(it) } }
//
//    private val zoomSpeed = SimpleDoubleProperty(1.05)
//
//    private val translationSpeed = SimpleDoubleProperty(1.0)
//
//    private val rotationSpeed = SimpleDoubleProperty(1.0)
//
//    val allowRotationsProperty = SimpleBooleanProperty(true)
//
//    private val buttonRotationSpeedConfig = ButtonRotationSpeedConfig()
//
//    private val mouseAndKeyHandlers = HashMap<ViewerPanelFX, Collection<InstallAndRemove<Node>>>()
//
//    private fun KeyEvent.allowedAndMatches(actionType: ActionType, keyComboName: String): Boolean {
//        return paintera.baseView.isActionAllowed(actionType) && keyBindings.matches(keyComboName, this)
//    }
//
//    private fun OrthogonalViews.ViewerAndTransforms.addNavigationHandlers() {
//        mouseAndKeyHandlers.getOrPut(viewer()) { createNavigationEventHandlers() }.forEach {
//            it.installInto(viewer())
//        }
//    }
//
//    private fun OrthogonalViews.ViewerAndTransforms.createNavigationEventHandlers(): ArrayList<InstallAndRemove<Node>> {
//        val viewerTransform = AffineTransform3D().apply {
//            viewer().addTransformListener { set(it) }
//        }
//
//        val mouseXProperty = viewer().mouseXProperty()
//        val mouseYProperty = viewer().mouseYProperty()
//        val isInsideProp = viewer().isMouseInsideProperty
//
//        val mouseX by mouseXProperty.nonnullVal()
//        val mouseY by mouseYProperty.nonnullVal()
//        val isInside by isInsideProp.nonnullVal()
//
//        val mouseXIfInsideElseCenterX = Bindings.createDoubleBinding(
//            { if (isInside) mouseX else viewer().width / 2 },
//            isInsideProp,
//            mouseXProperty
//        )
//
//        val mouseYIfInsideElseCenterY = Bindings.createDoubleBinding(
//            { if (isInside) mouseY else viewer().height / 2 },
//            isInsideProp,
//            mouseYProperty
//        )
//
//
//        val worldToSharedViewerSpace = AffineTransform3D()
//        val displayTransform = AffineTransform3D()
//        val globalToViewerTransform = AffineTransform3D()
//
//
//
//        displayTransform().addListener {
//            displayTransform.set(it)
//            globalToViewerTransform().getTransformCopy(worldToSharedViewerSpace)
//            worldToSharedViewerSpace.preConcatenate(it)
//        }
//
//        globalToViewerTransform().addListener {
//            globalToViewerTransform.set(it)
//            displayTransform().getTransformCopy(worldToSharedViewerSpace)
//            worldToSharedViewerSpace.concatenate(it)
//        }
//
//        val translateXYController = TranslateWithinPlane(manager, displayTransform(), globalToViewerTransform())
//        val normalTranslationController = TranslateAlongNormal(translationSpeed, manager, worldToSharedViewerSpace)
//        val zoomController = Zoom(zoomSpeed, manager, viewerTransform)
//        val keyRotationAxis = SimpleObjectProperty(KeyRotate.Axis.Z)
//        val resetRotationController = RemoveRotation(viewerTransform, globalTransform, { manager.setTransform(it) }, manager)
//
//        return arrayListOf(
//            EventFX.SCROLL(
//                "translate along normal",
//                { normalTranslationController.translate(-ControlUtils.getBiggestScroll(it)) },
//                { verifyAction(NAT.Scroll).andNoKeysActive() }),
//            EventFX.SCROLL(
//                "translate along normal fast",
//                { normalTranslationController.translate(-ControlUtils.getBiggestScroll(it), FAST) },
//                { verifyAction(NAT.Scroll).withOnlyTheseKeysDown(KeyCode.SHIFT) }),
//            EventFX.SCROLL(
//                "translate along normal slow",
//                { normalTranslationController.translate(-ControlUtils.getBiggestScroll(it), SLOW) },
//                { verifyAction(NAT.Scroll).withOnlyTheseKeysDown(KeyCode.CONTROL) }),
//
//            *getTranslateAlongNormalKeyHandlers(normalTranslationController),
//            getTranslateInPlaneDragHandler(translateXYController),
//            getZoomScrollHandler(zoomController),
//            *getZoomKeyHandlers(zoomController, mouseXIfInsideElseCenterX, mouseYIfInsideElseCenterY),
//            getRotationMouseHandler(displayTransform, globalToViewerTransform),
//            getFastRotationMouseHandler(displayTransform, globalToViewerTransform),
//            getSlowRotationMouseHandler(displayTransform, globalToViewerTransform),
//            *getSetRotationAxisHandlers(keyRotationAxis),
//            *getRotationKeyHandlers(mouseXIfInsideElseCenterX, mouseYIfInsideElseCenterY, keyRotationAxis, displayTransform, globalToViewerTransform),
//            getRemoveRotationHandler(resetRotationController, mouseXIfInsideElseCenterX, mouseYIfInsideElseCenterY)
//        )
//    }
//
//    private fun getRemoveRotationHandler(removeRotationController: RemoveRotation, mouseXIfInsideElseCenterX: DoubleBinding, mouseYIfInsideElseCenterY: DoubleBinding) = EventFX.KEY_PRESSED(
//        NK.REMOVE_ROTATION,
//        { removeRotationController.removeRotationCenteredAt(mouseXIfInsideElseCenterX.get(), mouseYIfInsideElseCenterY.get()) },
//        { allowedActionsProperty.get().isAllowed(NAT.Rotate) && keyBindings.matches(NK.REMOVE_ROTATION, it) })
//
//    private fun getTranslateAlongNormalKeyHandlers(translateAlongNormal: TranslateAlongNormal): Array<EventFX<KeyEvent>> {
//        return arrayListOf(
//            1.0 to arrayListOf(
//                NK.BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD to DEFAULT,
//                NK.BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD_FAST to FAST,
//                NK.BUTTON_TRANSLATE_ALONG_NORMAL_BACKWARD_SLOW to SLOW,
//            ),
//            -1.0 to arrayListOf(
//                NK.BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD to DEFAULT,
//                NK.BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD_FAST to FAST,
//                NK.BUTTON_TRANSLATE_ALONG_NORMAL_FORWARD_SLOW to SLOW
//            )
//        ).map { (step, keySpeedMap) ->
//            keySpeedMap.map { (key, speed) ->
//                EventFX.KEY_PRESSED(
//                    key,
//                    { translateAlongNormal.translate(step, speed) },
//                    { it.allowedAndMatches(NAT.Scroll, key) },
//                    true
//                )
//            }
//        }.flatMap {
//            it.asSequence()
//        }.toTypedArray()
//    }
//
//    private fun getTranslateInPlaneDragHandler(translateXYController: TranslateWithinPlane): MouseDragFX {
//        return MouseDragFX.createDrag(
//            "translate xy",
//            { verifyAction(NAT.Drag).andNoKeysActive() && it.isSecondaryButtonDown },
//            true,
//            { translateXYController.init() },
//            { dX, dY -> translateXYController.translate(dX, dY) }
//        )
//    }
//
//    private fun getZoomScrollHandler(zoomController: Zoom) = EventFX.SCROLL(
//        "zoom",
//        { zoomController.zoomCenteredAt(-ControlUtils.getBiggestScroll(it), it.x, it.y) },
//        { verifyAction(NAT.Zoom).withOnlyTheseKeysDown(KeyCode.META) || verifyAction(NAT.Zoom).withOnlyTheseKeysDown(KeyCode.CONTROL, KeyCode.SHIFT) }
//    )
//
//    private fun getZoomKeyHandlers(
//        zoomController: Zoom,
//        mouseXIfInsideElseCenterX: DoubleBinding,
//        mouseYIfInsideElseCenterY: DoubleBinding
//    ): Array<EventFX<KeyEvent>> {
//        return arrayOf(
//            1.0 to listOf(NK.BUTTON_ZOOM_OUT, NK.BUTTON_ZOOM_OUT2),
//            -1.0 to listOf(NK.BUTTON_ZOOM_IN, NK.BUTTON_ZOOM_IN2)
//        ).map { (delta, keys) ->
//            keys.map { kb ->
//                EventFX.KEY_PRESSED(
//                    kb,
//                    { zoomController.zoomCenteredAt(delta, mouseXIfInsideElseCenterX.get(), mouseYIfInsideElseCenterY.get()) },
//                    { allowedActionsProperty.get().isAllowed(NAT.Zoom) && keyBindings.matches(kb, it) }
//                )
//            }
//        }.flatMap {
//            it.asSequence()
//        }.toTypedArray()
//    }
//
//    private fun getRotationMouseHandler(displayTransform: AffineTransform3D, globalToViewerTransform: AffineTransform3D): MouseDragFX {
//        return rotationHandler(
//            "rotate",
//            allowRotationsProperty,
//            rotationSpeed.multiply(DEFAULT),
//            globalTransform,
//            displayTransform,
//            globalToViewerTransform,
//            { manager.setTransform(it) },
//            manager,
//            { verifyAction(NAT.Rotate).andNoKeysActive() && it.button == MouseButton.PRIMARY }
//        )
//    }
//
//    private fun getFastRotationMouseHandler(displayTransform: AffineTransform3D, globalToViewerTransform: AffineTransform3D): MouseDragFX {
//        return rotationHandler(
//            "rotate fast",
//            allowRotationsProperty,
//            rotationSpeed.multiply(FAST),
//            globalTransform,
//            displayTransform,
//            globalToViewerTransform,
//            { manager.setTransform(it) },
//            manager,
//            { verifyAction(NAT.Rotate).withOnlyTheseKeysDown(KeyCode.SHIFT) && it.button == MouseButton.PRIMARY }
//        )
//    }
//
//    private fun getSlowRotationMouseHandler(displayTransform: AffineTransform3D, globalToViewerTransform: AffineTransform3D): MouseDragFX {
//        return rotationHandler(
//            "rotate slow",
//            allowRotationsProperty,
//            rotationSpeed.multiply(SLOW),
//            globalTransform,
//            displayTransform,
//            globalToViewerTransform,
//            { manager.setTransform(it) },
//            manager,
//            { verifyAction(NAT.Rotate).withOnlyTheseKeysDown(KeyCode.CONTROL) && it.button == MouseButton.PRIMARY }
//        )
//    }
//
//    private fun getRotationKeyHandlers(
//        mouseXIfInsideElseCenterX: DoubleBinding,
//        mouseYIfInsideElseCenterY: DoubleBinding,
//        keyRotationAxis: SimpleObjectProperty<KeyRotate.Axis>,
//        displayTransform: AffineTransform3D,
//        globalToViewerTransform: AffineTransform3D
//    ): Array<EventFX<KeyEvent>> {
//        return arrayOf(
//            buttonRotationSpeedConfig.regular to mapOf(
//                -1 to NK.KEY_ROTATE_LEFT,
//                1 to NK.KEY_ROTATE_RIGHT,
//            ),
//            buttonRotationSpeedConfig.fast to mapOf(
//                -1 to NK.KEY_ROTATE_LEFT_FAST,
//                1 to NK.KEY_ROTATE_RIGHT_FAST,
//            ),
//            buttonRotationSpeedConfig.slow to mapOf(
//                -1 to NK.KEY_ROTATE_LEFT_SLOW,
//                1 to NK.KEY_ROTATE_RIGHT_SLOW,
//            ),
//        ).map { (speed, dirKeyMap) ->
//            dirKeyMap.map { (direction, key) ->
//                keyRotationHandler(
//                    key,
//                    mouseXIfInsideElseCenterX,
//                    mouseYIfInsideElseCenterY,
//                    allowRotationsProperty,
//                    keyRotationAxis,
//                    speed.multiply(direction * Math.PI / 180.0),
//                    displayTransform,
//                    globalToViewerTransform,
//                    globalTransform,
//                    { manager.setTransform(it) },
//                    manager,
//                    { allowedActionsProperty.get().isAllowed(NAT.Rotate) && keyBindings.matches(key, it) })
//            }
//        }.flatMap {
//            it.asSequence()
//        }.toTypedArray()
//    }
//
//
//    private fun getSetRotationAxisHandlers(keyRotationAxis: SimpleObjectProperty<KeyRotate.Axis>): Array<EventFX<KeyEvent>> {
//        return arrayOf(
//            KeyRotate.Axis.X to NK.SET_ROTATION_AXIS_X,
//            KeyRotate.Axis.Y to NK.SET_ROTATION_AXIS_Y,
//            KeyRotate.Axis.Z to NK.SET_ROTATION_AXIS_Z
//        ).map { (axis, key) ->
//            EventFX.KEY_PRESSED(
//                key,
//                { keyRotationAxis.set(axis) },
//                { allowedActionsProperty.get().isAllowed(NAT.Rotate) && keyBindings.matches(key, it) })
//        }.toTypedArray()
//    }
//
//    private fun OrthogonalViews.ViewerAndTransforms.removeNavigationHandlers() {
//        mouseAndKeyHandlers[viewer()]?.onEach { it.removeFrom(viewer()) }
//    }
//
//    fun bindTo(config: ButtonRotationSpeedConfig) {
//        this.buttonRotationSpeedConfig.regular.bind(config.regular)
//        this.buttonRotationSpeedConfig.slow.bind(config.slow)
//        this.buttonRotationSpeedConfig.fast.bind(config.fast)
//    }
//
//
//    internal val keyAndMouseBindings = KeyAndMouseBindings(NK.namedCombinationsCopy())
//
//    private val keyBindings = keyAndMouseBindings.keyCombinations
//
//    private const val DEFAULT = 1.0
//    private const val FAST = 10.0
//    private const val SLOW = 0.1
//
//    private fun rotationHandler(
//        name: String,
//        allowRotations: BooleanExpression,
//        speed: DoubleExpression,
//        globalTransform: AffineTransform3D,
//        displayTransform: AffineTransform3D,
//        globalToViewerTransform: AffineTransform3D,
//        submitTransform: Consumer<AffineTransform3D>,
//        lock: Any,
//        predicate: Predicate<MouseEvent>
//    ): MouseDragFX {
//        val rotate = Rotate(
//            speed,
//            globalTransform,
//            displayTransform,
//            globalToViewerTransform,
//            submitTransform,
//            lock
//        )
//
//        return object : MouseDragFX(name, predicate, true, false) {
//
//            override fun initDrag(event: MouseEvent) {
//                if (allowRotations()) {
//                    rotate.initialize()
//                } else {
//                    abortDrag()
//                }
//            }
//
//            override fun drag(event: MouseEvent) {
//                rotate.rotate(event.x, event.y, startX, startY)
//            }
//        }
//
//    }
//
//    private fun keyRotationHandler(
//        name: String,
//        rotationCenterX: DoubleExpression,
//        rotationCenterY: DoubleExpression,
//        allowRotations: BooleanExpression,
//        axis: ObjectExpression<KeyRotate.Axis>,
//        step: DoubleExpression,
//        displayTransform: AffineTransform3D,
//        globalToViewerTransform: AffineTransform3D,
//        globalTransform: AffineTransform3D,
//        submitTransform: Consumer<AffineTransform3D>,
//        lock: Any,
//        predicate: Predicate<KeyEvent>
//    ): EventFX<KeyEvent> {
//        val rotate = KeyRotate(
//            axis,
//            step,
//            displayTransform,
//            globalToViewerTransform,
//            globalTransform,
//            submitTransform,
//            lock
//        )
//
//        return EventFX.KEY_PRESSED(
//            name,
//            { rotate.rotate(rotationCenterX(), rotationCenterY()) },
//            predicate.and { allowRotations() },
//            true
//        )
//
//    }
//}
