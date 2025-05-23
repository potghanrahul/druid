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

package org.apache.druid.segment.metadata;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.Guice;
import com.google.inject.Module;
import com.google.inject.util.Modules;
import org.apache.druid.client.CachingClusteredClientTest.ServerExpectation;
import org.apache.druid.client.CachingClusteredClientTest.ServerExpectations;
import org.apache.druid.client.CoordinatorSegmentWatcherConfig;
import org.apache.druid.client.CoordinatorServerView;
import org.apache.druid.client.DirectDruidClientFactory;
import org.apache.druid.client.DruidServer;
import org.apache.druid.client.SegmentLoadInfo;
import org.apache.druid.client.ServerInventoryView;
import org.apache.druid.guice.http.DruidHttpClientConfig;
import org.apache.druid.java.util.common.Intervals;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.java.util.common.guava.Sequences;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.query.DataSource;
import org.apache.druid.query.QueryContexts;
import org.apache.druid.query.QueryPlus;
import org.apache.druid.query.QueryRunner;
import org.apache.druid.query.QueryRunnerFactoryConglomerate;
import org.apache.druid.query.RestrictedDataSource;
import org.apache.druid.query.SegmentDescriptor;
import org.apache.druid.query.TableDataSource;
import org.apache.druid.query.context.ResponseContext;
import org.apache.druid.query.metadata.metadata.AllColumnIncluderator;
import org.apache.druid.query.metadata.metadata.SegmentAnalysis;
import org.apache.druid.query.metadata.metadata.SegmentMetadataQuery;
import org.apache.druid.query.policy.NoRestrictionPolicy;
import org.apache.druid.query.spec.MultipleSpecificSegmentSpec;
import org.apache.druid.server.QueryStackTests;
import org.apache.druid.server.coordination.ServerType;
import org.apache.druid.server.initialization.ServerConfig;
import org.apache.druid.server.metrics.NoopServiceEmitter;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentId;
import org.apache.druid.timeline.VersionedIntervalTimeline;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.druid.timeline.partition.SingleDimensionShardSpec;
import org.easymock.EasyMock;
import org.easymock.IAnswer;
import org.joda.time.Interval;
import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

public class SegmentMetadataQuerySegmentWalkerTest
{
  private final String DATASOURCE = "testDatasource";
  private DruidServer[] servers;
  private Random random;

  private TestCoordinatorServerView testCoordinatorServerView;
  private Module module;

  @ClassRule
  public static QueryStackTests.Junit4ConglomerateRule conglomerateRule = new QueryStackTests.Junit4ConglomerateRule();

  @Before
  public void setUp()
  {
    DruidHttpClientConfig httpClientConfig = new DruidHttpClientConfig()
    {
      @Override
      public long getMaxQueuedBytes()
      {
        return 0L;
      }
    };
    servers =
        new DruidServer[]{
            new DruidServer("test1", "test1", null, 10, ServerType.HISTORICAL, "bye", 0),
            new DruidServer("test2", "test2", null, 10, ServerType.HISTORICAL, "bye", 0),
            new DruidServer("test3", "test2", null, 10, ServerType.INDEXER_EXECUTOR, "bye", 0)
        };

    random = new Random(0);
    module = Modules.combine(
        binder -> binder.bind(CoordinatorServerView.class).toInstance(testCoordinatorServerView),
        binder -> binder.bind(DruidHttpClientConfig.class).toInstance(httpClientConfig),
        binder -> binder.bind(QueryRunnerFactoryConglomerate.class).toInstance(conglomerateRule.getConglomerate()),
        binder -> binder.bind(ServerConfig.class).toInstance(new ServerConfig()),
        binder -> binder.bind(ServiceEmitter.class).toInstance(new NoopServiceEmitter())
    );
  }

