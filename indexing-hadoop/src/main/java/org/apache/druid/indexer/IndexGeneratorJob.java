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

package org.apache.druid.indexer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import org.apache.druid.common.guava.ThreadRenamingRunnable;
import org.apache.druid.data.input.InputRow;
import org.apache.druid.data.input.Row;
import org.apache.druid.data.input.Rows;
import org.apache.druid.indexer.hadoop.SegmentInputRow;
import org.apache.druid.indexer.path.DatasourcePathSpec;
import org.apache.druid.java.util.common.FileUtils;
import org.apache.druid.java.util.common.IAE;
import org.apache.druid.java.util.common.ISE;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.Execs;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.java.util.common.parsers.ParseException;
import org.apache.druid.query.aggregation.AggregatorFactory;
import org.apache.druid.segment.BaseProgressIndicator;
import org.apache.druid.segment.ProgressIndicator;
import org.apache.druid.segment.QueryableIndex;
import org.apache.druid.segment.column.ColumnFormat;
import org.apache.druid.segment.incremental.IncrementalIndex;
import org.apache.druid.segment.incremental.IncrementalIndexSchema;
import org.apache.druid.timeline.DataSegment;
import org.apache.druid.timeline.partition.NumberedShardSpec;
import org.apache.druid.timeline.partition.ShardSpec;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapreduce.Counter;
import org.apache.hadoop.mapreduce.Counters;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.joda.time.DateTime;
import org.joda.time.Interval;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 *
 */
public class IndexGeneratorJob implements Jobby
{
  private static final Logger log = new Logger(IndexGeneratorJob.class);

