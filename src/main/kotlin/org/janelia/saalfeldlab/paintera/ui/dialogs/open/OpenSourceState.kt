package org.janelia.saalfeldlab.paintera.ui.dialogs.open

import bdv.cache.SharedQueue
import io.github.oshai.kotlinlogging.KotlinLogging
import javafx.beans.property.*
import javafx.scene.Group
import kotlinx.coroutines.*
import net.imglib2.Volatile
import net.imglib2.cache.ref.SoftRefLoaderCache
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.IntegerType
import net.imglib2.type.numeric.RealType
import net.imglib2.type.volatiles.AbstractVolatileRealType
import net.imglib2.view.composite.RealComposite
import org.janelia.saalfeldlab.fx.extensions.createObservableBinding
import org.janelia.saalfeldlab.fx.extensions.nonnull
import org.janelia.saalfeldlab.fx.extensions.nullable
import org.janelia.saalfeldlab.fx.extensions.nullableVal
import org.janelia.saalfeldlab.fx.util.InvokeOnJavaFXApplicationThread
import org.janelia.saalfeldlab.n5.universe.N5TreeNode
import org.janelia.saalfeldlab.n5.universe.metadata.SpatialMetadata
import org.janelia.saalfeldlab.paintera.Paintera
import org.janelia.saalfeldlab.paintera.cache.AsyncCacheWithLoader
import org.janelia.saalfeldlab.paintera.data.n5.VolatileWithSet
import org.janelia.saalfeldlab.paintera.meshes.MeshWorkerPriority
import org.janelia.saalfeldlab.paintera.state.SourceState
import org.janelia.saalfeldlab.paintera.state.channel.ConnectomicsChannelState
import org.janelia.saalfeldlab.paintera.state.channel.n5.N5BackendChannel
import org.janelia.saalfeldlab.paintera.state.label.ConnectomicsLabelState
import org.janelia.saalfeldlab.paintera.state.label.n5.N5BackendLabel
import org.janelia.saalfeldlab.paintera.state.metadata.MetadataUtils
import org.janelia.saalfeldlab.paintera.state.metadata.N5ContainerState
import org.janelia.saalfeldlab.paintera.state.raw.ConnectomicsRawState
import org.janelia.saalfeldlab.paintera.state.raw.n5.N5BackendRaw
import org.janelia.saalfeldlab.paintera.viewer3d.ViewFrustum
import org.janelia.saalfeldlab.util.concurrent.HashPriorityQueueBasedTaskExecutor
import org.janelia.saalfeldlab.util.n5.discoverAndParseRecursive
import java.util.concurrent.ExecutorService
import kotlin.coroutines.coroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class OpenSourceState {

	private val containerStatePropertyWrapper = ReadOnlyObjectWrapper<N5ContainerState?>(null)
	private var writableContainerState by containerStatePropertyWrapper.nullable()

	val containerStateProperty = containerStatePropertyWrapper.readOnlyProperty!!
	val containerState by containerStateProperty.nullableVal()

	val activeNodeProperty = SimpleObjectProperty<N5TreeNode?>()
	var activeNode by activeNodeProperty.nullable()
	val activeMetadataProperty = activeNodeProperty.createObservableBinding {
		it.get()?.let { node -> node.metadata as? SpatialMetadata }
	}

	val resolutionProperty = SimpleObjectProperty<DoubleArray?>()
	var resolution by resolutionProperty.nullable()

	val translationProperty = SimpleObjectProperty<DoubleArray?>()
	var translation by translationProperty.nullable()

	val minIntensityProperty = SimpleDoubleProperty(Double.NaN)
	var minIntensity by minIntensityProperty.nonnull()

	val maxIntensityProperty = SimpleDoubleProperty(Double.NaN)
	var maxIntensity by maxIntensityProperty.nonnull()

	val metadataStateBinding = activeMetadataProperty.createObservableBinding {
		val metadata = activeMetadataProperty.get() ?: return@createObservableBinding null
		val container = containerStateProperty.get() ?: return@createObservableBinding null

		MetadataUtils.createMetadataState(container, metadata)
	}.apply {
		subscribe { metadata ->
			val (resolution, translation, min, max) = metadata?.run {
				MutableInfoState(resolution, translation, minIntensity, maxIntensity)
			} ?: MutableInfoState()

			resolutionProperty.value = resolution
			translationProperty.value = translation
			minIntensity = min
			maxIntensity = max
		}
	}
	val metadataState by metadataStateBinding.nullableVal()

	val datasetAttributes get() = metadataState?.datasetAttributes
	val dimensionsBinding = metadataStateBinding.createObservableBinding { it.value?.datasetAttributes?.dimensions }

	val datasetPath get() = activeNodeProperty.get()?.path
	val sourceNameProperty = SimpleStringProperty().also { prop ->
		activeNodeProperty.subscribe { it ->
			prop.value = datasetPath?.split("/")?.last()
		}
	}
	var sourceName by sourceNameProperty.nonnull()

	val validDatasets = SimpleObjectProperty<Map<String, N5TreeNode>>(emptyMap())

	private var parseJob: Deferred<Map<String, N5TreeNode>?>? = null

	fun parseContainer(state: N5ContainerState?): Deferred<Map<String, N5TreeNode>?>? {
		writableContainerState = state
		InvokeOnJavaFXApplicationThread { activeNode = null }
		parseJob?.cancel("Cancelled by new request")
		parseJob = state?.let { container ->
			ContainerLoaderCache.request(container).apply {
				invokeOnCompletion { cause ->
					when (cause) {
						null -> Unit
						is CancellationException -> {
							validDatasets.set(emptyMap<String, N5TreeNode>())
							LOG.trace(cause) {}
							return@invokeOnCompletion
						}

						else -> {
							validDatasets.set(emptyMap<String, N5TreeNode>())
							throw cause
						}
					}
					getCompleted()?.let {
						LOG.trace { "Found ${it.size} valid datasets at ${container.uri}" }
						validDatasets.set(it)
					}
				}
			}
		} ?: let {
			validDatasets.set(emptyMap())
			null
		}
		return parseJob
	}


	companion object {
		private val LOG = KotlinLogging.logger { }

		private data class MutableInfoState(
			val resolution: DoubleArray = DoubleArray(3) { 1.0 },
			val translation: DoubleArray = DoubleArray(3),
			val min: Double = Double.NaN,
			val max: Double = Double.NaN
		)

		@JvmStatic
		fun N5ContainerState.name() = uri.path.split("/").filter { it.isNotBlank() }.last()

		private fun getValidDatasets(node: N5TreeNode): MutableMap<String, N5TreeNode> {
			val map = mutableMapOf<String, N5TreeNode>()
			getValidDatasets(node, map)
			return map
		}

		private fun getValidDatasets(node: N5TreeNode, datasets: MutableMap<String, N5TreeNode>) {
			if (MetadataUtils.metadataIsValid(node.metadata))
				datasets[node.path] = node
			else
				node.childrenList().forEach { getValidDatasets(it, datasets) }

		}

		val ContainerLoaderCache = AsyncCacheWithLoader<N5ContainerState, Map<String, N5TreeNode>?>(
			SoftRefLoaderCache(),
			{ state ->
				val rootNode = discoverAndParseRecursive(state.reader, "/")
				coroutineContext.ensureActive()
				val datasets = getValidDatasets(rootNode).run {
					remove("/")?.let { node -> put(state.name(), node) }

					isEmpty() && return@run null
					this
				}
				coroutineContext.ensureActive()
				datasets
			}
		)

		@JvmStatic
		fun <T, V> getRaw(
			openSourceState: OpenSourceState,
			sharedQueue: SharedQueue,
			priority: Int
		): SourceState<T, V>
				where
				T : RealType<T>, T : NativeType<T>,
				V : AbstractVolatileRealType<T, V>, V : NativeType<V> {

			val metadataState = openSourceState.metadataState!!.copy()
			openSourceState.resolution?.let { resolution ->
				openSourceState.translation?.let { translation ->
					if (metadataState.resolution != resolution || metadataState.translation != translation)
						metadataState.updateTransform(resolution, translation)
				}
			}

			var backend = N5BackendRaw<T, V>(metadataState)
			return ConnectomicsRawState(backend, sharedQueue, priority, openSourceState.sourceName).apply {
				converter().min = openSourceState.minIntensity
				converter().max = openSourceState.maxIntensity
			}
		}


		@JvmStatic
		fun <T, V> getChannels(
			openSourceState: OpenSourceState,
			channelSelection: IntArray,
			sharedQueue: SharedQueue,
			priority: Int
		): List<out SourceState<RealComposite<T>, VolatileWithSet<RealComposite<V>>>>
				where
				T : RealType<T>, T : NativeType<T>,
				V : AbstractVolatileRealType<T, V>, V : NativeType<V> {

			val metadataState = openSourceState.metadataState!!.copy().also {
				openSourceState.resolution?.let { resolution ->
					openSourceState.translation?.let { translation ->
						if (it.resolution != resolution || it.translation != translation)
							it.updateTransform(resolution, translation)
					}
				}
			}

			var channelIdx = metadataState.channelAxis!!.second
			var backend = N5BackendChannel<T, V>(metadataState, channelSelection, channelIdx)
			val state = ConnectomicsChannelState(backend, sharedQueue, priority, openSourceState.sourceName).apply {
				converter().setMins { openSourceState.minIntensity }
				converter().setMaxs { openSourceState.maxIntensity }
			}
			return listOf(state)
		}


		@JvmStatic
		fun <T, V> getLabels(
			openSourceState: OpenSourceState,
			sharedQueue: SharedQueue,
			priority: Int,
			meshesGroup: Group,
			viewFrustumProperty: ObjectProperty<ViewFrustum>,
			eyeToWorldTransformProperty: ObjectProperty<AffineTransform3D>,
			manager: ExecutorService,
			workers: HashPriorityQueueBasedTaskExecutor<MeshWorkerPriority>,
			propagationQueue: ExecutorService,
		): SourceState<T, V>
				where
				T : IntegerType<T>, T : NativeType<T>,
				V : Volatile<T>, V : NativeType<V> {

			val metadataState = openSourceState.metadataState!!.copy()
			openSourceState.resolution?.let { resolution ->
				openSourceState.translation?.let { translation ->
					if (metadataState.resolution != resolution || metadataState.translation != translation)
						metadataState.updateTransform(resolution, translation)
				}
			}

			var backend = N5BackendLabel.createFrom<T, V>(metadataState, propagationQueue)
			return ConnectomicsLabelState(
				backend,
				meshesGroup,
				viewFrustumProperty,
				eyeToWorldTransformProperty,
				manager,
				workers,
				sharedQueue,
				priority,
				openSourceState.sourceName,
				null
			)
		}

	}
}

fun main() {
	val opener = OpenSourceState()

	opener.parseContainer(N5ContainerState(Paintera.n5Factory.openReaderOrNull("s3://janelia-cosem-datasets/jrc_mus-kidney/jrc_mus-kidney.zarr")!!))
	opener.parseContainer(null)
	opener.parseContainer(N5ContainerState(Paintera.n5Factory.openReaderOrNull("s3://janelia-cosem-datasets/jrc_mus-kidney/jrc_mus-kidney.zarr")!!))
	opener.parseContainer(null)
	var job = opener.parseContainer(N5ContainerState(Paintera.n5Factory.openReaderOrNull("s3://janelia-cosem-datasets/jrc_mus-kidney/jrc_mus-kidney.zarr")!!))
	opener.validDatasets.subscribe { map ->
		map.keys.forEach { key -> println(key) }
		println("\n")
	}
	runBlocking {
		job?.await()
	}

	job = opener.parseContainer(N5ContainerState(Paintera.n5Factory.openReaderOrNull("s3://janelia-cosem-datasets/jrc_mus-kidney/jrc_mus-kidney.zarr")!!))
	runBlocking {
		job?.await()
	}
}