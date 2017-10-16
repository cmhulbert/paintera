package bdv.bigcat.viewer.viewer3d;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import bdv.bigcat.ui.ARGBStream;
import bdv.bigcat.viewer.state.FragmentSegmentAssignmentState;
import bdv.bigcat.viewer.viewer3d.marchingCubes.ForegroundCheck;
import bdv.bigcat.viewer.viewer3d.util.MeshExtractor;
import cleargl.GLVector;
import graphics.scenery.Material;
import graphics.scenery.Mesh;
import net.imglib2.Interval;
import net.imglib2.Point;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RealLocalizable;
import net.imglib2.RealPoint;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.type.Type;

/**
 * Main class for the Marching Cubes
 *
 * @author Vanessa Leite
 * @author Philipp Hanslovsky
 */
public class Viewer3DController
{
	private final Viewer3D viewer3D;

	private final ViewerMode mode;

	private final ExecutorService es = Executors.newFixedThreadPool( Runtime.getRuntime().availableProcessors() - 1 );

	private final HashSet< NeuronRenderer > renderers = new HashSet<>();

	/**
	 * Enum of the viewer modes. There are two types: ONLY_ONE_NEURON_VISIBLE:
	 * Remove all previously rendered neurons and show only the most recent one.
	 * MANY_NEURONS_VISIBLE: Add a new neuron to the viewer and keep the ones
	 * that were already rendered.
	 */
	public enum ViewerMode
	{
		ONLY_ONE_NEURON_VISIBLE,
		MANY_NEURONS_VISIBLE
	}

	/**
	 * Default constructor
	 */
	public Viewer3DController( final Viewer3D viewer, final ViewerMode mode )
	{
		this.viewer3D = viewer;
		this.mode = mode;
	}

	/**
	 *
	 * @param labelVolumes
	 * @param transforms
	 * @param location
	 * @param label
	 */
	public < T extends Type< T > > void renderAtSelection(
			final RandomAccessible< T >[] labelVolumes,
			final Interval[] intervals,
			final AffineTransform3D[] transforms,
			final RealLocalizable location,
			final ForegroundCheck< T > isForeground,
			final int[] partitionSize,
			final int[] cubeSize )
	{
		if ( mode == ViewerMode.ONLY_ONE_NEURON_VISIBLE )
			viewer3D.removeAllNeurons();

		Mesh previousNeuron = null;
		for ( int i = 0; i < labelVolumes.length; ++i )
		{
			float[] verticesArray = new float[ 0 ];
			// parameters for each resolution
			final RandomAccessible< T > labelVolume = labelVolumes[ i ];
			final AffineTransform3D transform = transforms[ i ];
			final RandomAccess< T > access = labelVolume.randomAccess();
			final RealPoint p = new RealPoint( labelVolume.numDimensions() );
			transform.applyInverse( p, location );
			for ( int d = 0; d < p.numDimensions(); ++d )
				access.setPosition( ( long ) p.getDoublePosition( d ), d );

			// same label for all resolutions
			final MeshExtractor< T > meshExtractor = new MeshExtractor<>(
					labelVolume,
					intervals[ i ],
					transforms[ i ],
					partitionSize,
					cubeSize,
					access,
					isForeground );

			// create an empty mesh
			final Mesh completeNeuron = new Mesh();
			final Material material = new Material();
			material.setAmbient( new GLVector( 1f, 0.0f, 1f ) );
			material.setSpecular( new GLVector( 1f, 0.0f, 1f ) );
			// TODO: Get the color of the neuron in the segmentation
			material.setDiffuse( new GLVector( 1f, 1f, 0f ) );
			material.setOpacity( 1f );

			// define scale, position and material of the mesh
			completeNeuron.setMaterial( material );
			completeNeuron.setPosition( new GLVector( 0.0f, 0.0f, 0.0f ) );

			// if it is not the first resolution, remove the already created
			// resolution.
			// TODO: this must be done in a piece-wise way. I do not think
			// remove all the mesh and grown it again is the best way to do
			// this.
//			if ( i != 0 )
//				viewer3D.removeChild( previousNeuron );

			// add the mesh (still empty) in the viewer
			viewer3D.addChild( completeNeuron );
			// use cube of size - resolution is given by the data itself
			// TODO: generate mesh starting at position defined by access

			float[] completeNeuronVertices = new float[ 0 ];
			int completeMeshSize = 0;
			while ( meshExtractor.hasNext() )
			{
				final Optional< Mesh > neuron = meshExtractor.next();
				if ( neuron.isPresent() )
				{

					if ( completeNeuron.getVertices().hasArray() )
					{
						completeNeuronVertices = completeNeuron.getVertices().array();
						completeMeshSize = completeNeuronVertices.length;
					}

					final float[] neuronVertices = neuron.get().getVertices().array();
					final int meshSize = neuronVertices.length;
					verticesArray = Arrays.copyOf( completeNeuronVertices, completeMeshSize + meshSize );
					System.arraycopy( neuronVertices, 0, verticesArray, completeMeshSize, meshSize );

					// transform mesh into real world coordinates using
					verticesArray = applyTransformation( verticesArray, transform );
					// update the mesh in the viewer
					completeNeuron.setVertices( FloatBuffer.wrap( verticesArray ) );
					completeNeuron.recalculateNormals();
					completeNeuron.setDirty( true );
				}
			}
			previousNeuron = completeNeuron;
		}
	}

