/*
 * Copyright 2002-2017 the original author or authors.
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

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.parsing.Location;
import org.springframework.beans.factory.parsing.Problem;
import org.springframework.beans.factory.parsing.ProblemReporter;
import org.springframework.beans.factory.support.BeanDefinitionReader;
import org.springframework.core.io.DescriptiveResource;
import org.springframework.core.io.Resource;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.StandardAnnotationMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * Represents a user-defined {@link Configuration @Configuration} class.
 * Includes a set of {@link Bean} methods, including all such methods
 * defined in the ancestry of the class, in a 'flattened-out' manner.
 *
 * @author Chris Beams
 * @author Juergen Hoeller
 * @author Phillip Webb
 * @since 3.0
 * @see BeanMethod
 * @see ConfigurationClassParser
 */
final class ConfigurationClass {
	/**
	 * AnnotationMetadataReadingVisitor component-scan
	 * StandardAnnotationMetadata 基于xml的
	 */
	private final AnnotationMetadata metadata;
	/**
	 * DescriptiveResource
	 */
	private final Resource resource;
	/**
	 * beanName
	 */
	@Nullable
	private String beanName;
	/**
	 * 当前的ConfigurationClass是@Import注解导入进来的
	 * 所以importedBy属性就是记录是由哪些@Import注解所在类的ConfigurationClass导入进来的
	 * 如果当前的importedBy不为空，那么说明此ConfiguraionClass是@Import注解值的类的ConfigurationClass
	 * 其metadata属性是@Import注解值的AnnotationMetadataReadingVisitor或者StandardAnnotationMetadata
	 */
	private final Set<ConfigurationClass> importedBy = new LinkedHashSet<>(1);
	/**
	 * 存放@Bean修饰的方法
	 */
	private final Set<BeanMethod> beanMethods = new LinkedHashSet<>();
	/**
	 * 存放ImportSource注解的  资源路径和其解析器的映射集合
	 */
	private final Map<String, Class<? extends BeanDefinitionReader>> importedResources =
			new LinkedHashMap<>();
	/**
	 * 实现ImportBeanDefinitionRegistrar接口的@Import类和其@Import注解所在类的注解元数据对象的映射集合
	 */
	private final Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> importBeanDefinitionRegistrars =
			new LinkedHashMap<>();
	/**
	 * @Conditional 跳过的@Bean注解的方法名
	 */
	final Set<String> skippedBeanMethods = new HashSet<>();


	/**
	 * Create a new {@link ConfigurationClass} with the given name.
	 * @param metadataReader reader used to parse the underlying {@link Class}
	 * @param beanName must not be {@code null}
	 * @see ConfigurationClass#ConfigurationClass(Class, ConfigurationClass)
	 */
	public ConfigurationClass(MetadataReader metadataReader, String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		this.metadata = metadataReader.getAnnotationMetadata();
		this.resource = metadataReader.getResource();
		this.beanName = beanName;
	}

	/**
	 * Create a new {@link ConfigurationClass} representing a class that was imported
	 * using the {@link Import} annotation or automatically processed as a nested
	 * configuration class (if importedBy is not {@code null}).
	 * @param metadataReader reader used to parse the underlying {@link Class}
	 * @param importedBy the configuration class importing this one or {@code null}
	 * @since 3.1.1
	 */
	public ConfigurationClass(MetadataReader metadataReader, @Nullable ConfigurationClass importedBy) {
		this.metadata = metadataReader.getAnnotationMetadata();
		this.resource = metadataReader.getResource();
		this.importedBy.add(importedBy);
	}

	/**
	 * Create a new {@link ConfigurationClass} with the given name.
	 * @param clazz the underlying {@link Class} to represent
	 * @param beanName name of the {@code @Configuration} class bean
	 * @see ConfigurationClass#ConfigurationClass(Class, ConfigurationClass)
	 */
	public ConfigurationClass(Class<?> clazz, String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		this.metadata = new StandardAnnotationMetadata(clazz, true);
		this.resource = new DescriptiveResource(clazz.getName());
		this.beanName = beanName;
	}

