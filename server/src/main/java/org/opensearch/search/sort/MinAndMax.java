/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.sort;

import org.opensearch.common.io.stream.StreamInput;
import org.opensearch.common.io.stream.StreamOutput;
import org.opensearch.common.io.stream.Writeable;
import org.opensearch.common.lucene.Lucene;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;

/**
 * A class that encapsulates a minimum and a maximum, that are of the same type and {@link Comparable}.
 */
public class MinAndMax<T extends Comparable<? super T>> implements Writeable {
    private final T minValue;
    private final T maxValue;

    public MinAndMax(T minValue, T maxValue) {
        this.minValue = Objects.requireNonNull(minValue);
        this.maxValue = Objects.requireNonNull(maxValue);
    }

    @SuppressWarnings("unchecked")
    public MinAndMax(StreamInput in) throws IOException {
        this.minValue = (T)Lucene.readSortValue(in);
        this.maxValue = (T)Lucene.readSortValue(in);
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        Lucene.writeSortValue(out, minValue);
        Lucene.writeSortValue(out, maxValue);
    }

    /**
     * Return the minimum value.
     */
    public T getMin() {
        return minValue;
    }

    /**
     * Return the maximum value.
     */
    public T getMax() {
        return maxValue;
    }

    /**
     * Return a {@link Comparator} for {@link MinAndMax} values according to the provided {@link SortOrder}.
     */
    public static Comparator<MinAndMax<?>> getComparator(SortOrder order) {
        Comparator<MinAndMax<?>> cmp = order == SortOrder.ASC  ?
            Comparator.comparing(MinAndMax::getMin) : Comparator.comparing(MinAndMax::getMax);
        if (order == SortOrder.DESC) {
            cmp = cmp.reversed();
        }
        return Comparator.nullsLast(cmp);
    }
}
