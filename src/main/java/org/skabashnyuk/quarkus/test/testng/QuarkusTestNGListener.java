package org.skabashnyuk.quarkus.test.testng;

import io.quarkus.bootstrap.app.AdditionalDependency;
import io.quarkus.bootstrap.app.AugmentAction;
import io.quarkus.bootstrap.app.CuratedApplication;
import io.quarkus.bootstrap.app.QuarkusBootstrap;
import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import io.quarkus.bootstrap.app.StartupAction;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.TestAnnotationBuildItem;
import io.quarkus.deployment.builditem.TestClassBeanBuildItem;
import io.quarkus.deployment.builditem.TestClassPredicateBuildItem;
import io.quarkus.runtime.Timing;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.PropertyTestUtil;
import io.quarkus.test.common.TestClassIndexer;
import io.quarkus.test.common.TestResourceManager;
import io.quarkus.test.common.http.TestHTTPResourceManager;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.Type;
import org.skabashnyuk.quarkus.test.testng.callback.QuarkusTestAfterEachCallback;
import org.skabashnyuk.quarkus.test.testng.callback.QuarkusTestBeforeAllCallback;
import org.skabashnyuk.quarkus.test.testng.callback.QuarkusTestBeforeEachCallback;
import org.testng.ITestContext;
import org.testng.ITestListener;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static io.quarkus.test.common.PathTestHelper.getAppClassLocation;
import static io.quarkus.test.common.PathTestHelper.getTestClassesLocation;

public class QuarkusTestNGListener implements ITestListener {
  public static final String TEST_LOCATION = "test-location";
  public static final String TEST_CLASS = "test-class";
  private static ClassLoader originalCl;
  private static Path testClassLocation;
  private static boolean failedBoot;
  private static Throwable
      firstException; // if this is set then it will be thrown from the very first test that is run,
                      // the rest are aborted
  private static RunningQuarkusApplication runningQuarkusApplication;
  private static List<Object> beforeAllCallbacks = new ArrayList<>();
  private static List<Object> beforeEachCallbacks = new ArrayList<>();
  private static List<Object> afterEachCallbacks = new ArrayList<>();

  public void onStart(ITestContext context) {
    if (isNativeTest(context)) {
      return;
    }
    ensureStarted(context);
    if (runningQuarkusApplication != null) {
      //pushMockContext();
      setCCL(runningQuarkusApplication.getClassLoader());
    }
  }

  public void onFinish(ITestContext context) {}

  private ExtensionState ensureStarted(ITestContext extensionContext) {
    //    ExtensionContext root = extensionContext.getRoot();
    //    ExtensionContext.Store store = root.getStore(ExtensionContext.Namespace.GLOBAL);
    //    ExtensionState state = store.get(ExtensionState.class.getName(), ExtensionState.class);
    ExtensionState state = (ExtensionState) extensionContext.getAttribute(ExtensionState.class.getName());
    if (state == null && !failedBoot) {
      PropertyTestUtil.setLogFileProperty();
      try {
        state = doJavaStart(extensionContext);
        //store.put(ExtensionState.class.getName(), state);
         extensionContext.setAttribute(ExtensionState.class.getName(), state);

      } catch (Throwable e) {
        failedBoot = true;
        firstException = e;
      }
    }
    return state;
  }

