# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

SpringAIActions is a framework for building creating an action plan based on natural language inputs.
If is based on the principle that an LLM invocation should not perform changes on the local system via side effects i.e.
via tool calls, but rather return a plan that can be executed under the application's control.

## Build and Test Commands

```bash
# Build the project
./gradlew build

# Run all tests
./gradlew test

# Run a single test class
./gradlew test --tests "SomeTestClass"

# Run a single test method
./gradlew test --tests "SomeTestClass.someTestMethod"

# Publish to local Maven repository
./gradlew publishLocal

# Generate code coverage report (output: build/reports/jacoco)
./gradlew test jacocoTestReport
```

## Conventions

- Java 21 required
- Test subjects in `src/test/java/**/testsubjects/**` are executed via JUnit TestKit, not directly
- Construct all test assertions with assertj's `assertThat`
- All non-trivial functionality is rigorously tested using unit tests or, if dependent on resources outside the test's control, integration tests
- Observe a consistent and comparable level of abstraction in any given unit of functionality. Example public methods expressing high-level business functionality should not contain detail functionality like string concatenation or file I/O in the method body. Similarly, a class dealing with high-level business functionality should not contain low-level implementation details and should, in general, delegate to other classes for such tasks, which are subject to tests in their own right. We may make small exceptions to this rule if the implementation is trivial and the abstraction is not worth the extra effort. 
