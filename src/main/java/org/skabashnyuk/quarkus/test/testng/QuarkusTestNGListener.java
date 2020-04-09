package org.skabashnyuk.quarkus.test.testng;

import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import org.testng.ITestContext;
import org.testng.ITestListener;

public class QuarkusTestNGListener implements ITestListener {

  private static RunningQuarkusApplication runningQuarkusApplication;

  public void onStart(ITestContext context) {}

  public void onFinish(ITestContext context) {}

  private boolean isNativeTest(ITestContext context) {
    return context
        .getClass()
        .isAnnotationPresent(
            NativeImageTest
                .class); // getSuite()getRequiredTestClass().isAnnotationPresent(NativeImageTest.class);
  }
}
