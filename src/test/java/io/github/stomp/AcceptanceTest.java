package io.github.stomp;

import io.cucumber.junit.platform.engine.Constants;
import org.junit.platform.suite.api.*;

@Suite
@IncludeEngines(value = {"cucumber"})
@SelectClasspathResource(value = "feature")
@ConfigurationParameter(key = Constants.PLUGIN_PROPERTY_NAME, value = "pretty")
@ConfigurationParameter(key = Constants.FILTER_TAGS_PROPERTY_NAME, value = "not @Sandbox")
@ConfigurationParameter(key = Constants.GLUE_PROPERTY_NAME, value = "io.github.stomp")
public class AcceptanceTest {
}
