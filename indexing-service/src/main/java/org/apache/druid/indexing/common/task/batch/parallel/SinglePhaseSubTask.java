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

package org.apache.druid.indexing.common.task.batch.parallel;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.InputSource;
import org.apache.druid.indexer.IngestionState;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexer.granularity.ArbitraryGranularitySpec;
import org.apache.druid.indexer.granularity.GranularitySpec;
import org.apache.druid.indexer.partitions.DynamicPartitionsSpec;
import org.apache.druid.indexer.report.TaskReport;
import org.apache.druid.indexing.common.TaskRealtimeMetricsMonitorBuilder;
import org.apache.druid.indexing.common.TaskToolbox;
import org.apache.druid.indexing.common.actions.SurrogateTaskActionClient;
import org.apache.druid.indexing.common.actions.TaskActionClient;
import org.apache.druid.indexing.common.stats.TaskRealtimeMetricsMonitor;
import org.apache.druid.indexing.common.task.AbstractBatchIndexTask;
import org.apache.druid.indexing.common.task.AbstractTask;
import org.apache.druid.indexing.common.task.BatchAppenderators;
import org.apache.druid.indexing.common.task.IndexTask;
import org.apache.druid.indexing.common.task.SegmentAllocatorForBatch;
import org.apache.druid.indexing.common.task.SegmentAllocators;
import org.apache.druid.indexing.common.task.TaskResource;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.granularity.Granularity;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.common.parsers.CloseableIterator;
import org.apache.druid.segment.DataSegmentsWithSchemas;
import org.apache.druid.segment.SegmentSchemaMapping;
import org.apache.druid.segment.incremental.ParseExceptionHandler;
import org.apache.druid.segment.incremental.ParseExceptionReport;
import org.apache.druid.segment.incremental.RowIngestionMeters;
import org.apache.druid.segment.indexing.DataSchema;
import org.apache.druid.segment.metadata.CentralizedDatasourceSchemaConfig;
import org.apache.druid.segment.realtime.ChatHandler;
import org.apache.druid.segment.realtime.SegmentGenerationMetrics;
import org.apache.druid.segment.realtime.appenderator.Appenderator;
import org.apache.druid.segment.realtime.appenderator.AppenderatorDriverAddResult;
import org.apache.druid.segment.realtime.appenderator.BaseAppenderatorDriver;
import org.apache.druid.segment.realtime.appenderator.BatchAppenderatorDriver;
import org.apache.druid.segment.realtime.appenderator.SegmentsAndCommitMetadata;
import org.apache.druid.server.security.AuthorizationUtils;
import org.apache.druid.server.security.AuthorizerMapper;
import org.apache.druid.server.security.ResourceAction;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.SegmentTimeline;
import org.apache.druid.timeline.TimelineObjectHolder;
import org.apache.druid.timeline.partition.PartitionChunk;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.joda.time.Interval;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * The worker task of {@link SinglePhaseParallelIndexTaskRunner}. Similar to {@link IndexTask}, but this task
 * generates and pushes segments, and reports them to the {@link SinglePhaseParallelIndexTaskRunner} instead of
 * publishing on its own.
 */
public class SinglePhaseSubTask extends AbstractBatchSubtask implements ChatHandler
{
  public static final String TYPE = "single_phase_sub_task";
  public static final String OLD_TYPE_NAME = "index_sub";

  private static final Logger LOG = new Logger(SinglePhaseSubTask.class);

  private final int numAttempts;
  private final ParallelIndexIngestionSpec ingestionSchema;
  private final String subtaskSpecId;

  /**
   * If intervals are missing in the granularitySpec, parallel index task runs in "dynamic locking mode".
   * In this mode, sub tasks ask new locks whenever they see a new row which is not covered by existing locks.
   * If this task is overwriting existing segments, then we should know this task is changing segment granularity
   * in advance to know what types of lock we should use. However, if intervals are missing, we can't know
   * the segment granularity of existing segments until the task reads all data because we don't know what segments
   * are going to be overwritten. As a result, we assume that segment granularity is going to be changed if intervals
   * are missing and force to use timeChunk lock.
   * <p>
   * This variable is initialized in the constructor and used in {@link #run} to log that timeChunk lock was enforced
   * in the task logs.
   */
  private final boolean missingIntervalsInOverwriteMode;

