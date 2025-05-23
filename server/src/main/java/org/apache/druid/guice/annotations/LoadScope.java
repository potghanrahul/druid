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

package org.apache.druid.guice.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An annotation to specify node types that a {@link com.google.inject.Module} can be loaded on.
 * The {@link #roles()} should be the {@link org.apache.druid.discovery.NodeRole#jsonName}. If both {@link LoadScope}
 * and {@link ExcludeScope} are set, {@link ExcludeScope} takes precedence
 * <p>
 * A module not decorated with this annotation or {@link ExcludeScope} will be loaded on every node role.
 *
 * @see ExcludeScope to specify node roles which a module should NOT be loaded on
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface LoadScope
{
  String[] roles();
}