  public static List<DataSegmentAndIndexZipFilePath> getPublishedSegmentAndIndexZipFilePaths(HadoopDruidIndexerConfig config)
  {
    final Configuration conf = JobHelper.injectSystemProperties(new Configuration(), config);
    config.addJobProperties(conf);

    final ObjectMapper jsonMapper = HadoopDruidIndexerConfig.JSON_MAPPER;

    ImmutableList.Builder<DataSegmentAndIndexZipFilePath> publishedSegmentAndIndexZipFilePathsBuilder = ImmutableList.builder();

    final Path descriptorInfoDir = config.makeDescriptorInfoDir();

    try {
      FileSystem fs = descriptorInfoDir.getFileSystem(conf);

      for (FileStatus status : fs.listStatus(descriptorInfoDir)) {
        final DataSegmentAndIndexZipFilePath segmentAndIndexZipFilePath = jsonMapper.readValue((InputStream) fs.open(
            status.getPath()), DataSegmentAndIndexZipFilePath.class);
        publishedSegmentAndIndexZipFilePathsBuilder.add(segmentAndIndexZipFilePath);
        log.info(
            "Adding segment %s to the list of published segments",
            segmentAndIndexZipFilePath.getSegment().getId()
        );
      }
    }
    catch (FileNotFoundException e) {
      log.error(
          "[%s] SegmentDescriptorInfo is not found usually when indexing process did not produce any segments meaning"
          + " either there was no input data to process or all the input events were discarded due to some error",
          e.getMessage()
      );
      throw new RuntimeException(e);
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
    List<DataSegmentAndIndexZipFilePath> publishedSegmentAndIndexZipFilePaths = publishedSegmentAndIndexZipFilePathsBuilder.build();

    return publishedSegmentAndIndexZipFilePaths;
  }

  private final HadoopDruidIndexerConfig config;
  private IndexGeneratorStats jobStats;
  private Job job;

  public IndexGeneratorJob(
      HadoopDruidIndexerConfig config
  )
  {
    this.config = config;
    this.jobStats = new IndexGeneratorStats();
  }

  protected void setReducerClass(final Job job)
  {
    job.setReducerClass(IndexGeneratorReducer.class);
  }

  @Override
  public boolean run()
  {
    try {
      job = Job.getInstance(
          new Configuration(),
          StringUtils.format("%s-index-generator-%s", config.getDataSource(), config.getIntervals())
      );

      job.getConfiguration().set("io.sort.record.percent", "0.23");

      JobHelper.injectSystemProperties(job.getConfiguration(), config);
      config.addJobProperties(job);
      // inject druid properties like deep storage bindings
      JobHelper.injectDruidProperties(job.getConfiguration(), config);

      job.setMapperClass(IndexGeneratorMapper.class);
      job.setMapOutputValueClass(BytesWritable.class);

      SortableBytes.useSortableBytesAsMapOutputKey(job, IndexGeneratorPartitioner.class);

      int numReducers = Iterables.size(config.getAllBuckets().get());
      if (numReducers == 0) {
        throw new RuntimeException("No buckets?? seems there is no data to index.");
      }

      if (config.getSchema().getTuningConfig().getUseCombiner()) {
        job.setCombinerClass(IndexGeneratorCombiner.class);
        job.setCombinerKeyGroupingComparatorClass(BytesWritable.Comparator.class);
      }

      job.setNumReduceTasks(numReducers);

      setReducerClass(job);
      job.setOutputKeyClass(BytesWritable.class);
      job.setOutputValueClass(Text.class);
      job.setOutputFormatClass(IndexGeneratorOutputFormat.class);
      FileOutputFormat.setOutputPath(job, config.makeIntermediatePath());

      config.addInputPaths(job);

      config.intoConfiguration(job);

      JobHelper.setupClasspath(
          JobHelper.distributedClassPath(config.getWorkingPath()),
          JobHelper.distributedClassPath(config.makeIntermediatePath()),
          job
      );

      job.submit();
      log.info("Job %s submitted, status available at %s", job.getJobName(), job.getTrackingURL());

      // Store the jobId in the file
      if (job.getJobID() != null) {
        JobHelper.writeJobIdToFile(config.getHadoopJobIdFileName(), job.getJobID().toString());
      }

      try {
        boolean success = job.waitForCompletion(true);

        Counters counters = job.getCounters();
        if (counters == null) {
          log.info("No counters found for job [%s]", job.getJobName());
        } else {
          Counter invalidRowCount = counters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.INVALID_ROW_COUNTER);
          if (invalidRowCount != null) {
            jobStats.setInvalidRowCount(invalidRowCount.getValue());
          } else {
            log.info("No invalid row counter found for job [%s]", job.getJobName());
          }
        }

        return success;
      }
      catch (IOException ioe) {
        if (!Utils.checkAppSuccessForJobIOException(ioe, job, config.isUseYarnRMJobStatusFallback())) {
          throw ioe;
        } else {
          return true;
        }
      }

    }
    catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public Map<String, Object> getStats()
  {
    if (job == null) {
      return null;
    }

    try {
      Counters jobCounters = job.getCounters();

      Map<String, Object> metrics = TaskMetricsUtils.makeIngestionRowMetrics(
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_PROCESSED_COUNTER).getValue(),
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_PROCESSED_WITH_ERRORS_COUNTER)
                     .getValue(),
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_UNPARSEABLE_COUNTER).getValue(),
          jobCounters.findCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_THROWN_AWAY_COUNTER).getValue()
      );

      return metrics;
    }
    catch (IllegalStateException ise) {
      log.debug("Couldn't get counters due to job state");
      return null;
    }
    catch (Exception e) {
      log.debug(e, "Encountered exception in getStats().");
      return null;
    }
  }

  @Override
  public String getErrorMessage()
  {
    if (job == null) {
      return null;
    }

    return Utils.getFailureMessage(job, HadoopDruidIndexerConfig.JSON_MAPPER);
  }

  private static IncrementalIndex makeIncrementalIndex(
      Bucket theBucket,
      AggregatorFactory[] aggs,
      HadoopDruidIndexerConfig config,
      @Nullable Iterable<String> oldDimOrder,
      @Nullable Map<String, ColumnFormat> oldCapabilities
  )
  {
    final HadoopTuningConfig tuningConfig = config.getSchema().getTuningConfig();
    final IncrementalIndexSchema indexSchema = new IncrementalIndexSchema.Builder()
        .withMinTimestamp(theBucket.time.getMillis())
        .withTimestampSpec(config.getSchema().getDataSchema().getTimestampSpec())
        .withDimensionsSpec(config.getSchema().getDataSchema().getDimensionsSpec())
        .withQueryGranularity(config.getSchema().getDataSchema().getGranularitySpec().getQueryGranularity())
        .withMetrics(aggs)
        .withRollup(config.getSchema().getDataSchema().getGranularitySpec().isRollup())
        .build();

    // Build the incremental-index according to the spec that was chosen by the user
    IncrementalIndex newIndex = tuningConfig.getAppendableIndexSpec().builder()
                                            .setIndexSchema(indexSchema)
                                            .setMaxRowCount(tuningConfig.getMaxRowsInMemory())
                                            .setMaxBytesInMemory(tuningConfig.getMaxBytesInMemoryOrDefault())
                                            .build();

    if (oldDimOrder != null && !indexSchema.getDimensionsSpec().hasFixedDimensions()) {
      newIndex.loadDimensionIterable(oldDimOrder, oldCapabilities);
    }

    return newIndex;
  }

  public static class IndexGeneratorMapper extends HadoopDruidIndexerMapper<BytesWritable, BytesWritable>
  {
    private static final HashFunction HASH_FUNCTION = Hashing.murmur3_128();

    private AggregatorFactory[] aggregators;

    private AggregatorFactory[] aggsForSerializingSegmentInputRow;
    private Map<String, InputRowSerde.IndexSerdeTypeHelper> typeHelperMap;

    @Override
    protected void setup(Context context)
        throws IOException, InterruptedException
    {
      super.setup(context);
      aggregators = config.getSchema().getDataSchema().getAggregators();

      if (DatasourcePathSpec.checkIfReindexingAndIsUseAggEnabled(config.getSchema().getIOConfig().getPathSpec())) {
        aggsForSerializingSegmentInputRow = aggregators;
      } else {
        // Note: this is required for "delta-ingestion" use case where we are reading rows stored in Druid as well
        // as late arriving data on HDFS etc.
        aggsForSerializingSegmentInputRow = new AggregatorFactory[aggregators.length];
        for (int i = 0; i < aggregators.length; ++i) {
          aggsForSerializingSegmentInputRow[i] = aggregators[i].getCombiningFactory();
        }
      }
      typeHelperMap = InputRowSerde.getTypeHelperMap(config.getSchema().getDataSchema().getDimensionsSpec());
    }

    @Override
    protected void innerMap(
        InputRow inputRow,
        Context context
    ) throws IOException, InterruptedException
    {
      // Group by bucket, sort by timestamp
      final Optional<Bucket> bucket = getConfig().getBucket(inputRow);

      if (!bucket.isPresent()) {
        throw new ISE("No bucket found for row: %s", inputRow);
      }

      final long truncatedTimestamp = granularitySpec.getQueryGranularity()
                                                     .bucketStart(inputRow.getTimestamp())
                                                     .getMillis();
      final byte[] hashedDimensions = HASH_FUNCTION.hashBytes(
          HadoopDruidIndexerConfig.JSON_MAPPER.writeValueAsBytes(
              Rows.toGroupKey(
                  truncatedTimestamp,
                  inputRow
              )
          )
      ).asBytes();

      // type SegmentInputRow serves as a marker that these InputRow instances have already been combined
      // and they contain the columns as they show up in the segment after ingestion, not what you would see in raw
      // data
      InputRowSerde.SerializeResult serializeResult = inputRow instanceof SegmentInputRow ?
                                                      InputRowSerde.toBytes(
                                                          typeHelperMap,
                                                          inputRow,
                                                          aggsForSerializingSegmentInputRow
                                                      )
                                                                                          :
                                                      InputRowSerde.toBytes(
                                                          typeHelperMap,
                                                          inputRow,
                                                          aggregators
                                                      );

      context.write(
          new SortableBytes(
              bucket.get().toGroupKey(),
              // sort rows by truncated timestamp and hashed dimensions to help reduce spilling on the reducer side
              ByteBuffer.allocate(Long.BYTES + hashedDimensions.length)
                        .putLong(truncatedTimestamp)
                        .put(hashedDimensions)
                        .array()
          ).toBytesWritable(),
          new BytesWritable(serializeResult.getSerializedRow())
      );

      ParseException pe = IncrementalIndex.getCombinedParseException(
          inputRow,
          serializeResult.getParseExceptionMessages(),
          null
      );
      if (pe != null) {
        throw pe;
      } else {
        context.getCounter(HadoopDruidIndexerConfig.IndexJobCounters.ROWS_PROCESSED_COUNTER).increment(1);
      }
    }
  }

  public static class IndexGeneratorCombiner extends Reducer<BytesWritable, BytesWritable, BytesWritable, BytesWritable>
  {
    private HadoopDruidIndexerConfig config;
    private AggregatorFactory[] aggregators;
    private AggregatorFactory[] combiningAggs;
    private Map<String, InputRowSerde.IndexSerdeTypeHelper> typeHelperMap;

    @Override
    protected void setup(Context context)
    {
      config = HadoopDruidIndexerConfig.fromConfiguration(context.getConfiguration());

      aggregators = config.getSchema().getDataSchema().getAggregators();
      combiningAggs = new AggregatorFactory[aggregators.length];
      for (int i = 0; i < aggregators.length; ++i) {
        combiningAggs[i] = aggregators[i].getCombiningFactory();
      }
      typeHelperMap = InputRowSerde.getTypeHelperMap(config.getSchema().getDataSchema().getDimensionsSpec());
    }

    @Override
    protected void reduce(final BytesWritable key, Iterable<BytesWritable> values, final Context context)
        throws IOException, InterruptedException
    {
      Iterator<BytesWritable> iter = values.iterator();
      BytesWritable first = iter.next();

      if (iter.hasNext()) {
        LinkedHashSet<String> dimOrder = new LinkedHashSet<>();
        SortableBytes keyBytes = SortableBytes.fromBytesWritable(key);
        Bucket bucket = Bucket.fromGroupKey(keyBytes.getGroupKey()).lhs;
        IncrementalIndex index = makeIncrementalIndex(bucket, combiningAggs, config, null, null);
        index.add(InputRowSerde.fromBytes(typeHelperMap, first.getBytes(), aggregators));

        while (iter.hasNext()) {
          context.progress();
          InputRow value = InputRowSerde.fromBytes(typeHelperMap, iter.next().getBytes(), aggregators);

          if (!index.canAppendRow()) {
            dimOrder.addAll(index.getDimensionOrder());
            log.info("current index full due to [%s]. creating new index.", index.getOutOfRowsReason());
            flushIndexToContextAndClose(key, index, context);
            index = makeIncrementalIndex(bucket, combiningAggs, config, dimOrder, index.getColumnFormats());
          }

          index.add(value);
        }

        flushIndexToContextAndClose(key, index, context);
      } else {
        context.write(key, first);
      }
    }

    private void flushIndexToContextAndClose(BytesWritable key, IncrementalIndex index, Context context)
        throws IOException, InterruptedException
    {
      final List<String> dimensions = index.getDimensionNames(false);
      Iterator<Row> rows = index.iterator();
      while (rows.hasNext()) {
        context.progress();
        Row row = rows.next();
        InputRow inputRow = getInputRowFromRow(row, dimensions);

        // reportParseExceptions is true as any unparseable data is already handled by the mapper.
        InputRowSerde.SerializeResult serializeResult = InputRowSerde.toBytes(typeHelperMap, inputRow, combiningAggs);

        context.write(
            key,
            new BytesWritable(serializeResult.getSerializedRow())
        );
      }
      index.close();
    }

    private InputRow getInputRowFromRow(final Row row, final List<String> dimensions)
    {
      return new InputRow()
      {
        @Override
        public List<String> getDimensions()
        {
          return dimensions;
        }

        @Override
        public long getTimestampFromEpoch()
        {
          return row.getTimestampFromEpoch();
        }

        @Override
        public DateTime getTimestamp()
        {
          return row.getTimestamp();
        }

        @Override
        public List<String> getDimension(String dimension)
        {
          return row.getDimension(dimension);
        }

        @Override
        public Object getRaw(String dimension)
        {
          return row.getRaw(dimension);
        }

        @Override
        public Number getMetric(String metric)
        {
          return row.getMetric(metric);
        }

        @Override
        public int compareTo(Row o)
        {
          return row.compareTo(o);
        }
      };
    }
  }

  public static class IndexGeneratorPartitioner extends Partitioner<BytesWritable, Writable> implements Configurable
  {
    private Configuration config;

    @Override
    public int getPartition(BytesWritable bytesWritable, Writable value, int numPartitions)
    {
      final ByteBuffer bytes = ByteBuffer.wrap(bytesWritable.getBytes());
      bytes.position(4); // Skip length added by SortableBytes
      int shardNum = bytes.getInt();
      if ("local".equals(JobHelper.getJobTrackerAddress(config))) {
        return shardNum % numPartitions;
      } else {
        if (shardNum >= numPartitions) {
          throw new ISE("Not enough partitions, shard[%,d] >= numPartitions[%,d]", shardNum, numPartitions);
        }
        return shardNum;

      }
    }

    @Override
    public Configuration getConf()
    {
      return config;
    }

    @Override
    public void setConf(Configuration config)
    {
      this.config = config;
    }
  }

  public static class IndexGeneratorReducer extends Reducer<BytesWritable, BytesWritable, BytesWritable, Text>
  {
    protected HadoopDruidIndexerConfig config;
    private List<String> metricNames = new ArrayList<>();
    private AggregatorFactory[] aggregators;
    private AggregatorFactory[] combiningAggs;
    private Map<String, InputRowSerde.IndexSerdeTypeHelper> typeHelperMap;

    protected ProgressIndicator makeProgressIndicator(final Context context)
    {
      return new BaseProgressIndicator()
      {
        @Override
        public void progress()
        {
          super.progress();
          context.progress();
        }
      };
    }

    private File persist(
        final IncrementalIndex index,
        final Interval interval,
        final File file,
        final ProgressIndicator progressIndicator
    ) throws IOException
    {
      return HadoopDruidIndexerConfig.INDEX_MERGER_V9
          .persist(index, interval, file, config.getIndexSpecForIntermediatePersists(), progressIndicator, null);
    }

    protected File mergeQueryableIndex(
        final List<QueryableIndex> indexes,
        final AggregatorFactory[] aggs,
        final File file,
        ProgressIndicator progressIndicator
    ) throws IOException
    {
      boolean rollup = config.getSchema().getDataSchema().getGranularitySpec().isRollup();
      return HadoopDruidIndexerConfig.INDEX_MERGER_V9
          .mergeQueryableIndex(
              indexes,
              rollup,
              aggs,
              null,
              file,
              config.getIndexSpec(),
              config.getIndexSpecForIntermediatePersists(),
              progressIndicator,
              null,
              -1
          );
    }

    @Override
    protected void setup(Context context)
    {
      config = HadoopDruidIndexerConfig.fromConfiguration(context.getConfiguration());

      aggregators = config.getSchema().getDataSchema().getAggregators();
      combiningAggs = new AggregatorFactory[aggregators.length];
      for (int i = 0; i < aggregators.length; ++i) {
        metricNames.add(aggregators[i].getName());
        combiningAggs[i] = aggregators[i].getCombiningFactory();
      }
      typeHelperMap = InputRowSerde.getTypeHelperMap(config.getSchema().getDataSchema().getDimensionsSpec());
    }

    @Override
    protected void reduce(BytesWritable key, Iterable<BytesWritable> values, final Context context)
        throws IOException, InterruptedException
    {
      SortableBytes keyBytes = SortableBytes.fromBytesWritable(key);
      Bucket bucket = Bucket.fromGroupKey(keyBytes.getGroupKey()).lhs;

      final Interval interval = config.getGranularitySpec().bucketInterval(bucket.time).get();

      ListeningExecutorService persistExecutor = null;
      List<ListenableFuture<?>> persistFutures = new ArrayList<>();
      IncrementalIndex index = makeIncrementalIndex(
          bucket,
          combiningAggs,
          config,
          null,
          null
      );
      try {
        File baseFlushFile = FileUtils.createTempDir("base-flush");
        Set<File> toMerge = new TreeSet<>();
        int indexCount = 0;
        int lineCount = 0;
        int runningTotalLineCount = 0;
        long startTime = System.currentTimeMillis();

        Set<String> allDimensionNames = new LinkedHashSet<>();
        final ProgressIndicator progressIndicator = makeProgressIndicator(context);
        int numBackgroundPersistThreads = config.getSchema().getTuningConfig().getNumBackgroundPersistThreads();
        if (numBackgroundPersistThreads > 0) {
          final BlockingQueue<Runnable> queue = new SynchronousQueue<>();
          ExecutorService executorService = new ThreadPoolExecutor(
              numBackgroundPersistThreads,
              numBackgroundPersistThreads,
              0L,
              TimeUnit.MILLISECONDS,
              queue,
              Execs.makeThreadFactory("IndexGeneratorJob_persist_%d"),
              new RejectedExecutionHandler()
              {
                @Override
                public void rejectedExecution(Runnable r, ThreadPoolExecutor executor)
                {
                  try {
                    executor.getQueue().put(r);
                  }
                  catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RejectedExecutionException("Got Interrupted while adding to the Queue", e);
                  }
                }
              }
          );
          persistExecutor = MoreExecutors.listeningDecorator(executorService);
        } else {
          persistExecutor = Execs.directExecutor();
        }

        for (final BytesWritable bw : values) {
          context.progress();

          final InputRow inputRow = index.formatRow(InputRowSerde.fromBytes(typeHelperMap, bw.getBytes(), aggregators));
          int numRows = index.add(inputRow).getRowCount();

          ++lineCount;

          if (!index.canAppendRow()) {
            allDimensionNames.addAll(index.getDimensionNames(false));

            log.info(index.getOutOfRowsReason());
            log.info(
                "%,d lines to %,d rows in %,d millis",
                lineCount - runningTotalLineCount,
                numRows,
                System.currentTimeMillis() - startTime
            );
            runningTotalLineCount = lineCount;

            final File file = new File(baseFlushFile, StringUtils.format("index%,05d", indexCount));
            toMerge.add(file);

            context.progress();
            final IncrementalIndex persistIndex = index;
            persistFutures.add(
                persistExecutor.submit(
                    new ThreadRenamingRunnable(StringUtils.format("%s-persist", file.getName()))
                    {
                      @Override
                      public void doRun()
                      {
                        try {
                          persist(persistIndex, interval, file, progressIndicator);
                        }
                        catch (Exception e) {
                          log.error(e, "persist index error");
                          throw new RuntimeException(e);
                        }
                        finally {
                          // close this index
                          persistIndex.close();
                        }
                      }
                    }
                )
            );

            index = makeIncrementalIndex(
                bucket,
                combiningAggs,
                config,
                index.getDimensionOrder(),
                persistIndex.getColumnFormats()
            );
            startTime = System.currentTimeMillis();
            ++indexCount;
          }
        }

        allDimensionNames.addAll(index.getDimensionNames(false));

        log.info("%,d lines completed.", lineCount);

        List<QueryableIndex> indexes = Lists.newArrayListWithCapacity(indexCount);
        final File mergedBase;

        if (toMerge.size() == 0) {
          if (index.isEmpty()) {
            throw new IAE("If you try to persist empty indexes you are going to have a bad time");
          }

          mergedBase = new File(baseFlushFile, "merged");
          persist(index, interval, mergedBase, progressIndicator);
        } else {
          if (!index.isEmpty()) {
            final File finalFile = new File(baseFlushFile, "final");
            persist(index, interval, finalFile, progressIndicator);
            toMerge.add(finalFile);
          }

          Futures.allAsList(persistFutures).get(1, TimeUnit.HOURS);
          persistExecutor.shutdown();

          for (File file : toMerge) {
            indexes.add(HadoopDruidIndexerConfig.INDEX_IO.loadIndex(file));
          }

          log.info("starting merge of intermediate persisted segments.");
          long mergeStartTime = System.currentTimeMillis();
          mergedBase = mergeQueryableIndex(indexes, aggregators, new File(baseFlushFile, "merged"), progressIndicator);
          log.info(
              "finished merge of intermediate persisted segments. time taken [%d] ms.",
              (System.currentTimeMillis() - mergeStartTime)
          );
        }
        final FileSystem outputFS = new Path(config.getSchema().getIOConfig().getSegmentOutputPath())
            .getFileSystem(context.getConfiguration());

        // ShardSpec used for partitioning within this Hadoop job.
        final ShardSpec shardSpecForPartitioning = config.getShardSpec(bucket).getActualSpec();

        // ShardSpec to be published.
        final ShardSpec shardSpecForPublishing;
        if (config.isForceExtendableShardSpecs()) {
          shardSpecForPublishing = new NumberedShardSpec(
              shardSpecForPartitioning.getPartitionNum(),
              config.getShardSpecCount(bucket)
          );
        } else {
          shardSpecForPublishing = shardSpecForPartitioning;
        }

        final DataSegment segmentTemplate = new DataSegment(
            config.getDataSource(),
            interval,
            config.getSchema().getTuningConfig().getVersion(),
            null,
            ImmutableList.copyOf(allDimensionNames),
            metricNames,
            shardSpecForPublishing,
            -1,
            0
        );

        final DataSegmentAndIndexZipFilePath segmentAndIndexZipFilePath = JobHelper.serializeOutIndex(
            segmentTemplate,
            context.getConfiguration(),
            context,
            mergedBase,
            JobHelper.makeFileNamePath(
                new Path(config.getSchema().getIOConfig().getSegmentOutputPath()),
                outputFS,
                segmentTemplate,
                JobHelper.INDEX_ZIP,
                HadoopDruidIndexerConfig.DATA_SEGMENT_PUSHER
            ),
            JobHelper.makeTmpPath(
                new Path(config.getSchema().getIOConfig().getSegmentOutputPath()),
                outputFS,
                segmentTemplate,
                context.getTaskAttemptID(),
                HadoopDruidIndexerConfig.DATA_SEGMENT_PUSHER
            ),
            HadoopDruidIndexerConfig.DATA_SEGMENT_PUSHER
        );

        Path descriptorPath = config.makeDescriptorInfoPath(segmentAndIndexZipFilePath.getSegment());
        descriptorPath = JobHelper.prependFSIfNullScheme(
            FileSystem.get(
                descriptorPath.toUri(),
                context.getConfiguration()
            ), descriptorPath
        );

        log.info("Writing descriptor to path[%s]", descriptorPath);
        JobHelper.writeSegmentDescriptor(
            config.makeDescriptorInfoDir().getFileSystem(context.getConfiguration()),
            segmentAndIndexZipFilePath,
            descriptorPath,
            context
        );
        for (File file : toMerge) {
          FileUtils.deleteDirectory(file);
        }
      }
      catch (ExecutionException | TimeoutException e) {
        throw new RuntimeException(e);
      }
      finally {
        index.close();
        if (persistExecutor != null) {
          persistExecutor.shutdownNow();
        }
      }
    }
  }

  public static class IndexGeneratorOutputFormat extends TextOutputFormat
  {
    @Override
    public void checkOutputSpecs(JobContext job) throws IOException
    {
      Path outDir = getOutputPath(job);
      if (outDir == null) {
        throw new InvalidJobConfException("Output directory not set.");
      }
    }
  }

  public static class IndexGeneratorStats
  {
    private long invalidRowCount = 0;

    @SuppressWarnings("unused") // Unused now, but useful in theory, probably needs to be exposed to users.
    public long getInvalidRowCount()
    {
      return invalidRowCount;
    }

    public void setInvalidRowCount(long invalidRowCount)
    {
      this.invalidRowCount = invalidRowCount;
    }
  }
}
