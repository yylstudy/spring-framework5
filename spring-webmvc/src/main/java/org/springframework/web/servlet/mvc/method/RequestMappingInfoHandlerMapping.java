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

package org.springframework.web.servlet.mvc.method;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.util.CollectionUtils;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.UnsatisfiedServletRequestParameterException;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.mvc.condition.NameValueExpression;
import org.springframework.web.util.WebUtils;

/**
 * Abstract base class for classes for which {@link RequestMappingInfo} defines
 * the mapping between a request and a handler method.
 *
 * @author Arjen Poutsma
 * @author Rossen Stoyanchev
 * @since 3.1
 */
public abstract class RequestMappingInfoHandlerMapping extends AbstractHandlerMethodMapping<RequestMappingInfo> {

	private static final Method HTTP_OPTIONS_HANDLE_METHOD;

	static {
		try {
			HTTP_OPTIONS_HANDLE_METHOD = HttpOptionsHandler.class.getMethod("handle");
		} catch (NoSuchMethodException ex) {
			// Should never happen
			throw new IllegalStateException("Failed to retrieve internal handler method for HTTP OPTIONS", ex);
		}
	}

	/**
	 * 空构造
	 */
	protected RequestMappingInfoHandlerMapping() {
		//设置HandlerMethod映射名称策略
		setHandlerMethodMappingNamingStrategy(new RequestMappingInfoHandlerMethodMappingNamingStrategy());
	}


	/**
	 * Get the URL path patterns associated with this {@link RequestMappingInfo}.
	 */
	@Override
	protected Set<String> getMappingPathPatterns(RequestMappingInfo info) {
		return info.getPatternsCondition().getPatterns();
	}

	/**
	 * Check if the given RequestMappingInfo matches the current request and
	 * return a (potentially new) instance with conditions that match the
	 * current request -- for example with a subset of URL patterns.
	 *
	 * @return an info in case of a match; or {@code null} otherwise.
	 */
	@Override
	protected RequestMappingInfo getMatchingMapping(RequestMappingInfo info, HttpServletRequest request) {
		return info.getMatchingCondition(request);
	}

	/**
	 * Provide a Comparator to sort RequestMappingInfos matched to a request.
	 */
	@Override
	protected Comparator<RequestMappingInfo> getMappingComparator(final HttpServletRequest request) {
		return (info1, info2) -> info1.compareTo(info2, request);
	}

	/**
	 * 处理匹配的请求
	 * @param info
	 * @param lookupPath mapping lookup path within the current servlet mapping
	 * @param request the current request
	 */
	@Override
	protected void handleMatch(RequestMappingInfo info, String lookupPath, HttpServletRequest request) {
		super.handleMatch(info, lookupPath, request);

		String bestPattern;
		Map<String, String> uriVariables;
		//获取RequestMappingInfo的url
		Set<String> patterns = info.getPatternsCondition().getPatterns();
		if (patterns.isEmpty()) {
			bestPattern = lookupPath;
			uriVariables = Collections.emptyMap();
		} else {
			bestPattern = patterns.iterator().next();
			uriVariables = getPathMatcher().extractUriTemplateVariables(bestPattern, lookupPath);
		}
		//设置最匹配的url字符串
		request.setAttribute(BEST_MATCHING_PATTERN_ATTRIBUTE, bestPattern);

		if (isMatrixVariableContentAvailable()) {
			Map<String, MultiValueMap<String, String>> matrixVars = extractMatrixVariables(request, uriVariables);
			request.setAttribute(HandlerMapping.MATRIX_VARIABLES_ATTRIBUTE, matrixVars);
		}

		Map<String, String> decodedUriVariables = getUrlPathHelper().decodePathVariables(request, uriVariables);
		request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, decodedUriVariables);

