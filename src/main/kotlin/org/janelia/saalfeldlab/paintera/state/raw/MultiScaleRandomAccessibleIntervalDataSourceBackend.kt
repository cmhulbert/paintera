package org.janelia.saalfeldlab.paintera.state.raw

import bdv.util.volatiles.SharedQueue
import javafx.scene.Node
import net.imglib2.Volatile
import net.imglib2.cache.img.CachedCellImg
import net.imglib2.cache.volatiles.CacheHints
import net.imglib2.cache.volatiles.LoadingStrategy
import net.imglib2.img.basictypeaccess.volatiles.VolatileArrayDataAccess
import net.imglib2.interpolation.randomaccess.NearestNeighborInterpolatorFactory
import net.imglib2.realtransform.AffineTransform3D
import net.imglib2.type.NativeType
import net.imglib2.type.numeric.RealType
import net.imglib2.util.Util
import org.janelia.saalfeldlab.n5.*
import org.janelia.saalfeldlab.n5.imglib2.N5Utils
import org.janelia.saalfeldlab.n5.metadata.N5MultiScaleMetadata
import org.janelia.saalfeldlab.n5.metadata.N5SingleScaleMetadata
import org.janelia.saalfeldlab.paintera.data.DataSource
import org.janelia.saalfeldlab.paintera.data.RandomAccessibleIntervalDataSource
import org.janelia.saalfeldlab.paintera.state.metadata.MetadataState
import org.janelia.saalfeldlab.paintera.state.metadata.MultiScaleMetadataState
import org.janelia.saalfeldlab.paintera.state.metadata.N5ContainerState
import org.janelia.saalfeldlab.util.TmpVolatileHelpers
import org.janelia.saalfeldlab.util.TmpVolatileHelpers.Companion.createVolatileCachedCellImgWithInvalidate
import org.janelia.saalfeldlab.util.n5.ImagesWithTransform
import java.lang.reflect.Type

class MultiScaleRandomAccessibleIntervalDataSourceBackend<D, T, A>(
    override val name: String,
    val dimensions: Array<LongArray>,
    val blockSize: Array<IntArray>,
    val data: Array<CachedCellImg<D, A>>,
    val dataType: DataType = N5Utils.dataType(Util.getTypeFromInterval(data[0]))
) : ConnectomicsRawBackend<D, T>
    where D : NativeType<D>, D : RealType<D>, T : NativeType<T>, T : Volatile<D>, A : VolatileArrayDataAccess<A> {

    val datasetAttributes = dimensions.mapIndexed { idx, dims ->
        DatasetAttributes(dims, blockSize[idx], dataType, RawCompression())
    }

    private val metadata = N5MultiScaleMetadata(
        "Virtual RAI Backend",
        datasetAttributes.mapIndexed { idx, attrs ->
            val downscaledFactors = dimensions[0].zip(dimensions[idx]).map { (z, i) -> z.toDouble() / i }.toDoubleArray()
            val downscaleTransform = AffineTransform3D()
            downscaleTransform.scale(downscaledFactors[0], downscaledFactors[1], downscaledFactors[2])
            N5SingleScaleMetadata(
                "s$idx",
                downscaleTransform,
                downscaledFactors,
                doubleArrayOf(1.0, 1.0, 1.0),
                doubleArrayOf(0.0, 0.0, 0.0),
                "pixel",
                attrs
            )
        }.toTypedArray()
    )

    private val metadataState = object : MultiScaleMetadataState(N5ContainerState("Virtual", Companion.NULL_READER, null), metadata) {
        override fun <DD : NativeType<DD>, TT : Volatile<DD>> getData(queue: SharedQueue, priority: Int): Array<ImagesWithTransform<DD, TT>> {

            return data.map { datum ->
                val raiWithInvalidate: TmpVolatileHelpers.RaiWithInvalidate<T> = createVolatileCachedCellImgWithInvalidate(
                    datum,
                    queue,
                    CacheHints(LoadingStrategy.VOLATILE, 0, true)
                )
                ImagesWithTransform(datum, raiWithInvalidate.rai, transform, datum.cache, raiWithInvalidate.invalidate) as ImagesWithTransform<DD, TT>
            }.toTypedArray()

        }
    }

    override fun getMetadataState(): MetadataState = metadataState;

    override fun canWriteToSource() = false

    override fun createSource(queue: SharedQueue, priority: Int, name: String): DataSource<D, T> {

        val imagesWithTransforms = metadataState.getData(queue, priority) as Array<ImagesWithTransform<D, T>>

        val dataWithInvalidate = RandomAccessibleIntervalDataSource.asDataWithInvalidate(imagesWithTransforms)

        return RandomAccessibleIntervalDataSource(
            dataWithInvalidate,
            { NearestNeighborInterpolatorFactory() },
            { NearestNeighborInterpolatorFactory() },
            "Test"
        )
    }

    override fun createMetaDataNode(): Node {
        TODO("Not yet implemented")
    }

    companion object {
        val NULL_READER = object : N5Reader {
            override fun <T : Any?> getAttribute(pathName: String, key: String, clazz: Class<T>?): T? = null
            override fun <T : Any?> getAttribute(pathName: String, key: String, type: Type?): T? = null
            override fun getDatasetAttributes(pathName: String): DatasetAttributes? = null
            override fun readBlock(pathName: String, datasetAttributes: DatasetAttributes, vararg gridPosition: Long): DataBlock<*>? = null
            override fun exists(pathName: String): Boolean = false
            override fun list(pathName: String): Array<String> = arrayOf()
            override fun listAttributes(pathName: String): MutableMap<String, Class<*>> = mutableMapOf()
        }

    }
}
