package org.janelia.saalfeldlab.fx.extensions

import javafx.scene.input.MouseEvent
import net.imglib2.RealPoint

val MouseEvent.position: RealPoint
    get() = RealPoint(x, y)