	/**
	 *
	 * @param volumeLabels
	 * @param location
	 */
	public synchronized < T extends Type< T >, F extends FragmentSegmentAssignmentState< F > > void generateMesh(
			final RandomAccessible< T > volumeLabels,
			final Interval interval,
			final AffineTransform3D transform,
			final RealLocalizable worldLocation,
			final int[] partitionSize,
			final int[] cubeSize,
			final Function< T, ForegroundCheck< T > > getForegroundCheck,
			final long fragmentId,
			final F fragmentSegmentAssignment,
			final ARGBStream stream,
			final boolean append )
	{
		if ( mode == ViewerMode.ONLY_ONE_NEURON_VISIBLE )
			viewer3D.removeAllNeurons();

//		viewer3D.setCameraPosition( worldLocation );

		final RealPoint imageLocation = new RealPoint( worldLocation.numDimensions() );
		transform.applyInverse( imageLocation, worldLocation );
		final Point locationInImageCoordinates = new Point( imageLocation.numDimensions() );
		for ( int d = 0; d < locationInImageCoordinates.numDimensions(); ++d )
			locationInImageCoordinates.setPosition( ( long ) imageLocation.getDoublePosition( d ), d );

		synchronized ( this.renderers )
		{
			if ( !append )
			{
				this.renderers.forEach( NeuronRenderer::removeSelfFromScene );
				this.renderers.clear();
			}

			final List< NeuronRenderer > filteredNrs = renderers.stream()
					.filter( nr -> nr.fragmentId() == fragmentId || nr.segmentId() == fragmentSegmentAssignment.getSegment( fragmentId ) )
					.collect( Collectors.toList() );

			filteredNrs.forEach( NeuronRenderer::removeSelfFromScene );
			this.renderers.removeAll( filteredNrs );

			final NeuronRenderer< T, F > nr = new NeuronRenderer<>(
					fragmentId,
					fragmentSegmentAssignment,
					stream,
					locationInImageCoordinates,
					volumeLabels,
					interval,
					getForegroundCheck,
					viewer3D.scene(),
					es,
					transform,
					partitionSize,
					cubeSize );
			nr.render();
			this.renderers.add( nr );
		}

	}

	/**
	 * transform mesh into real world coordinates applying affine
	 * transformations
	 *
	 * @param source
	 *            original vertices values
	 * @param transform
	 *            transformations to be applied
	 * @return vertices transformed
	 */
	public static float[] applyTransformation( final float[] source, final AffineTransform3D transform )
	{
		final RealPoint p = new RealPoint( 3 );
		final float[] target = new float[ source.length ];
		for ( int n = 0; n < source.length; n += 3 )
		{
			p.setPosition( source[ n + 0 ], 0 );
			p.setPosition( source[ n + 1 ], 1 );
			p.setPosition( source[ n + 2 ], 2 );
			transform.apply( p, p );
			target[ n + 0 ] = p.getFloatPosition( 0 );
			target[ n + 1 ] = p.getFloatPosition( 1 );
			target[ n + 2 ] = p.getFloatPosition( 2 );
		}

		return target;
	}
}
