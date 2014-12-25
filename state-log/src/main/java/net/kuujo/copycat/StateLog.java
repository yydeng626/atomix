/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.kuujo.copycat;

import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.cluster.coordinator.ClusterCoordinator;
import net.kuujo.copycat.internal.DefaultStateLog;
import net.kuujo.copycat.internal.cluster.coordinator.DefaultClusterCoordinator;
import net.kuujo.copycat.internal.util.concurrent.NamedThreadFactory;
import net.kuujo.copycat.log.LogConfig;
import net.kuujo.copycat.protocol.Consistency;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Copycat event log.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public interface StateLog<T> extends CopycatResource {

  /**
   * Creates a new state log.
   *
   * @param name The log name.
   * @param uri The local log member URI.
   * @param cluster The state log cluster.
   * @return A new state log instance.
   */
  static <T> StateLog<T> create(String name, String uri, ClusterConfig cluster) {
    return create(name, uri, cluster, new LogConfig(), Executors.newSingleThreadExecutor(new NamedThreadFactory("copycat-state-log-" + name + "-%d")));
  }

  /**
   * Creates a new state log.
   *
   * @param name The log name.
   * @param uri The local log member URI.
   * @param cluster The state log cluster.
   * @param config The state log configuration.
   * @return A new state log instance.
   */
  static <T> StateLog<T> create(String name, String uri, ClusterConfig cluster, LogConfig config) {
    return create(name, uri, cluster, config, Executors.newSingleThreadExecutor(new NamedThreadFactory("copycat-state-log-" + name + "-%d")));
  }

  /**
   * Creates a new state log.
   *
   * @param name The log name.
   * @param uri The local log member URI.
   * @param cluster The state log cluster.
   * @param executor The user execution context.
   * @return A new state log instance.
   */
  static <T> StateLog<T> create(String name, String uri, ClusterConfig cluster, Executor executor) {
    return create(name, uri, cluster, new LogConfig(), executor);
  }

  /**
   * Creates a new state log.
   *
   * @param name The log name.
   * @param uri The local log member URI.
   * @param cluster The state log cluster.
   * @param config The state log configuration.
   * @param executor The user execution context.
   * @return A new state log instance.
   */
  static <T> StateLog<T> create(String name, String uri, ClusterConfig cluster, LogConfig config, Executor executor) {
    ClusterCoordinator coordinator = new DefaultClusterCoordinator(uri, cluster, Executors.newSingleThreadExecutor(new NamedThreadFactory("copycat-coordinator-%d")));
    try {
      coordinator.open().get();
      return new DefaultStateLog<T>(name, coordinator.createResource(name, cluster, config).get(), coordinator, config, executor).withShutdownTask(coordinator::close);
    } catch (InterruptedException | ExecutionException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Registers a state command.
   *
   * @param name The command name.
   * @param command The command function.
   * @param <U> The command input type.
   * @param <V> The command output type.
   * @return The state log.
   */
  <U extends T, V> StateLog<T> registerCommand(String name, Function<U, V> command);

  /**
   * Unregisters a state command.
   *
   * @param name The command name.
   * @return The state log.
   */
  StateLog<T> unregisterCommand(String name);

  /**
   * Registers a state query.
   *
   * @param name The query name.
   * @param query The query function.
   * @param <U> The query input type.
   * @param <V> The query output type.
   * @return The state log.
   */
  <U extends T, V> StateLog<T> registerQuery(String name, Function<U, V> query);

  /**
   * Registers a state query.
   *
   * @param name The query name.
   * @param query The query function.
   * @param consistency The default query consistency.
   * @param <U> The query input type.
   * @param <V> The query output type.
   * @return The state log.
   */
  <U extends T, V> StateLog<T> registerQuery(String name, Function<U, V> query, Consistency consistency);

  /**
   * Unregisters a state query.
   *
   * @param name The query name.
   * @return The state log.
   */
  StateLog<T> unregisterQuery(String name);

  /**
   * Unregisters a state command or query.
   *
   * @param name The command or query name.
   * @return The state log.
   */
  StateLog<T> unregister(String name);

  /**
   * Registers a state log snapshot provider.
   *
   * @param snapshotter The snapshot provider.
   * @return The state log.
   */
  <U> StateLog<T> takeSnapshotWith(Supplier<U> snapshotter);

  /**
   * Registers a state log snapshot installer.
   *
   * @param installer The snapshot installer.
   * @return The state log.
   */
  <U> StateLog<T> installSnapshotWith(Consumer<U> installer);

  /**
   * Submits a state command or query to the log.
   *
   * @param command The command name.
   * @param entry The command entry.
   * @return A completable future to be completed once the command output is received.
   */
  <U> CompletableFuture<U> submit(String command, T entry);

}
