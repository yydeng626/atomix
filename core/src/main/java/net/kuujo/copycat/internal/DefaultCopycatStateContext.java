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
package net.kuujo.copycat.internal;

import net.kuujo.copycat.CopycatState;
import net.kuujo.copycat.cluster.ClusterConfig;
import net.kuujo.copycat.cluster.MessageHandler;
import net.kuujo.copycat.election.Election;
import net.kuujo.copycat.internal.util.concurrent.Futures;
import net.kuujo.copycat.log.Log;
import net.kuujo.copycat.protocol.*;
import net.kuujo.copycat.spi.ExecutionContext;

import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Observable;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;

/**
 * Raft state context.
 *
 * @author <a href="http://github.com/kuujo">Jordan Halterman</a>
 */
public class DefaultCopycatStateContext extends Observable implements CopycatStateContext {
  private final ExecutionContext executor;
  private final Log log;
  private AbstractState state;
  private BiFunction<Long, ByteBuffer, ByteBuffer> consumer;
  private MessageHandler<SyncRequest, SyncResponse> syncHandler;
  private MessageHandler<PingRequest, PingResponse> pingHandler;
  private MessageHandler<PollRequest, PollResponse> pollHandler;
  private MessageHandler<AppendRequest, AppendResponse> appendHandler;
  private MessageHandler<QueryRequest, QueryResponse> queryHandler;
  private MessageHandler<CommitRequest, CommitResponse> commitHandler;
  private CompletableFuture<Void> openFuture;
  private final String localMember;
  private final Set<String> remoteMembers;
  private Election.Status status;
  private String leader;
  private long term;
  private String lastVotedFor;
  private long commitIndex;
  private long lastApplied;
  private long electionTimeout = 500;
  private long heartbeatInterval = 250;
  private boolean open;

  public DefaultCopycatStateContext(ClusterConfig config, Log log, ExecutionContext executor) {
    this.localMember = config.getLocalMember();
    this.remoteMembers = config.getRemoteMembers();
    this.log = log;
    this.executor = executor;
    this.electionTimeout = config.getElectionTimeout();
    this.heartbeatInterval = config.getHeartbeatInterval();
  }

  @Override
  public String getLocalMember() {
    return localMember;
  }

  @Override
  public Set<String> getMembers() {
    Set<String> members = new HashSet<>(remoteMembers);
    members.add(localMember);
    return members;
  }

  @Override
  public Set<String> getRemoteMembers() {
    return new HashSet<>(remoteMembers);
  }

  @Override
  public Election.Status getStatus() {
    return status;
  }

  @Override
  public CopycatStateContext setLeader(String leader) {
    if (this.leader == null) {
      if (leader != null) {
        this.leader = leader;
        this.lastVotedFor = null;
        this.status = Election.Status.COMPLETE;
        if (openFuture != null) {
          openFuture.complete(null);
        }
        triggerChangeEvent();
      }
    } else if (leader != null) {
      if (!this.leader.equals(leader)) {
        this.leader = leader;
        this.lastVotedFor = null;
        this.status = Election.Status.COMPLETE;
        triggerChangeEvent();
      }
    } else {
      this.leader = null;
      this.status = Election.Status.IN_PROGRESS;
      triggerChangeEvent();
    }
    return this;
  }

  @Override
  public String getLeader() {
    return leader;
  }

  @Override
  public CopycatStateContext setTerm(long term) {
    if (term > this.term) {
      this.term = term;
      this.leader = null;
      this.status = Election.Status.IN_PROGRESS;
      this.lastVotedFor = null;
      triggerChangeEvent();
    }
    return this;
  }

  @Override
  public long getTerm() {
    return term;
  }

  @Override
  public CopycatStateContext setLastVotedFor(String candidate) {
    // If we've already voted for another candidate in this term then the last voted for candidate cannot be overridden.
    if (lastVotedFor != null && candidate != null) {
      throw new IllegalStateException("Already voted for another candidate");
    }
    if (leader != null && candidate != null) {
      throw new IllegalStateException("Cannot cast vote - leader already exists");
    }
    this.lastVotedFor = candidate;
    this.status = Election.Status.IN_PROGRESS;
    triggerChangeEvent();
    return this;
  }

  @Override
  public String getLastVotedFor() {
    return lastVotedFor;
  }

  @Override
  public CopycatStateContext setCommitIndex(long commitIndex) {
    if (commitIndex <= this.commitIndex) {
      throw new IllegalStateException("Cannot decrease commit index");
    }
    this.commitIndex = commitIndex;
    triggerChangeEvent();
    return this;
  }

  @Override
  public long getCommitIndex() {
    return commitIndex;
  }

  @Override
  public CopycatStateContext setLastApplied(long lastApplied) {
    if (lastApplied <= this.lastApplied) {
      throw new IllegalStateException("Cannot decrease last applied index");
    }
    this.lastApplied = lastApplied;
    triggerChangeEvent();
    return this;
  }

  @Override
  public long getLastApplied() {
    return lastApplied;
  }

  @Override
  public CopycatStateContext setElectionTimeout(long electionTimeout) {
    this.electionTimeout = electionTimeout;
    return this;
  }

  @Override
  public long getElectionTimeout() {
    return electionTimeout;
  }

  @Override
  public CopycatStateContext setHeartbeatInterval(long heartbeatInterval) {
    this.heartbeatInterval = heartbeatInterval;
    return this;
  }

  @Override
  public long getHeartbeatInterval() {
    return heartbeatInterval;
  }

  @Override
  public CopycatState state() {
    return state.state();
  }

  @Override
  public ExecutionContext executor() {
    return executor;
  }

