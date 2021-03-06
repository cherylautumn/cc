/*
 * Copyright 2002-2007 the original author or authors.
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

package org.springframework.test.context;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.Assert;

/**
 * <p>
 * <code>TestContextManager</code> is the main entry point into the
 * <em>Spring TestContext Framework</em>, which provides support for loading
 * and accessing {@link ApplicationContext application contexts}, dependency
 * injection of test instances,
 * {@link org.springframework.transaction.annotation.Transactional transactional}
 * execution of test methods, etc.
 * </p>
 * <p>
 * Specifically, a <code>TestContextManager</code> is responsible for managing
 * a single {@link TestContext} and signaling events to all registered
 * {@link TestExecutionListener TestExecutionListeners} at well defined test
 * execution points:
 * </p>
 * <ul>
 * <li>{@link #prepareTestInstance(Object) test instance preparation}:
 * immediately following instantiation of the test instance</li>
 * <li>{@link #beforeTestMethod(Object,Method) before test method execution}:
 * prior to any <em>before methods</em> of a particular testing framework
 * (e.g., JUnit 4's {@link org.junit.Before @Before})</li>
 * <li>{@link #afterTestMethod(Object,Method,Throwable) after test method execution}:
 * after any <em>after methods</em> of a particular testing framework (e.g.,
 * JUnit 4's {@link org.junit.After @After})</li>
 * </ul>
 *
 * @author Sam Brannen
 * @author Juergen Hoeller
 * @since 2.5
 * @see TestContext
 * @see TestExecutionListeners
 * @see ContextConfiguration
 * @see org.springframework.test.context.transaction.TransactionConfiguration
 */
public class TestContextManager {

	private static final String[] DEFAULT_TEST_EXECUTION_LISTENER_CLASS_NAMES = new String[] {
			"org.springframework.test.context.support.DependencyInjectionTestExecutionListener",
			"org.springframework.test.context.support.DirtiesContextTestExecutionListener",
			"org.springframework.test.context.transaction.TransactionalTestExecutionListener" };

	private static final Log logger = LogFactory.getLog(TestContextManager.class);

	/**
	 * Cache of Spring application contexts. This needs to be static, as tests
	 * may be destroyed and recreated between running individual test methods,
	 * for example with JUnit.
	 */
	private static final ContextCache<String, ApplicationContext> contextCache = new ContextCache<String, ApplicationContext>();


	private final TestContext testContext;

	private final List<TestExecutionListener> testExecutionListeners = new ArrayList<TestExecutionListener>();


	/**
	 * <p>
	 * Constructs a new <code>TestContextManager</code> for the specified
	 * {@link Class test class} and automatically
	 * {@link #registerTestExecutionListeners(TestExecutionListener...) registers}
	 * the {@link TestExecutionListener TestExecutionListeners} configured for
	 * the test class via the
	 * {@link TestExecutionListeners @TestExecutionListeners} annotation.
	 * </p>
	 *
	 * @param testClass the Class object corresponding to the test class to be managed
	 * @see #registerTestExecutionListeners(TestExecutionListener...)
	 * @see #retrieveTestExecutionListeners(Class)
	 */
	public TestContextManager(final Class<?> testClass) {

		this.testContext = new TestContext(testClass, getContextCache());
		registerTestExecutionListeners(retrieveTestExecutionListeners(testClass));
	}

	/**
	 * <p>
	 * Retrieves an array of newly instantiated
	 * {@link TestExecutionListener TestExecutionListeners} for the specified
	 * {@link Class class}. If
	 * {@link TestExecutionListeners @TestExecutionListeners} is not
	 * <em>present</em> on the supplied class, the default listeners will be
	 * returned.
	 * </p>
	 * <p>
	 * Note that the
	 * {@link TestExecutionListeners#inheritListeners() inheritListeners} flag
	 * of {@link TestExecutionListeners @TestExecutionListeners} will be taken
	 * into consideration. Specifically, if the <code>inheritListeners</code>
	 * flag is set to <code>true</code>, listeners defined in the annotated
	 * class will be appended to the listeners defined in superclasses.
	 * </p>
	 *
	 * @param clazz the Class object corresponding to the test class for which
	 * the listeners should be retrieved
	 * @return an array of TestExecutionListeners for the specified class
	 */
	private TestExecutionListener[] retrieveTestExecutionListeners(final Class<?> clazz) {

		Assert.notNull(clazz, "Can not retrieve TestExecutionListeners for a NULL class");
		final Class<TestExecutionListeners> annotationType = TestExecutionListeners.class;
		final List<Class<? extends TestExecutionListener>> classesList = new ArrayList<Class<? extends TestExecutionListener>>();
		Class<?> declaringClass = AnnotationUtils.findAnnotationDeclaringClass(annotationType, clazz);

		// Use defaults?
		if (declaringClass == null) {
			if (logger.isInfoEnabled()) {
				logger.info("@TestExecutionListeners is not present for class [" + clazz + "]: using defaults.");
			}
			classesList.addAll(getDefaultTestExecutionListenerClasses());
		}
		else {
			// Traverse the class hierarchy
			while (declaringClass != null) {

				final TestExecutionListeners testExecutionListeners = declaringClass.getAnnotation(annotationType);
				if (logger.isTraceEnabled()) {
					logger.trace("Retrieved @TestExecutionListeners [" + testExecutionListeners
							+ "] for declaring class [" + declaringClass + "].");
				}

				Class<? extends TestExecutionListener>[] classes = testExecutionListeners.value();
				if (classes != null) {
					classesList.addAll(0, Arrays.<Class<? extends TestExecutionListener>> asList(classes));
				}

				declaringClass = testExecutionListeners.inheritListeners() ? AnnotationUtils.findAnnotationDeclaringClass(
						annotationType, declaringClass.getSuperclass())
						: null;
			}
		}

		final TestExecutionListener[] listeners = new TestExecutionListener[classesList.size()];
		int i = 0;
		for (final Class<? extends TestExecutionListener> listenerClass : classesList) {
			listeners[i++] = (TestExecutionListener) BeanUtils.instantiateClass(listenerClass);
		}
		return listeners;
	}

