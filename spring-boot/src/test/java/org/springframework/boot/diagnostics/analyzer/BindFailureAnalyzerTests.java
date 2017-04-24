/*
 * Copyright 2012-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.diagnostics.analyzer;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.validation.Valid;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;
import org.springframework.validation.annotation.Validated;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BindFailureAnalyzer}.
 *
 * @author Andy Wilkinson
 * @author Madhura Bhave
 */
public class BindFailureAnalyzerTests {

	@Before
	public void setup() {
		LocaleContextHolder.setLocale(Locale.US);
	}

	@After
	public void cleanup() {
		LocaleContextHolder.resetLocaleContext();
	}

	@Test
	public void bindExceptionWithFieldErrorsDueToValidationFailure() {
		FailureAnalysis analysis = performAnalysis(
				FieldValidationFailureConfiguration.class);
		assertThat(analysis.getDescription())
				.contains(failure("test.foo.foo", "null", "may not be null"));
		assertThat(analysis.getDescription())
				.contains(failure("test.foo.value", "0", "at least five"));
		assertThat(analysis.getDescription())
				.contains(failure("test.foo.nested.bar", "null", "may not be null"));
	}

	@Test
	public void bindExceptionWithObjectErrorsDueToValidationFailure() throws Exception {
		FailureAnalysis analysis = performAnalysis(
				ObjectValidationFailureConfiguration.class);
		assertThat(analysis.getDescription())
				.contains("Reason: This object could not be bound.");
	}

	@Test
	public void bindExceptionWithOriginDueToValidationFailure() throws Exception {
		FailureAnalysis analysis = performAnalysis(
				FieldValidationFailureConfiguration.class, "test.foo.value=4");
		assertThat(analysis.getDescription())
				.contains("Origin: \"test.foo.value\" from property source \"test\"");
	}

	@Test
	public void bindExceptionDueToUnboundElements() throws Exception {
		FailureAnalysis analysis = performAnalysis(
				UnboundElementsFailureConfiguration.class, "test.foo.listValue[0]=hello",
				"test.foo.listValue[2]=world");
		assertThat(analysis.getDescription()).contains(failure("test.foo.listvalue[2]",
				"world", "\"test.foo.listValue[2]\" from property source \"test\"",
				"The elements [test.foo.listvalue[2]] were left unbound."));
	}

	@Test
	public void bindExceptionDueToOtherFailure() throws Exception {
		FailureAnalysis analysis = performAnalysis(GenericFailureConfiguration.class,
				"test.foo.value=${BAR}");
		assertThat(analysis.getDescription()).contains(failure("test.foo.value", "${BAR}",
				"\"test.foo.value\" from property source \"test\"",
				"Could not resolve placeholder 'BAR' in value \"${BAR}\""));
	}

	private static String failure(String property, String value, String reason) {
		return String.format("Property: %s%n    Value: %s%n    Reason: %s", property,
				value, reason);
	}

	private static String failure(String property, String value, String origin,
			String reason) {
		return String.format(
				"Property: %s%n    Value: %s%n    Origin: %s%n    Reason: %s", property,
				value, origin, reason);
	}

	private FailureAnalysis performAnalysis(Class<?> configuration,
			String... environment) {
		BeanCreationException failure = createFailure(configuration, environment);
		assertThat(failure).isNotNull();
		return new BindFailureAnalyzer().analyze(failure);
	}

	private BeanCreationException createFailure(Class<?> configuration,
			String... environment) {
		try {
			AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
			addEnvironment(context, environment);
			context.register(configuration);
			context.refresh();
			context.close();
			return null;
		}
		catch (BeanCreationException ex) {
			return ex;
		}
	}

	private void addEnvironment(AnnotationConfigApplicationContext context,
			String[] environment) {
		MutablePropertySources sources = context.getEnvironment().getPropertySources();
		Map<String, Object> map = new HashMap<>();
		for (String pair : environment) {
			int index = pair.indexOf("=");
			String key = pair.substring(0, index > 0 ? index : pair.length());
			String value = index > 0 ? pair.substring(index + 1) : "";
			map.put(key.trim(), value.trim());
		}
		sources.addFirst(new MapPropertySource("test", map));
	}

	@EnableConfigurationProperties(FieldValidationFailureProperties.class)
	static class FieldValidationFailureConfiguration {

	}

	@EnableConfigurationProperties(ObjectErrorFailureProperties.class)
	static class ObjectValidationFailureConfiguration {

	}

	@EnableConfigurationProperties(UnboundElementsFailureProperties.class)
	static class UnboundElementsFailureConfiguration {

	}

	@EnableConfigurationProperties(GenericFailureProperties.class)
	static class GenericFailureConfiguration {

	}

	@ConfigurationProperties("test.foo")
	@Validated
	static class FieldValidationFailureProperties {

		@NotNull
		private String foo;

		@Min(value = 5, message = "at least five")
		private int value;

		@Valid
		private Nested nested = new Nested();

		public String getFoo() {
			return this.foo;
		}

		public void setFoo(String foo) {
			this.foo = foo;
		}

		public int getValue() {
			return this.value;
		}

		public void setValue(int value) {
			this.value = value;
		}

		public Nested getNested() {
			return this.nested;
		}

		public void setNested(Nested nested) {
			this.nested = nested;
		}

		static class Nested {

			@NotNull
			private String bar;

			public String getBar() {
				return this.bar;
			}

			public void setBar(String bar) {
				this.bar = bar;
			}

		}

	}

	@ConfigurationProperties("foo.bar")
	@Validated
	static class ObjectErrorFailureProperties implements Validator {

		@Override
		public void validate(Object target, Errors errors) {
			errors.reject("my.objectError", "This object could not be bound.");
		}

		@Override
		public boolean supports(Class<?> clazz) {
			return true;
		}

	}

	@ConfigurationProperties("test.foo")
	static class UnboundElementsFailureProperties {

		private List<String> listValue;

		public List<String> getListValue() {
			return this.listValue;
		}

		public void setListValue(List<String> listValue) {
			this.listValue = listValue;
		}
	}

	@ConfigurationProperties("test.foo")
	static class GenericFailureProperties {

		private String value;

		public String getValue() {
			return this.value;
		}

		public void setValue(String value) {
			this.value = value;
		}
	}

}
