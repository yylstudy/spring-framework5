/*
 * Copyright 2002-2013 the original author or authors.
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

package org.springframework.beans;

import java.beans.BeanInfo;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.lang.reflect.Method;

import org.springframework.core.Ordered;
import org.springframework.lang.Nullable;

/**
 * {@link BeanInfoFactory} implementation that evaluates whether bean classes have
 * "non-standard" JavaBeans setter methods and are thus candidates for introspection
 * by Spring's (package-visible) {@code ExtendedBeanInfo} implementation.
 *
 * <p>Ordered at {@link Ordered#LOWEST_PRECEDENCE} to allow other user-defined
 * {@link BeanInfoFactory} types to take precedence.
 *
 * @author Chris Beams
 * @since 3.2
 * @see BeanInfoFactory
 * @see CachedIntrospectionResults
 */
public class ExtendedBeanInfoFactory implements BeanInfoFactory, Ordered {

	/**
	 * Return an {@link ExtendedBeanInfo} for the given bean class, if applicable.
	 */
	/**
	 * 获取bean信息对象
	 * @param beanClass the bean class
	 * @return
	 * @throws IntrospectionException
	 */
	@Override
	@Nullable
	public BeanInfo getBeanInfo(Class<?> beanClass) throws IntrospectionException {
		//创建一个ExtendedBeanInfo对象
		return (supports(beanClass) ? new ExtendedBeanInfo(Introspector.getBeanInfo(beanClass)) : null);
	}

	/**
	 * Return whether the given bean class declares or inherits any non-void
	 * returning bean property or indexed property setter methods.
	 */
	/**
	 * class类型是否支持
	 * @param beanClass
	 * @return
	 */
	private boolean supports(Class<?> beanClass) {
		//遍历方法
		for (Method method : beanClass.getMethods()) {
			//是否存在可写的方法 set方法
			if (ExtendedBeanInfo.isCandidateWriteMethod(method)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
