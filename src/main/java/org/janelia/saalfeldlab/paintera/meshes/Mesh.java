package org.janelia.saalfeldlab.paintera.meshes;

import gnu.trove.impl.Constants;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.hash.TIntHashSet;
import javafx.geometry.Point3D;
import net.imglib2.Interval;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.util.Triple;
import net.imglib2.util.ValueTriple;

import java.util.ArrayList;
import java.util.Arrays;

public class Mesh {

	/**
	 * vertex coordinates, each consisting of three elements
	 * [x_0, y_0, z_0, x_1, y_1, z_1, ... , x_n, y_n, z_n]
	 */
	private final float[] vertices;

	/**
	 * normal vectors for each vertex
	 * [x_0, y_0, z_0, x_1, y_1, z_1, ... , x_n, y_n, z_n]
	 */
	private final float[] normals;

	/**
	 * vertex indices forming triangles
	 * [vertex_0.0, vertex_0.1, vertex_0.2, vertex_1.0, vertex_1.1, vertex_1.2, ... , vertex_n.0, vertex_n.1, vertex_n.2]
	 */
	private final int[] vertexIndices;

	/**
	 *
	 */
	private final ArrayList<int[]> vertexTriangles = new ArrayList<>();

	/**
	 * overhanging vertices
	 */
	private final TIntHashSet overhanging = new TIntHashSet(Constants.DEFAULT_CAPACITY, Constants.DEFAULT_LOAD_FACTOR, -1);


	public Mesh(final float[] flatVertices, final Interval interval, final AffineTransform3D transform) {

		assert flatVertices.length % 9 == 0;

		final TFloatArrayList vertexList = new TFloatArrayList();
		final TObjectIntHashMap<Point3D> vertexIndexMap = new TObjectIntHashMap<>();
		final ArrayList<TIntHashSet> vertexTrianglesList = new ArrayList<>();
		final TIntArrayList triangleList = new TIntArrayList();

		final double minY = interval.min(1) - 1;
		final double minX = interval.min(0) - 1;
		final double minZ = interval.min(2) - 1;

		final double maxX = interval.max(0) + 1; // overlap 1
		final double maxY = interval.max(1) + 1; // overlap 1
		final double maxZ = interval.max(2) + 1; // overlap 1

		final double[] p = new double[3];

		for (int triangle = 0; triangle < flatVertices.length; triangle += 9) {

			final Point3D[] keys = new Point3D[]{
					new Point3D(flatVertices[triangle + 0], flatVertices[triangle + 1], flatVertices[triangle + 2]),
					new Point3D(flatVertices[triangle + 3], flatVertices[triangle + 4], flatVertices[triangle + 5]),
					new Point3D(flatVertices[triangle + 6], flatVertices[triangle + 7], flatVertices[triangle + 8])
			};

			for (int i = 0; i < keys.length; ++i) {
				final Point3D key = keys[i];
				final int vertexIndex;
				if (vertexIndexMap.contains(key))
					vertexIndex = vertexIndexMap.get(keys[i]);
				else {
					vertexIndex = vertexList.size() / 3;
					vertexIndexMap.put(key, vertexIndex);

					p[0] = key.getX();
					p[1] = key.getY();
					p[2] = key.getZ();

					if (p[0] < minX || p[1] < minY || p[2] < minZ || p[0] > maxX || p[1] > maxY || p[2] > maxZ)
//					if (p[0] < minX + 1 || p[1] < minY + 1 || p[2] < minZ + 1 || p[0] > maxX - 1 || p[1] > maxY - 1 || p[2] > maxZ - 1)
						overhanging.add(vertexIndex);

					transform.apply(p, p);

					vertexList.add((float)p[0]);
					vertexList.add((float)p[1]);
					vertexList.add((float)p[2]);
				}
				triangleList.add(vertexIndex);

				final TIntHashSet triangleIndices;
				if (vertexTrianglesList.size() > vertexIndex) {
					triangleIndices = vertexTrianglesList.get(vertexIndex);
				} else {
					triangleIndices = new TIntHashSet();
					vertexTrianglesList.add(triangleIndices);
				}
				triangleIndices.add(triangle / 9);
			}
		}

		vertices = vertexList.toArray();
		vertexIndices = triangleList.toArray();
		normals = new float[vertices.length];

		for (final TIntHashSet vertexIndices : vertexTrianglesList) {
			vertexTriangles.add(vertexIndices.toArray());
		}

//		averageNormals();
	}

