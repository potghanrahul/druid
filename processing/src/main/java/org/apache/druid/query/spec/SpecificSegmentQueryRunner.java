/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.query.spec;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Supplier;
import org.apache.druid.java.util.common.guava.Accumulator;
import org.apache.druid.java.util.common.guava.Sequence;
import org.apache.druid.java.util.common.guava.SequenceWrapper;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.common.guava.Yielder;
import org.apache.druid.java.util.common.guava.Yielders;
import org.apache.druid.java.util.common.guava.YieldingAccumulator;
import org.apache.druid.query.Queries;
import org.apache.druid.query.Query;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.segment.SegmentMissingException;

import java.io.IOException;
import java.util.Collections;

/**
 *
 */
public class SpecificSegmentQueryRunner<T> implements QueryRunner<T>
{
  private final QueryRunner<T> base;
  private final SpecificSegmentSpec specificSpec;

  @VisibleForTesting
  static final String CTX_SET_THREAD_NAME = "setProcessingThreadNames";

  public SpecificSegmentQueryRunner(
      QueryRunner<T> base,
      SpecificSegmentSpec specificSpec
  )
  {
    this.base = base;
    this.specificSpec = specificSpec;
  }

  @Override
  public Sequence<T> run(final QueryPlus<T> input, final ResponseContext responseContext)
  {
    final QueryPlus<T> queryPlus = input.withQuery(
        Queries.withSpecificSegments(
            input.getQuery(),
            Collections.singletonList(specificSpec.getDescriptor())
        )
    );

    final boolean setName = input.getQuery().context().getBoolean(CTX_SET_THREAD_NAME, true);

    final Query<T> query = queryPlus.getQuery();

    final Thread currThread = setName ? Thread.currentThread() : null;
    final String currThreadName = setName ? currThread.getName() : null;
    final String newName = setName ? "processing_" + query.getId() : null;

    final Sequence<T> baseSequence;

    if (setName) {
      baseSequence = doNamed(
          currThread,
          currThreadName,
          newName,
          () -> base.run(queryPlus, responseContext)
      );
    } else {
      baseSequence = base.run(queryPlus, responseContext);
    }

    Sequence<T> segmentMissingCatchingSequence = new Sequence<>()
    {
      @Override
      public <OutType> OutType accumulate(final OutType initValue, final Accumulator<OutType, T> accumulator)
      {
        try {
          return baseSequence.accumulate(initValue, accumulator);
        }
        catch (SegmentMissingException e) {
          appendMissingSegment(responseContext);
          return initValue;
        }
      }

      @Override
      public <OutType> Yielder<OutType> toYielder(
          final OutType initValue,
          final YieldingAccumulator<OutType, T> accumulator
      )
      {
        try {
          return makeYielder(baseSequence.toYielder(initValue, accumulator));
        }
        catch (SegmentMissingException e) {
          appendMissingSegment(responseContext);
          return Yielders.done(initValue, null);
        }
      }

      private <OutType> Yielder<OutType> makeYielder(final Yielder<OutType> yielder)
      {
        return new Yielder<>()
        {
          @Override
          public OutType get()
          {
            return yielder.get();
          }

          @Override
          public Yielder<OutType> next(final OutType initValue)
          {
            try {
              return yielder.next(initValue);
            }
            catch (SegmentMissingException e) {
              appendMissingSegment(responseContext);
              return Yielders.done(initValue, null);
            }
          }

          @Override
          public boolean isDone()
          {
            return yielder.isDone();
          }

          @Override
          public void close() throws IOException
          {
            yielder.close();
          }
        };
      }
    };
    return Sequences.wrap(
        segmentMissingCatchingSequence,
        new SequenceWrapper()
        {
          @Override
          public <RetType> RetType wrap(Supplier<RetType> sequenceProcessing)
          {
            if (setName) {
              return doNamed(currThread, currThreadName, newName, sequenceProcessing);
            } else {
              return sequenceProcessing.get();
            }
          }
        }
    );
  }

  private void appendMissingSegment(ResponseContext responseContext)
  {
    responseContext.addMissingSegments(
        Collections.singletonList(specificSpec.getDescriptor())
    );
  }

  private <RetType> RetType doNamed(Thread currThread, String currName, String newName, Supplier<RetType> toRun)
  {
    try {
      currThread.setName(newName);
      return toRun.get();
    }
    finally {
      currThread.setName(currName);
    }
  }
}
