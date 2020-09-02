/*
 * Copyright 2002-2018 the original author or authors.
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

package org.springframework.context.annotation;

import java.lang.annotation.Annotation;
import java.util.Set;
import java.util.regex.Pattern;

import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.parsing.CompositeComponentDefinition;
import org.springframework.beans.factory.support.BeanNameGenerator;
import org.springframework.beans.factory.xml.BeanDefinitionParser;
import org.springframework.beans.factory.xml.ParserContext;
import org.springframework.beans.factory.xml.XmlReaderContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.type.filter.AspectJTypeFilter;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.core.type.filter.TypeFilter;
import org.springframework.lang.Nullable;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * Parser for the {@code <context:component-scan/>} element.
 *
 * @author Mark Fisher
 * @author Ramnivas Laddad
 * @author Juergen Hoeller
 * @since 2.5
 */
public class ComponentScanBeanDefinitionParser implements BeanDefinitionParser {

	private static final String BASE_PACKAGE_ATTRIBUTE = "base-package";

	private static final String RESOURCE_PATTERN_ATTRIBUTE = "resource-pattern";

	private static final String USE_DEFAULT_FILTERS_ATTRIBUTE = "use-default-filters";

	private static final String ANNOTATION_CONFIG_ATTRIBUTE = "annotation-config";

	private static final String NAME_GENERATOR_ATTRIBUTE = "name-generator";

	private static final String SCOPE_RESOLVER_ATTRIBUTE = "scope-resolver";

	private static final String SCOPED_PROXY_ATTRIBUTE = "scoped-proxy";

	private static final String EXCLUDE_FILTER_ELEMENT = "exclude-filter";

	private static final String INCLUDE_FILTER_ELEMENT = "include-filter";

	private static final String FILTER_TYPE_ATTRIBUTE = "type";

	private static final String FILTER_EXPRESSION_ATTRIBUTE = "expression";

	/**
	 * component-scan 解析方法
	 * @param element the element that is to be parsed into one or more {@link BeanDefinition BeanDefinitions}
	 * @param parserContext the object encapsulating the current state of the parsing process;
	 * provides access to a {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
	 * @return
	 */
	@Override
	@Nullable
	public BeanDefinition parse(Element element, ParserContext parserContext) {
		//获取扫描基础包
		String basePackage = element.getAttribute(BASE_PACKAGE_ATTRIBUTE);
		//支持${}的形式
		basePackage = parserContext.getReaderContext().getEnvironment().resolvePlaceholders(basePackage);
		//获取基础包数组
		String[] basePackages = StringUtils.tokenizeToStringArray(basePackage,
				ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);

		// Actually scan for bean definitions and register them.
		//创建一个基于ClassPath的BeanDefinition扫描器
		ClassPathBeanDefinitionScanner scanner = configureScanner(parserContext, element);
		//使用扫描器去扫描
		Set<BeanDefinitionHolder> beanDefinitions = scanner.doScan(basePackages);
		//注册一些@Component使用到的注解
		registerComponents(parserContext.getReaderContext(), beanDefinitions, element);

		return null;
	}

	/**
	 * 创建一个基于ClassPath的BeanDefinition扫描器
	 * @param parserContext
	 * @param element
	 * @return
	 */
	protected ClassPathBeanDefinitionScanner configureScanner(ParserContext parserContext, Element element) {
		//是否使用默认的过滤条件
		boolean useDefaultFilters = true;
		if (element.hasAttribute(USE_DEFAULT_FILTERS_ATTRIBUTE)) {
			useDefaultFilters = Boolean.valueOf(element.getAttribute(USE_DEFAULT_FILTERS_ATTRIBUTE));
		}

		// Delegate bean definition registration to scanner class.
		//创建一个ClaaaPathBeanDefinitionScanner
		ClassPathBeanDefinitionScanner scanner = createScanner(parserContext.getReaderContext(), useDefaultFilters);
		//设置一些默认值
		scanner.setBeanDefinitionDefaults(parserContext.getDelegate().getBeanDefinitionDefaults());
		scanner.setAutowireCandidatePatterns(parserContext.getDelegate().getAutowireCandidatePatterns());
		if (element.hasAttribute(RESOURCE_PATTERN_ATTRIBUTE)) {
			scanner.setResourcePattern(element.getAttribute(RESOURCE_PATTERN_ATTRIBUTE));
		}

		try {
			//解析beanName生成器
			parseBeanNameGenerator(element, scanner);
		}
		catch (Exception ex) {
			parserContext.getReaderContext().error(ex.getMessage(), parserContext.extractSource(element), ex.getCause());
		}

		try {
			//解析scope
			parseScope(element, scanner);
		}
		catch (Exception ex) {
			parserContext.getReaderContext().error(ex.getMessage(), parserContext.extractSource(element), ex.getCause());
		}
		//解析TypeFilters
		parseTypeFilters(element, scanner, parserContext);

		return scanner;
	}