	/**
	 * <p>
	 * Determine the default {@link TestExecutionListener} classes.
	 * </p>
	 */
	@SuppressWarnings("unchecked")
	protected Set<Class<? extends TestExecutionListener>> getDefaultTestExecutionListenerClasses() {
		final Set<Class<? extends TestExecutionListener>> defaultListenerClasses = new LinkedHashSet<Class<? extends TestExecutionListener>>();
		for (String className : DEFAULT_TEST_EXECUTION_LISTENER_CLASS_NAMES) {
			try {
				defaultListenerClasses.add((Class<? extends TestExecutionListener>) getClass().getClassLoader().loadClass(
						className));
			}
			catch (ClassNotFoundException ex) {
				if (logger.isWarnEnabled()) {
					logger.warn("Could not load default TestExecutionListener class [" + className
							+ "]. Specify custom listener classes or make the default listener classes available.");
				}
			}
		}
		return defaultListenerClasses;
	}

	/**
	 * <p>
	 * Hook for preparing a test instance prior to execution of any individual
	 * test methods, for example for injecting dependencies, etc. Should be
	 * called immediately after instantiation of the test instance.
	 * </p>
	 * <p>
	 * The managed {@link TestContext} will be updated with the supplied
	 * <code>testInstance</code>.
	 * </p>
	 * <p>
	 * An attempt will be made to give each registered
	 * {@link TestExecutionListener} a chance to prepare the test instance. If a
	 * listener throws an exception, however, the remaining registered listeners
	 * will <strong>not</strong> be called.
	 * </p>
	 *
	 * @param testInstance the test instance to prepare (never <code>null</code>)
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void prepareTestInstance(final Object testInstance) throws Exception {

		Assert.notNull(testInstance, "testInstance must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("prepareTestInstance(): instance [" + testInstance + "]");
		}

		getTestContext().updateState(testInstance, null, null);

		for (TestExecutionListener testExecutionListener : getTestExecutionListeners()) {
			try {
				testExecutionListener.prepareTestInstance(getTestContext());
			}
			catch (Exception ex) {
				logger.error("Caught exception while allowing TestExecutionListener [" + testExecutionListener
						+ "] to prepare test instance [" + testInstance + "]", ex);
				throw ex;
			}
		}
	}

	/**
	 * <p>
	 * Hook for pre-processing a test <em>before</em> execution of the
	 * supplied {@link Method test method}, for example for setting up test
	 * fixtures, starting a transaction, etc. Should be called prior to any
	 * framework-specific <em>before methods</em> (e.g., methods annotated
	 * with JUnit's {@link org.junit.Before @Before} ).
	 * </p>
	 * <p>
	 * The managed {@link TestContext} will be updated with the supplied
	 * <code>testInstance</code> and <code>testMethod</code>.
	 * </p>
	 * <p>
	 * An attempt will be made to give each registered
	 * {@link TestExecutionListener} a chance to pre-process the test method
	 * execution. If a listener throws an exception, however, the remaining
	 * registered listeners will <strong>not</strong> be called.
	 * </p>
	 *
	 * @param testInstance the current test instance (never <code>null</code>)
	 * @param testMethod the test method which is about to be executed on the
	 * test instance
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void beforeTestMethod(final Object testInstance, final Method testMethod) throws Exception {

		Assert.notNull(testInstance, "testInstance must not be null.");
		if (logger.isTraceEnabled()) {
			logger.trace("beforeTestMethod(): instance [" + testInstance + "], method [" + testMethod + "]");
		}

		getTestContext().updateState(testInstance, testMethod, null);

		for (TestExecutionListener testExecutionListener : getTestExecutionListeners()) {
			try {
				testExecutionListener.beforeTestMethod(getTestContext());
			}
			catch (Exception ex) {
				logger.warn("Caught exception while allowing TestExecutionListener [" + testExecutionListener
						+ "] to process 'before' execution of test method [" + testMethod + "] for test instance ["
						+ testInstance + "]", ex);
				throw ex;
			}
		}
	}

	/**
	 * <p>
	 * Hook for post-processing a test <em>after</em> execution of the
	 * supplied {@link Method test method}, for example for tearing down test
	 * fixtures, ending a transaction, etc. Should be called after any
	 * framework-specific <em>after methods</em> (e.g., methods annotated with
	 * JUnit's {@link org.junit.After @After}).
	 * </p>
	 * <p>
	 * The managed {@link TestContext} will be updated with the supplied
	 * <code>testInstance</code>, <code>testMethod</code>, and
	 * <code>exception</code>.
	 * </p>
	 * <p>
	 * Each registered {@link TestExecutionListener} will be given a chance to
	 * post-process the test method execution. If a listener throws an
	 * exception, the remaining registered listeners will still be called, but
	 * the first exception thrown will be tracked and rethrown after all
	 * listeners have executed. Note that registered listeners will be executed
	 * in the opposite order in which they were registered.
	 * </p>
	 *
	 * @param testInstance the current test instance (never <code>null</code>)
	 * @param testMethod the test method which has just been executed on the
	 * test instance
	 * @param exception the exception that was thrown during execution of the
	 * test method or by a TestExecutionListener, or <code>null</code>
	 * if none was thrown
	 * @throws Exception if a registered TestExecutionListener throws an exception
	 * @see #getTestExecutionListeners()
	 */
	public void afterTestMethod(final Object testInstance, final Method testMethod, final Throwable exception)
			throws Exception {

		Assert.notNull(testInstance, "testInstance must not be null");
		if (logger.isTraceEnabled()) {
			logger.trace("afterTestMethod(): instance [" + testInstance + "], method [" + testMethod + "], exception ["
					+ exception + "]");
		}

		getTestContext().updateState(testInstance, testMethod, exception);

		// Traverse the TestExecutionListeners in reverse order to ensure proper
		// "wrapper"-style execution of listeners.
		final List<TestExecutionListener> listenersReversed = new ArrayList<TestExecutionListener>(
				getTestExecutionListeners());
		Collections.reverse(listenersReversed);

		Exception afterTestMethodException = null;

		for (TestExecutionListener testExecutionListener : listenersReversed) {
			try {
				testExecutionListener.afterTestMethod(getTestContext());
			}
			catch (Exception ex) {
				logger.warn("Caught exception while allowing TestExecutionListener [" + testExecutionListener
						+ "] to process 'after' execution for test: method [" + testMethod + "], instance ["
						+ testInstance + "], exception [" + exception + "]", ex);
				if (afterTestMethodException == null) {
					afterTestMethodException = ex;
				}
			}
		}

		if (afterTestMethodException != null) {
			throw afterTestMethodException;
		}
	}

