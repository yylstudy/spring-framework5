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

package org.springframework.core;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

import org.springframework.lang.Nullable;
import org.springframework.util.ConcurrentReferenceHashMap;
import org.springframework.util.ObjectUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Internal utility class that can be used to obtain wrapped {@link Serializable}
 * variants of {@link java.lang.reflect.Type}s.
 *
 * <p>{@link #forField(Field) Fields} or {@link #forMethodParameter(MethodParameter)
 * MethodParameters} can be used as the root source for a serializable type.
 * Alternatively the {@link #forGenericSuperclass(Class) superclass},
 * {@link #forGenericInterfaces(Class) interfaces} or {@link #forTypeParameters(Class)
 * type parameters} or a regular {@link Class} can also be used as source.
 *
 * <p>The returned type will either be a {@link Class} or a serializable proxy of
 * {@link GenericArrayType}, {@link ParameterizedType}, {@link TypeVariable} or
 * {@link WildcardType}. With the exception of {@link Class} (which is final) calls
 * to methods that return further {@link Type}s (for example
 * {@link GenericArrayType#getGenericComponentType()}) will be automatically wrapped.
 *
 * @author Phillip Webb
 * @author Juergen Hoeller
 * @since 4.0
 */
abstract class SerializableTypeWrapper {
	/**
	 * Type的四大类型
	 */
	private static final Class<?>[] SUPPORTED_SERIALIZABLE_TYPES = {
			GenericArrayType.class, ParameterizedType.class, TypeVariable.class, WildcardType.class};

	static final ConcurrentReferenceHashMap<Type, Type> cache = new ConcurrentReferenceHashMap<>(256);


	/**
	 * Return a {@link Serializable} variant of {@link Field#getGenericType()}.
	 */
	@Nullable
	public static Type forField(Field field) {
		return forTypeProvider(new FieldTypeProvider(field));
	}

	/**
	 * Return a {@link Serializable} variant of
	 * {@link MethodParameter#getGenericParameterType()}.
	 */
	@Nullable
	public static Type forMethodParameter(MethodParameter methodParameter) {
		return forTypeProvider(new MethodParameterTypeProvider(methodParameter));
	}

	/**
	 * Return a {@link Serializable} variant of {@link Class#getGenericSuperclass()}.
	 */
	@SuppressWarnings("serial")
	@Nullable
	public static Type forGenericSuperclass(final Class<?> type) {
		return forTypeProvider(type::getGenericSuperclass);
	}

	/**
	 * Return a {@link Serializable} variant of {@link Class#getGenericInterfaces()}.
	 */
	@SuppressWarnings("serial")
	public static Type[] forGenericInterfaces(final Class<?> type) {
		Type[] result = new Type[type.getGenericInterfaces().length];
		for (int i = 0; i < result.length; i++) {
			final int index = i;
			//处理拥有泛型参数的接口
			result[i] = forTypeProvider(() -> type.getGenericInterfaces()[index]);
		}
		return result;
	}

	/**
	 * 获取当前接口的泛型参数 如 MyApplication<MyEvent> 中的MyEvent
	 * @param type
	 * @return
	 */
	@SuppressWarnings("serial")
	public static Type[] forTypeParameters(final Class<?> type) {
		//获取class上的泛型的Type
		Type[] result = new Type[type.getTypeParameters().length];
		for (int i = 0; i < result.length; i++) {
			final int index = i;
			//获取class上泛型的代理对象
			result[i] = forTypeProvider(() -> type.getTypeParameters()[index]);
		}
		return result;
	}

	/**
	 * Unwrap the given type, effectively returning the original non-serializable type.
	 * @param type the type to unwrap
	 * @return the original non-serializable type
	 */
	@SuppressWarnings("unchecked")
	public static <T extends Type> T unwrap(T type) {
		Type unwrapped = type;
		//如果是代理对象，那么就是SerializableTypeProxy的子类
		while (unwrapped instanceof SerializableTypeProxy) {
			unwrapped = ((SerializableTypeProxy) type).getTypeProvider().getType();
		}
		return (unwrapped != null ? (T) unwrapped : type);
	}

	/**
	 * Return a {@link Serializable} {@link Type} backed by a {@link TypeProvider} .
	 */
	/**
	 * 处理拥有泛型参数
	 * @param provider
	 * @return
	 */
	@Nullable
	static Type forTypeProvider(TypeProvider provider) {
		//获取对应的type
		Type providedType = provider.getType();
		//如果该type已经实现了Serializable接口，直接返回，所以这里可以看到创建动态代理的作用就是使type的四大对象
		//实现Serializable，但是这有什么作用呢？？？
		if (providedType == null || providedType instanceof Serializable) {
			// No serializable type wrapping necessary (e.g. for java.lang.Class)
			return providedType;
		}

		// Obtain a serializable type proxy for the given provider...
		Type cached = cache.get(providedType);
		if (cached != null) {
			return cached;
		}
		//轮询Type的四大类型
		for (Class<?> type : SUPPORTED_SERIALIZABLE_TYPES) {
			if (type.isInstance(providedType)) {
				ClassLoader classLoader = provider.getClass().getClassLoader();
				//创建动态代理的接口数组，所以这个动态代理 代理的是四大Type类型的所有方法
				//并添加代理类SerializableTypeProxy的getTypeProvider方法，这个方法得到的TypeProvider也是具有
				//Serializable的能力
				Class<?>[] interfaces = new Class<?>[] {type, SerializableTypeProxy.class, Serializable.class};
				//创建一个泛型参数接口的InvocationHandler
				InvocationHandler handler = new TypeProxyInvocationHandler(provider);
				//创建一个基于泛型参数接口的Type 的jdk动态代理类
				cached = (Type) Proxy.newProxyInstance(classLoader, interfaces, handler);
				cache.put(providedType, cached);
				return cached;
			}
		}
		throw new IllegalArgumentException("Unsupported Type class: " + providedType.getClass().getName());
	}


	/**
	 * Additional interface implemented by the type proxy.
	 */
	interface SerializableTypeProxy {

		/**
		 * Return the underlying type provider.
		 */
		TypeProvider getTypeProvider();
	}


	/**
	 * A {@link Serializable} interface providing access to a {@link Type}.
	 */
	@SuppressWarnings("serial")
	interface TypeProvider extends Serializable {

		/**
		 * Return the (possibly non {@link Serializable}) {@link Type}.
		 */
		@Nullable
		Type getType();

		/**
		 * Return the source of the type, or {@code null} if not known.
		 * <p>The default implementations returns {@code null}.
		 */
		@Nullable
		default Object getSource() {
			return null;
		}
	}


	/**
	 * {@link Serializable} {@link InvocationHandler} used by the proxied {@link Type}.
	 * Provides serialization support and enhances any methods that return {@code Type}
	 * or {@code Type[]}.
	 */
	@SuppressWarnings("serial")
	private static class TypeProxyInvocationHandler implements InvocationHandler, Serializable {
		/**
		 * 拥有泛型参数接口的Type
		 */
		private final TypeProvider provider;

		public TypeProxyInvocationHandler(TypeProvider provider) {
			this.provider = provider;
		}

		/**
		 * jdk动态代理方法
		 * @param proxy
		 * @param method
		 * @param args
		 * @return
		 * @throws Throwable
		 */
		@Override
		@Nullable
		public Object invoke(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
			if (method.getName().equals("equals") && args != null) {
				Object other = args[0];
				// Unwrap proxies for speed
				if (other instanceof Type) {
					other = unwrap((Type) other);
				}
				return ObjectUtils.nullSafeEquals(this.provider.getType(), other);
			}
			else if (method.getName().equals("hashCode")) {
				return ObjectUtils.nullSafeHashCode(this.provider.getType());
			}
			//如果方法是SerializableTypeProxy的方法getTypeProvider
			//就获取field封装的TypeProvider
			else if (method.getName().equals("getTypeProvider")) {
				return this.provider;
			}
			//四大Type类型对应的参数为空的返回值为Type的方法
			if (Type.class == method.getReturnType() && args == null) {
				return forTypeProvider(new MethodInvokeTypeProvider(this.provider, method, -1));
			}
			//四大Type类型对应的参数为空返回类型为Type[]的方法
			else if (Type[].class == method.getReturnType() && args == null) {
				Type[] result = new Type[((Type[]) method.invoke(this.provider.getType())).length];
				for (int i = 0; i < result.length; i++) {
					//获取方法执行对象的Type
					result[i] = forTypeProvider(new MethodInvokeTypeProvider(this.provider, method, i));
				}
				return result;
			}

			try {
				//执行四大类型原本的方法
				return method.invoke(this.provider.getType(), args);
			}
			catch (InvocationTargetException ex) {
				throw ex.getTargetException();
			}
		}
	}


	/**
	 * {@link TypeProvider} for {@link Type}s obtained from a {@link Field}.
	 */
	@SuppressWarnings("serial")
	static class FieldTypeProvider implements TypeProvider {
		/**
		 * 属性名
		 */
		private final String fieldName;
		/**
		 * 属性所在类的class
		 */
		private final Class<?> declaringClass;
		/**
		 * 字段本身的Field对象
		 */
		private transient Field field;

		public FieldTypeProvider(Field field) {
			this.fieldName = field.getName();
			this.declaringClass = field.getDeclaringClass();
			this.field = field;
		}

		/**
		 * 返回字段类型的class对象
		 * @return
		 */
		@Override
		public Type getType() {
			return this.field.getGenericType();
		}

		/**
		 * 获取源对象
		 * @return
		 */
		@Override
		public Object getSource() {
			return this.field;
		}

		private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
			inputStream.defaultReadObject();
			try {
				this.field = this.declaringClass.getDeclaredField(this.fieldName);
			}
			catch (Throwable ex) {
				throw new IllegalStateException("Could not find original class structure", ex);
			}
		}
	}


	/**
	 * {@link TypeProvider} for {@link Type}s obtained from a {@link MethodParameter}.
	 */
	@SuppressWarnings("serial")
	static class MethodParameterTypeProvider implements TypeProvider {
		/**
		 * 方法名
		 */
		@Nullable
		private final String methodName;
		/**
		 * 构造器或者方法的参数类型
		 */
		private final Class<?>[] parameterTypes;
		/**
		 * 构造器或者方法所在的class
		 */
		private final Class<?> declaringClass;
		/**
		 * 参数下标
		 */
		private final int parameterIndex;
		/**
		 * 方法参数对象
		 */
		private transient MethodParameter methodParameter;

		/**
		 * 构建一个方法参数类型提供者
		 * @param methodParameter
		 */
		public MethodParameterTypeProvider(MethodParameter methodParameter) {
			this.methodName = (methodParameter.getMethod() != null ? methodParameter.getMethod().getName() : null);
			this.parameterTypes = methodParameter.getExecutable().getParameterTypes();
			this.declaringClass = methodParameter.getDeclaringClass();
			this.parameterIndex = methodParameter.getParameterIndex();
			this.methodParameter = methodParameter;
		}

		/**
		 * 获取方法参数的类型（包括包括参数的泛型类型）
		 * @return
		 */
		@Override
		public Type getType() {
			//获取genericParamterType
			return this.methodParameter.getGenericParameterType();
		}

		@Override
		public Object getSource() {
			return this.methodParameter;
		}

		private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
			inputStream.defaultReadObject();
			try {
				if (this.methodName != null) {
					this.methodParameter = new MethodParameter(
							this.declaringClass.getDeclaredMethod(this.methodName, this.parameterTypes), this.parameterIndex);
				}
				else {
					this.methodParameter = new MethodParameter(
							this.declaringClass.getDeclaredConstructor(this.parameterTypes), this.parameterIndex);
				}
			}
			catch (Throwable ex) {
				throw new IllegalStateException("Could not find original class structure", ex);
			}
		}
	}


	/**
	 * {@link TypeProvider} for {@link Type}s obtained by invoking a no-arg method.
	 */
	@SuppressWarnings("serial")
	static class MethodInvokeTypeProvider implements TypeProvider {
		/**
		 * 拥有泛型参数Type提供者
		 */
		private final TypeProvider provider;
		/**
		 * 被代理方法名
		 */
		private final String methodName;
		/**
		 * 被代理方法所在的类（通常也就是对应的type类型）
		 */
		private final Class<?> declaringClass;
		/**
		 * 下标-1
		 */
		private final int index;
		/**
		 * Type所在的方法
		 */
		private transient Method method;

		@Nullable
		private transient volatile Object result;

		public MethodInvokeTypeProvider(TypeProvider provider, Method method, int index) {
			this.provider = provider;
			this.methodName = method.getName();
			this.declaringClass = method.getDeclaringClass();
			this.index = index;
			this.method = method;
		}

		/**
		 * 获取Type对象
		 * @return
		 */
		@Override
		@Nullable
		public Type getType() {
			Object result = this.result;
			if (result == null) {
				// Lazy invocation of the target method on the provided type
				result = ReflectionUtils.invokeMethod(this.method, this.provider.getType());
				// Cache the result for further calls to getType()
				this.result = result;
			}
			return (result instanceof Type[] ? ((Type[]) result)[this.index] : (Type) result);
		}

		@Override
		@Nullable
		public Object getSource() {
			return null;
		}

		private void readObject(ObjectInputStream inputStream) throws IOException, ClassNotFoundException {
			inputStream.defaultReadObject();
			Method method = ReflectionUtils.findMethod(this.declaringClass, this.methodName);
			if (method == null) {
				throw new IllegalStateException("Cannot find method on deserialization: " + this.methodName);
			}
			if (method.getReturnType() != Type.class && method.getReturnType() != Type[].class) {
				throw new IllegalStateException(
						"Invalid return type on deserialized method - needs to be Type or Type[]: " + method);
			}
			this.method = method;
		}
	}

}
