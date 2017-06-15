package com.mesosphere.sdk.cockroachdb.scheduler;

import com.mesosphere.sdk.config.ConfigStore;
import com.mesosphere.sdk.scheduler.plan.*;
import com.mesosphere.sdk.scheduler.plan.strategy.SerialStrategy;
import com.mesosphere.sdk.scheduler.recovery.DefaultRecoveryStep;
import com.mesosphere.sdk.scheduler.recovery.RecoveryPlanOverrider;
//import com.mesosphere.sdk.scheduler.recovery.RecoveryType;
import com.mesosphere.sdk.scheduler.recovery.constrain.UnconstrainedLaunchConstrainer;
//import com.mesosphere.sdk.specification.CommandSpec;
//import com.mesosphere.sdk.specification.DefaultCommandSpec;
//import com.mesosphere.sdk.specification.DefaultPodSpec;
//import com.mesosphere.sdk.specification.DefaultTaskSpec;
//import com.mesosphere.sdk.specification.PodInstance;
//import com.mesosphere.sdk.specification.PodSpec;
import com.mesosphere.sdk.specification.ServiceSpec;
//import com.mesosphere.sdk.specification.TaskSpec;
import com.mesosphere.sdk.state.StateStore;
//import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
//import java.util.stream.Collectors;

/**
 * The CockroachdbRecoveryPlanManager handles failure scenarios unique to Cockroachdb. 
 * It falls back to the default recovery
 * behavior when appropriate.
 */
public class CockroachdbRecoveryPlanOverrider implements RecoveryPlanOverrider {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    private static final String RECOVERY_PHASE_NAME = "permanent-node-failure-recovery";
    private final StateStore stateStore;
    private final ConfigStore<ServiceSpec> configStore;
    private final Plan replaceNodePlan;

    public CockroachdbRecoveryPlanOverrider(
            StateStore stateStore,
            ConfigStore<ServiceSpec> configStore,
            Plan replaceNodePlan) {
        this.stateStore = stateStore;
        this.configStore = configStore;
        this.replaceNodePlan = replaceNodePlan;
    }

    @Override
    public Optional<Phase> override(PodInstanceRequirement stoppedPod) {
        if (!stoppedPod.getPodInstance().getPod().getType().equals("cockroachdb")) {
            logger.info("No overrides necessary. Pod is not a cockroachdb.");
            return Optional.empty();
        }

        Phase nnPhase = null;
        int index = stoppedPod.getPodInstance().getIndex();
        logger.info(String.format("Returning replacement plan for node %d.", index));
        return Optional.ofNullable(getNodeRecoveryPhase(replaceNodePlan, index));
    }

    private Phase getNodeRecoveryPhase(Plan inputPlan, int index) {
        Phase inputPhase = inputPlan.getChildren().get(0);
        Step inputLaunchStep = inputPhase.getChildren().get(index);

        // Dig all the way down into the command, so we can append the replace_address option to it.
        PodInstance podInstance = inputLaunchStep.start().get().getPodInstance();
        PodSpec podSpec = podInstance.getPod();
        TaskSpec taskSpec = podSpec.getTasks().stream().filter(t -> t.getName().equals("node-join")).findFirst().get();
        CommandSpec command = taskSpec.getCommand().get();

        // Rebuild a new PodSpec with the modified command, and add it to the phase we return.
        TaskSpec newTaskSpec = DefaultTaskSpec.newBuilder(taskSpec).commandSpec(command).build();
        List<TaskSpec> tasks = podSpec.getTasks().stream()
                .map(t -> {
                    if (t.getName().equals(newTaskSpec.getName())) {
                        return newTaskSpec;
                    }
                    return t;
                })
                .collect(Collectors.toList());
        PodSpec newPodSpec = DefaultPodSpec.newBuilder(podSpec).tasks(tasks).build();
        PodInstance newPodInstance = new DefaultPodInstance(newPodSpec, index);

        PodInstanceRequirement replacePodInstanceRequirement =
                PodInstanceRequirement.newBuilder(
                    newPodInstance, inputLaunchStep.start().get().getTasksToLaunch())
                .recoveryType(RecoveryType.PERMANENT)
                .build();
		
        Step replaceStep = new DefaultRecoveryStep(
                inputLaunchStep.getName(),
                Status.PENDING,
                replacePodInstanceRequirement,
                new UnconstrainedLaunchConstrainer(),
                stateStore);

        return new DefaultPhase(
                RECOVERY_PHASE_NAME, Arrays.asList(replaceStep), new SerialStrategy<>(), Collections.emptyList());
    }
}
