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

package org.apache.druid.curator.discovery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import org.apache.curator.utils.ZKPaths;
import org.apache.druid.curator.announcement.ServiceAnnouncer;
import org.apache.druid.discovery.DiscoveryDruidNode;
import org.apache.druid.discovery.DruidNodeAnnouncer;
import org.apache.druid.discovery.NodeRole;
import org.apache.druid.guice.annotations.Json;
import org.apache.druid.guice.annotations.SingleThreadedAnnouncer;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.logger.Logger;
import org.apache.druid.server.DruidNode;
import org.apache.druid.server.initialization.ZkPathsConfig;

public class CuratorDruidNodeAnnouncer implements DruidNodeAnnouncer
{
  static String makeNodeAnnouncementPath(ZkPathsConfig config, NodeRole nodeRole, DruidNode node)
  {
    return ZKPaths.makePath(config.getInternalDiscoveryPath(), nodeRole.toString(), node.getHostAndPortToUse());
  }

  private static final Logger log = new Logger(CuratorDruidNodeAnnouncer.class);

  private final ServiceAnnouncer announcer;
  private final ZkPathsConfig config;
  private final ObjectMapper jsonMapper;

  @Inject
  public CuratorDruidNodeAnnouncer(
      @SingleThreadedAnnouncer ServiceAnnouncer announcer,
      ZkPathsConfig config,
      @Json ObjectMapper jsonMapper
  )
  {
    this.announcer = announcer;
    this.config = config;
    this.jsonMapper = jsonMapper;
  }

  @Override
  public void announce(DiscoveryDruidNode discoveryDruidNode)
  {
    try {
      final String asString = jsonMapper.writeValueAsString(discoveryDruidNode);

      log.debug("Announcing self [%s].", asString);

      String path =
          makeNodeAnnouncementPath(config, discoveryDruidNode.getNodeRole(), discoveryDruidNode.getDruidNode());
      announcer.announce(path, StringUtils.toUtf8(asString));

      log.info("Announced self [%s].", asString);
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void unannounce(DiscoveryDruidNode discoveryDruidNode)
  {
    try {
      final String asString = jsonMapper.writeValueAsString(discoveryDruidNode);

      log.debug("Unannouncing self [%s].", asString);

      String path =
          makeNodeAnnouncementPath(config, discoveryDruidNode.getNodeRole(), discoveryDruidNode.getDruidNode());
      announcer.unannounce(path);

      log.info("Unannounced self [%s].", asString);
    }
    catch (JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }
}