  @MonotonicNonNull
  private AuthorizerMapper authorizerMapper;

  @MonotonicNonNull
  private RowIngestionMeters rowIngestionMeters;

  @MonotonicNonNull
  private ParseExceptionHandler parseExceptionHandler;

  @Nullable
  private String errorMsg;

  private IngestionState ingestionState;

  @JsonCreator
  public SinglePhaseSubTask(
      // id shouldn't be null except when this task is created by ParallelIndexSupervisorTask
      @JsonProperty("id") @Nullable final String id,
      @JsonProperty("groupId") final String groupId,
      @JsonProperty("resource") final TaskResource taskResource,
      @JsonProperty("supervisorTaskId") final String supervisorTaskId,
      // subtaskSpecId can be null only for old task versions.
      @JsonProperty("subtaskSpecId") @Nullable final String subtaskSpecId,
      @JsonProperty("numAttempts") final int numAttempts, // zero-based counting
      @JsonProperty("spec") final ParallelIndexIngestionSpec ingestionSchema,
      @JsonProperty("context") final Map<String, Object> context
  )
  {
    super(
        getOrMakeId(id, TYPE, ingestionSchema.getDataSchema().getDataSource()),
        groupId,
        taskResource,
        ingestionSchema.getDataSchema().getDataSource(),
        context,
        AbstractTask.computeBatchIngestionMode(ingestionSchema.getIOConfig()),
        supervisorTaskId
    );

    if (ingestionSchema.getTuningConfig().isForceGuaranteedRollup()) {
      throw new UnsupportedOperationException("Guaranteed rollup is not supported");
    }

    this.subtaskSpecId = subtaskSpecId;
    this.numAttempts = numAttempts;
    this.ingestionSchema = ingestionSchema;
    this.missingIntervalsInOverwriteMode = ingestionSchema.getIOConfig().isAppendToExisting() != true
                                           && ingestionSchema.getDataSchema()
                                                             .getGranularitySpec()
                                                             .inputIntervals()
                                                             .isEmpty();
    if (missingIntervalsInOverwriteMode) {
      addToContext(Tasks.FORCE_TIME_CHUNK_LOCK_KEY, true);
    }
    this.ingestionState = IngestionState.NOT_STARTED;
  }

  @Override
  public String getType()
  {
    return TYPE;
  }

  @Nonnull
  @JsonIgnore
  @Override
  public Set<ResourceAction> getInputSourceResources()
  {
    return getIngestionSchema().getIOConfig().getInputSource() != null ?
           getIngestionSchema().getIOConfig().getInputSource().getTypes()
                               .stream()
                               .map(AuthorizationUtils::createExternalResourceReadAction)
                               .collect(Collectors.toSet()) :
           ImmutableSet.of();
  }

  @Override
  public boolean isReady(TaskActionClient taskActionClient) throws IOException
  {
    return determineLockGranularityAndTryLock(
        new SurrogateTaskActionClient(getSupervisorTaskId(), taskActionClient),
        ingestionSchema.getDataSchema().getGranularitySpec().inputIntervals()
    );
  }

  @JsonProperty
  public int getNumAttempts()
  {
    return numAttempts;
  }

  @JsonProperty("spec")
  public ParallelIndexIngestionSpec getIngestionSchema()
  {
    return ingestionSchema;
  }

  @Override
  @JsonProperty
  public String getSubtaskSpecId()
  {
    return subtaskSpecId;
  }

