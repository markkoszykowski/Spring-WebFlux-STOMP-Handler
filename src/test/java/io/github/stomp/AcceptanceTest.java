package io.github.stomp;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines(value = {"cucumber"})
@SelectClasspathResource(value = "feature")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME, value = "pretty")
@ConfigurationParameter(key = Constants.FILTER_TAGS_PROPERTY_NAME, value = "not @Sample")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "io.github.stomp.config")
public class AcceptanceTest {
}
