package org.janelia.saalfeldlab.paintera

import ch.qos.logback.classic.Level
import com.sun.javafx.application.PlatformImpl
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.input.MouseEvent
import javafx.stage.Modality
import javafx.stage.Stage
import org.janelia.saalfeldlab.fx.extensions.nonnull
import org.janelia.saalfeldlab.fx.ui.Exceptions
import org.janelia.saalfeldlab.paintera.config.ScreenScalesConfig
import org.janelia.saalfeldlab.paintera.ui.PainteraAlerts
import org.janelia.saalfeldlab.paintera.util.logging.LogUtils
import org.janelia.saalfeldlab.util.n5.universe.N5FactoryWithCache
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.io.File
import java.lang.invoke.MethodHandles
import kotlin.system.exitProcess


internal val paintera by lazy { PainteraMainWindow() }
internal val properties
	get() = paintera.properties

fun main(args: Array<String>) {
	System.setProperty("javafx.preloader", PainteraSplashScreen::class.java.canonicalName)
	Application.launch(Paintera::class.java, *args)
}

class Paintera : Application() {

	private val painteraArgs = PainteraCommandLineArgs()
	private var projectDir: String? = null

	init {
		application = this
		/* add window listener for scenes */
	}

	override fun init() {
		val parsedSuccessfully = parsePainteraCommandLine()
		if (!parsedSuccessfully) {
			Platform.exit()
			return
		}
		Platform.setImplicitExit(true)

		projectDir = painteraArgs.project()
		val projectPath = projectDir?.let { File(it).absoluteFile }
		if (!canAccessProjectDir(projectPath)) {
			Platform.exit()
			return
		}

		projectPath?.let {
			notifyPreloader(SplashScreenShowPreloader())
			notifyPreloader(SplashScreenUpdateNotification("Loading Project: ${it.path}", false))
		} ?: let {
            notifyPreloader(SplashScreenShowPreloader())
            notifyPreloader(SplashScreenUpdateNumItemsNotification(2, false))
            notifyPreloader(SplashScreenUpdateNotification("Launching Paintera...", true))
        }
		try {
			paintera.deserialize()
		} catch (error: Exception) {
			LOG.error("Unable to deserialize Paintera project `{}'.", projectPath, error)
			notifyPreloader(SplashScreenFinishPreloader())
			PlatformImpl.runAndWait {
				Exceptions.exceptionAlert(Constants.NAME, "Unable to open Paintera project", error).apply {
					setOnHidden { exitProcess(Error.UNABLE_TO_DESERIALIZE_PROJECT.code) }
					initModality(Modality.NONE)
					showAndWait()
				}
			}
			Platform.exit()
			return
		}
		projectPath?.let {
			notifyPreloader(SplashScreenUpdateNotification("Finalizing Project: ${it.path}"))
		} ?: notifyPreloader(SplashScreenUpdateNotification("Launching Paintera...", true))
		paintable = true
		runPaintable()
		PlatformImpl.runAndWait {
			paintera.properties.loggingConfig.apply {
				painteraArgs.logLevel?.let { rootLoggerLevel = it }
				painteraArgs.logLevelsByName?.forEach { (name, level) -> name?.let { setLogLevelFor(it, level) } }
			}
			painteraArgs.addToViewer(paintera.baseView) { paintera.projectDirectory.actualDirectory?.absolutePath }

			if (painteraArgs.wereScreenScalesProvided())
				paintera.properties.screenScalesConfig.screenScalesProperty().set(ScreenScalesConfig.ScreenScales(*painteraArgs.screenScales()))

			// TODO figure out why this update is necessary?
			paintera.properties.screenScalesConfig.screenScalesProperty().apply {
				val scales = ScreenScalesConfig.ScreenScales(*get().scalesCopy.clone())
				set(ScreenScalesConfig.ScreenScales(*scales.scalesCopy.map { it * 0.5 }.toDoubleArray()))
				set(scales)
			}
		}
		notifyPreloader(SplashScreenFinishPreloader())
	}

	private fun parsePainteraCommandLine(): Boolean {
		val cmd = CommandLine(painteraArgs).apply {
			registerConverter(Level::class.java, LogUtils.Logback.Levels.CmdLineConverter())
		}
		val exitCode = cmd.execute(*parameters.raw.toTypedArray())
		return (cmd.getExecutionResult() ?: false) && exitCode == 0
	}

	private fun canAccessProjectDir(projectPath: File?): Boolean {
		if (projectPath != null && !projectPath.exists()) {
			/* does the project dir exist? If not, try to make it*/
			projectPath.mkdirs()
		}
		var projectDirAccess = true
		PlatformImpl.runAndWait {
			if (projectPath != null && !projectPath.canWrite()) {
				LOG.info("User doesn't have write permissions for project at '$projectPath'. Exiting.")
				PainteraAlerts.alert(Alert.AlertType.ERROR).apply {
					headerText = "Invalid Permissions"
					contentText = "User doesn't have write permissions for project at '$projectPath'. Exiting."
				}.showAndWait()
				projectDirAccess = false
			} else if (!PainteraAlerts.ignoreLockFileDialog(paintera.projectDirectory, projectPath, "_Quit", false)) {
				LOG.info("Paintera project `$projectPath' is locked, will exit.")
				projectDirAccess = false
			}
		}
		return projectDirAccess
	}

