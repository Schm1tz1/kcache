/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kcache.rocksdb;

import io.kcache.KeyValueIterator;
import io.kcache.KeyValueIterators;
import io.kcache.exceptions.CacheException;
import io.kcache.exceptions.CacheInitializationException;
import io.kcache.utils.PersistentCache;
import io.kcache.utils.KeyBytesComparator;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.kafka.common.serialization.Serde;
import org.rocksdb.BlockBasedTableConfig;
import org.rocksdb.BloomFilter;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.FlushOptions;
import org.rocksdb.InfoLogLevel;
import org.rocksdb.LRUCache;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.rocksdb.WriteBatch;
import org.rocksdb.WriteOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A persistent key-value store based on RocksDB.
 */
public class RocksDBCache<K, V> extends PersistentCache<K, V> {
    private static final Logger log = LoggerFactory.getLogger(RocksDBCache.class);

    private static final CompressionType COMPRESSION_TYPE = CompressionType.NO_COMPRESSION;
    private static final CompactionStyle COMPACTION_STYLE = CompactionStyle.UNIVERSAL;
    private static final long WRITE_BUFFER_SIZE = 16 * 1024 * 1024L;
    private static final long BLOCK_CACHE_SIZE = 50 * 1024 * 1024L;
    private static final long BLOCK_SIZE = 4096L;
    private static final int MAX_WRITE_BUFFERS = 3;
    private static final String DB_FILE_DIR = "rocksdb";

    private RocksDB db;
    private RocksDBAccessor dbAccessor;
    private final Set<KeyValueIterator<byte[], byte[]>> openIterators = ConcurrentHashMap.newKeySet();

    // the following option objects will be created in openDB and closed in the close() method
    private RocksDBGenericOptionsToDbOptionsColumnFamilyOptionsAdapter userSpecifiedOptions;
    private WriteOptions wOptions;
    private FlushOptions fOptions;
    private org.rocksdb.Cache cache;
    private BloomFilter filter;

    public RocksDBCache(final String name,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde) {
        this(name, DB_FILE_DIR, rootDir, keySerde, valueSerde);
    }

    public RocksDBCache(final String name,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde,
                        Comparator<K> comparator) {
        this(name, DB_FILE_DIR, rootDir, keySerde, valueSerde, comparator);
    }

    public RocksDBCache(final String name,
                        final String parentDir,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde) {
        this(name, parentDir, rootDir, keySerde, valueSerde, null);
    }

    public RocksDBCache(final String name,
                        final String parentDir,
                        final String rootDir,
                        Serde<K> keySerde,
                        Serde<V> valueSerde,
                        Comparator<K> comparator) {
        super(name, parentDir, rootDir, keySerde, valueSerde, comparator);
    }

    @Override
    protected void openDB() {
        // initialize the default rocksdb options

        final DBOptions dbOptions = new DBOptions();
        final ColumnFamilyOptions columnFamilyOptions = new ColumnFamilyOptions();
        userSpecifiedOptions = new RocksDBGenericOptionsToDbOptionsColumnFamilyOptionsAdapter(dbOptions, columnFamilyOptions);
        userSpecifiedOptions.setComparator(new RocksDBKeySliceComparator<>(keySerde(), comparator()));

        final BlockBasedTableConfig tableConfig = new BlockBasedTableConfig();
        cache = new LRUCache(BLOCK_CACHE_SIZE);
        tableConfig.setBlockCache(cache);
        tableConfig.setBlockSize(BLOCK_SIZE);

        filter = new BloomFilter();
        tableConfig.setFilterPolicy(filter);

        userSpecifiedOptions.optimizeFiltersForHits();
        userSpecifiedOptions.setTableFormatConfig(tableConfig);
        userSpecifiedOptions.setWriteBufferSize(WRITE_BUFFER_SIZE);
        userSpecifiedOptions.setCompressionType(COMPRESSION_TYPE);
        userSpecifiedOptions.setCompactionStyle(COMPACTION_STYLE);
        userSpecifiedOptions.setMaxWriteBufferNumber(MAX_WRITE_BUFFERS);
        userSpecifiedOptions.setCreateIfMissing(true);
        userSpecifiedOptions.setErrorIfExists(false);
        userSpecifiedOptions.setInfoLogLevel(InfoLogLevel.ERROR_LEVEL);
        // this is the recommended way to increase parallelism in RocksDb
        // note that the current implementation of setIncreaseParallelism affects the number
        // of compaction threads but not flush threads (the latter remains one). Also
        // the parallelism value needs to be at least two because of the code in
        // https://github.com/facebook/rocksdb/blob/62ad0a9b19f0be4cefa70b6b32876e764b7f3c11/util/options.cc#L580
        // subtracts one from the value passed to determine the number of compaction threads
        // (this could be a bug in the RocksDB code and their devs have been contacted).
        userSpecifiedOptions.setIncreaseParallelism(Math.max(Runtime.getRuntime().availableProcessors(), 2));

        wOptions = new WriteOptions();
        wOptions.setDisableWAL(true);

        fOptions = new FlushOptions();
        fOptions.setWaitForFlush(true);

        final List<ColumnFamilyDescriptor> columnFamilyDescriptors
            = Collections.singletonList(new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, columnFamilyOptions));
        final List<ColumnFamilyHandle> columnFamilies = new ArrayList<>(columnFamilyDescriptors.size());

