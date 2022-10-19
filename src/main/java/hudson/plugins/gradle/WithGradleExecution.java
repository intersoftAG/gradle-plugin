package hudson.plugins.gradle;

import hudson.model.Run;
import hudson.model.TaskListener;
import org.jenkinsci.plugins.workflow.graph.FlowNode;
import org.jenkinsci.plugins.workflow.log.TaskListenerDecorator;
import org.jenkinsci.plugins.workflow.steps.BodyExecutionCallback;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.jenkinsci.plugins.workflow.steps.StepExecution;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.List;

public class WithGradleExecution extends StepExecution {
    private final String buildScanLabel;

    public WithGradleExecution(StepContext context, WithGradle withGradle) {
        super(context);
        this.buildScanLabel = withGradle.getBuildScanLabel();
    }

    @Override
    public boolean start() throws IOException, InterruptedException {
        GradleTaskListenerDecorator decorator = new GradleTaskListenerDecorator();

        getContext().newBodyInvoker()
                .withContext(TaskListenerDecorator.merge(getContext().get(TaskListenerDecorator.class), decorator))
                .withCallback(new BuildScanCallback(decorator, getContext(), buildScanLabel)).start();

        return false;
    }

    private static class BuildScanCallback extends BodyExecutionCallback {
        private final GradleTaskListenerDecorator decorator;
        private final StepContext parentContext;
        private final String buildScanLabel;

        public BuildScanCallback(GradleTaskListenerDecorator decorator, StepContext parentContext, String buildScanLabel) {
            this.decorator = decorator;
            this.parentContext = parentContext;
            this.buildScanLabel = buildScanLabel;
        }

        @Override
        public void onSuccess(StepContext context, Object result) {
            parentContext.onSuccess(extractBuildScans(context));
        }

        private List<String> extractBuildScans(StepContext context) {
            try {
                PrintStream logger = context.get(TaskListener.class).getLogger();

                if (decorator == null) {
                    logger.println("WARNING: No decorator found, not looking for build scans");
                    return Collections.emptyList();
                }
                List<String> buildScans = decorator.getBuildScans();
                if (buildScans.isEmpty()) {
                    return Collections.emptyList();
                }
                Run run = context.get(Run.class);
                FlowNode flowNode = context.get(FlowNode.class);
                flowNode.getParents().stream().findFirst().ifPresent(parent -> {
                    BuildScanFlowAction nodeBuildScanAction = new BuildScanFlowAction(parent);
                    buildScans.forEach(buildScanUrl -> nodeBuildScanAction.addScanUrl(buildScanUrl, buildScanLabel));
                    parent.addAction(nodeBuildScanAction);
                });

                BuildScanAction existingAction = run.getAction(BuildScanAction.class);
                BuildScanAction buildScanAction = existingAction == null
                        ? new BuildScanAction()
                        : existingAction;
                buildScans.forEach(buildScanUrl -> buildScanAction.addScanUrl(buildScanUrl, buildScanLabel));
                if (existingAction == null) {
                    run.addAction(buildScanAction);
                }
                return buildScans;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        @Override
        public void onFailure(StepContext context, Throwable t) {
            parentContext.onFailure(t);
            extractBuildScans(context);
        }
    }
}
