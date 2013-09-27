/***
 * Copyright (c) 2009 Caelum - www.caelum.com.br/opensource All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package br.com.caelum.vraptor.proxy;

import static java.util.Arrays.asList;
import static javassist.util.proxy.ProxyFactory.isProxyClass;

import java.lang.reflect.Method;
import java.util.List;

import javassist.util.proxy.MethodFilter;
import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.ProxyFactory;

import javax.enterprise.context.ApplicationScoped;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Javassist implementation for {@link Proxifier}.
 * 
 * @author Otávio Scherer Garcia
 * @since 3.3.1
 */
@ApplicationScoped
public class JavassistProxifier implements Proxifier {

	private static final Logger logger = LoggerFactory.getLogger(JavassistProxifier.class);

	/**
	 * Do not proxy these methods.
	 */
	private static final MethodFilter IGNORE_BRIDGE_AND_OBJECT_METHODS = new MethodFilter() {
		/**
		 * Methods like toString and finalize will be ignored.
		 */
		final List<Method> OBJECT_METHODS = asList(Object.class.getDeclaredMethods());

		@Override
		public boolean isHandled(Method method) {
			return !method.isBridge() && !OBJECT_METHODS.contains(method);
		}
	};

	@Override
	public <T> T proxify(Class<T> type, MethodInvocation<? super T> handler) {
		final ProxyFactory factory = new ProxyFactory();
		factory.setFilter(IGNORE_BRIDGE_AND_OBJECT_METHODS);
		Class<?> rawType = extractRawType(type);

		if (type.isInterface()) {
			factory.setInterfaces(new Class[] { rawType });
		} else {
			factory.setSuperclass(rawType);
		}

		Object instance;
		try {
			instance = factory.create(null, null, new MethodInvocationAdapter<T>(handler));
		} catch (ReflectiveOperationException | IllegalArgumentException e) {
			logger.error("An error occurs when create a proxy for type " + type, e);
			throw new ProxyCreationException(e);
		}

		logger.debug("a proxy for {} was created as {}", type, instance.getClass());
		return type.cast(instance);
	}

	private <T> Class<?> extractRawType(Class<T> type) {
		return isProxyType(type) ? type.getSuperclass() : type;
	}

	@Override
	public boolean isProxy(Object o) {
		return o != null && isProxyType(o.getClass());
	}

	@Override
	public boolean isProxyType(Class<?> type) {
		boolean proxy = isProxyClass(type) || isWeldProxy(type);
		logger.debug("Class {} is proxy: {}", type.getName(), proxy);

		return proxy;
	}

	// FIXME only works with weld, and can throws ClassNotFoundException in another CDI implementations
	private boolean isWeldProxy(Class<?> type) {
		return org.jboss.weld.bean.proxy.ProxyFactory.isProxy(type);
	}

	private static class MethodInvocationAdapter<T> implements MethodHandler {
		private MethodInvocation<? super T> handler;

		public MethodInvocationAdapter(MethodInvocation<? super T> handler) {
			this.handler = handler;
		}

		@Override
		public Object invoke(Object self, Method thisMethod, final Method proceed, Object[] args)
			throws Throwable {
			return handler.intercept((T) self, thisMethod, args, new SuperMethod() {
				@Override
				public Object invoke(Object proxy, Object[] args) {
					try {
						return proceed.invoke(proxy, args);
					} catch (Throwable throwable) {
						throw new ProxyInvocationException(throwable);
					}
				}
			});
		}
	}
}