	public void averageNormals() {

		final double[] triangleNormals = new double[vertexIndices.length]; // coincidental match 3 vertices and 3 coordinates

		for (int triangle = 0; triangle < vertexIndices.length; triangle += 3) {

			final int v1 = vertexIndices[triangle] * 3;
			final int v2 = vertexIndices[triangle + 1] * 3;
			final int v3 = vertexIndices[triangle + 2] * 3;

			final double v11 = vertices[v1], v12 = vertices[v1 + 1], v13 = vertices[v1 + 2];
			final double v21 = vertices[v2], v22 = vertices[v2 + 1], v23 = vertices[v2 + 2];
			final double v31 = vertices[v3], v32 = vertices[v3 + 1], v33 = vertices[v3 + 2];

			final double diff10 = v21 - v11;
			final double diff11 = v22 - v12;
			final double diff12 = v23 - v13;

			final double diff20 = v31 - v11;
			final double diff21 = v32 - v12;
			final double diff22 = v33 - v13;

			double x = diff11 * diff22 - diff12 * diff21;
			double y = diff12 * diff20 - diff10 * diff22;
			double z = diff10 * diff21 - diff11 * diff20;
			final double norm = Math.sqrt(x * x + y * y + z * z);
			x /= norm;
			y /= norm;
			z /= norm;

			triangleNormals[triangle + 0] = x;
			triangleNormals[triangle + 1] = y;
			triangleNormals[triangle + 2] = z;
		}

		for (int vertex = 0; vertex < vertices.length; vertex += 3) {

			final int[] triangles = vertexTriangles.get(vertex / 3);
			double x = 0, y = 0, z = 0;
			for (final int triangle : triangles) {
				final int t = triangle * 3;
				x += triangleNormals[t];
				y += triangleNormals[t + 1];
				z += triangleNormals[t + 2];
			}
			normals[vertex] = (float)(x / triangles.length);
			normals[vertex + 1] = (float)(y / triangles.length);
			normals[vertex + 2] = (float)(z / triangles.length);
		}
	}

	public void smooth(final double lambda, final int iterations) {

		final float[] smoothedVertices = new float[vertices.length];
		final double lambda1 = 1.0 - lambda;
		final TIntHashSet otherVertices = new TIntHashSet();
		for (int i = 0; i < iterations; ++i) {
			for (int vertex = 0; vertex < vertices.length; vertex += 3) {

				final int vi = vertex / 3;
				otherVertices.clear();
				final int[] triangles = vertexTriangles.get(vi);
				for (final int triangle : triangles) {
					final int ti = triangle * 3;
					otherVertices.add(vertexIndices[ti]);
					otherVertices.add(vertexIndices[ti + 1]);
					otherVertices.add(vertexIndices[ti + 2]);
				}
				otherVertices.remove(vi);

				double x = 0, y = 0, z = 0;
				final TIntIterator it = otherVertices.iterator();
				final double norm = 1.0 / otherVertices.size() * lambda;
				while (it.hasNext()) {
					final int oi = it.next() * 3;
					x += vertices[oi];
					y += vertices[oi + 1];
					z += vertices[oi + 2];
				}

				smoothedVertices[vertex] = (float) (lambda1 * vertices[vertex] + x * norm);
				smoothedVertices[vertex + 1] = (float) (lambda1 * vertices[vertex + 1] + y * norm);
				smoothedVertices[vertex + 2] = (float) (lambda1 * vertices[vertex + 2] + z * norm);
			}
			System.arraycopy(smoothedVertices, 0, vertices, 0, vertices.length);
		}
	}

	/**
	 * TODO remove this as ASAP
	 *
	 * @deprecated this is only for testing
	 */
	public Triple<float[], float[], int[]> export() {

		final TFloatArrayList exportVertices = new TFloatArrayList();
		final TFloatArrayList exportNormals = new TFloatArrayList();

		for (int i = 0; i < vertexIndices.length; i += 3) {

			if (!(
					overhanging.contains(vertexIndices[i]) ||
					overhanging.contains(vertexIndices[i + 1]) ||
					overhanging.contains(vertexIndices[i + 2]))) {

				for (int k = 0; k < 3; ++k) {
					int k1 = vertexIndices[i + k] * 3;
					int k2 = k1 + 1;
					int k3 = k1 + 2;
					exportVertices.add(vertices[k1]);
					exportNormals.add(normals[k1]);
					exportVertices.add(vertices[k2]);
					exportNormals.add(normals[k2]);
					exportVertices.add(vertices[k3]);
					exportNormals.add(normals[k3]);
				}
			}
		}

		return new ValueTriple<>(exportVertices.toArray(), exportNormals.toArray(), null);
	}
}
