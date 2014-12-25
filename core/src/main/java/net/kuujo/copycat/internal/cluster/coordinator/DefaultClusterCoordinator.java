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
package net.kuujo.copycat.internal.cluster.coordinator;

import net.kuujo.copycat.CopycatContext;
import net.kuujo.copycat.cluster.Cluster;
import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.cluster.Member;
import net.kuujo.copycat.cluster.coordinator.ClusterCoordinator;
import net.kuujo.copycat.cluster.coordinator.LocalMemberCoordinator;
import net.kuujo.copycat.cluster.coordinator.MemberCoordinator;
import net.kuujo.copycat.internal.CopycatStateContext;
import net.kuujo.copycat.internal.DefaultCopycatContext;
import net.kuujo.copycat.internal.cluster.CoordinatedCluster;
import net.kuujo.copycat.internal.cluster.Router;
import net.kuujo.copycat.internal.cluster.Topics;
import net.kuujo.copycat.internal.util.Services;
import net.kuujo.copycat.log.Log;
import net.kuujo.copycat.log.LogConfig;
import net.kuujo.copycat.protocol.MemberInfo;
import net.kuujo.copycat.protocol.RaftProtocol;
import net.kuujo.copycat.protocol.Request;
import net.kuujo.copycat.protocol.Response;
import net.kuujo.copycat.util.serializer.Serializer;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/**
 * Default cluster coordinator implementation.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class DefaultClusterCoordinator implements ClusterCoordinator, Observer {
  private final Serializer serializer = Serializer.serializer();
  private final Executor executor;
  private final CopycatStateContext state;
  private final CopycatContext context;
  private final ClusterConfig config;
  private final DefaultLocalMemberCoordinator localMember;
  private final Map<String, AbstractMemberCoordinator> members = new ConcurrentHashMap<>();
  private final Map<String, CopycatContext> contexts = new ConcurrentHashMap<>();

  public DefaultClusterCoordinator(String uri, ClusterConfig config, Executor executor) {
    this.config = config.copy();
    this.executor = executor;
    this.localMember = new DefaultLocalMemberCoordinator(uri, config.getMembers().contains(uri) ? Member.Type.MEMBER
      : Member.Type.LISTENER, Member.State.ALIVE, config.getProtocol(), executor);
    this.members.put(uri, localMember);
    for (String member : config.getMembers()) {
      this.members.put(member, new DefaultRemoteMemberCoordinator(member, Member.Type.MEMBER, Member.State.ALIVE,
        config.getProtocol(), executor));
    }
    Map<String, Object> logConfig = new HashMap<>();
    logConfig.put("name", "copycat");
    state = new CopycatStateContext(uri, config, Services.load(Log.class, new LogConfig().withName("copycat")));
    context = new DefaultCopycatContext(new CoordinatedCluster(0, this, state, new ResourceRouter("copycat"), executor), state);
  }

  @Override
  public void update(Observable o, Object arg) {
    for (MemberInfo member : state.getMembers()) {
      if (member.type().equals(Member.Type.LISTENER)) {
        if (members.containsKey(member.uri())) {
          if (member.state().equals(Member.State.DEAD)) {
            MemberCoordinator coordinator = members.remove(member.uri());
            try {
              coordinator.close().get();
            } catch (InterruptedException | ExecutionException ignore) {
            }
          } else {
            members.get(member.uri()).state(member.state());
          }
        } else {
          if (!member.state().equals(Member.State.DEAD)) {
            members.computeIfAbsent(member.uri(), k -> {
              DefaultRemoteMemberCoordinator coordinator = new DefaultRemoteMemberCoordinator(member.uri(),
                Member.Type.LISTENER, member.state(), config.getProtocol(), executor);
              try {
                coordinator.open().get();
              } catch (InterruptedException | ExecutionException ignore) {
              }
              return coordinator;
            });
          }
        }
      }
    }
  }

  @Override
  public LocalMemberCoordinator member() {
    return localMember;
  }

  @Override
  public MemberCoordinator member(String uri) {
    return members.get(uri);
  }

  @Override
  public Collection<MemberCoordinator> members() {
    return Collections.unmodifiableCollection(members.values());
  }

  @Override
  public CompletableFuture<CopycatContext> createResource(String name) {
    return createResource(name, config, new LogConfig().withName(name));
  }

  @Override
  public CompletableFuture<CopycatContext> createResource(String name, ClusterConfig cluster) {
    return createResource(name, cluster, new LogConfig().withName(name));
  }

  @Override
  public CompletableFuture<CopycatContext> createResource(String name, LogConfig log) {
    return createResource(name, config, log.withName(name));
  }

  @Override
  public CompletableFuture<CopycatContext> createResource(String name, ClusterConfig cluster, LogConfig log) {
    log.setName(name);
    ByteBuffer serializedCluster = serializer.writeObject(cluster.getMembers());
    ByteBuffer serializedLog = serializer.writeObject(log);
    ByteBuffer entry = ByteBuffer.allocate(16 + name.getBytes().length + serializedCluster.capacity() + serializedLog.capacity());
    entry.putInt(1);
    entry.putInt(name.getBytes().length);
    entry.put(name.getBytes());
    entry.putInt(serializedCluster.capacity());
    entry.put(serializedCluster);
    entry.putInt(serializedLog.capacity());
    entry.put(serializedLog);
    entry.rewind();
    return context.commit(entry).thenApplyAsync(buffer -> {
      int result = buffer.getInt();
      if (result == 0) {
        return null;
      } else {
        return contexts.computeIfAbsent(name, k -> createContext(k, log, cluster.getMembers()));
      }
    }, executor);
  }

  @Override
  public CompletableFuture<Void> deleteResource(String name) {
    ByteBuffer entry = ByteBuffer.allocate(8 + name.getBytes().length);
    entry.putInt(-1);
    entry.putInt(name.getBytes().length);
    entry.put(name.getBytes());
    return context.commit(entry).thenApplyAsync(result -> null, context);
  }

  /**
   * Creates a new Copycat context.
   *
   * @param name The context name.
   * @return The created context.
   */
  private CopycatContext createContext(String name, LogConfig config, Collection<String> members) {
    CopycatStateContext state = new CopycatStateContext(localMember.uri(), this.config.copy().withMembers(members), Services.load(Log.class, config));
    CoordinatedCluster cluster = new CoordinatedCluster(name.hashCode(), this, state, new ResourceRouter(name), state.executor());
    return new DefaultCopycatContext(cluster, state);
  }

  /**
   * Consumes messages from the log.
   */
  private ByteBuffer consume(Long index, ByteBuffer buffer) {
    buffer.rewind();
    int type = buffer.getInt();
    byte[] nameBytes;
    String name;
    ByteBuffer result;

    switch (type) {
      case 1: // create
        nameBytes = new byte[buffer.getInt()];
        buffer.get(nameBytes);
        name = new String(nameBytes);
        byte[] clusterBytes = new byte[buffer.getInt()];
        buffer.get(clusterBytes);
        Set<String> members = serializer.readObject(ByteBuffer.wrap(clusterBytes));
        byte[] logBytes = new byte[buffer.getInt()];
        buffer.get(logBytes);
        LogConfig log = serializer.readObject(ByteBuffer.wrap(logBytes));
        result = ByteBuffer.allocate(4);
        if (!contexts.containsKey(name)) {
          contexts.computeIfAbsent(name, k -> {
            result.putInt(1);
            return createContext(name, log, members);
          });
        } else {
          result.putInt(0);
        }
        break;
      case -1: // delete
        nameBytes = new byte[buffer.getInt()];
        buffer.get(nameBytes);
        name = new String(nameBytes);
        result = ByteBuffer.allocate(4);
        CopycatContext context = contexts.remove(name);
        if (context != null) {
          try {
            context.close().get();
            context.delete().get();
          } catch (Exception e) {
          }
          result.putInt(1);
        } else {
          result.putInt(0);
        }
        break;
      default:
        throw new UnsupportedOperationException("Invalid command");
    }
    result.rewind();
    return result;
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<Void> open() {
    state.addObserver(this);
    CompletableFuture<Void>[] futures = new CompletableFuture[members.size()];
    int i = 0;
    for (MemberCoordinator member : members.values()) {
      futures[i++] = member.open();
    }
    context.consumer(this::consume);
    return CompletableFuture.allOf(futures).thenCompose((v) -> context.open());
  }

  @Override
  @SuppressWarnings("unchecked")
  public CompletableFuture<Void> close() {
    state.deleteObserver(this);
    CompletableFuture<Void>[] futures = new CompletableFuture[members.size()];
    int i = 0;
    for (MemberCoordinator member : members.values()) {
      futures[i++] = member.close();
    }
    return context.close().thenCompose((v) -> CompletableFuture.allOf(futures));
  }

  @Override
  public String toString() {
    return String.format("%s[members=%s]", getClass().getCanonicalName(), members.values());
  }

  /**
   * Resource router.
   */
  private static class ResourceRouter implements Router {
    private final String name;

    private ResourceRouter(String name) {
      this.name = name;
    }

    @Override
    public void createRoutes(Cluster cluster, RaftProtocol protocol) {
      cluster.member().registerHandler(Topics.SYNC, protocol::sync);
      cluster.member().registerHandler(Topics.PING, protocol::ping);
      cluster.member().registerHandler(Topics.POLL, protocol::poll);
      cluster.member().registerHandler(Topics.APPEND, protocol::append);
      cluster.member().registerHandler(Topics.QUERY, protocol::query);
      cluster.member().registerHandler(Topics.COMMIT, protocol::commit);
      protocol.pingHandler(request -> handleOutboundRequest(Topics.SYNC, request, cluster));
      protocol.pingHandler(request -> handleOutboundRequest(Topics.PING, request, cluster));
      protocol.pollHandler(request -> handleOutboundRequest(Topics.POLL, request, cluster));
      protocol.appendHandler(request -> handleOutboundRequest(Topics.APPEND, request, cluster));
      protocol.queryHandler(request -> handleOutboundRequest(Topics.QUERY, request, cluster));
      protocol.commitHandler(request -> handleOutboundRequest(Topics.COMMIT, request, cluster));
    }

    /**
     * Handles an outbound protocol request.
     */
    private <T extends Request, U extends Response> CompletableFuture<U> handleOutboundRequest(String topic, T request,
      Cluster cluster) {
      Member member = cluster.member(request.uri());
      if (member != null) {
        return member.send(topic, request);
      }
      CompletableFuture<U> future = new CompletableFuture<>();
      future.completeExceptionally(new IllegalStateException(String.format("Invalid URI %s", request.uri())));
      return future;
    }

    @Override
    public void destroyRoutes(Cluster cluster, RaftProtocol protocol) {
      cluster.member().unregisterHandler(name);
      protocol.pingHandler(null);
      protocol.pollHandler(null);
      protocol.appendHandler(null);
      protocol.queryHandler(null);
      protocol.commitHandler(null);
    }
  }

}