	/**
	 * <p>
	 * Gets the {@link ContextCache} used by this
	 * <code>TestContextManager</code>.
	 * </p>
	 * <p>
	 * The default implementation returns a reference to a static cache shared
	 * by all <code>TestContextManager</code>s.
	 * </p>
	 *
	 * @return the context cache
	 */
	protected ContextCache<String, ApplicationContext> getContextCache() {
		return contextCache;
	}

	/**
	 * <p>
	 * Gets the {@link TestContext} managed by this
	 * <code>TestContextManager</code>.
	 * </p>
	 *
	 * @return the test context.
	 */
	protected final TestContext getTestContext() {
		return this.testContext;
	}

	/**
	 * <p>
	 * Gets an {@link Collections#unmodifiableList(List) unmodifiable} copy of
	 * the {@link TestExecutionListener TestExecutionListeners} registered for
	 * this <code>TestContextManager</code>.
	 * </p>
	 *
	 * @return a copy of the TestExecutionListeners
	 */
	public final List<TestExecutionListener> getTestExecutionListeners() {
		return Collections.unmodifiableList(this.testExecutionListeners);
	}

	/**
	 * <p>
	 * Registers the supplied
	 * {@link TestExecutionListener TestExecutionListeners} by appending them to
	 * the set of listeners used by this <code>TestContextManager</code>.
	 * </p>
	 */
	public void registerTestExecutionListeners(final TestExecutionListener... testExecutionListeners) {
		for (final TestExecutionListener listener : testExecutionListeners) {
			if (logger.isTraceEnabled()) {
				logger.trace("Registering TestExecutionListener [" + listener + "]");
			}
			this.testExecutionListeners.add(listener);
		}
	}

}
