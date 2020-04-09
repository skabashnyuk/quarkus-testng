package org.skabashnyuk.quarkus.test.testng;

import io.quarkus.bootstrap.app.RunningQuarkusApplication;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigBuilder;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.Collections;
import java.util.Optional;

class RunningAppConfigResolver extends ConfigProviderResolver {
    private final RunningQuarkusApplication runningQuarkusApplication;

    RunningAppConfigResolver(RunningQuarkusApplication runningQuarkusApplication) {
        this.runningQuarkusApplication = runningQuarkusApplication;
    }

    @Override
    public Config getConfig() {
        return new Config() {
            @Override
            public <T> T getValue(String propertyName, Class<T> propertyType) {
                return runningQuarkusApplication.getConfigValue(propertyName, propertyType).get();
            }

            @Override
            public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
                return runningQuarkusApplication.getConfigValue(propertyName, propertyType);
            }

            @Override
            public Iterable<String> getPropertyNames() {
                return runningQuarkusApplication.getConfigKeys();
            }

            @Override
            public Iterable<ConfigSource> getConfigSources() {
                return Collections.emptyList();
            }
        };
    }

    @Override
    public Config getConfig(ClassLoader loader) {
        return getConfig();
    }

    @Override
    public ConfigBuilder getBuilder() {
        return null;
    }

    @Override
    public void registerConfig(Config config, ClassLoader classLoader) {

    }

    @Override
    public void releaseConfig(Config config) {

    }
}