  @Test
  public void testWalker() throws IOException
  {
    Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines = new HashMap<>();
    Map<String, QueryRunner> queryRunnerMap = new HashMap<>();
    Map<String, ServerExpectations> serverExpectationsMap =
        populateTimeline(
            timelines,
            queryRunnerMap,
            Lists.newArrayList(
                Pair.of(Intervals.of("2011-01-01/2011-01-02"), Lists.newArrayList(0, 4, 5)),
                Pair.of(Intervals.of("2011-01-05/2011-01-07"), Lists.newArrayList(0, 1, 1))
            )
        );
    testCoordinatorServerView = new TestCoordinatorServerView(timelines, queryRunnerMap);

    List<SegmentDescriptor> segmentDescriptors =
        serverExpectationsMap.values()
                             .stream()
                             .flatMap(serverExpectations -> Lists.newArrayList(serverExpectations.iterator()).stream())
                             .map(ServerExpectation::getSegment)
                             .map(segment -> segment.getId().toDescriptor())
                             .collect(Collectors.toList());
    SegmentMetadataQuery segmentMetadataQuery = new SegmentMetadataQuery(
        new TableDataSource(DATASOURCE),
        new MultipleSpecificSegmentSpec(segmentDescriptors),
        new AllColumnIncluderator(),
        false,
        QueryContexts.override(Collections.emptyMap(), QueryContexts.BROKER_PARALLEL_MERGE_KEY, false),
        EnumSet.noneOf(SegmentMetadataQuery.AnalysisType.class),
        false,
        null,
        null
    );

    SegmentMetadataQuerySegmentWalker walker = Guice.createInjector(module)
                                                    .getInstance(SegmentMetadataQuerySegmentWalker.class);
    Set<String> actualSegmentIds = walker.getQueryRunnerForSegments(segmentMetadataQuery, segmentDescriptors)
                                         .run(QueryPlus.wrap(segmentMetadataQuery))
                                         .toList()
                                         .stream()
                                         .map(SegmentAnalysis::getId)
                                         .collect(Collectors.toSet());

    Set<String> expectedSegmentIds = serverExpectationsMap.values()
                                                          .stream()
                                                          .flatMap(serverExpectations -> Lists.newArrayList(
                                                              serverExpectations.iterator()).stream())
                                                          .map(ServerExpectation::getSegment)
                                                          .map(segment -> segment.getId().toString())
                                                          .collect(Collectors.toSet());
    Assert.assertEquals(expectedSegmentIds, actualSegmentIds);
  }

  @Test
  public void testWalkerWithRestriction() throws IOException
  {
    Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines = new HashMap<>();
    Map<String, QueryRunner> queryRunnerMap = new HashMap<>();
    Map<String, ServerExpectations> serverExpectationsMap =
        populateTimeline(
            timelines,
            queryRunnerMap,
            Lists.newArrayList(
                Pair.of(Intervals.of("2011-01-01/2011-01-02"), Lists.newArrayList(0, 4, 5)),
                Pair.of(Intervals.of("2011-01-05/2011-01-07"), Lists.newArrayList(0, 1, 1))
            )
        );
    testCoordinatorServerView = new TestCoordinatorServerView(timelines, queryRunnerMap);

    List<SegmentDescriptor> segmentDescriptors =
        serverExpectationsMap.values()
                             .stream()
                             .flatMap(serverExpectations -> Lists.newArrayList(serverExpectations.iterator()).stream())
                             .map(ServerExpectation::getSegment)
                             .map(segment -> segment.getId().toDescriptor())
                             .collect(Collectors.toList());
    SegmentMetadataQuery segmentMetadataQuery = new SegmentMetadataQuery(
        RestrictedDataSource.create(new TableDataSource(DATASOURCE), NoRestrictionPolicy.instance()),
        new MultipleSpecificSegmentSpec(segmentDescriptors),
        new AllColumnIncluderator(),
        false,
        QueryContexts.override(Collections.emptyMap(), QueryContexts.BROKER_PARALLEL_MERGE_KEY, false),
        EnumSet.noneOf(SegmentMetadataQuery.AnalysisType.class),
        false,
        null,
        null
    );

    SegmentMetadataQuerySegmentWalker walker = Guice.createInjector(module)
                                                    .getInstance(SegmentMetadataQuerySegmentWalker.class);
    Set<String> actualSegmentIds = walker.getQueryRunnerForSegments(segmentMetadataQuery, segmentDescriptors)
                                         .run(QueryPlus.wrap(segmentMetadataQuery))
                                         .toList()
                                         .stream()
                                         .map(SegmentAnalysis::getId)
                                         .collect(Collectors.toSet());

    Set<String> expectedSegmentIds = serverExpectationsMap.values()
                                                          .stream()
                                                          .flatMap(serverExpectations -> Lists.newArrayList(
                                                              serverExpectations.iterator()).stream())
                                                          .map(ServerExpectation::getSegment)
                                                          .map(segment -> segment.getId().toString())
                                                          .collect(Collectors.toSet());
    Assert.assertEquals(expectedSegmentIds, actualSegmentIds);
  }

