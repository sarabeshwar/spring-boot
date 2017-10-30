/*
 * Copyright 2012-2017 the original author or authors.
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

package org.springframework.boot.actuate.autoconfigure.cloudfoundry.reactive;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import org.springframework.boot.actuate.endpoint.EndpointInfo;
import org.springframework.boot.actuate.endpoint.OperationInvoker;
import org.springframework.boot.actuate.endpoint.OperationType;
import org.springframework.boot.actuate.endpoint.ParameterMappingException;
import org.springframework.boot.actuate.endpoint.ParametersMissingException;
import org.springframework.boot.actuate.endpoint.web.EndpointLinksResolver;
import org.springframework.boot.actuate.endpoint.web.EndpointMediaTypes;
import org.springframework.boot.actuate.endpoint.web.Link;
import org.springframework.boot.actuate.endpoint.web.WebEndpointOperation;
import org.springframework.boot.actuate.endpoint.web.WebEndpointResponse;
import org.springframework.boot.actuate.endpoint.web.reactive.AbstractWebFluxEndpointHandlerMapping;
import org.springframework.boot.endpoint.web.EndpointMapping;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * A custom {@link HandlerMapping} that makes web endpoints available over HTTP using
 * Spring WebFlux.
 *
 * @author Madhura Bhave
 */
public class CloudFoundryWebFluxEndpointHandlerMapping extends AbstractWebFluxEndpointHandlerMapping {

	private final Method handleRead = ReflectionUtils
			.findMethod(ReadOperationHandler.class, "handle", ServerWebExchange.class);

	private final Method handleWrite = ReflectionUtils.findMethod(
			WriteOperationHandler.class, "handle", ServerWebExchange.class, Map.class);

	private final Method links = ReflectionUtils.findMethod(getClass(), "links",
			ServerHttpRequest.class);

	private final EndpointLinksResolver endpointLinksResolver = new EndpointLinksResolver();

	private final ReactiveCloudFoundrySecurityInterceptor securityInterceptor;

	@Override
	protected Method getLinks() {
		return this.links;
	}

	@Override
	protected void registerMappingForOperation(WebEndpointOperation operation) {
		OperationType operationType = operation.getType();
		OperationInvoker operationInvoker = operation.getInvoker();
		if (operation.isBlocking()) {
			operationInvoker = new ElasticSchedulerOperationInvoker(operationInvoker);
		}
		registerMapping(createRequestMappingInfo(operation),
				operationType == OperationType.WRITE
						? new WriteOperationHandler(operationInvoker)
						: new ReadOperationHandler(operationInvoker),
				operationType == OperationType.WRITE ? this.handleWrite
						: this.handleRead);
	}

	@ResponseBody
	private Map<String, Map<String, Link>> links(ServerHttpRequest request) {
		return Collections.singletonMap("_links",
				this.endpointLinksResolver.resolveLinks(getEndpoints(),
						UriComponentsBuilder.fromUri(request.getURI()).replaceQuery(null)
								.toUriString()));
	}

	/**
	 * Creates a new {@code WebEndpointHandlerMapping} that provides mappings for the
	 * operations of the given {@code webEndpoints}.
	 * @param endpointMapping the base mapping for all endpoints
	 * @param webEndpoints the web endpoints
	 * @param endpointMediaTypes media types consumed and produced by the endpoints
	 * @param corsConfiguration the CORS configuration for the endpoints
	 */
	public CloudFoundryWebFluxEndpointHandlerMapping(EndpointMapping endpointMapping,
			Collection<EndpointInfo<WebEndpointOperation>> webEndpoints,
			EndpointMediaTypes endpointMediaTypes, CorsConfiguration corsConfiguration,
			ReactiveCloudFoundrySecurityInterceptor securityInterceptor) {
		super(endpointMapping, webEndpoints, endpointMediaTypes, corsConfiguration);
		this.securityInterceptor = securityInterceptor;
	}

	/**
	 * Base class for handlers for endpoint operations.
	 */
	abstract class AbstractOperationHandler {

		private final OperationInvoker operationInvoker;

		private final ReactiveCloudFoundrySecurityInterceptor securityInterceptor;

		AbstractOperationHandler(OperationInvoker operationInvoker, ReactiveCloudFoundrySecurityInterceptor securityInterceptor) {
			this.operationInvoker = operationInvoker;
			this.securityInterceptor = securityInterceptor;
		}

		@SuppressWarnings({ "unchecked" })
		Publisher<ResponseEntity<Object>> doHandle(ServerWebExchange exchange,
				Map<String, String> body) {
			return this.securityInterceptor
					.preHandle(exchange.getRequest(), "")
					.flatMap(securityResponse -> {
						if (!securityResponse.getStatus().equals(HttpStatus.OK)) {
							return Mono.just(new ResponseEntity<>(securityResponse.getStatus()));
						}
						Map<String, Object> arguments = new HashMap<>(exchange
								.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
						if (body != null) {
							arguments.putAll(body);
						}
						exchange.getRequest().getQueryParams().forEach((name, values) -> arguments
								.put(name, values.size() == 1 ? values.get(0) : values));
						return handleResult((Publisher<?>) operationInvoker.invoke(arguments),
								exchange.getRequest().getMethod());
					});
		}

		private Mono<ResponseEntity<Object>> handleResult(Publisher<?> result,
				HttpMethod httpMethod) {
			return Mono.from(result).map(this::toResponseEntity)
					.onErrorReturn(ParametersMissingException.class,
							new ResponseEntity<>(HttpStatus.BAD_REQUEST))
					.onErrorReturn(ParameterMappingException.class,
							new ResponseEntity<>(HttpStatus.BAD_REQUEST))
					.defaultIfEmpty(new ResponseEntity<>(httpMethod == HttpMethod.GET
							? HttpStatus.NOT_FOUND : HttpStatus.NO_CONTENT));
		}

		private ResponseEntity<Object> toResponseEntity(Object response) {
			if (!(response instanceof WebEndpointResponse)) {
				return new ResponseEntity<>(response, HttpStatus.OK);
			}
			WebEndpointResponse<?> webEndpointResponse = (WebEndpointResponse<?>) response;
			return new ResponseEntity<>(webEndpointResponse.getBody(),
					HttpStatus.valueOf(webEndpointResponse.getStatus()));
		}

	}

	/**
	 * A handler for an endpoint write operation.
	 */
	final class WriteOperationHandler extends AbstractOperationHandler {

		WriteOperationHandler(OperationInvoker operationInvoker) {
			super(operationInvoker, securityInterceptor);
		}

		@ResponseBody
		public Publisher<ResponseEntity<Object>> handle(ServerWebExchange exchange,
				@RequestBody(required = false) Map<String, String> body) {
			return doHandle(exchange, body);
		}

	}

	/**
	 * A handler for an endpoint write operation.
	 */
	final class ReadOperationHandler extends AbstractOperationHandler {

		ReadOperationHandler(OperationInvoker operationInvoker) {
			super(operationInvoker, securityInterceptor);
		}

		@ResponseBody
		public Publisher<ResponseEntity<Object>> handle(ServerWebExchange exchange) {
			return doHandle(exchange, null);
		}

	}
}