  @Override
  public TaskStatus runTask(final TaskToolbox toolbox) throws Exception
  {
    try {
      if (missingIntervalsInOverwriteMode) {
        LOG.warn(
            "Intervals are missing in granularitySpec while this task is potentially overwriting existing segments. "
            + "Forced to use timeChunk lock."
        );
      }
      this.authorizerMapper = toolbox.getAuthorizerMapper();

      toolbox.getChatHandlerProvider().register(getId(), this, false);

      rowIngestionMeters = toolbox.getRowIngestionMetersFactory().createRowIngestionMeters();
      parseExceptionHandler = new ParseExceptionHandler(
          rowIngestionMeters,
          ingestionSchema.getTuningConfig().isLogParseExceptions(),
          ingestionSchema.getTuningConfig().getMaxParseExceptions(),
          ingestionSchema.getTuningConfig().getMaxSavedParseExceptions()
      );

      final InputSource inputSource = ingestionSchema.getIOConfig().getNonNullInputSource(toolbox);

      final ParallelIndexSupervisorTaskClient taskClient = toolbox.getSupervisorTaskClientProvider().build(
          getSupervisorTaskId(),
          ingestionSchema.getTuningConfig().getChatHandlerTimeout(),
          ingestionSchema.getTuningConfig().getChatHandlerNumRetries()
      );
      ingestionState = IngestionState.BUILD_SEGMENTS;
      final DataSegmentsWithSchemas dataSegmentsWithSchemas = generateAndPushSegments(
          toolbox,
          taskClient,
          inputSource,
          toolbox.getIndexingTmpDir()
      );
      
      // Find inputSegments overshadowed by pushedSegments
      final Set<DataSegment> allSegments = new HashSet<>(getTaskLockHelper().getLockedExistingSegments());
      allSegments.addAll(dataSegmentsWithSchemas.getSegments());
      final SegmentTimeline timeline = SegmentTimeline.forSegments(allSegments);
      final Set<DataSegment> oldSegments = FluentIterable.from(timeline.findFullyOvershadowed())
                                                         .transformAndConcat(TimelineObjectHolder::getObject)
                                                         .transform(PartitionChunk::getObject)
                                                         .toSet();

      TaskReport.ReportMap taskReport = getTaskCompletionReports();
      taskClient.report(new PushedSegmentsReport(getId(), oldSegments, dataSegmentsWithSchemas.getSegments(), taskReport, dataSegmentsWithSchemas.getSegmentSchemaMapping()));

      toolbox.getTaskReportFileWriter().write(getId(), taskReport);

      return TaskStatus.success(getId());
    }
    catch (Exception e) {
      LOG.error(e, "Encountered exception in parallel sub task.");
      errorMsg = Throwables.getStackTraceAsString(e);
      toolbox.getTaskReportFileWriter().write(getId(), getTaskCompletionReports());
      return TaskStatus.failure(
          getId(),
          errorMsg
      );
    }
    finally {
      toolbox.getChatHandlerProvider().unregister(getId());
    }
  }

  @Override
  public boolean requireLockExistingSegments()
  {
    return getIngestionMode() != IngestionMode.APPEND;
  }

  @Override
  public List<DataSegment> findSegmentsToLock(TaskActionClient taskActionClient, List<Interval> intervals)
      throws IOException
  {
    return findInputSegments(
        getDataSource(),
        taskActionClient,
        intervals
    );
  }

  @Override
  public boolean isPerfectRollup()
  {
    return false;
  }

  @Nullable
  @Override
  public Granularity getSegmentGranularity()
  {
    final GranularitySpec granularitySpec = ingestionSchema.getDataSchema().getGranularitySpec();
    if (granularitySpec instanceof ArbitraryGranularitySpec) {
      return null;
    } else {
      return granularitySpec.getSegmentGranularity();
    }
  }

