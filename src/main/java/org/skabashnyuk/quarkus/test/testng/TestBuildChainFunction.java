package org.skabashnyuk.quarkus.test.testng;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.TestAnnotationBuildItem;
import io.quarkus.deployment.builditem.TestClassBeanBuildItem;
import io.quarkus.deployment.builditem.TestClassPredicateBuildItem;
import io.quarkus.test.common.PathTestHelper;
import io.quarkus.test.common.TestClassIndexer;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.FieldInfo;
import org.jboss.jandex.Index;
import org.jboss.jandex.Type;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public  class TestBuildChainFunction
        implements Function<Map<String, Object>, List<Consumer<BuildChainBuilder>>> {

    @Override
    public List<Consumer<BuildChainBuilder>> apply(Map<String, Object> stringObjectMap) {
        Path testLocation = (Path) stringObjectMap.get(QuarkusTestNGListener.TEST_LOCATION);
        // the index was written by the extension
        Index testClassesIndex =
                TestClassIndexer.readIndex((Class<?>) stringObjectMap.get(QuarkusTestNGListener.TEST_CLASS));
        return Collections.singletonList(
                new Consumer<BuildChainBuilder>() {

                    private static final int ANNOTATION = 0x00002000;

                    boolean isAnnotation(final int mod) {
                        return (mod & ANNOTATION) != 0;
                    }

                    @Override
                    public void accept(BuildChainBuilder buildChainBuilder) {
                        buildChainBuilder
                                .addBuildStep(
                                        new BuildStep() {
                                            @Override
                                            public void execute(BuildContext context) {
                                                context.produce(
                                                        new TestClassPredicateBuildItem(
                                                                new Predicate<String>() {
                                                                    @Override
                                                                    public boolean test(String className) {
                                                                        return PathTestHelper.isTestClass(
                                                                                className,
                                                                                Thread.currentThread().getContextClassLoader(),
                                                                                testLocation);
                                                                    }
                                                                }));
                                            }
                                        })
                                .produces(TestClassPredicateBuildItem.class)
                                .build();

                        buildChainBuilder
                                .addBuildStep(
                                        new BuildStep() {
                                            @Override
                                            public void execute(BuildContext context) {
                                                throw new RuntimeException("NotImplemented");
                                                // context.produce(new
                                                // TestAnnotationBuildItem(QuarkusTest.class.getName()));
                                            }
                                        })
                                .produces(TestAnnotationBuildItem.class)
                                .build();

                        List<String> testClassBeans = new ArrayList<>();

                        List<AnnotationInstance> extendWith =
                                testClassesIndex.getAnnotations(DotNames.EXTEND_WITH);
                        for (AnnotationInstance annotationInstance : extendWith) {
                            if (annotationInstance.target().kind() != AnnotationTarget.Kind.CLASS) {
                                continue;
                            }
                            ClassInfo classInfo = annotationInstance.target().asClass();
                            if (isAnnotation(classInfo.flags())) {
                                continue;
                            }
                            Type[] extendsWithTypes = annotationInstance.value().asClassArray();
                            for (Type type : extendsWithTypes) {
                                if (DotNames.QUARKUS_TEST_EXTENSION.equals(type.name())) {
                                    testClassBeans.add(classInfo.name().toString());
                                }
                            }
                        }

                        List<AnnotationInstance> registerExtension =
                                testClassesIndex.getAnnotations(DotNames.REGISTER_EXTENSION);
                        for (AnnotationInstance annotationInstance : registerExtension) {
                            if (annotationInstance.target().kind() != AnnotationTarget.Kind.FIELD) {
                                continue;
                            }
                            FieldInfo fieldInfo = annotationInstance.target().asField();
                            if (DotNames.QUARKUS_TEST_EXTENSION.equals(fieldInfo.type().name())) {
                                testClassBeans.add(fieldInfo.declaringClass().name().toString());
                            }
                        }

                        if (!testClassBeans.isEmpty()) {
                            buildChainBuilder
                                    .addBuildStep(
                                            new BuildStep() {
                                                @Override
                                                public void execute(BuildContext context) {
                                                    for (String quarkusExtendWithTestClass : testClassBeans) {
                                                        context.produce(
                                                                new TestClassBeanBuildItem(quarkusExtendWithTestClass));
                                                    }
                                                }
                                            })
                                    .produces(TestClassBeanBuildItem.class)
                                    .build();
                        }
                    }
                });
    }
}