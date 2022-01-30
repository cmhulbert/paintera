package org.janelia.saalfeldlab.paintera.state

import javafx.beans.property.SimpleDoubleProperty
import org.janelia.saalfeldlab.fx.extensions.nonnull

class BrushProperties {

    private var boundTo: BrushProperties? = null

    internal val brushRadiusProperty = SimpleDoubleProperty(5.0)
    internal var brushRadius by brushRadiusProperty.nonnull()

    internal val brushRadiusScaleProperty = SimpleDoubleProperty(1.1)
    internal var brushRadiusScale by brushRadiusScaleProperty.nonnull()

    internal val brushDepthProperty = SimpleDoubleProperty(1.0)
    internal var brushDepth by brushDepthProperty.nonnull()

    internal fun copyFrom(other: BrushProperties) {
        brushRadius = other.brushRadius
        brushRadiusScale = other.brushRadiusScale
        brushDepth = other.brushDepth
    }

    internal fun bindBidirectional(other: BrushProperties) {
        brushRadiusProperty.bindBidirectional(other.brushRadiusProperty)
        brushRadiusScaleProperty.bindBidirectional(other.brushRadiusScaleProperty)
        brushDepthProperty.bindBidirectional(other.brushDepthProperty)
        boundTo = other
    }

    internal fun unbindBidirectional() {
        boundTo?.let {
            brushRadiusProperty.unbindBidirectional(it.brushRadiusProperty)
            brushRadiusScaleProperty.unbindBidirectional(it.brushRadiusScaleProperty)
            brushDepthProperty.unbindBidirectional(it.brushDepthProperty)
            boundTo = null
        }
    }
}