  @Test
  public void testQueryAppendedSegments() throws IOException
  {
    Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines = new HashMap<>();
    Map<String, QueryRunner> queryRunnerMap = new HashMap<>();

    // populate the core partition set
    populateTimeline(
        timelines,
        queryRunnerMap,
        Collections.singletonList(
            Pair.of(Intervals.of("2011-01-01/2011-01-02"), Lists.newArrayList(0, 4, 5))
        )
    );

    queryRunnerMap.clear();
    testCoordinatorServerView = new TestCoordinatorServerView(timelines, queryRunnerMap);

    // append 2 new segments
    Map<String, ServerExpectations> serverExpectationsMap =
        populateTimeline(
            timelines,
            queryRunnerMap,
            Collections.singletonList(
                Pair.of(Intervals.of("2011-01-01/2011-01-02"), Lists.newArrayList(5, 6, 5))
            )
        );

    List<SegmentDescriptor> segmentDescriptors =
        serverExpectationsMap.values()
                             .stream()
                             .flatMap(serverExpectations -> Lists.newArrayList(serverExpectations.iterator()).stream())
                             .map(ServerExpectation::getSegment)
                             .map(segment -> segment.getId().toDescriptor())
                             .collect(Collectors.toList());

    SegmentMetadataQuery segmentMetadataQuery = new SegmentMetadataQuery(
        new TableDataSource(DATASOURCE),
        new MultipleSpecificSegmentSpec(segmentDescriptors),
        new AllColumnIncluderator(),
        false,
        QueryContexts.override(Collections.emptyMap(), QueryContexts.BROKER_PARALLEL_MERGE_KEY, false),
        EnumSet.noneOf(SegmentMetadataQuery.AnalysisType.class),
        false,
        null,
        null
    );

    SegmentMetadataQuerySegmentWalker walker = Guice.createInjector(module)
                                                    .getInstance(SegmentMetadataQuerySegmentWalker.class);
    Set<String> actualSegmentIds = walker.getQueryRunnerForSegments(segmentMetadataQuery, segmentDescriptors)
                                         .run(QueryPlus.wrap(segmentMetadataQuery))
                                         .toList()
                                         .stream()
                                         .map(SegmentAnalysis::getId)
                                         .collect(Collectors.toSet());

    Set<String> expectedSegmentIds =
        serverExpectationsMap.values()
                             .stream()
                             .flatMap(serverExpectations -> Lists.newArrayList(
                                 serverExpectations.iterator()).stream())
                             .map(ServerExpectation::getSegment)
                             .map(segment -> segment.getId().toString())
                             .collect(Collectors.toSet());
    Assert.assertEquals(expectedSegmentIds, actualSegmentIds);
  }

