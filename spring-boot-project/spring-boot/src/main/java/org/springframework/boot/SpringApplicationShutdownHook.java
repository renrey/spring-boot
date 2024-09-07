/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot;

import java.security.AccessControlException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.util.Assert;

/**
 * A {@link Runnable} to be used as a {@link Runtime#addShutdownHook(Thread) shutdown
 * hook} to perform graceful shutdown of Spring Boot applications. This hook tracks
 * registered application contexts as well as any actions registered via
 * {@link SpringApplication#getShutdownHandlers()}.
 *
 * @author Andy Wilkinson
 * @author Phillip Webb
 */
class SpringApplicationShutdownHook implements Runnable {

	private static final int SLEEP = 50;

	private static final long TIMEOUT = TimeUnit.MINUTES.toMillis(10);

	private static final Log logger = LogFactory.getLog(SpringApplicationShutdownHook.class);

	private final Handlers handlers = new Handlers();

	// 已注册的spring context
	private final Set<ConfigurableApplicationContext> contexts = new LinkedHashSet<>();

	// 代表正常关闭spring context，会从contexts删掉
	private final Set<ConfigurableApplicationContext> closedContexts = Collections.newSetFromMap(new WeakHashMap<>());

	// 用来 spring上下文通知 当前构子
	private final ApplicationContextClosedListener contextCloseListener = new ApplicationContextClosedListener();

	private final AtomicBoolean shutdownHookAdded = new AtomicBoolean(false);

	private boolean inProgress;

	SpringApplicationShutdownHandlers getHandlers() {
		return this.handlers;
	}

	void registerApplicationContext(ConfigurableApplicationContext context) {
		// 加入jvm关闭的钩子 -》 用于不正常关闭
		/**
		 * @see SpringApplicationShutdownHook#run()
		 */
		addRuntimeShutdownHookIfNecessary();


		synchronized (SpringApplicationShutdownHook.class) {
			assertNotInProgress();

			// 往spring上下文加入listener ，监听ContextClosedEvent事件 -》用于正常关闭
			context.addApplicationListener(this.contextCloseListener);

			// 用于jvm关闭构造回调
			this.contexts.add(context);// 上下文集合保存当前上下文？
		}
	}

	private void addRuntimeShutdownHookIfNecessary() {
		if (this.shutdownHookAdded.compareAndSet(false, true)) {
			addRuntimeShutdownHook();
		}
	}

	void addRuntimeShutdownHook() {
		try {
			Runtime.getRuntime().addShutdownHook(new Thread(this, "SpringApplicationShutdownHook"));
		}
		catch (AccessControlException ex) {
			// Not allowed in some environments
		}
	}

	@Override
	public void run() {
		Set<ConfigurableApplicationContext> contexts;
		Set<ConfigurableApplicationContext> closedContexts;
		Set<Runnable> actions;
		synchronized (SpringApplicationShutdownHook.class) {
			this.inProgress = true;
			contexts = new LinkedHashSet<>(this.contexts);
			closedContexts = new LinkedHashSet<>(this.closedContexts);
			actions = new LinkedHashSet<>(this.handlers.getActions());
		}
		// 正在正常运行的context
		contexts.forEach(this::closeAndWait);
		// 已执行关闭的context
		closedContexts.forEach(this::closeAndWait);
		actions.forEach(Runnable::run);
	}

	boolean isApplicationContextRegistered(ConfigurableApplicationContext context) {
		synchronized (SpringApplicationShutdownHook.class) {
			return this.contexts.contains(context);
		}
	}

	void reset() {
		synchronized (SpringApplicationShutdownHook.class) {
			this.contexts.clear();
			this.closedContexts.clear();
			this.handlers.getActions().clear();
			this.inProgress = false;
		}
	}

	/**
	 * Call {@link ConfigurableApplicationContext#close()} and wait until the context
	 * becomes inactive. We can't assume that just because the close method returns that
	 * the context is actually inactive. It could be that another thread is still in the
	 * process of disposing beans.
	 * @param context the context to clean
	 */
	private void closeAndWait(ConfigurableApplicationContext context) {
		if (!context.isActive()) {
			return;
		}
		context.close();
		try {
			int waited = 0;
			while (context.isActive()) {
				if (waited > TIMEOUT) {
					throw new TimeoutException();
				}
				Thread.sleep(SLEEP);
				waited += SLEEP;
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			logger.warn("Interrupted waiting for application context " + context + " to become inactive");
		}
		catch (TimeoutException ex) {
			logger.warn("Timed out waiting for application context " + context + " to become inactive", ex);
		}
	}

	private void assertNotInProgress() {
		Assert.state(!SpringApplicationShutdownHook.this.inProgress, "Shutdown in progress");
	}

	/**
	 * The handler actions for this shutdown hook.
	 */
	private class Handlers implements SpringApplicationShutdownHandlers {

		private final Set<Runnable> actions = Collections.newSetFromMap(new IdentityHashMap<>());

		@Override
		public void add(Runnable action) {
			Assert.notNull(action, "Action must not be null");
			synchronized (SpringApplicationShutdownHook.class) {
				assertNotInProgress();
				this.actions.add(action);
			}
		}

		@Override
		public void remove(Runnable action) {
			Assert.notNull(action, "Action must not be null");
			synchronized (SpringApplicationShutdownHook.class) {
				assertNotInProgress();
				this.actions.remove(action);
			}
		}

		Set<Runnable> getActions() {
			return this.actions;
		}

	}

	/**
	 * {@link ApplicationListener} to track closed contexts.
	 */
	private class ApplicationContextClosedListener implements ApplicationListener<ContextClosedEvent> {

		@Override
		public void onApplicationEvent(ContextClosedEvent event) {
			// The ContextClosedEvent is fired at the start of a call to {@code close()}
			// and if that happens in a different thread then the context may still be
			// active. Rather than just removing the context, we add it to a {@code
			// closedContexts} set. This is weak set so that the context can be GC'd once
			// the {@code close()} method returns.
			synchronized (SpringApplicationShutdownHook.class) {
				// 大概正常的上下文关闭 -》去掉contexts，加入closedContexts
				ApplicationContext applicationContext = event.getApplicationContext();
				SpringApplicationShutdownHook.this.contexts.remove(applicationContext);
				SpringApplicationShutdownHook.this.closedContexts
						.add((ConfigurableApplicationContext) applicationContext);
			}
		}

	}

}
