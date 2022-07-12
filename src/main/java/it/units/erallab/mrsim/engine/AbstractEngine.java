/*
 * Copyright 2022 Eric Medvet <eric.medvet@gmail.com> (as eric)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package it.units.erallab.mrsim.engine;

import it.units.erallab.mrsim.core.*;
import it.units.erallab.mrsim.core.actions.*;
import it.units.erallab.mrsim.core.bodies.*;
import it.units.erallab.mrsim.core.geometry.Point;
import it.units.erallab.mrsim.util.AtomicDouble;
import it.units.erallab.mrsim.util.Pair;
import it.units.erallab.mrsim.util.Profiled;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * @author "Eric Medvet" on 2022/07/06 for 2dmrsim
 */
public abstract class AbstractEngine implements Engine, Profiled {

  @FunctionalInterface
  protected interface ActionSolver<A extends Action<O>, O> {
    O solve(A action, Agent agent) throws ActionException;
  }

  protected final AtomicDouble t;
  protected final List<Body> bodies;
  protected final List<Pair<Agent, List<ActionOutcome<?, ?>>>> agentPairs;
  private final Map<Class<? extends Action<?>>, ActionSolver<?, ?>> actionSolvers;
  private final static Logger L = Logger.getLogger(AbstractEngine.class.getName());

  private final AtomicInteger nOfTicks;
  private final AtomicDouble engineT;
  private final Instant startingInstant;
  private final AtomicInteger nOfActions;
  private final AtomicInteger nOfUnsupportedActions;
  private final AtomicInteger nOfIllegalActions;
  private final List<ActionOutcome<?, ?>> lastTickPerformedActions;


  public AbstractEngine() {
    bodies = new ArrayList<>();
    agentPairs = new ArrayList<>();
    actionSolvers = new LinkedHashMap<>();
    t = new AtomicDouble(0d);
    nOfTicks = new AtomicInteger(0);
    engineT = new AtomicDouble(0d);
    startingInstant = Instant.now();
    nOfActions = new AtomicInteger(0);
    nOfUnsupportedActions = new AtomicInteger(0);
    nOfIllegalActions = new AtomicInteger(0);
    lastTickPerformedActions = new ArrayList<>();
    registerActionSolvers();
  }

  @Override
  public Snapshot tick() {
    Instant tickStartingInstant = Instant.now();
    nOfTicks.incrementAndGet();
    for (int i = 0; i < agentPairs.size(); i++) {
      List<ActionOutcome<?, ?>> outcomes = new ArrayList<>();
      for (Action<?> action : agentPairs.get(i).first().act(t.get(), agentPairs.get(i).second())) {
        outcomes.add(perform(action, agentPairs.get(i).first()));
      }
      Pair<Agent, List<ActionOutcome<?, ?>>> pair = new Pair<>(agentPairs.get(i).first(), outcomes);
      agentPairs.set(i, pair);
    }
    double newT = innerTick();
    t.set(newT);
    engineT.add(Duration.between(tickStartingInstant, Instant.now()).toNanos() / 1000000000d);
    EngineSnapshot snapshot = new EngineSnapshot(
        t.get(),
        List.copyOf(getBodies()),
        agentPairs.stream().map(Pair::first).toList(),
        List.copyOf(lastTickPerformedActions),
        engineT.get(),
        Duration.between(startingInstant, Instant.now()).toMillis() / 1000d,
        nOfTicks.get(),
        nOfActions.get(),
        nOfUnsupportedActions.get(),
        nOfIllegalActions.get()
    );
    lastTickPerformedActions.clear();
    return snapshot;
  }

  @Override
  public Map<String, Double> values() {
    return Map.ofEntries(
        Map.entry("engineT", engineT.get()),
        Map.entry("t", t.get()),
        Map.entry("wallT", Duration.between(startingInstant, Instant.now()).toMillis() / 1000d)
    );
  }

  @SuppressWarnings("unchecked")
  @Override
  public <A extends Action<O>, O> ActionOutcome<A, O> perform(A action, Agent agent) {
    nOfActions.incrementAndGet();
    ActionSolver<A, O> actionSolver = (ActionSolver<A, O>) actionSolvers.get(action.getClass());
    ActionOutcome<A, O> outcome;
    if (actionSolver == null) {
      L.finer(String.format("Ignoring unsupported action: %s", action.getClass().getSimpleName()));
      nOfUnsupportedActions.incrementAndGet();
      outcome = new ActionOutcome<>(agent, action, Optional.empty());
    } else {
      try {
        O o = actionSolver.solve(action, agent);
        outcome = new ActionOutcome<>(agent, action, o == null ? Optional.empty() : Optional.of(o));
      } catch (ActionException e) {
        L.finer(String.format("Ignoring illegal action: %s", e));
        nOfIllegalActions.incrementAndGet();
        outcome = new ActionOutcome<>(agent, action, Optional.empty());
      } catch (RuntimeException e) {
        L.warning(String.format("Ignoring action throwing exception: %s", e));
        nOfIllegalActions.incrementAndGet();
        outcome = new ActionOutcome<>(agent, action, Optional.empty());
      }
    }
    lastTickPerformedActions.add(outcome);
    return outcome;
  }