        try {
            db = RocksDB.open(dbOptions, dbDir().getAbsolutePath(), columnFamilyDescriptors, columnFamilies);
            dbAccessor = new SingleColumnFamilyAccessor(columnFamilies.get(0));
        } catch (final RocksDBException e) {
            throw new CacheInitializationException("Error opening store " + name() + " at location " + dbDir(), e);
        }
    }

    @Override
    public int size() {
        validateStoreOpen();
        return (int) approximateNumEntries();
    }

    @Override
    public V put(final K key, final V value) {
        Objects.requireNonNull(key, "key cannot be null");
        validateStoreOpen();
        final V originalValue = get(key);
        byte[] keyBytes = keySerde().serializer().serialize(null, key);
        byte[] valueBytes = valueSerde().serializer().serialize(null, value);
        dbAccessor.put(keyBytes, valueBytes);
        return originalValue;
    }

    @Override
    public void putAll(Map<? extends K, ? extends V> entries) {
        validateStoreOpen();
        try (final WriteBatch batch = new WriteBatch()) {
            Map<byte[], byte[]> rawEntries = entries.entrySet().stream()
                .collect(Collectors.toMap(
                    e -> keySerde().serializer().serialize(null, e.getKey()),
                    e -> valueSerde().serializer().serialize(null, e.getValue())));
            dbAccessor.prepareBatch(rawEntries, batch);
            write(batch);
        } catch (final RocksDBException e) {
            throw new CacheException("Error while batch writing to store " + name(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V get(final Object key) {
        validateStoreOpen();
        try {
            byte[] keyBytes = keySerde().serializer().serialize(null, (K) key);
            byte[] valueBytes = dbAccessor.get(keyBytes);
            return valueSerde().deserializer().deserialize(null, valueBytes);
        } catch (final RocksDBException e) {
            // String format is happening in wrapping stores. So formatted message is thrown from wrapping stores.
            throw new CacheException("Error while getting value for key from store " + name(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public V remove(final Object key) {
        Objects.requireNonNull(key, "key cannot be null");
        return put((K) key, null);
    }

    @Override
    protected KeyValueIterator<K, V> range(K from, boolean fromInclusive, K to, boolean toInclusive, boolean isDescending) {
        byte[] fromBytes = keySerde().serializer().serialize(null, from);
        byte[] toBytes = keySerde().serializer().serialize(null, to);

        validateStoreOpen();

        final KeyValueIterator<byte[], byte[]> rocksDBIterator =
            dbAccessor.range(fromBytes, fromInclusive, toBytes, toInclusive, isDescending);
        openIterators.add(rocksDBIterator);

        return KeyValueIterators.transformRawIterator(keySerde(), valueSerde(), rocksDBIterator);
    }

    @Override
    protected KeyValueIterator<K, V> all(boolean isDescending) {
        validateStoreOpen();
        final KeyValueIterator<byte[], byte[]> rocksDBIterator = dbAccessor.all(isDescending);
        openIterators.add(rocksDBIterator);
        return KeyValueIterators.transformRawIterator(keySerde(), valueSerde(), rocksDBIterator);
    }

    /**
     * Return an approximate count of key-value mappings in this store.
     *
     * <code>RocksDB</code> cannot return an exact entry count without doing a
     * full scan, so this method relies on the <code>rocksdb.estimate-num-keys</code>
     * property to get an approximate count. The returned size also includes
     * a count of dirty keys in the store's in-memory cache, which may lead to some
     * double-counting of entries and inflate the estimate.
     *
     * @return an approximate count of key-value mappings in the store.
     */
    private long approximateNumEntries() {
        validateStoreOpen();
        final long numEntries;
        try {
            numEntries = dbAccessor.approximateNumEntries();
        } catch (final RocksDBException e) {
            throw new CacheException("Error fetching property from store " + name(), e);
        }
        if (isOverflowing(numEntries)) {
            return Long.MAX_VALUE;
        }
        return numEntries;
    }

    private boolean isOverflowing(final long value) {
        // RocksDB returns an unsigned 8-byte integer, which could overflow long
        // and manifest as a negative value.
        return value < 0;
    }

    @Override
    public void flush() {
        if (db == null) {
            return;
        }
        try {
            dbAccessor.flush();
        } catch (final RocksDBException e) {
            throw new CacheException("Error while executing flush from store " + name(), e);
        }
    }

    private void write(final WriteBatch batch) throws RocksDBException {
        db.write(wOptions, batch);
    }

    @Override
    protected void closeDB() {
        closeOpenIterators();

        dbAccessor.close();
        userSpecifiedOptions.close();
        wOptions.close();
        fOptions.close();
        db.close();
        filter.close();
        cache.close();

        dbAccessor = null;
        userSpecifiedOptions = null;
        wOptions = null;
        fOptions = null;
        db = null;
        filter = null;
        cache = null;
    }

    private void closeOpenIterators() {
        if (openIterators.size() != 0) {
            log.warn("Closing {} open iterators for store {}", openIterators.size(), name());
            for (final KeyValueIterator<byte[], byte[]> iterator : openIterators) {
                iterator.close();
            }
        }
    }

    interface RocksDBAccessor {

        void put(final byte[] key,
                 final byte[] value);

        void prepareBatch(final Map<byte[], byte[]> entries,
                          final WriteBatch batch) throws RocksDBException;

        byte[] get(final byte[] key) throws RocksDBException;

        KeyValueIterator<byte[], byte[]> range(byte[] from, boolean fromInclusive,
                                               byte[] to, boolean toInclusive, boolean isDescending);

        KeyValueIterator<byte[], byte[]> all(boolean isDescending);

        long approximateNumEntries() throws RocksDBException;

        void flush() throws RocksDBException;

        void addToBatch(final byte[] key,
                        final byte[] value,
                        final WriteBatch batch) throws RocksDBException;

        void close();
    }

    class SingleColumnFamilyAccessor implements RocksDBAccessor {
        private final ColumnFamilyHandle columnFamily;

        SingleColumnFamilyAccessor(final ColumnFamilyHandle columnFamily) {
            this.columnFamily = columnFamily;
        }

        @Override
        public void put(final byte[] key,
                        final byte[] value) {
            if (value == null) {
                try {
                    db.delete(columnFamily, wOptions, key);
                } catch (final RocksDBException e) {
                    // String format is happening in wrapping stores. So formatted message is thrown from wrapping stores.
                    throw new CacheException("Error while removing key from store " + name(), e);
                }
            } else {
                try {
                    db.put(columnFamily, wOptions, key, value);
                } catch (final RocksDBException e) {
                    // String format is happening in wrapping stores. So formatted message is thrown from wrapping stores.
                    throw new CacheException("Error while putting key/value into store " + name(), e);
                }
            }
        }

        @Override
        public void prepareBatch(final Map<byte[], byte[]> entries,
                                 final WriteBatch batch) throws RocksDBException {
            for (final Map.Entry<byte[], byte[]> entry : entries.entrySet()) {
                Objects.requireNonNull(entry.getKey(), "key cannot be null");
                addToBatch(entry.getKey(), entry.getValue(), batch);
            }
        }

        @Override
        public byte[] get(final byte[] key) throws RocksDBException {
            return db.get(columnFamily, key);
        }

        @Override
        public KeyValueIterator<byte[], byte[]> range(byte[] from, boolean fromInclusive,
                                                      byte[] to, boolean toInclusive, boolean isDescending) {
            Comparator<byte[]> bytesComparator = new KeyBytesComparator<>(keySerde(), comparator());

            if (from != null && to != null) {
                int cmp = bytesComparator.compare(from, to);
                if ((isDescending && cmp < 0) || (!isDescending && cmp > 0)) {
                    log.warn("Returning empty iterator for fetch with invalid key range. "
                        + "This may be due to serdes that don't preserve ordering when lexicographically comparing the serialized bytes. " +
                        "Note that the built-in numerical serdes do not follow this for negative numbers");
                    return KeyValueIterators.emptyIterator();
                }
            }

            return new RocksDBRangeIterator(
                name(),
                db.newIterator(columnFamily),
                openIterators,
                from,
                fromInclusive,
                to,
                toInclusive,
                isDescending,
                bytesComparator);
        }

        @Override
        public KeyValueIterator<byte[], byte[]> all(boolean isDescending) {
            return new RocksDBIterator(name(), db.newIterator(columnFamily), openIterators, isDescending);
        }

        @Override
        public long approximateNumEntries() throws RocksDBException {
            return db.getLongProperty(columnFamily, "rocksdb.estimate-num-keys");
        }

        @Override
        public void flush() throws RocksDBException {
            db.flush(fOptions, columnFamily);
        }

        @Override
        public void addToBatch(final byte[] key,
                               final byte[] value,
                               final WriteBatch batch) throws RocksDBException {
            if (value == null) {
                batch.delete(columnFamily, key);
            } else {
                batch.put(columnFamily, key, value);
            }
        }

        @Override
        public void close() {
            columnFamily.close();
        }
    }
}