  private ExtensionState doJavaStart(ITestContext context) throws Throwable {
    Closeable testResourceManager = null;
    try {
      final LinkedBlockingDeque<Runnable> shutdownTasks = new LinkedBlockingDeque<>();

      Class<?> requiredTestClass = context.getCurrentXmlTest().getClasses().get(0).getSupportClass(); //context.gegetRequiredTestClass();
      Path appClassLocation = getAppClassLocation(requiredTestClass);

      final QuarkusBootstrap.Builder runnerBuilder =
          QuarkusBootstrap.builder(appClassLocation)
              .setIsolateDeployment(true)
              .setMode(QuarkusBootstrap.Mode.TEST);

      originalCl = Thread.currentThread().getContextClassLoader();
      testClassLocation = getTestClassesLocation(requiredTestClass);

      if (!appClassLocation.equals(testClassLocation)) {
        runnerBuilder.addAdditionalApplicationArchive(
            new AdditionalDependency(testClassLocation, false, true, true));
      }
      CuratedApplication curatedApplication =
          runnerBuilder
              .setTest(true)
              .setProjectRoot(Files.isDirectory(appClassLocation) ? new File("").toPath() : null)
              .build()
              .bootstrap();

      Index testClassesIndex = TestClassIndexer.indexTestClasses(requiredTestClass);
      // we need to write the Index to make it reusable from other parts of the testing
      // infrastructure that run in different ClassLoaders
      TestClassIndexer.writeIndex(testClassesIndex, requiredTestClass);

      Timing.staticInitStarted(curatedApplication.getBaseRuntimeClassLoader());
      final Map<String, Object> props = new HashMap<>();
      props.put(TEST_LOCATION, testClassLocation);
      props.put(TEST_CLASS, requiredTestClass);
      AugmentAction augmentAction =
          curatedApplication.createAugmentor(TestBuildChainFunction.class.getName(), props);
      StartupAction startupAction = augmentAction.createInitialRuntimeApplication();
      Thread.currentThread().setContextClassLoader(startupAction.getClassLoader());

      // must be done after the TCCL has been set
      testResourceManager =
          (Closeable)
              startupAction
                  .getClassLoader()
                  .loadClass(TestResourceManager.class.getName())
                  .getConstructor(Class.class)
                  .newInstance(requiredTestClass);
      testResourceManager.getClass().getMethod("start").invoke(testResourceManager);

      populateCallbacks(startupAction.getClassLoader());

      runningQuarkusApplication = startupAction.run();

      ConfigProviderResolver.setInstance(new RunningAppConfigResolver(runningQuarkusApplication));

      System.setProperty("test.url", TestHTTPResourceManager.getUri(runningQuarkusApplication));

      Closeable tm = testResourceManager;
      Closeable shutdownTask =
          new Closeable() {
            @Override
            public void close() throws IOException {
              try {
                runningQuarkusApplication.close();
              } catch (Exception e) {
                throw new RuntimeException(e);
              } finally {
                try {
                  while (!shutdownTasks.isEmpty()) {
                    shutdownTasks.pop().run();
                  }
                } finally {
                  tm.close();
                }
              }
            }
          };
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  new Runnable() {
                    @Override
                    public void run() {
                      try {
                        shutdownTask.close();
                      } catch (IOException e) {
                        e.printStackTrace();
                      } finally {
                        curatedApplication.close();
                      }
                    }
                  },
                  "Quarkus Test Cleanup Shutdown task"));
      return new ExtensionState(testResourceManager, shutdownTask);
    } catch (Throwable e) {

      try {
        if (testResourceManager != null) {
          testResourceManager.close();
        }
      } catch (Exception ex) {
        e.addSuppressed(ex);
      }
      throw e;
    }
  }

  private boolean isNativeTest(ITestContext context) {
    return context
        .getClass()
        .isAnnotationPresent(
            NativeImageTest
                .class); // getSuite()getRequiredTestClass().isAnnotationPresent(NativeImageTest.class);
  }

  private void populateCallbacks(ClassLoader classLoader) throws ClassNotFoundException {
    ServiceLoader<?> quarkusTestBeforeAllLoader =
        ServiceLoader.load(
            Class.forName(QuarkusTestBeforeAllCallback.class.getName(), false, classLoader),
            classLoader);
    for (Object quarkusTestBeforeAllCallback : quarkusTestBeforeAllLoader) {
      beforeAllCallbacks.add(quarkusTestBeforeAllCallback);
    }
    ServiceLoader<?> quarkusTestBeforeEachLoader =
        ServiceLoader.load(
            Class.forName(QuarkusTestBeforeEachCallback.class.getName(), false, classLoader),
            classLoader);
    for (Object quarkusTestBeforeEachCallback : quarkusTestBeforeEachLoader) {
      beforeEachCallbacks.add(quarkusTestBeforeEachCallback);
    }
    ServiceLoader<?> quarkusTestAfterEachLoader =
        ServiceLoader.load(
            Class.forName(QuarkusTestAfterEachCallback.class.getName(), false, classLoader),
            classLoader);
    for (Object quarkusTestAfterEach : quarkusTestAfterEachLoader) {
      afterEachCallbacks.add(quarkusTestAfterEach);
    }
  }

  private static ClassLoader setCCL(ClassLoader cl) {
    final Thread thread = Thread.currentThread();
    final ClassLoader original = thread.getContextClassLoader();
    thread.setContextClassLoader(cl);
    return original;
  }

  class ExtensionState implements Closeable {

    private final Closeable testResourceManager;
    private final Closeable resource;

    ExtensionState(Closeable testResourceManager, Closeable resource) {
      this.testResourceManager = testResourceManager;
      this.resource = resource;
    }

    @Override
    public void close() throws IOException {
      try {
        resource.close();
      } finally {
        if (QuarkusTestNGListener.this.originalCl != null) {
          setCCL(QuarkusTestNGListener.this.originalCl);
        }
        testResourceManager.close();
      }
    }
  }
}
