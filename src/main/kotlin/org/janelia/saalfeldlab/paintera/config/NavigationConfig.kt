package org.janelia.saalfeldlab.paintera.config

import javafx.beans.property.BooleanProperty
import javafx.beans.property.SimpleBooleanProperty
import org.janelia.saalfeldlab.paintera.control.navigation.ButtonRotationSpeedConfig

class NavigationConfig {

    val allowRotations = SimpleBooleanProperty(true)

    val buttonRotationSpeeds = ButtonRotationSpeedConfig()

    fun allowRotationsProperty(): BooleanProperty {
        return this.allowRotations
    }

//        FIXME Caleb: Remove when NavigationControlMode works
//    fun bindNavigationToConfig(navigation: Navigation) {
//        navigation.allowRotationsProperty.bind(this.allowRotations)
//        navigation.bindTo(this.buttonRotationSpeeds)
//    }

    fun buttonRotationSpeeds(): ButtonRotationSpeedConfig {
        return this.buttonRotationSpeeds
    }

    fun set(that: NavigationConfig) {
        this.allowRotations.set(that.allowRotations.get())
        this.buttonRotationSpeeds.slow.set(that.buttonRotationSpeeds.slow.get())
        this.buttonRotationSpeeds.fast.set(that.buttonRotationSpeeds.fast.get())
        this.buttonRotationSpeeds.regular.set(that.buttonRotationSpeeds.regular.get())
    }

}