	override fun start(primaryStage: Stage) {

		primaryStage.scene = Scene(paintera.pane)
		primaryStage.scene.addEventFilter(MouseEvent.ANY, paintera.mouseTracker)
		primaryStage.scene.stylesheets.add("style/glyphs.css")
		primaryStage.scene.stylesheets.add("style/toolbar.css")
		primaryStage.scene.stylesheets.add("style/navigation.css")
		primaryStage.scene.stylesheets.add("style/interpolation.css")
		primaryStage.scene.stylesheets.add("style/sam.css")
		primaryStage.scene.stylesheets.add("style/paint.css")

		paintera.setupStage(primaryStage)
		primaryStage.show()
//NOTE: Uncomment for an FPS window. TODO: Probably should add some debug/developer menu

//        val fpsWindow = Stage().apply {
//            val averageFps = Label()
//            val instantFps = Label()
//			scene = Scene(VBox(averageFps, instantFps), 400.0, 60.0).apply {
//                val tracker = PerformanceTracker.getSceneTracker(primaryStage.scene)
//                val frameRateMeter: AnimationTimer = object : AnimationTimer() {
//
//					val nanosPerSecond = 10.0.pow(9.0)
//					val averageWindow = 3 * nanosPerSecond
//					var prevAvgTimeStamp = 0L
//
//                    override fun handle(now: Long) {
//						val nanoDiff = now - prevAvgTimeStamp
//						if (nanoDiff > averageWindow) {
//							val secondDiff = nanoDiff / nanosPerSecond
//							averageFps.text = "Average frame rate: ${tracker.averageFPS} fps"
//							tracker.resetAverageFPS()
//							prevAvgTimeStamp = now
//						}
//						instantFps.text = "Instantaneous frame rate: ${tracker.instantFPS} fps"
//					}
//                }
//                frameRateMeter.start()
//            }
//            primaryStage.setOnCloseRequest { close() }
//            show()
//        }


		paintera.properties.viewer3DConfig.bindViewerToConfig(paintera.baseView.viewer3D())

		paintera.properties.windowProperties.apply {
			primaryStage.width = widthProperty.get().toDouble()
			primaryStage.height = heightProperty.get().toDouble()
			widthProperty.bind(primaryStage.widthProperty())
			heightProperty.bind(primaryStage.heightProperty())
			fullScreenProperty.addListener { _, _, newv -> primaryStage.isFullScreen = newv }
			primaryStage.isFullScreen = fullScreenProperty.value
		}
	}

	companion object {

		@JvmStatic
		val n5Factory = N5FactoryWithCache()

		private val LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass())

		@JvmStatic
		fun main(args: Array<String>) {
			System.setProperty("javafx.preloader", PainteraSplashScreen::class.java.canonicalName)
			launch(Paintera::class.java, *args)
		}

		@JvmStatic
		fun getPaintera() = paintera

		@JvmStatic
		lateinit var application: Application
			private set

		private val paintableRunnables = mutableListOf<Runnable>()

		private val paintableProperty = SimpleBooleanProperty(false)

		@JvmStatic
		private var paintable: Boolean by paintableProperty.nonnull()

		/**
		 * Run [runIfPaintable] if [paintable]. Otherwise, do nothing
		 *
		 * @param runIfPaintable the runnable to execute if [paintable]
		 */
		@JvmStatic
		fun ifPaintable(runIfPaintable: Runnable) {
			if (paintable) {
				whenPaintable(runIfPaintable)
			}
		}

		/**
		 * Add [onChangeToPaintable] to a queue, to be executed (FIFO) when Paintera is [paintable].
		 * If already [paintable], and the [paintableRunnables] queue is empty, [onChangeToPaintable] will be executed immediately
		 *
		 * @param onChangeToPaintable
		 */
		@JvmStatic
		fun whenPaintable(onChangeToPaintable: Runnable) {
			synchronized(paintableRunnables) {
				if (paintable) {
					if (paintableRunnables.isNotEmpty()) {
						paintableRunnables.add(onChangeToPaintable)
					} else {
						onChangeToPaintable.run()
					}
				} else {
					paintableRunnables += onChangeToPaintable
				}
			}
		}

		private fun runPaintable() {
			synchronized(paintableRunnables) {
				while (paintableRunnables.isNotEmpty()) {
					paintableRunnables.removeAt(0).run()
				}
			}
		}
	}

}


