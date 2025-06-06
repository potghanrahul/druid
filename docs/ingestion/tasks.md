---
id: tasks
title: Task reference
sidebar_label: Task reference
---

<!--
  ~ Licensed to the Apache Software Foundation (ASF) under one
  ~ or more contributor license agreements.  See the NOTICE file
  ~ distributed with this work for additional information
  ~ regarding copyright ownership.  The ASF licenses this file
  ~ to you under the Apache License, Version 2.0 (the
  ~ "License"); you may not use this file except in compliance
  ~ with the License.  You may obtain a copy of the License at
  ~
  ~   http://www.apache.org/licenses/LICENSE-2.0
  ~
  ~ Unless required by applicable law or agreed to in writing,
  ~ software distributed under the License is distributed on an
  ~ "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  ~ KIND, either express or implied.  See the License for the
  ~ specific language governing permissions and limitations
  ~ under the License.
  -->

Tasks do all [ingestion](index.md)-related work in Druid.

For batch ingestion, you will generally submit tasks directly to Druid using the
[Tasks APIs](../api-reference/tasks-api.md). For streaming ingestion, tasks are generally submitted for you by a
supervisor.

## Task API

Task APIs are available in two main places:

- The [Overlord](../design/overlord.md) process offers HTTP APIs to submit tasks, cancel tasks, check their status,
review logs and reports, and more. Refer to the [Tasks API reference](../api-reference/tasks-api.md) for a
full list.
- Druid SQL includes a [`sys.tasks`](../querying/sql-metadata-tables.md#tasks-table) table that provides information about active
and recently completed tasks. This table is read-only and has a subset of the full task report available through
the Overlord APIs.

<a name="reports"></a>

## Task reports

A report containing information about the number of rows ingested, and any parse exceptions that occurred is available for both completed tasks and running tasks.

The reporting feature is supported by [native batch tasks](native-batch.md), the Hadoop batch task, and Kafka and Kinesis ingestion tasks.

### Completion report

After a task completes, if it supports reports, its report can be retrieved at:

```
http://<OVERLORD-HOST>:<OVERLORD-PORT>/druid/indexer/v1/task/{taskId}/reports
```

An example output is shown below:

```json
{
  "ingestionStatsAndErrors": {
    "taskId": "compact_twitter_2018-09-24T18:24:23.920Z",
    "payload": {
      "ingestionState": "COMPLETED",
      "unparseableEvents": {},
      "rowStats": {
        "determinePartitions": {
          "processed": 0,
          "processedBytes": 0,
          "processedWithError": 0,
          "thrownAway": 0,
          "unparseable": 0
        },
        "buildSegments": {
          "processed": 5390324,
          "processedBytes": 5109573212,
          "processedWithError": 0,
          "thrownAway": 0,
          "unparseable": 0
        }
      },
      "segmentAvailabilityConfirmed": false,
      "segmentAvailabilityWaitTimeMs": 0,
      "recordsProcessed": {
        "partition-a": 5789
      },
      "errorMsg": null
    },
    "type": "ingestionStatsAndErrors"
  },
  "taskContext": {
    "type": "taskContext",
    "taskId": "compact_twitter_2018-09-24T18:24:23.920Z",
    "payload": {
      "forceTimeChunkLock": true,
      "useLineageBasedSegmentAllocation": true
    }
  }
}
```

Compaction tasks can generate multiple sets of segment output reports based on how the input interval is split. So the overall report contains mappings from each split to each report.
Example report could be:

```json
{
  "ingestionStatsAndErrors_0": {
    "taskId": "compact_twitter_2018-09-24T18:24:23.920Z",
    "payload": {
      "ingestionState": "COMPLETED",
      "unparseableEvents": {},
      "rowStats": {
        "buildSegments": {
          "processed": 5390324,
          "processedBytes": 5109573212,
          "processedWithError": 0,
          "thrownAway": 0,
          "unparseable": 0
        }
      },
      "segmentAvailabilityConfirmed": false,
      "segmentAvailabilityWaitTimeMs": 0,
      "recordsProcessed": null,
      "errorMsg": null
    },
    "type": "ingestionStatsAndErrors"
  },
  "ingestionStatsAndErrors_1": {
   "taskId": "compact_twitter_2018-09-25T18:24:23.920Z",
   "payload": {
    "ingestionState": "COMPLETED",
    "unparseableEvents": {},
    "rowStats": {
     "buildSegments": {
      "processed": 12345,
      "processedBytes": 132456789,
      "processedWithError": 0,
      "thrownAway": 0,
      "unparseable": 0
     }
    },
    "segmentAvailabilityConfirmed": false,
    "segmentAvailabilityWaitTimeMs": 0,
    "recordsProcessed": null,
    "errorMsg": null
   },
   "type": "ingestionStatsAndErrors"
  }
}
```



#### Segment Availability Fields

For some task types, the indexing task can wait for the newly ingested segments to become available for queries after ingestion completes. The below fields inform the end user regarding the duration and result of the availability wait. For batch ingestion task types, refer to `tuningConfig` docs to see if the task supports an availability waiting period.

|Field|Description|
|---|---|
|`segmentAvailabilityConfirmed`|Whether all segments generated by this ingestion task had been confirmed as available for queries in the cluster before the task completed.|
|`segmentAvailabilityWaitTimeMs`|Milliseconds waited by the ingestion task for the newly ingested segments to be available for query after completing ingestion was completed.|
|`recordsProcessed`| Partitions that were processed by an ingestion task and includes count of records processed from each partition.|


#### Compaction task segment info fields

|Field|Description|
|---|---|
|`segmentsRead`|Number of segments read by compaction task.|
|`segmentsPublished`|Number of segments published by compaction task.|

### Live report

When a task is running, a live report containing ingestion state, unparseable events and moving average for number of events processed for 1 min, 5 min, 15 min time window can be retrieved at:

```
http://<OVERLORD-HOST>:<OVERLORD-PORT>/druid/indexer/v1/task/{taskId}/reports
```

An example output is shown below:

```json
{
  "ingestionStatsAndErrors": {
    "taskId": "compact_twitter_2018-09-24T18:24:23.920Z",
    "payload": {
      "ingestionState": "RUNNING",
      "unparseableEvents": {},
      "rowStats": {
        "movingAverages": {
          "buildSegments": {
            "5m": {
              "processed": 3.392158326408501,
              "processedBytes": 627.5492903856,
              "unparseable": 0,
              "thrownAway": 0,
              "processedWithError": 0
            },
            "15m": {
              "processed": 1.736165476881023,
              "processedBytes": 321.1906130223,
              "unparseable": 0,
              "thrownAway": 0,
              "processedWithError": 0
            },
            "1m": {
              "processed": 4.206417693750045,
              "processedBytes": 778.1872733438,
              "unparseable": 0,
              "thrownAway": 0,
              "processedWithError": 0
            }
          }
        },
        "totals": {
          "buildSegments": {
            "processed": 1994,
            "processedBytes": 3425110,
            "processedWithError": 0,
            "thrownAway": 0,
            "unparseable": 0
          }
        }
      },
      "errorMsg": null
    },
    "type": "ingestionStatsAndErrors"
  }
}
```

A description of the fields:

The `ingestionStatsAndErrors` report provides information about row counts and errors.

The `ingestionState` shows what step of ingestion the task reached. Possible states include:
- `NOT_STARTED`: The task has not begun reading any rows
- `DETERMINE_PARTITIONS`: The task is processing rows to determine partitioning
- `BUILD_SEGMENTS`: The task is processing rows to construct segments
- `SEGMENT_AVAILABILITY_WAIT`: The task has published its segments and is waiting for them to become available.
- `COMPLETED`: The task has finished its work.

Only batch tasks have the DETERMINE_PARTITIONS phase. Realtime tasks such as those created by the Kafka Indexing Service do not have a DETERMINE_PARTITIONS phase.

`unparseableEvents` contains lists of exception messages that were caused by unparseable inputs. This can help with identifying problematic input rows. There will be one list each for the DETERMINE_PARTITIONS and BUILD_SEGMENTS phases. Note that the Hadoop batch task does not support saving of unparseable events.

the `rowStats` map contains information about row counts. There is one entry for each ingestion phase. The definitions of the different row counts are shown below:
- `processed`: Number of rows successfully ingested without parsing errors
- `processedBytes`: Total number of uncompressed bytes processed by the task. This reports the total byte size of all rows i.e. even those that are included in `processedWithError`, `unparseable` or `thrownAway`.
- `processedWithError`: Number of rows that were ingested, but contained a parsing error within one or more columns. This typically occurs where input rows have a parseable structure but invalid types for columns, such as passing in a non-numeric String value for a numeric column.
- `thrownAway`: Number of rows skipped. This includes rows with timestamps that were outside of the ingestion task's defined time interval and rows that were filtered out with a [`transformSpec`](ingestion-spec.md#transformspec), but doesn't include the rows skipped by explicit user configurations. For example, the rows skipped by `skipHeaderRows` or `hasHeaderRow` in the CSV format are not counted.
- `unparseable`: Number of rows that could not be parsed at all and were discarded. This tracks input rows without a parseable structure, such as passing in non-JSON data when using a JSON parser.

The `errorMsg` field shows a message describing the error that caused a task to fail. It will be null if the task was successful.

## Live reports

### Row stats

The [native batch task](native-batch.md), the Hadoop batch task, and Kafka and Kinesis ingestion tasks support retrieval of row stats while the task is running.

The live report can be accessed with a GET to the following URL on a Peon running a task:

```
http://<middlemanager-host>:<worker-port>/druid/worker/v1/chat/{taskId}/rowStats
```

An example report is shown below. The `movingAverages` section contains 1 minute, 5 minute, and 15 minute moving averages of increases to the four row counters, which have the same definitions as those in the completion report. The `totals` section shows the current totals.

```json
{
  "movingAverages": {
    "buildSegments": {
      "5m": {
        "processed": 3.392158326408501,
        "processedBytes": 627.5492903856,
        "unparseable": 0,
        "thrownAway": 0,
        "processedWithError": 0
      },
      "15m": {
        "processed": 1.736165476881023,
        "processedBytes": 321.1906130223,
        "unparseable": 0,
        "thrownAway": 0,
        "processedWithError": 0
      },
      "1m": {
        "processed": 4.206417693750045,
        "processedBytes": 778.1872733438,
        "unparseable": 0,
        "thrownAway": 0,
        "processedWithError": 0
      }
    }
  },
  "totals": {
    "buildSegments": {
      "processed": 1994,
      "processedBytes": 3425110,
      "processedWithError": 0,
      "thrownAway": 0,
      "unparseable": 0
    }
  }
}
```

For the Kafka Indexing Service, a GET to the following Overlord API will retrieve live row stat reports from each task being managed by the supervisor and provide a combined report.

```
http://<OVERLORD-HOST>:<OVERLORD-PORT>/druid/indexer/v1/supervisor/{supervisorId}/stats
```

### Unparseable events

Lists of recently-encountered unparseable events can be retrieved from a running task with a GET to the following Peon API:

```
http://<middlemanager-host>:<worker-port>/druid/worker/v1/chat/{taskId}/unparseableEvents
```

Note that this functionality is not supported by all task types. Currently, it is only supported by the
non-parallel [native batch task](native-batch.md) (type `index`) and the tasks created by the Kafka
and Kinesis indexing services.

<a name="locks"></a>

## Task lock system

This section explains the task locking system in Druid. Druid's locking system
and versioning system are tightly coupled with each other to guarantee the correctness of ingested data.

### "Overshadowing" between segments

You can run a task to overwrite existing data. The segments created by an overwriting task _overshadows_ existing segments.
Note that the overshadow relation holds only for the same time chunk and the same data source.
These overshadowed segments are not considered in query processing to filter out stale data.

Each segment has a _major_ version and a _minor_ version. The major version is
represented as a timestamp in the format of [`"yyyy-MM-dd'T'hh:mm:ss"`](https://www.joda.org/joda-time/apidocs/org/joda/time/format/DateTimeFormat)
while the minor version is an integer number. These major and minor versions
are used to determine the overshadow relation between segments as seen below.

A segment `s1` overshadows another `s2` if

- `s1` has a higher major version than `s2`, or
- `s1` has the same major version and a higher minor version than `s2`.

Here are some examples.

- A segment of the major version of `2019-01-01T00:00:00.000Z` and the minor version of `0` overshadows
 another of the major version of `2018-01-01T00:00:00.000Z` and the minor version of `1`.
- A segment of the major version of `2019-01-01T00:00:00.000Z` and the minor version of `1` overshadows
 another of the major version of `2019-01-01T00:00:00.000Z` and the minor version of `0`.

### Locking

If you are running two or more [Druid tasks](./tasks.md) which generate segments for the same data source and the same time chunk,
the generated segments could potentially overshadow each other, which could lead to incorrect query results.

To avoid this problem, tasks will attempt to get locks prior to creating any segment in Druid.
There are two types of locks, i.e., _time chunk lock_ and _segment lock_.

When the time chunk lock is used, a task locks the entire time chunk of a data source where generated segments will be written.
For example, suppose we have a task ingesting data into the time chunk of `2019-01-01T00:00:00.000Z/2019-01-02T00:00:00.000Z` of the `wikipedia` data source.
With the time chunk locking, this task will lock the entire time chunk of `2019-01-01T00:00:00.000Z/2019-01-02T00:00:00.000Z` of the `wikipedia` data source
before it creates any segments. As long as it holds the lock, any other tasks will be unable to create segments for the same time chunk of the same data source.
The segments created with the time chunk locking have a _higher_ major version than existing segments. Their minor version is always `0`.

When the segment lock is used, a task locks individual segments instead of the entire time chunk.
As a result, two or more tasks can create segments for the same time chunk of the same data source simultaneously
if they are reading different segments.
For example, a Kafka indexing task and a compaction task can always write segments into the same time chunk of the same data source simultaneously.
The reason for this is because a Kafka indexing task always appends new segments, while a compaction task always overwrites existing segments.
The segments created with the segment locking have the _same_ major version and a _higher_ minor version.

:::info
 The segment locking is still experimental. It could have unknown bugs which potentially lead to incorrect query results.
:::

To enable segment locking, you may need to set `forceTimeChunkLock` to `false` in the [task context](#context).
Once `forceTimeChunkLock` is unset, the task will choose a proper lock type to use automatically.
Please note that segment lock is not always available. The most common use case where time chunk lock is enforced is
when an overwriting task changes the segment granularity.
Also, the segment locking is supported by only native indexing tasks and Kafka/Kinesis indexing tasks.
Hadoop indexing tasks don't support it.

`forceTimeChunkLock` in the task context is only applied to individual tasks.
If you want to unset it for all tasks, you would want to set `druid.indexer.tasklock.forceTimeChunkLock` to false in the [overlord configuration](../configuration/index.md#overlord-operations).

Lock requests can conflict with each other if two or more tasks try to get locks for the overlapped time chunks of the same data source.
Note that the lock conflict can happen between different locks types.

The behavior on lock conflicts depends on the [task priority](#lock-priority).
If all tasks of conflicting lock requests have the same priority, then the task who requested first will get the lock.
Other tasks will wait for the task to release the lock.

If a task of a lower priority asks a lock later than another of a higher priority,
this task will also wait for the task of a higher priority to release the lock.
If a task of a higher priority asks a lock later than another of a lower priority,
then this task will _preempt_ the other task of a lower priority. The lock
of the lower-prioritized task will be revoked and the higher-prioritized task will acquire a new lock.

This lock preemption can happen at any time while a task is running except
when it is _publishing segments_ in a critical section. Its locks become preemptible again once publishing segments is finished.

Note that locks are shared by the tasks of the same groupId.
For example, Kafka indexing tasks of the same supervisor have the same groupId and share all locks with each other.

<a name="priority"></a>

### Lock priority

Each task type has a different default lock priority. The below table shows the default priorities of different task types. Higher the number, higher the priority.

|task type|default priority|
|---------|----------------|
|Realtime index task|75|
|Batch index tasks, including [native batch](native-batch.md), [SQL](../multi-stage-query/index.md), and [Hadoop-based](hadoop.md)|50|
|Merge/Append/Compaction task|25|
|Other tasks|0|

You can override the task priority by setting your priority in the task context as below.

```json
"context" : {
  "priority" : 100
}
```
<a name="actions"></a>

## Task actions

Task actions are overlord actions performed by tasks during their lifecycle. Some typical task actions are:
- `lockAcquire`: acquires a time-chunk lock on an interval for the task
- `lockRelease`: releases a lock acquired by the task on an interval
- `segmentTransactionalInsert`: publishes new segments created by a task and optionally overwrites and/or drops existing segments in a single transaction
- `segmentAllocate`: allocates pending segments to a task to write rows

### Batching `segmentAllocate` actions

In a cluster with several concurrent tasks, `segmentAllocate` actions on the overlord can take a long time to finish, causing spikes in the `task/action/run/time`. This can result in ingestion lag building up while a task waits for a segment to be allocated.
The root cause of such spikes is likely to be one or more of the following:
- several concurrent tasks trying to allocate segments for the same datasource and interval
- large number of metadata calls made to the segments and pending segments tables 
- concurrency limitations while acquiring a task lock required for allocating a segment

Since the contention typically arises from tasks allocating segments for the same datasource and interval, you can improve the run times by batching the actions together.
To enable batched segment allocation on the overlord, set  `druid.indexer.tasklock.batchSegmentAllocation` to `true`. See [overlord configuration](../configuration/index.md#overlord-operations) for more details.

<a name="context"></a>

## Context parameters

The task context is used for various individual task configuration.
Specify task context configurations in the `context` field of the ingestion spec.
When configuring [automatic compaction](../data-management/automatic-compaction.md), set the task context configurations in `taskContext` rather than in `context`.
The settings get passed into the `context` field of the compaction tasks issued to Middle Managers.

The following parameters apply to all task types.

|Property|Description|Default|
|--------|-------|-----------|
|`forceTimeChunkLock`|_Setting this to false is still experimental._<br/> Force to use time chunk lock. When `true`, this parameter overrides the overlord runtime property `druid.indexer.tasklock.forceTimeChunkLock` [configuration for the overlord](../configuration/index.md#overlord-operations). If neither this parameter nor the runtime property is `true`, each task automatically chooses a lock type to use. See [Locking](#locking) for more details.|`true`|
|`priority`|Task priority|Depends on the task type. See [Priority](#priority) for more details.|
|`storeCompactionState`|Enables the task to store the compaction state of created segments in the metadata store. When `true`, the segments created by the task fill `lastCompactionState` in the segment metadata. This parameter is set automatically on compaction tasks. |`true` for compaction tasks, `false` for other task types|
|`storeEmptyColumns`|Enables the task to store empty columns during ingestion. When `true`, Druid stores every column specified in the [`dimensionsSpec`](ingestion-spec.md#dimensionsspec). When `false`, Druid SQL queries referencing empty columns will fail. If you intend to leave `storeEmptyColumns` disabled, you should either ingest dummy data for empty columns or else not query on empty columns.<br/><br/>When set in the task context, `storeEmptyColumns` overrides the system property [`druid.indexer.task.storeEmptyColumns`](../configuration/index.md#additional-peon-configuration).|`true`|
|`taskLockTimeout`|Task lock timeout in milliseconds. For more details, see [Locking](#locking).<br/><br/>When a task acquires a lock, it sends a request via HTTP and awaits until it receives a response containing the lock acquisition result. As a result, an HTTP timeout error can occur if `taskLockTimeout` is greater than `druid.server.http.maxIdleTime` of Overlords.|300000|
|`useLineageBasedSegmentAllocation`|Enables the new lineage-based segment allocation protocol for the native Parallel task with dynamic partitioning. This option should be off during the replacing rolling upgrade from one of the Druid versions between 0.19 and 0.21 to Druid 0.22 or higher. Once the upgrade is done, it must be set to `true` to ensure data correctness.|`false` in 0.21 or earlier, `true` in 0.22 or later|
|`lookupLoadingMode`|Controls the lookup loading behavior in tasks. This property supports three values: `ALL` mode loads all the lookups, `NONE` mode does not load any lookups and `ONLY_REQUIRED` mode loads the lookups specified with context key `lookupsToLoad`. This property must not be specified for `MSQ` and `kill` tasks as the task engine enforces `ONLY_REQUIRED` mode for `MSQWorkerTask` and `NONE` mode for `MSQControllerTask` and `kill` tasks.|`ALL`|
|`lookupsToLoad`|List of lookup names to load in tasks. This property is required only if the `lookupLoadingMode` is set to `ONLY_REQUIRED`. For `MSQWorkerTask` type, the lookup names to load are identified by the controller task by parsing the SQL. |`null`|
|`subTaskTimeoutMillis`|Maximum time (in milliseconds) to wait before cancelling a long-running sub-task. Applicable only for `index_parallel` tasks and `compact` tasks (when running in parallel mode). Set to 0 for no timeout (infinite).|0 (unlimited)|

## Task logs

Logs are created by ingestion tasks as they run. You can configure Druid to push these into a repository for long-term storage after they complete.

Once the task has been submitted to the Overlord it remains `WAITING` for locks to be acquired. Worker slot allocation is then `PENDING` until the task can actually start executing.

The task then starts creating logs in a local directory of the middle manager (or indexer) in a `log` directory for the specific `taskId` at [`druid.worker.baseTaskDirs`](../configuration/index.md#middle-manager-configuration).

When the task completes - whether it succeeds or fails - the middle manager (or indexer) will push the task log file into the location specified in [`druid.indexer.logs`](../configuration/index.md#task-logging).

Task logs on the Druid web console are retrieved via an [API](../api-reference/service-status-api.md#overlord) on the Overlord. It automatically detects where the log file is, either in the Middle Manager / indexer or in long-term storage, and passes it back.

If you don't see the log file in long-term storage, it means either:

- the Middle Manager / indexer failed to push the log file to deep storage or
- the task did not complete.

You can check the Middle Manager / indexer logs locally to see if there was a push failure. If there was not, check the Overlord's own process logs to see why the task failed before it started.

:::info
 If you are running the indexing service in remote mode, the task logs must be stored in S3, Azure Blob Store, Google Cloud Storage or HDFS.
:::

You can configure retention periods for logs in milliseconds by setting `druid.indexer.logs.kill` properties in [configuration](../configuration/index.md#task-logging).  The Overlord will then automatically manage task logs in log directories along with entries in task-related metadata storage tables.

:::info
 Automatic log file deletion typically works based on the log file's 'modified' timestamp in the back-end store. Large clock skews between Druid processes and the long-term store might result in unintended behavior.
:::

## Configuring task storage sizes

Tasks sometimes need to use local disk for storage of things while the task is active.  For example, for realtime ingestion tasks to accept broadcast segments for broadcast joins.  Or intermediate data sets for Multi-stage Query jobs

Task storage sizes are configured through a combination of three properties:
1. `druid.worker.capacity` - i.e. the "number of task slots"
2. `druid.worker.baseTaskDirs` - i.e. the list of directories to use for task storage. 
3. `druid.worker.baseTaskDirSize` - i.e. the amount of storage to use on each storage location

While it seems like one task might use multiple directories, only one directory from the list of base directories will be used for any given task, as such, each task is only given a singular directory for scratch space.

The actual amount of memory assigned to any given task is computed by determining the largest size that enables all task slots to be given an equivalent amount of disk storage. For example, with 5 slots, 2 directories (A and B) and a size of 300 GB, 3 slots would be given to directory A, 2 slots to directory B and each slot would be allowed 100 GB 

## All task types

### `index_parallel`

See [Native batch ingestion (parallel task)](native-batch.md).

### `index_hadoop`

See [Hadoop-based ingestion](hadoop.md).

### `index_kafka`

Submitted automatically, on your behalf, by a
[Kafka-based ingestion supervisor](../ingestion/kafka-ingestion.md).

### `index_kinesis`

Submitted automatically, on your behalf, by a
[Kinesis-based ingestion supervisor](../ingestion/kinesis-ingestion.md).

### `compact`

Compaction tasks merge all segments of the given interval. See the documentation on
[compaction](../data-management/compaction.md) for details.

### `kill`

Kill tasks delete all metadata about certain segments and removes them from deep storage.
See the documentation on [deleting data](../data-management/delete.md) for details.