  @Override
  public CopycatStateContext consumer(BiFunction<Long, ByteBuffer, ByteBuffer> consumer) {
    this.consumer = consumer;
    return this;
  }

  @Override
  public BiFunction<Long, ByteBuffer, ByteBuffer> consumer() {
    return consumer;
  }

  @Override
  public Log log() {
    return log;
  }

  @Override
  public CompletableFuture<SyncResponse> sync(SyncRequest request) {
    return state.sync(request);
  }

  @Override
  public RaftProtocol syncHandler(MessageHandler<SyncRequest, SyncResponse> handler) {
    this.syncHandler = handler;
    return this;
  }

  @Override
  public DefaultCopycatStateContext pingHandler(MessageHandler<PingRequest, PingResponse> handler) {
    this.pingHandler = handler;
    return this;
  }

  @Override
  public CompletableFuture<PingResponse> ping(PingRequest request) {
    return state.ping(request);
  }

  @Override
  public DefaultCopycatStateContext pollHandler(MessageHandler<PollRequest, PollResponse> handler) {
    this.pollHandler = handler;
    return this;
  }

  @Override
  public CompletableFuture<PollResponse> poll(PollRequest request) {
    return state.poll(request);
  }

  @Override
  public DefaultCopycatStateContext appendHandler(MessageHandler<AppendRequest, AppendResponse> handler) {
    this.appendHandler = handler;
    return this;
  }

  @Override
  public CompletableFuture<AppendResponse> append(AppendRequest request) {
    return state.append(request);
  }

  @Override
  public DefaultCopycatStateContext queryHandler(MessageHandler<QueryRequest, QueryResponse> handler) {
    this.queryHandler = handler;
    return this;
  }

  @Override
  public CompletableFuture<QueryResponse> query(QueryRequest request) {
    return state.query(request);
  }

  @Override
  public DefaultCopycatStateContext commitHandler(MessageHandler<CommitRequest, CommitResponse> handler) {
    this.commitHandler = handler;
    return this;
  }

  @Override
  public CompletableFuture<CommitResponse> commit(CommitRequest request) {
    return state.commit(request);
  }

  /**
   * Transition registerHandler.
   */
  CompletableFuture<CopycatState> transition(CopycatState state) {
    if (this.state != null && state == this.state.state()) {
      return CompletableFuture.completedFuture(this.state.state());
    }

    CompletableFuture<CopycatState> future = new CompletableFuture<>();
    if (this.state != null) {
      this.state.close().whenComplete((result, error) -> {
        unregisterHandlers(this.state);
        if (error == null) {
          switch (state) {
            case START:
              this.state = new StartState(this);
              break;
            case FOLLOWER:
              this.state = new FollowerState(this);
              break;
            case CANDIDATE:
              this.state = new CandidateState(this);
              break;
            case LEADER:
              this.state = new LeaderState(this);
              break;
            default:
              this.state = new StartState(this);
              break;
          }

          registerHandlers(this.state);
          this.state.open().whenComplete((result2, error2) -> {
            if (error2 == null) {
              future.complete(this.state.state());
            } else {
              future.completeExceptionally(error2);
            }
          });
        }
      });
    } else {
      switch (state) {
        case START:
          this.state = new StartState(this);
          break;
        case FOLLOWER:
          this.state = new FollowerState(this);
          break;
        case CANDIDATE:
          this.state = new CandidateState(this);
          break;
        case LEADER:
          this.state = new LeaderState(this);
          break;
        default:
          this.state = new StartState(this);
          break;
      }

      registerHandlers(this.state);
      this.state.open().whenComplete((result, error) -> {
        if (error == null) {
          future.complete(this.state.state());
        } else {
          future.completeExceptionally(error);
        }
      });
    }
    return future;
  }

  /**
   * Registers handlers on the given state.
   */
  private void registerHandlers(AbstractState state) {
    state.syncHandler(syncHandler);
    state.pingHandler(pingHandler);
    state.appendHandler(appendHandler);
    state.pollHandler(pollHandler);
    state.queryHandler(queryHandler);
    state.commitHandler(commitHandler);
    state.transitionHandler(this::transition);
  }

  /**
   * Unregisters handlers on the given state.
   */
  private void unregisterHandlers(AbstractState state) {
    state.syncHandler(null);
    state.pingHandler(null);
    state.appendHandler(null);
    state.pollHandler(null);
    state.queryHandler(null);
    state.commitHandler(null);
    state.transitionHandler(null);
  }

  /**
   * Triggers an observable changed event.
   */
  private void triggerChangeEvent() {
    setChanged();
    notifyObservers();
    clearChanged();
  }

  @Override
  public CompletableFuture<Void> open() {
    if (openFuture != null) {
      return openFuture;
    }
    openFuture = new CompletableFuture<>();
    executor.execute(() -> {
      try {
        open = true;
        log.open();
        transition(CopycatState.FOLLOWER);
      } catch (Exception e) {
        openFuture.completeExceptionally(e);
        openFuture = null;
      }
    });
    return openFuture;
  }

  @Override
  public CompletableFuture<Void> close() {
    if (openFuture != null) {
      openFuture.cancel(false);
      openFuture = null;
    } else if (!open) {
      return Futures.exceptionalFuture(new IllegalStateException("Context not open"));
    }

    CompletableFuture<Void> future = new CompletableFuture<>();
    executor.execute(() -> {
      transition(CopycatState.START).whenComplete((result, error) -> {
        if (error == null) {
          try {
            log.close();
            future.complete(null);
          } catch (Exception e) {
            future.completeExceptionally(e);
          }
        } else {
          try {
            log.close();
            future.completeExceptionally(error);
          } catch (Exception e) {
            future.completeExceptionally(error);
          }
        }
      });
    });
    return future;
  }

}
