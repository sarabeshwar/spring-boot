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

package org.springframework.boot.context.properties.bind.handler;

import org.springframework.boot.context.properties.bind.AbstractBindHandler;
import org.springframework.boot.context.properties.bind.BindContext;
import org.springframework.boot.context.properties.bind.BindHandler;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;

/**
 * {@link BindHandler} to limit binding to only first level properties.
 *
 * @author Phillip Webb
 * @since 2.0.0
 */
public class IgnoreNestedPropertiesBindHandler extends AbstractBindHandler {

	public IgnoreNestedPropertiesBindHandler() {
		super();
	}

	public IgnoreNestedPropertiesBindHandler(BindHandler parent) {
		super(parent);
	}

	@Override
	public boolean onStart(ConfigurationPropertyName name, Bindable<?> target,
			BindContext context) {
		if (context.getDepth() > 1) {
			return false;
		}
		return super.onStart(name, target, context);
	}

}
