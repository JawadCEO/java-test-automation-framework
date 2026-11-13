package com.jawad.qa.bdd;



import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = "cucumber.glue", value = "com.jawad.qa.bdd")
@ConfigurationParameter(key = "cucumber.plugin", value = "io.qameta.allure.cucumber7jvm.AllureCucumber7Jvm")
class BookingAcceptanceTest {}
