package com.google.jstestdriver.idea.coverage;

import com.google.jstestdriver.idea.execution.JstdRunConfiguration;
import com.google.jstestdriver.idea.execution.JstdRunProfileState;
import com.google.jstestdriver.idea.execution.NopProcessHandler;
import com.google.jstestdriver.idea.server.JstdBrowserInfo;
import com.google.jstestdriver.idea.server.JstdServer;
import com.google.jstestdriver.idea.server.JstdServerLifeCycleAdapter;
import com.google.jstestdriver.idea.server.JstdServerRegistry;
import com.google.jstestdriver.idea.server.ui.JstdToolWindowManager;
import com.intellij.coverage.CoverageExecutor;
import com.intellij.coverage.CoverageHelper;
import com.intellij.coverage.CoverageRunnerData;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionResult;
import com.intellij.execution.Executor;
import com.intellij.execution.RunProfileStarter;
import com.intellij.execution.configurations.ConfigurationInfoProvider;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.configurations.RunProfileState;
import com.intellij.execution.configurations.RunnerSettings;
import com.intellij.execution.configurations.coverage.CoverageEnabledConfiguration;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.AsyncGenericProgramRunner;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.runners.RunContentBuilder;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.util.NullableConsumer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class JstdCoverageProgramRunner extends AsyncGenericProgramRunner {

  private static final String COVERAGE_RUNNER_ID = JstdCoverageProgramRunner.class.getSimpleName();

  @NotNull
  @Override
  public String getRunnerId() {
    return COVERAGE_RUNNER_ID;
  }

  @Override
  public boolean canRun(@NotNull String executorId, @NotNull RunProfile profile) {
    return CoverageExecutor.EXECUTOR_ID.equals(executorId) && profile instanceof JstdRunConfiguration;
  }

  @Override
  public RunnerSettings createConfigurationData(final ConfigurationInfoProvider settingsProvider) {
    return new CoverageRunnerData();
  }

  @NotNull
  @Override
  protected AsyncResult<RunProfileStarter> prepare(@NotNull Project project,
                                                   @NotNull ExecutionEnvironment environment,
                                                   @NotNull RunProfileState state) throws ExecutionException {
    JstdRunProfileState jstdState = JstdRunProfileState.cast(state);
    if (jstdState.getRunSettings().isExternalServerType()) {
      return AsyncResult.<RunProfileStarter>done(new MyStarter(null));
    }
    JstdToolWindowManager jstdToolWindowManager = JstdToolWindowManager.getInstance(project);
    jstdToolWindowManager.setAvailable(true);
    JstdServer server = JstdServerRegistry.getInstance().getServer();
    if (server != null && !server.isStopped()) {
      return AsyncResult.<RunProfileStarter>done(new MyStarter(server));
    }
    final AsyncResult<RunProfileStarter> result = new AsyncResult<RunProfileStarter>();
    jstdToolWindowManager.restartServer(new NullableConsumer<JstdServer>() {
      @Override
      public void consume(@Nullable JstdServer server) {
        if (server != null) {
          result.setDone(new MyStarter(server));
        }
        else {
          result.setDone(null);
        }
      }
    });
    return result;
  }

  public static class MyStarter extends RunProfileStarter {
    private final JstdServer myServer;

    public MyStarter(@Nullable JstdServer server) {
      myServer = server;
    }

    @Nullable
    @Override
    public RunContentDescriptor execute(@NotNull Project project,
                                        @NotNull Executor executor,
                                        @NotNull RunProfileState state,
                                        @Nullable RunContentDescriptor contentToReuse,
                                        @NotNull ExecutionEnvironment environment) throws ExecutionException {
      FileDocumentManager.getInstance().saveAllDocuments();
      JstdRunConfiguration runConfiguration = (JstdRunConfiguration) environment.getRunProfile();
      CoverageEnabledConfiguration coverageEnabledConfiguration = CoverageEnabledConfiguration.getOrCreate(runConfiguration);
      String coverageFilePath = coverageEnabledConfiguration.getCoverageFilePath();
      JstdRunProfileState jstdState = new JstdRunProfileState(project, environment, runConfiguration.getRunSettings(), coverageFilePath);
      ExecutionResult executionResult = jstdState.executeWithServer(myServer);

      RunContentBuilder contentBuilder = new RunContentBuilder(executionResult, environment);
      final RunContentDescriptor descriptor = contentBuilder.showRunContent(contentToReuse);
      ProcessHandler processHandler = executionResult.getProcessHandler();
      if (processHandler instanceof NopProcessHandler) {
        if (myServer != null) {
          myServer.addLifeCycleListener(new JstdServerLifeCycleAdapter() {
            @Override
            public void onBrowserCaptured(@NotNull JstdBrowserInfo info) {
              ExecutionUtil.restartIfActive(descriptor);
              myServer.removeLifeCycleListener(this);
            }
          }, contentBuilder);
        }
      }
      else {
        CoverageHelper.attachToProcess(runConfiguration, processHandler, environment.getRunnerSettings());
      }
      return descriptor;
    }
  }
}
