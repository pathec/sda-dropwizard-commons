package org.sdase.commons.server.spring.data.mongo;

import io.dropwizard.Configuration;
import java.util.function.Function;

@FunctionalInterface
public interface MongoConfigurationProvider<C extends Configuration>
    extends Function<C, MongoConfiguration> {}