	/**
	 * 创建一个ClassPathbBeanDefinitionScanner
	 * @param readerContext
	 * @param useDefaultFilters
	 * @return
	 */
	protected ClassPathBeanDefinitionScanner createScanner(XmlReaderContext readerContext, boolean useDefaultFilters) {
		return new ClassPathBeanDefinitionScanner(readerContext.getRegistry(), useDefaultFilters,
				readerContext.getEnvironment(), readerContext.getResourceLoader());
	}

	/**
	 * 注册一些@Component使用到的注解
	 * @param readerContext
	 * @param beanDefinitions
	 * @param element
	 */
	protected void registerComponents(
			XmlReaderContext readerContext, Set<BeanDefinitionHolder> beanDefinitions, Element element) {

		Object source = readerContext.extractSource(element);
		CompositeComponentDefinition compositeDef = new CompositeComponentDefinition(element.getTagName(), source);

		for (BeanDefinitionHolder beanDefHolder : beanDefinitions) {
			compositeDef.addNestedComponent(new BeanComponentDefinition(beanDefHolder));
		}

		// Register annotation config processors, if necessary.
		//默认开启context:annotation-config的配置，所以component-scan拥有context:annotation-config标签的功能
		boolean annotationConfig = true;
		if (element.hasAttribute(ANNOTATION_CONFIG_ATTRIBUTE)) {
			annotationConfig = Boolean.valueOf(element.getAttribute(ANNOTATION_CONFIG_ATTRIBUTE));
		}
		if (annotationConfig) {
			//注册注解配置处理器
			Set<BeanDefinitionHolder> processorDefinitions =
					AnnotationConfigUtils.registerAnnotationConfigProcessors(readerContext.getRegistry(), source);
			for (BeanDefinitionHolder processorDefinition : processorDefinitions) {
				compositeDef.addNestedComponent(new BeanComponentDefinition(processorDefinition));
			}
		}

		readerContext.fireComponentRegistered(compositeDef);
	}

	/**
	 * 解析beanName生成器
	 * @param element
	 * @param scanner
	 */
	protected void parseBeanNameGenerator(Element element, ClassPathBeanDefinitionScanner scanner) {
		if (element.hasAttribute(NAME_GENERATOR_ATTRIBUTE)) {
			//反射生成BeanNameGenerator
			BeanNameGenerator beanNameGenerator = (BeanNameGenerator) instantiateUserDefinedStrategy(
					element.getAttribute(NAME_GENERATOR_ATTRIBUTE), BeanNameGenerator.class,
					scanner.getResourceLoader().getClassLoader());
			scanner.setBeanNameGenerator(beanNameGenerator);
		}
	}

	/**
	 * 解析scope
	 * @param element
	 * @param scanner
	 */
	protected void parseScope(Element element, ClassPathBeanDefinitionScanner scanner) {
		// Register ScopeMetadataResolver if class name provided.
		if (element.hasAttribute(SCOPE_RESOLVER_ATTRIBUTE)) {
			if (element.hasAttribute(SCOPED_PROXY_ATTRIBUTE)) {
				throw new IllegalArgumentException(
						"Cannot define both 'scope-resolver' and 'scoped-proxy' on <component-scan> tag");
			}
			ScopeMetadataResolver scopeMetadataResolver = (ScopeMetadataResolver) instantiateUserDefinedStrategy(
					element.getAttribute(SCOPE_RESOLVER_ATTRIBUTE), ScopeMetadataResolver.class,
					scanner.getResourceLoader().getClassLoader());
			scanner.setScopeMetadataResolver(scopeMetadataResolver);
		}

		if (element.hasAttribute(SCOPED_PROXY_ATTRIBUTE)) {
			String mode = element.getAttribute(SCOPED_PROXY_ATTRIBUTE);
			if ("targetClass".equals(mode)) {
				scanner.setScopedProxyMode(ScopedProxyMode.TARGET_CLASS);
			}
			else if ("interfaces".equals(mode)) {
				scanner.setScopedProxyMode(ScopedProxyMode.INTERFACES);
			}
			else if ("no".equals(mode)) {
				scanner.setScopedProxyMode(ScopedProxyMode.NO);
			}
			else {
				throw new IllegalArgumentException("scoped-proxy only supports 'no', 'interfaces' and 'targetClass'");
			}
		}
	}

