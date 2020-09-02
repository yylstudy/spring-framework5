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

package org.springframework.core.type.classreading;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.core.annotation.AnnotationAttributes;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.lang.Nullable;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

/**
 * ASM class visitor which looks for the class name and implemented types as
 * well as for the annotations defined on the class, exposing them through
 * the {@link org.springframework.core.type.AnnotationMetadata} interface.
 *
 * @author Juergen Hoeller
 * @author Mark Fisher
 * @author Costin Leau
 * @author Phillip Webb
 * @author Sam Brannen
 * @since 2.5
 */

/**
 * component注解扫描的元注解类
 */
public class AnnotationMetadataReadingVisitor extends ClassMetadataReadingVisitor implements AnnotationMetadata {

	@Nullable
	protected final ClassLoader classLoader;
	/**
	 * 类上的注解集合（不包含注解中的注解）
	 */
	protected final Set<String> annotationSet = new LinkedHashSet<>(4);
	/**
	 * 类上注解对应其注解中的注解（多层次的元注解）的映射集合
	 */
	protected final Map<String, Set<String>> metaAnnotationMap = new LinkedHashMap<>(4);

	/**
	 * 类上注解和其注解中的注解对应其注解属性对象的集合
	 */
	protected final LinkedMultiValueMap<String, AnnotationAttributes> attributesMap = new LinkedMultiValueMap<>(4);
	/**
	 * 方法元数据 MethodMetadataReadingVisitor
	 */
	protected final Set<MethodMetadata> methodMetadataSet = new LinkedHashSet<>(4);


	public AnnotationMetadataReadingVisitor(@Nullable ClassLoader classLoader) {
		this.classLoader = classLoader;
	}


	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		// Skip bridge methods - we're only interested in original annotation-defining user methods.
		// On JDK 8, we'd otherwise run into double detection of the same annotated method...
		if ((access & Opcodes.ACC_BRIDGE) != 0) {
			return super.visitMethod(access, name, desc, signature, exceptions);
		}
		return new MethodMetadataReadingVisitor(name, access, getClassName(),
				Type.getReturnType(desc).getClassName(), this.classLoader, this.methodMetadataSet);
	}

	@Override
	public AnnotationVisitor visitAnnotation(final String desc, boolean visible) {
		String className = Type.getType(desc).getClassName();
		this.annotationSet.add(className);
		return new AnnotationAttributesReadingVisitor(
				className, this.attributesMap, this.metaAnnotationMap, this.classLoader);
	}


	@Override
	public Set<String> getAnnotationTypes() {
		return this.annotationSet;
	}

	@Override
	public Set<String> getMetaAnnotationTypes(String annotationName) {
		Set<String> metaAnnotationTypes = this.metaAnnotationMap.get(annotationName);
		return (metaAnnotationTypes != null ? metaAnnotationTypes : Collections.emptySet());
	}

	/**
	 * 类上拥有对应注解
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for
	 * @return
	 */
	@Override
	public boolean hasAnnotation(String annotationName) {
		return this.annotationSet.contains(annotationName);
	}

	/**
	 * 允许复合注解时 复合注解中包含对应注解
	 * @param metaAnnotationType
	 * @return
	 */
	@Override
	public boolean hasMetaAnnotation(String metaAnnotationType) {
		Collection<Set<String>> allMetaTypes = this.metaAnnotationMap.values();
		for (Set<String> metaTypes : allMetaTypes) {
			if (metaTypes.contains(metaAnnotationType)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 是否包含目标注解
	 * @param annotationName the fully qualified class name of the annotation
	 * type to look for
	 * @return
	 */
	@Override
	public boolean isAnnotated(String annotationName) {
		return (!AnnotationUtils.isInJavaLangAnnotationPackage(annotationName) &&
				this.attributesMap.containsKey(annotationName));
	}

	@Override
	@Nullable
	public AnnotationAttributes getAnnotationAttributes(String annotationName) {
		return getAnnotationAttributes(annotationName, false);
	}

	/**
	 * 获取目标注解的属性
	 * @param annotationName 目标注解
	 * @param classValuesAsString false
	 * @return
	 */
	@Override
	@Nullable
	public AnnotationAttributes getAnnotationAttributes(String annotationName, boolean classValuesAsString) {
		AnnotationAttributes raw = AnnotationReadingVisitorUtils.getMergedAnnotationAttributes(
				this.attributesMap, this.metaAnnotationMap, annotationName);
		if (raw == null) {
			return null;
		}
		return AnnotationReadingVisitorUtils.convertClassValues(
				"class '" + getClassName() + "'", this.classLoader, raw, classValuesAsString);
	}

	@Override
	@Nullable
	public MultiValueMap<String, Object> getAllAnnotationAttributes(String annotationName) {
		return getAllAnnotationAttributes(annotationName, false);
	}

	/**
	 * 获取注解的属性值
	 * @param annotationName 注解名
	 * type to look for
	 * @param classValuesAsString 转成string
	 * @return
	 */
	@Override
	@Nullable
	public MultiValueMap<String, Object> getAllAnnotationAttributes(String annotationName, boolean classValuesAsString) {
		MultiValueMap<String, Object> allAttributes = new LinkedMultiValueMap<>();
		List<AnnotationAttributes> attributes = this.attributesMap.get(annotationName);
		if (attributes == null) {
			return null;
		}
		for (AnnotationAttributes raw : attributes) {
			//转化class的value值
			for (Map.Entry<String, Object> entry : AnnotationReadingVisitorUtils.convertClassValues(
					"class '" + getClassName() + "'", this.classLoader, raw, classValuesAsString).entrySet()) {
				allAttributes.add(entry.getKey(), entry.getValue());
			}
		}
		return allAttributes;
	}

	@Override
	public boolean hasAnnotatedMethods(String annotationName) {
		for (MethodMetadata methodMetadata : this.methodMetadataSet) {
			if (methodMetadata.isAnnotated(annotationName)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Set<MethodMetadata> getAnnotatedMethods(String annotationName) {
		Set<MethodMetadata> annotatedMethods = new LinkedHashSet<>(4);
		for (MethodMetadata methodMetadata : this.methodMetadataSet) {
			if (methodMetadata.isAnnotated(annotationName)) {
				annotatedMethods.add(methodMetadata);
			}
		}
		return annotatedMethods;
	}

}