		if (!info.getProducesCondition().getProducibleMediaTypes().isEmpty()) {
			Set<MediaType> mediaTypes = info.getProducesCondition().getProducibleMediaTypes();
			request.setAttribute(PRODUCIBLE_MEDIA_TYPES_ATTRIBUTE, mediaTypes);
		}
	}

	private boolean isMatrixVariableContentAvailable() {
		return !getUrlPathHelper().shouldRemoveSemicolonContent();
	}

	private Map<String, MultiValueMap<String, String>> extractMatrixVariables(
			HttpServletRequest request, Map<String, String> uriVariables) {

		Map<String, MultiValueMap<String, String>> result = new LinkedHashMap<>();
		uriVariables.forEach((uriVarKey, uriVarValue) -> {
			int equalsIndex = uriVarValue.indexOf('=');
			if (equalsIndex == -1) {
				return;
			}

			String matrixVariables;

			int semicolonIndex = uriVarValue.indexOf(';');
			if ((semicolonIndex == -1) || (semicolonIndex == 0) || (equalsIndex < semicolonIndex)) {
				matrixVariables = uriVarValue;
			} else {
				matrixVariables = uriVarValue.substring(semicolonIndex + 1);
				uriVariables.put(uriVarKey, uriVarValue.substring(0, semicolonIndex));
			}

			MultiValueMap<String, String> vars = WebUtils.parseMatrixVariables(matrixVariables);
			result.put(uriVarKey, getUrlPathHelper().decodeMatrixVariables(request, vars));
		});
		return result;
	}

	/**
	 * 处理请求
	 * @param infos
	 * @param lookupPath
	 * @param request
	 * @return
	 * @throws ServletException
	 */
	@Override
	protected HandlerMethod handleNoMatch(
			Set<RequestMappingInfo> infos, String lookupPath, HttpServletRequest request) throws ServletException {
		//创建PartialMatchHelper
		PartialMatchHelper helper = new PartialMatchHelper(infos, request);
		if (helper.isEmpty()) {
			return null;
		}
		//请求方式是否缺失匹配
		if (helper.hasMethodsMismatch()) {
			Set<String> methods = helper.getAllowedMethods();
			if (HttpMethod.OPTIONS.matches(request.getMethod())) {
				HttpOptionsHandler handler = new HttpOptionsHandler(methods);
				return new HandlerMethod(handler, HTTP_OPTIONS_HANDLE_METHOD);
			}
			throw new HttpRequestMethodNotSupportedException(request.getMethod(), methods);
		}
		//consumes缺失匹配
		if (helper.hasConsumesMismatch()) {
			Set<MediaType> mediaTypes = helper.getConsumableMediaTypes();
			MediaType contentType = null;
			if (StringUtils.hasLength(request.getContentType())) {
				try {
					contentType = MediaType.parseMediaType(request.getContentType());
				} catch (InvalidMediaTypeException ex) {
					throw new HttpMediaTypeNotSupportedException(ex.getMessage());
				}
			}
			throw new HttpMediaTypeNotSupportedException(contentType, new ArrayList<>(mediaTypes));
		}
		//produces缺失匹配
		if (helper.hasProducesMismatch()) {
			Set<MediaType> mediaTypes = helper.getProducibleMediaTypes();
			throw new HttpMediaTypeNotAcceptableException(new ArrayList<>(mediaTypes));
		}
		//params缺失匹配
		if (helper.hasParamsMismatch()) {
			List<String[]> conditions = helper.getParamConditions();
			throw new UnsatisfiedServletRequestParameterException(conditions, request.getParameterMap());
		}

		return null;
	}


	/**
	 * Aggregate all partial matches and expose methods checking across them.
	 */
	private static class PartialMatchHelper {
		/**
		 * 匹配当前Url的PartialMatch
		 */
		private final List<PartialMatch> partialMatches = new ArrayList<>();

		/**
		 * 创建PartialMatchHelper
		 * @param infos
		 * @param request
		 */
		public PartialMatchHelper(Set<RequestMappingInfo> infos, HttpServletRequest request) {
			//遍历容器中所有的RequestMappingInfo
			for (RequestMappingInfo info : infos) {
				//找到匹配当前url的RequestMappingInfo
				if (info.getPatternsCondition().getMatchingCondition(request) != null) {
					this.partialMatches.add(new PartialMatch(info, request));
				}
			}
		}

		/**
		 * Whether there any partial matches.
		 */
		public boolean isEmpty() {
			return this.partialMatches.isEmpty();
		}

		/**
		 * Any partial matches for "methods"?
		 */
		public boolean hasMethodsMismatch() {
			for (PartialMatch match : this.partialMatches) {
				if (match.hasMethodsMatch()) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Any partial matches for "methods" and "consumes"?
		 */
		public boolean hasConsumesMismatch() {
			for (PartialMatch match : this.partialMatches) {
				if (match.hasConsumesMatch()) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Any partial matches for "methods", "consumes", and "produces"?
		 */
		public boolean hasProducesMismatch() {
			for (PartialMatch match : this.partialMatches) {
				if (match.hasProducesMatch()) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Any partial matches for "methods", "consumes", "produces", and "params"?
		 */
		public boolean hasParamsMismatch() {
			for (PartialMatch match : this.partialMatches) {
				if (match.hasParamsMatch()) {
					return false;
				}
			}
			return true;
		}

		/**
		 * Return declared HTTP methods.
		 */
		public Set<String> getAllowedMethods() {
			Set<String> result = new LinkedHashSet<>();
			for (PartialMatch match : this.partialMatches) {
				for (RequestMethod method : match.getInfo().getMethodsCondition().getMethods()) {
					result.add(method.name());
				}
			}
			return result;
		}

		/**
		 * Return declared "consumable" types but only among those that also
		 * match the "methods" condition.
		 */
		public Set<MediaType> getConsumableMediaTypes() {
			Set<MediaType> result = new LinkedHashSet<>();
			for (PartialMatch match : this.partialMatches) {
				if (match.hasMethodsMatch()) {
					result.addAll(match.getInfo().getConsumesCondition().getConsumableMediaTypes());
				}
			}
			return result;
		}

		/**
		 * Return declared "producible" types but only among those that also
		 * match the "methods" and "consumes" conditions.
		 */
		public Set<MediaType> getProducibleMediaTypes() {
			Set<MediaType> result = new LinkedHashSet<>();
			for (PartialMatch match : this.partialMatches) {
				if (match.hasConsumesMatch()) {
					result.addAll(match.getInfo().getProducesCondition().getProducibleMediaTypes());
				}
			}
			return result;
		}

		/**
		 * Return declared "params" conditions but only among those that also
		 * match the "methods", "consumes", and "params" conditions.
		 */
		public List<String[]> getParamConditions() {
			List<String[]> result = new ArrayList<>();
			for (PartialMatch match : this.partialMatches) {
				if (match.hasProducesMatch()) {
					Set<NameValueExpression<String>> set = match.getInfo().getParamsCondition().getExpressions();
					if (!CollectionUtils.isEmpty(set)) {
						int i = 0;
						String[] array = new String[set.size()];
						for (NameValueExpression<String> expression : set) {
							array[i++] = expression.toString();
						}
						result.add(array);
					}
				}
			}
			return result;
		}


		/**
		 * Container for a RequestMappingInfo that matches the URL path at least.
		 */
		private static class PartialMatch {
			/**
			 * 匹配当前url的RequestMappingInfo
			 */
			private final RequestMappingInfo info;
			/**
			 * 请求方式是否匹配
			 */
			private final boolean methodsMatch;
			/**
			 * consumes是否匹配
			 */
			private final boolean consumesMatch;
			/**
			 * produces是否匹配
			 */
			private final boolean producesMatch;

			private final boolean paramsMatch;

			/**
			 * @param info    RequestMappingInfo that matches the URL path.
			 * @param request the current request
			 */
			public PartialMatch(RequestMappingInfo info, HttpServletRequest request) {
				this.info = info;
				this.methodsMatch = (info.getMethodsCondition().getMatchingCondition(request) != null);
				this.consumesMatch = (info.getConsumesCondition().getMatchingCondition(request) != null);
				this.producesMatch = (info.getProducesCondition().getMatchingCondition(request) != null);
				this.paramsMatch = (info.getParamsCondition().getMatchingCondition(request) != null);
			}

			public RequestMappingInfo getInfo() {
				return this.info;
			}

			public boolean hasMethodsMatch() {
				return this.methodsMatch;
			}

			public boolean hasConsumesMatch() {
				return (hasMethodsMatch() && this.consumesMatch);
			}

			public boolean hasProducesMatch() {
				return (hasConsumesMatch() && this.producesMatch);
			}

			public boolean hasParamsMatch() {
				return (hasProducesMatch() && this.paramsMatch);
			}

			@Override
			public String toString() {
				return this.info.toString();
			}
		}
	}


	/**
	 * Default handler for HTTP OPTIONS.
	 */
	private static class HttpOptionsHandler {

		private final HttpHeaders headers = new HttpHeaders();

		public HttpOptionsHandler(Set<String> declaredMethods) {
			this.headers.setAllow(initAllowedHttpMethods(declaredMethods));
		}

		private static Set<HttpMethod> initAllowedHttpMethods(Set<String> declaredMethods) {
			Set<HttpMethod> result = new LinkedHashSet<>(declaredMethods.size());
			if (declaredMethods.isEmpty()) {
				for (HttpMethod method : HttpMethod.values()) {
					if (method != HttpMethod.TRACE) {
						result.add(method);
					}
				}
			} else {
				for (String method : declaredMethods) {
					HttpMethod httpMethod = HttpMethod.valueOf(method);
					result.add(httpMethod);
					if (httpMethod == HttpMethod.GET) {
						result.add(HttpMethod.HEAD);
					}
				}
			}
			return result;
		}

		public HttpHeaders handle() {
			return this.headers;
		}
	}

}
