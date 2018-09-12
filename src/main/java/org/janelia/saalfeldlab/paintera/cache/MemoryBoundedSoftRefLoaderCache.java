package org.janelia.saalfeldlab.paintera.cache;

import net.imglib2.cache.CacheLoader;
import net.imglib2.cache.LoaderCache;
import net.imglib2.cache.ref.WeakRefLoaderCache;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.ToLongFunction;

/**
 * A cache that forwards to some other (usually {@link WeakRefLoaderCache}) cache and
 * additionally keeps {@link SoftReference}s to the <em>N</em> most recently
 * accessed values.
 *
 * @param <K>
 * @param <V>
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class MemoryBoundedSoftRefLoaderCache<K, V> implements LoaderCache<K, V> {
	private final LoaderCache<K, V> cache;

	private SoftRefs softRefs;

	private final ToLongFunction<V> memoryUsageInBytes;

	private final List<LongConsumer> memoryUsageListeners = new ArrayList<>();

	public MemoryBoundedSoftRefLoaderCache(final long maxSizeInBytes, final LoaderCache<K, V> cache, final ToLongFunction<V> memoryUsageInBytes) {
		this.cache = cache;
		this.softRefs = new SoftRefs(maxSizeInBytes);
		this.memoryUsageInBytes = memoryUsageInBytes;
	}

	public MemoryBoundedSoftRefLoaderCache(final long maxSizeInBytes, final ToLongFunction<V> memoryUsageInBytes) {
		this(maxSizeInBytes, new WeakRefLoaderCache<>(), memoryUsageInBytes);
	}

	public long getCurrentMemoryUsageInBytes()
	{
		synchronized(softRefs)
		{
			return this.softRefs.currentSizeInBytes;
		}
	}

	public void addMemoryUsageListener(LongConsumer listener)
	{
		this.memoryUsageListeners.add(listener);
	}

	@Override
	public V getIfPresent(final K key) {
		final V value = cache.getIfPresent(key);
		if (value != null)
			softRefs.touch(key, value);
		return value;
	}

	@Override
	public V get(final K key, final CacheLoader<? super K, ? extends V> loader) throws ExecutionException {
		final V value = cache.get(key, loader);
		softRefs.touch(key, value);
		return value;
	}

	@Override
	public void invalidateAll() {
		softRefs.clear();
		cache.invalidateAll();
	}

	class SoftRefs extends LinkedHashMap<K, SoftRef<V>> {
		private static final long serialVersionUID = 1L;

		private final long maxSizeInBytes;

		private long currentSizeInBytes = 0;

		private final Consumer<V> onConstruction = v -> this.addToCurrentSizeInBytes(memoryUsageInBytes.applyAsLong(v));

		private final Consumer<V> onClear = v -> this.subtractFromCurrentSizeInBytes(memoryUsageInBytes.applyAsLong(v));

		public SoftRefs(final long maxSizeInBytes) {
			// 262144 = 8 * 8 * 8 * 1byte
			super(100, 0.75f, true);
			this.maxSizeInBytes = maxSizeInBytes;
		}

		@Override
		protected boolean removeEldestEntry(final Entry<K, SoftRef<V>> eldest) {
			if (currentSizeInBytes > maxSizeInBytes) {
				eldest.getValue().clear();
				return true;
			} else
				return false;
		}

		synchronized void touch(final K key, final V value) {
			final SoftRef<V> ref = get(key);
			if (ref == null || ref.get() == null)
				put(key, new SoftRef<V>(value, onConstruction, onClear, this));
		}

		@Override
		public synchronized void clear() {
			for (final SoftReference<V> ref : values())
				ref.clear();
			super.clear();
		}

		private void subtractFromCurrentSizeInBytes(long sizeInBytes)
		{
			setCurrentSizeInBytes(this.currentSizeInBytes - sizeInBytes);
		}

		private void addToCurrentSizeInBytes(long sizeInBytes)
		{
			setCurrentSizeInBytes(this.currentSizeInBytes + sizeInBytes);
		}

		private void setCurrentSizeInBytes(long sizeInBytes)
		{
			this.currentSizeInBytes = sizeInBytes;
			MemoryBoundedSoftRefLoaderCache.this.memoryUsageListeners.forEach(l -> l.accept(sizeInBytes));
		}
	}
}