	/**
	 * 解析TypeFilters
	 * @param element
	 * @param scanner
	 * @param parserContext
	 */
	protected void parseTypeFilters(Element element, ClassPathBeanDefinitionScanner scanner, ParserContext parserContext) {
		// Parse exclude and include filter elements.
		ClassLoader classLoader = scanner.getResourceLoader().getClassLoader();
		NodeList nodeList = element.getChildNodes();
		for (int i = 0; i < nodeList.getLength(); i++) {
			Node node = nodeList.item(i);
			if (node.getNodeType() == Node.ELEMENT_NODE) {
				String localName = parserContext.getDelegate().getLocalName(node);
				try {
					if (INCLUDE_FILTER_ELEMENT.equals(localName)) {
						//解析自定义的包含的过滤条件
						TypeFilter typeFilter = createTypeFilter((Element) node, classLoader, parserContext);
						scanner.addIncludeFilter(typeFilter);
					}
					else if (EXCLUDE_FILTER_ELEMENT.equals(localName)) {
						//解析自定义的排除的过滤条件
						TypeFilter typeFilter = createTypeFilter((Element) node, classLoader, parserContext);
						scanner.addExcludeFilter(typeFilter);
					}
				}
				catch (ClassNotFoundException ex) {
					parserContext.getReaderContext().warning(
							"Ignoring non-present type filter class: " + ex, parserContext.extractSource(element));
				}
				catch (Exception ex) {
					parserContext.getReaderContext().error(
							ex.getMessage(), parserContext.extractSource(element), ex.getCause());
				}
			}
		}
	}

	/**
	 * 创建TypeFilter
	 * @param element
	 * @param classLoader
	 * @param parserContext
	 * @return
	 * @throws ClassNotFoundException
	 */
	@SuppressWarnings("unchecked")
	protected TypeFilter createTypeFilter(Element element, @Nullable ClassLoader classLoader,
			ParserContext parserContext) throws ClassNotFoundException {
		//type属性的值
		String filterType = element.getAttribute(FILTER_TYPE_ATTRIBUTE);
		//expression的值
		String expression = element.getAttribute(FILTER_EXPRESSION_ATTRIBUTE);
		expression = parserContext.getReaderContext().getEnvironment().resolvePlaceholders(expression);
		//常用的有annotation和assignable
		if ("annotation".equals(filterType)) {
			return new AnnotationTypeFilter((Class<Annotation>) ClassUtils.forName(expression, classLoader));
		}
		else if ("assignable".equals(filterType)) {
			return new AssignableTypeFilter(ClassUtils.forName(expression, classLoader));
		}
		else if ("aspectj".equals(filterType)) {
			return new AspectJTypeFilter(expression, classLoader);
		}
		else if ("regex".equals(filterType)) {
			return new RegexPatternTypeFilter(Pattern.compile(expression));
		}
		else if ("custom".equals(filterType)) {
			Class<?> filterClass = ClassUtils.forName(expression, classLoader);
			if (!TypeFilter.class.isAssignableFrom(filterClass)) {
				throw new IllegalArgumentException(
						"Class is not assignable to [" + TypeFilter.class.getName() + "]: " + expression);
			}
			return (TypeFilter) BeanUtils.instantiateClass(filterClass);
		}
		else {
			throw new IllegalArgumentException("Unsupported filter type: " + filterType);
		}
	}

	/**
	 * 反射生成对应类型实例
	 * @param className
	 * @param strategyType
	 * @param classLoader
	 * @return
	 */
	@SuppressWarnings("unchecked")
	private Object instantiateUserDefinedStrategy(
			String className, Class<?> strategyType, @Nullable ClassLoader classLoader) {

		Object result;
		try {
			result = ReflectionUtils.accessibleConstructor(ClassUtils.forName(className, classLoader)).newInstance();
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalArgumentException("Class [" + className + "] for strategy [" +
					strategyType.getName() + "] not found", ex);
		}
		catch (Throwable ex) {
			throw new IllegalArgumentException("Unable to instantiate class [" + className + "] for strategy [" +
					strategyType.getName() + "]: a zero-argument constructor is required", ex);
		}

		if (!strategyType.isAssignableFrom(result.getClass())) {
			throw new IllegalArgumentException("Provided class name must be an implementation of " + strategyType);
		}
		return result;
	}

}