  /**
   * This method reads input data row by row and adds the read row to a proper segment using {@link BaseAppenderatorDriver}.
   * If there is no segment for the row, a new one is created.  Segments can be published in the middle of reading inputs
   * if one of below conditions are satisfied.
   *
   * <ul>
   * <li>
   * If the number of rows in a segment exceeds {@link DynamicPartitionsSpec#maxRowsPerSegment}
   * </li>
   * <li>
   * If the number of rows added to {@link BaseAppenderatorDriver} so far exceeds {@link DynamicPartitionsSpec#maxTotalRows}
   * </li>
   * </ul>
   * <p>
   * At the end of this method, all the remaining segments are published.
   *
   * @return true if generated segments are successfully published, otherwise false
   */
  private DataSegmentsWithSchemas generateAndPushSegments(
      final TaskToolbox toolbox,
      final ParallelIndexSupervisorTaskClient taskClient,
      final InputSource inputSource,
      final File tmpDir
  ) throws IOException, InterruptedException
  {
    final DataSchema dataSchema = ingestionSchema.getDataSchema();
    final GranularitySpec granularitySpec = dataSchema.getGranularitySpec();
    final SegmentGenerationMetrics segmentGenerationMetrics = new SegmentGenerationMetrics();
    final TaskRealtimeMetricsMonitor metricsMonitor =
        TaskRealtimeMetricsMonitorBuilder.build(this, segmentGenerationMetrics, rowIngestionMeters);
    toolbox.addMonitor(metricsMonitor);

    final ParallelIndexTuningConfig tuningConfig = ingestionSchema.getTuningConfig();
    final DynamicPartitionsSpec partitionsSpec = (DynamicPartitionsSpec) tuningConfig.getGivenOrDefaultPartitionsSpec();
    final long pushTimeout = tuningConfig.getPushTimeout();
    final boolean useLineageBasedSegmentAllocation = getContextValue(
        SinglePhaseParallelIndexTaskRunner.CTX_USE_LINEAGE_BASED_SEGMENT_ALLOCATION_KEY,
        SinglePhaseParallelIndexTaskRunner.LEGACY_DEFAULT_USE_LINEAGE_BASED_SEGMENT_ALLOCATION
    );
    // subtaskSpecId is used as the sequenceName, so that retry tasks for the same spec
    // can allocate the same set of segments.
    final String sequenceName = useLineageBasedSegmentAllocation
                                ? Preconditions.checkNotNull(subtaskSpecId, "subtaskSpecId")
                                : getId();
    final SegmentAllocatorForBatch segmentAllocator = SegmentAllocators.forLinearPartitioning(
        toolbox,
        sequenceName,
        new SupervisorTaskAccess(getSupervisorTaskId(), taskClient),
        getIngestionSchema().getDataSchema(),
        getTaskLockHelper(),
        getIngestionMode(),
        partitionsSpec,
        useLineageBasedSegmentAllocation
    );

    final Appenderator appenderator = BatchAppenderators.newAppenderator(
        getId(),
        toolbox.getAppenderatorsManager(),
        segmentGenerationMetrics,
        toolbox,
        dataSchema,
        tuningConfig,
        rowIngestionMeters,
        parseExceptionHandler
    );
    boolean exceptionOccurred = false;
    try (
        final BatchAppenderatorDriver driver = BatchAppenderators.newDriver(appenderator, toolbox, segmentAllocator);
        final CloseableIterator<InputRow> inputRowIterator = AbstractBatchIndexTask.inputSourceReader(
            tmpDir,
            dataSchema,
            inputSource,
            inputSource.needsFormat() ? ParallelIndexSupervisorTask.getInputFormat(ingestionSchema) : null,
            allowNonNullRowsWithinInputIntervalsOf(granularitySpec),
            rowIngestionMeters,
            parseExceptionHandler
        )
    ) {
      driver.startJob();

      final Set<DataSegment> pushedSegments = new HashSet<>();
      final SegmentSchemaMapping segmentSchemaMapping = new SegmentSchemaMapping(CentralizedDatasourceSchemaConfig.SCHEMA_VERSION);

      while (inputRowIterator.hasNext()) {
        final InputRow inputRow = inputRowIterator.next();

        // Segments are created as needed, using a single sequence name. They may be allocated from the overlord
        // (in append mode) or may be created on our own authority (in overwrite mode).
        final AppenderatorDriverAddResult addResult = driver.add(inputRow, sequenceName);

        if (addResult.isOk()) {
          final boolean isPushRequired = addResult.isPushRequired(
              partitionsSpec.getMaxRowsPerSegment(),
              partitionsSpec.getMaxTotalRowsOr(DynamicPartitionsSpec.DEFAULT_MAX_TOTAL_ROWS)
          );
          if (isPushRequired) {
            // There can be some segments waiting for being published even though any rows won't be added to them.
            // If those segments are not published here, the available space in appenderator will be kept to be small
            // which makes the size of segments smaller.
            final SegmentsAndCommitMetadata pushed = driver.pushAllAndClear(pushTimeout);
            pushedSegments.addAll(pushed.getSegments());
            segmentSchemaMapping.merge(pushed.getSegmentSchemaMapping());
            LOG.info("Pushed [%s] segments and [%s] schemas", pushed.getSegments().size(), segmentSchemaMapping.getSchemaCount());
            LOG.infoSegments(pushed.getSegments(), "Pushed segments");
            LOG.info("SegmentSchema is [%s]", segmentSchemaMapping);
          }
        } else {
          throw new ISE("Failed to add a row with timestamp[%s]", inputRow.getTimestamp());
        }
      }

      final SegmentsAndCommitMetadata pushed = driver.pushAllAndClear(pushTimeout);
      pushedSegments.addAll(pushed.getSegments());
      segmentSchemaMapping.merge(pushed.getSegmentSchemaMapping());
      LOG.info("Pushed [%s] segments and [%s] schemas", pushed.getSegments().size(), segmentSchemaMapping.getSchemaCount());
      LOG.infoSegments(pushed.getSegments(), "Pushed segments");
      LOG.info("SegmentSchema is [%s]", segmentSchemaMapping);
      appenderator.close();

      return new DataSegmentsWithSchemas(pushedSegments, segmentSchemaMapping.isNonEmpty() ? segmentSchemaMapping : null);
    }
    catch (TimeoutException | ExecutionException e) {
      exceptionOccurred = true;
      throw new RuntimeException(e);
    }
    catch (Exception e) {
      exceptionOccurred = true;
      throw e;
    }
    finally {
      if (exceptionOccurred) {
        appenderator.closeNow();
      } else {
        appenderator.close();
      }
      toolbox.removeMonitor(metricsMonitor);
    }
  }