	/**
	 * Create a new {@link ConfigurationClass} representing a class that was imported
	 * using the {@link Import} annotation or automatically processed as a nested
	 * configuration class (if imported is {@code true}).
	 * @param clazz the underlying {@link Class} to represent
	 * @param importedBy the configuration class importing this one (or {@code null})
	 * @since 3.1.1
	 */
	public ConfigurationClass(Class<?> clazz, @Nullable ConfigurationClass importedBy) {
		this.metadata = new StandardAnnotationMetadata(clazz, true);
		this.resource = new DescriptiveResource(clazz.getName());
		this.importedBy.add(importedBy);
	}

	/**
	 * Create a new {@link ConfigurationClass} with the given name.
	 * @param metadata the metadata for the underlying class to represent
	 * @param beanName name of the {@code @Configuration} class bean
	 * @see ConfigurationClass#ConfigurationClass(Class, ConfigurationClass)
	 */
	public ConfigurationClass(AnnotationMetadata metadata, String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		this.metadata = metadata;
		this.resource = new DescriptiveResource(metadata.getClassName());
		this.beanName = beanName;
	}


	public AnnotationMetadata getMetadata() {
		return this.metadata;
	}

	public Resource getResource() {
		return this.resource;
	}

	public String getSimpleName() {
		return ClassUtils.getShortName(getMetadata().getClassName());
	}

	public void setBeanName(String beanName) {
		this.beanName = beanName;
	}

	@Nullable
	public String getBeanName() {
		return this.beanName;
	}

	/**
	 * Return whether this configuration class was registered via @{@link Import} or
	 * automatically registered due to being nested within another configuration class.
	 * @since 3.1.1
	 * @see #getImportedBy()
	 */
	public boolean isImported() {
		return !this.importedBy.isEmpty();
	}

	/**
	 * Merge the imported-by declarations from the given configuration class into this one.
	 * @since 4.0.5
	 */
	public void mergeImportedBy(ConfigurationClass otherConfigClass) {
		this.importedBy.addAll(otherConfigClass.importedBy);
	}

	/**
	 * Return the configuration classes that imported this class,
	 * or an empty Set if this configuration was not imported.
	 * @since 4.0.5
	 * @see #isImported()
	 */
	public Set<ConfigurationClass> getImportedBy() {
		return this.importedBy;
	}

	public void addBeanMethod(BeanMethod method) {
		this.beanMethods.add(method);
	}

	public Set<BeanMethod> getBeanMethods() {
		return this.beanMethods;
	}

	public void addImportedResource(String importedResource, Class<? extends BeanDefinitionReader> readerClass) {
		this.importedResources.put(importedResource, readerClass);
	}

	public void addImportBeanDefinitionRegistrar(ImportBeanDefinitionRegistrar registrar, AnnotationMetadata importingClassMetadata) {
		this.importBeanDefinitionRegistrars.put(registrar, importingClassMetadata);
	}

	public Map<ImportBeanDefinitionRegistrar, AnnotationMetadata> getImportBeanDefinitionRegistrars() {
		return this.importBeanDefinitionRegistrars;
	}

	public Map<String, Class<? extends BeanDefinitionReader>> getImportedResources() {
		return this.importedResources;
	}

	public void validate(ProblemReporter problemReporter) {
		// A configuration class may not be final (CGLIB limitation)
		//使用@Configuration注解的类不能是final修饰的，因为@Configuration需要使用cglib进行代理
		if (getMetadata().isAnnotated(Configuration.class.getName())) {
			if (getMetadata().isFinal()) {
				problemReporter.error(new FinalConfigurationProblem());
			}
		}
		//校验@Bean
		for (BeanMethod beanMethod : this.beanMethods) {
			beanMethod.validate(problemReporter);
		}
	}

	@Override
	public boolean equals(Object other) {
		return (this == other || (other instanceof ConfigurationClass &&
				getMetadata().getClassName().equals(((ConfigurationClass) other).getMetadata().getClassName())));
	}

	@Override
	public int hashCode() {
		return getMetadata().getClassName().hashCode();
	}

	@Override
	public String toString() {
		return "ConfigurationClass: beanName '" + this.beanName + "', " + this.resource;
	}


	/**
	 * Configuration classes must be non-final to accommodate CGLIB subclassing.
	 */
	private class FinalConfigurationProblem extends Problem {

		public FinalConfigurationProblem() {
			super(String.format("@Configuration class '%s' may not be final. Remove the final modifier to continue.",
					getSimpleName()), new Location(getResource(), getMetadata()));
		}
	}

}