  private Map<String, ServerExpectations> populateTimeline(
      final Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines,
      final Map<String, QueryRunner> queryRunnerMap,
      final List<Pair<Interval, List<Integer>>> intervalAndChunks
  )
  {
    VersionedIntervalTimeline<String, SegmentLoadInfo> timeline = new VersionedIntervalTimeline<>(Comparator.naturalOrder());
    timelines.put(DATASOURCE, timeline);

    Map<String, ServerExpectations> serverExpectationsMap = new HashMap<>();

    for (Pair<Interval, List<Integer>> intervalAndChunk : intervalAndChunks) {
      List<Integer> partitionDetails = intervalAndChunk.rhs;
      int startNum = partitionDetails.get(0);
      int endNum = partitionDetails.get(1);
      int corePartitions = partitionDetails.get(2);
      for (int partitionNum = startNum; partitionNum <= endNum; partitionNum++) {

        Interval interval = intervalAndChunk.lhs;
        int numChunks = endNum - startNum + 1;
        SegmentId segmentId = SegmentId.of(DATASOURCE, interval, "0", partitionNum);

        DataSegment mockSegment = EasyMock.mock(DataSegment.class);

        final ShardSpec shardSpec;
        if (corePartitions == 1) {
          shardSpec = new SingleDimensionShardSpec("dimAll", null, null, 0, 1);
        } else {
          String start = null;
          String end = null;
          if (partitionNum > 0) {
            start = String.valueOf(partitionNum);
          }
          if (partitionNum + 1 < numChunks) {
            end = String.valueOf(partitionNum + 1);
          }
          shardSpec = new SingleDimensionShardSpec("dim", start, end, partitionNum, corePartitions);
        }

        ServerExpectation<Object> expectation = new ServerExpectation<>(
            segmentId,
            interval,
            mockSegment,
            shardSpec,
            null
        );
        DruidServer server = servers[random.nextInt(servers.length)];

        EasyMock.expect(mockSegment.getShardSpec())
                .andReturn(shardSpec)
                .anyTimes();

        EasyMock.replay(mockSegment);

        serverExpectationsMap.computeIfAbsent(
            server.getName(),
            s -> new ServerExpectations(server, EasyMock.mock(QueryRunner.class))
        );

        SegmentLoadInfo segmentLoadInfo = new SegmentLoadInfo(expectation.getSegment());
        segmentLoadInfo.addServer(server.getMetadata());
        serverExpectationsMap.get(server.getName()).addExpectation(expectation);

        queryRunnerMap.computeIfAbsent(
            server.getName(),
            v -> serverExpectationsMap.get(server.getName()).getQueryRunner()
        );
        timeline.add(interval, "0", shardSpec.createChunk(segmentLoadInfo));
        timelines.put(DATASOURCE, timeline);
      }
    }

    for (ServerExpectations serverExpectations : serverExpectationsMap.values()) {
      QueryRunner queryRunner = serverExpectations.getQueryRunner();
      List<ServerExpectation> serverExpectationList = Lists.newArrayList(serverExpectations.iterator());

      EasyMock.expect(queryRunner.run(EasyMock.anyObject(QueryPlus.class), EasyMock.anyObject(ResponseContext.class)))
              .andReturn(Sequences.simple(toSegmentAnalysis(serverExpectationList)))
              .anyTimes();
      EasyMock.replay(queryRunner);
    }
    return serverExpectationsMap;
  }

  private List<SegmentAnalysis> toSegmentAnalysis(List<ServerExpectation> serverExpectationList)
  {
    List<SegmentAnalysis> segmentAnalyses = new ArrayList<>();

    for (ServerExpectation serverExpectation : serverExpectationList) {
      SegmentAnalysis mockSegmentAnalysis = EasyMock.mock(SegmentAnalysis.class);
      EasyMock.expect(mockSegmentAnalysis.getId()).andReturn(serverExpectation.getSegmentId().toString()).anyTimes();
      EasyMock.expect(mockSegmentAnalysis.compareTo(EasyMock.isA(SegmentAnalysis.class)))
              .andAnswer(new IAnswer<>()
              {
                @Override
                public Integer answer()
                {
                  SegmentAnalysis otherSegment = (SegmentAnalysis) EasyMock.getCurrentArguments()[0];
                  String thisId = mockSegmentAnalysis.getId();
                  String otherId = otherSegment.getId();
                  return thisId.compareTo(otherId);
                }
              }).anyTimes();
      EasyMock.replay(mockSegmentAnalysis);
      segmentAnalyses.add(mockSegmentAnalysis);
    }

    return segmentAnalyses;
  }

  private static class TestCoordinatorServerView extends CoordinatorServerView
  {
    private final Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines;
    private final Map<String, QueryRunner> queryRunnerMap;

    public TestCoordinatorServerView(
        final Map<String, VersionedIntervalTimeline<String, SegmentLoadInfo>> timelines,
        final Map<String, QueryRunner> queryRunnerMap
    )
    {
      super(
          Mockito.mock(ServerInventoryView.class),
          Mockito.mock(CoordinatorSegmentWatcherConfig.class),
          Mockito.mock(ServiceEmitter.class),
          Mockito.mock(DirectDruidClientFactory.class)
      );
      this.timelines = timelines;
      this.queryRunnerMap = queryRunnerMap;
    }

    @Override
    public QueryRunner getQueryRunner(String serverName)
    {
      return queryRunnerMap.get(serverName);
    }

    @Override
    public VersionedIntervalTimeline<String, SegmentLoadInfo> getTimeline(DataSource dataSource)
    {
      return timelines.get(Iterables.getOnlyElement(dataSource.getTableNames()));
    }
  }
}