  @GET
  @Path("/unparseableEvents")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getUnparseableEvents(
      @Context final HttpServletRequest req,
      @QueryParam("full") String full
  )
  {
    AuthorizationUtils.verifyUnrestrictedAccessToDatasource(req, getDataSource(), authorizerMapper);
    Map<String, List<ParseExceptionReport>> events = new HashMap<>();

    if (addBuildSegmentStatsToReport(full != null, ingestionState)) {
      events.put(
          RowIngestionMeters.BUILD_SEGMENTS,
          parseExceptionHandler.getSavedParseExceptionReports()
      );
    }

    return Response.ok(events).build();
  }

  private Map<String, Object> doGetRowStats(boolean isFullReport)
  {
    Map<String, Object> returnMap = new HashMap<>();
    Map<String, Object> totalsMap = new HashMap<>();
    Map<String, Object> averagesMap = new HashMap<>();

    if (addBuildSegmentStatsToReport(isFullReport, ingestionState)) {
      totalsMap.put(
          RowIngestionMeters.BUILD_SEGMENTS,
          rowIngestionMeters.getTotals()
      );
      averagesMap.put(
          RowIngestionMeters.BUILD_SEGMENTS,
          rowIngestionMeters.getMovingAverages()
      );
    }

    returnMap.put("totals", totalsMap);
    returnMap.put("movingAverages", averagesMap);
    return returnMap;
  }

  @GET
  @Path("/rowStats")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getRowStats(
      @Context final HttpServletRequest req,
      @QueryParam("full") String full
  )
  {
    AuthorizationUtils.verifyUnrestrictedAccessToDatasource(req, getDataSource(), authorizerMapper);
    return Response.ok(doGetRowStats(full != null)).build();
  }

  TaskReport.ReportMap doGetLiveReports(boolean isFullReport)
  {
    return buildLiveIngestionStatsReport(
        ingestionState,
        getTaskCompletionUnparseableEvents(),
        doGetRowStats(isFullReport)
    );
  }

  @GET
  @Path("/liveReports")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getLiveReports(
      @Context final HttpServletRequest req,
      @QueryParam("full") String full
  )
  {
    AuthorizationUtils.verifyUnrestrictedAccessToDatasource(req, getDataSource(), authorizerMapper);
    return Response.ok(doGetLiveReports(full != null)).build();
  }

  @Override
  protected Map<String, Object> getTaskCompletionRowStats()
  {
    return Collections.singletonMap(
        RowIngestionMeters.BUILD_SEGMENTS,
        rowIngestionMeters.getTotals()
    );
  }

  /**
   * Generate an IngestionStatsAndErrorsTaskReport for the task.
   */
  private TaskReport.ReportMap getTaskCompletionReports()
  {
    return buildIngestionStatsReport(IngestionState.COMPLETED, errorMsg, null, null);
  }

  @Override
  protected Map<String, Object> getTaskCompletionUnparseableEvents()
  {
    List<ParseExceptionReport> parseExceptionMessages = Objects.requireNonNullElse(
        parseExceptionHandler.getSavedParseExceptionReports(),
        List.of()
    );

    return Map.of(RowIngestionMeters.BUILD_SEGMENTS, parseExceptionMessages);
  }
}