  protected abstract double innerTick();

  @Override
  public double t() {
    return t.get();
  }

  protected abstract Collection<Body> getBodies();

  protected final <A extends Action<O>, O> void registerActionSolver(
      Class<A> actionClass,
      ActionSolver<A, O> actionSolver
  ) {
    actionSolvers.put(actionClass, actionSolver);
  }

  protected void registerActionSolvers() {
    registerActionSolver(AddAgent.class, this::addAgent);
    registerActionSolver(CreateAndTranslateRigidBody.class, this::createAndTranslateRigidBody);
    registerActionSolver(CreateAndTranslateUnmovableBody.class, this::createAndTranslateUnmovableBody);
    registerActionSolver(CreateAndTranslateVoxel.class, this::createAndTranslateVoxel);
    registerActionSolver(AttachClosestAnchors.class, this::attachClosestAnchors);
    registerActionSolver(DetachAllAnchorsFromAnchorable.class, this::detachAllAnchorsFromAnchorable);
    registerActionSolver(DetachAllAnchors.class, this::detachAllAnchors);
    registerActionSolver(TranslateAgent.class, this::translateAgent);
    registerActionSolver(AddAndTranslateAgent.class, this::addAndTranslateAgent);
  }

  protected Agent addAgent(AddAgent action, Agent agent) throws ActionException {
    if (action.agent() instanceof EmbodiedAgent embodiedAgent) {
      embodiedAgent.assemble(this);
      agentPairs.add(new Pair<>(action.agent(), List.of()));
    } else {
      agentPairs.add(new Pair<>(action.agent(), List.of()));
    }
    return action.agent();
  }

  protected RigidBody createAndTranslateRigidBody(
      CreateAndTranslateRigidBody action,
      Agent agent
  ) throws ActionException {
    RigidBody rigidBody = perform(
        new CreateRigidBody(action.poly(), action.mass()),
        agent
    ).outcome().orElseThrow(() -> new ActionException(action, "Undoable creation"));
    perform(new TranslateBody(rigidBody, action.translation()), agent);
    return rigidBody;
  }

  protected UnmovableBody createAndTranslateUnmovableBody(
      CreateAndTranslateUnmovableBody action,
      Agent agent
  ) throws ActionException {
    UnmovableBody unmovableBody = perform(
        new CreateUnmovableBody(action.poly()),
        agent
    ).outcome().orElseThrow(() -> new ActionException(action, "Undoable creation"));
    perform(new TranslateBody(unmovableBody, action.translation()), agent);
    return unmovableBody;
  }

  protected Voxel createAndTranslateVoxel(
      CreateAndTranslateVoxel action,
      Agent agent
  ) throws ActionException {
    Voxel voxel = perform(
        new CreateVoxel(action.sideLength(), action.mass(), action.material()),
        agent
    ).outcome().orElseThrow(() -> new ActionException(action, "Undoable creation"));
    perform(new TranslateBody(voxel, action.translation()), agent);
    return voxel;
  }

  protected Collection<Anchor.Link> attachClosestAnchors(AttachClosestAnchors action, Agent agent) {
    Point targetCenter = Point.average(action.targetAnchorable()
        .anchors()
        .stream()
        .map(Anchor::point)
        .toArray(Point[]::new));
    return action.sourceAnchorable().anchors().stream()
        .sorted(Comparator.comparingDouble(a -> a.point().distance(targetCenter)))
        .limit(action.nOfAnchors())
        .map(a -> perform(new AttachAnchor(a, action.targetAnchorable()), agent).outcome().orElseThrow())
        .toList();
  }

  protected Collection<Anchor.Link> detachAllAnchorsFromAnchorable(
      DetachAllAnchorsFromAnchorable action,
      Agent agent
  ) {
    return action.sourceAnchorable().anchors().stream()
        .map(a -> perform(new DetachAnchor(a, action.targetAnchorable()), agent).outcome().orElseThrow())
        .flatMap(Collection::stream)
        .toList();
  }

  protected Collection<Anchor.Link> detachAllAnchors(DetachAllAnchors action, Agent agent) {
    Set<Anchorable> anchorables = action.anchorable().anchors().stream()
        .map(a -> a.attachedAnchors().stream().map(Anchor::anchorable).collect(Collectors.toSet()))
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
    return anchorables.stream()
        .map(target -> perform(new DetachAllAnchorsFromAnchorable(action.anchorable(), target), agent).outcome()
            .orElseThrow())
        .flatMap(Collection::stream)
        .collect(Collectors.toSet());
  }

  protected EmbodiedAgent addAndTranslateAgent(AddAndTranslateAgent action, Agent agent) throws ActionException {
    EmbodiedAgent embodiedAgent = (EmbodiedAgent) perform(
        new AddAgent(action.agent()), agent)
        .outcome().orElseThrow(() -> new ActionException(action, "Undoable addition")
        );
    perform(new TranslateAgent(embodiedAgent, action.translation()), agent);
    return embodiedAgent;
  }

  protected EmbodiedAgent translateAgent(TranslateAgent action, Agent agent) {
    action.agent().bodyParts().forEach(b -> perform(new TranslateBody(b, action.translation()), agent));
    return action.agent();
  }

}
