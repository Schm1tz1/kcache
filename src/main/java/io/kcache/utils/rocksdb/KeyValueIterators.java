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

package io.kcache.utils.rocksdb;

import java.util.NoSuchElementException;

class KeyValueIterators {

    private static class EmptyKeyValueIterator<K, V> implements KeyValueIterator<K, V> {

        @Override
        public void close() {
        }

        @Override
        public K peekNextKey() {
            throw new NoSuchElementException();
        }

        @Override
        public boolean hasNext() {
            return false;
        }

        @Override
        public KeyValue<K, V> next() {
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
        }
    }

    private static final KeyValueIterator EMPTY_ITERATOR = new EmptyKeyValueIterator();

    @SuppressWarnings("unchecked")
    static <K, V> KeyValueIterator<K, V> emptyIterator() {
        return (KeyValueIterator<K, V>) EMPTY_ITERATOR;
    }
}
