/**
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
package org.apache.aurora.scheduler.updater;

import java.util.Set;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.collect.DiscreteDomain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableRangeSet;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.twitter.common.quantity.Amount;
import com.twitter.common.quantity.Time;
import com.twitter.common.util.Clock;

import org.apache.aurora.gen.JobUpdateStatus;
import org.apache.aurora.scheduler.base.Numbers;
import org.apache.aurora.scheduler.storage.entities.IInstanceTaskConfig;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateInstructions;
import org.apache.aurora.scheduler.storage.entities.IJobUpdateSettings;
import org.apache.aurora.scheduler.storage.entities.IRange;
import org.apache.aurora.scheduler.storage.entities.IScheduledTask;
import org.apache.aurora.scheduler.storage.entities.ITaskConfig;
import org.apache.aurora.scheduler.updater.strategy.QueueStrategy;
import org.apache.aurora.scheduler.updater.strategy.UpdateStrategy;

import static java.util.Objects.requireNonNull;

import static com.google.common.base.Preconditions.checkArgument;

import static org.apache.aurora.scheduler.base.Numbers.toRange;

/**
 * A factory that produces job updaters based on a job update configuration.
 * <p>
 * TODO(wfarner): Use AssistedInject to inject this (github.com/google/guice/wiki/AssistedInject).
 */
interface UpdateFactory {

  /**
   * Creates a one-way job updater that will execute the job update configuration in the direction
   * specified by {@code rollingForward}.
   *
   * @param configuration Configuration to act on.
   * @param rollingForward {@code true} if this is a job update, {@code false} if it is a rollback.
   * @return An updater that will execute the job update as specified in the
   *         {@code configuration}.
   */
  Update newUpdate(
      IJobUpdateInstructions configuration,
      boolean rollingForward);

  class UpdateFactoryImpl implements UpdateFactory {
    private final Clock clock;

    @Inject
    UpdateFactoryImpl(Clock clock) {
      this.clock = requireNonNull(clock);
    }

    @Override
    public Update newUpdate(IJobUpdateInstructions instructions, boolean rollingForward) {
      requireNonNull(instructions);
      IJobUpdateSettings settings = instructions.getSettings();
      checkArgument(
          settings.getMaxWaitToInstanceRunningMs() > 0,
          "Max wait to running must be positive.");
      checkArgument(
          settings.getMinWaitInInstanceRunningMs() > 0,
          "Min wait in running must be positive.");
      checkArgument(
          settings.getUpdateGroupSize() > 0,
          "Update group size must be positive.");
      checkArgument(
          !instructions.getDesiredState().getInstances().isEmpty(),
          "Instance count must be positive.");

      Set<Integer> instances;
      Set<Integer> desiredInstances =
          expandInstanceIds(ImmutableSet.of(instructions.getDesiredState()));

      if (settings.getUpdateOnlyTheseInstances().isEmpty()) {
        // In a full job update, the working set is the union of instance IDs before and after.
        instances =  ImmutableSet.copyOf(
            Sets.union(expandInstanceIds(instructions.getInitialState()), desiredInstances));
      } else {
        instances = Numbers.rangesToInstanceIds(settings.getUpdateOnlyTheseInstances());
      }

      ImmutableMap.Builder<Integer, StateEvaluator<Optional<IScheduledTask>>> evaluators =
          ImmutableMap.builder();
      for (int instanceId : instances) {
        Optional<ITaskConfig> desiredState;
        if (rollingForward) {
          desiredState = desiredInstances.contains(instanceId)
              ? Optional.of(instructions.getDesiredState().getTask())
              : Optional.<ITaskConfig>absent();
        } else {
          desiredState = getConfig(instanceId, instructions.getInitialState());
        }

        evaluators.put(
            instanceId,
            new InstanceUpdater(
                desiredState,
                settings.getMaxPerInstanceFailures(),
                Amount.of((long) settings.getMinWaitInInstanceRunningMs(), Time.MILLISECONDS),
                Amount.of((long) settings.getMaxWaitToInstanceRunningMs(), Time.MILLISECONDS),
                clock));
      }

      Ordering<Integer> updateOrder = rollingForward
          ? Ordering.<Integer>natural()
          : Ordering.<Integer>natural().reverse();

      // TODO(wfarner): Add the batch_completion flag to JobUpdateSettings and pick correct
      // strategy.
      UpdateStrategy<Integer> strategy =
          new QueueStrategy<>(updateOrder, settings.getUpdateGroupSize());

      return new Update(
          new OneWayJobUpdater<>(
              strategy,
              settings.getMaxFailedInstances(),
              evaluators.build()),
          rollingForward);
    }

    @VisibleForTesting
    static Set<Integer> expandInstanceIds(Set<IInstanceTaskConfig> instanceGroups) {
      ImmutableRangeSet.Builder<Integer> instanceIds = ImmutableRangeSet.builder();
      for (IInstanceTaskConfig group : instanceGroups) {
        for (IRange range : group.getInstances()) {
          instanceIds.add(toRange(range));
        }
      }

      return instanceIds.build().asSet(DiscreteDomain.integers());
    }

    private static Optional<ITaskConfig> getConfig(
        int id,
        Set<IInstanceTaskConfig> instanceGroups) {

      for (IInstanceTaskConfig group : instanceGroups) {
        for (IRange range : group.getInstances()) {
          if (toRange(range).contains(id)) {
            return Optional.of(group.getTask());
          }
        }
      }

      return Optional.absent();
    }
  }

  class Update {
    private final OneWayJobUpdater<Integer, Optional<IScheduledTask>> updater;
    private final boolean rollingForward;

    Update(OneWayJobUpdater<Integer, Optional<IScheduledTask>> updater, boolean rollingForward) {
      this.updater = requireNonNull(updater);
      this.rollingForward = rollingForward;
    }

    OneWayJobUpdater<Integer, Optional<IScheduledTask>> getUpdater() {
      return updater;
    }

    JobUpdateStatus getSuccessStatus() {
      return rollingForward ? JobUpdateStatus.ROLLED_FORWARD : JobUpdateStatus.ROLLED_BACK;
    }

    JobUpdateStatus getFailureStatus() {
      return rollingForward ? JobUpdateStatus.ROLLING_BACK : JobUpdateStatus.FAILED;
    }
  }
}