/*
 * Copyright 2002-2006 the original author or authors.
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

package org.springframework.scripting.support;

import junit.framework.TestCase;
import org.easymock.MockControl;
import org.springframework.beans.factory.BeanFactory;

/**
 * Unit tests for the RefreshableScriptTargetSource class.
 *
 * @author Rick Evans
 */
public final class RefreshableScriptTargetSourceTests extends TestCase {

	public void testCreateWithNullScriptSource() throws Exception {
		MockControl mockFactory = MockControl.createNiceControl(BeanFactory.class);
		mockFactory.replay();
		try {
			new RefreshableScriptTargetSource((BeanFactory) mockFactory.getMock(), "a.bean", null, false);
			fail("Must have failed when passed a null ScriptSource.");
		}
		catch (IllegalArgumentException expected) {
		}
		mockFactory.verify();
	}

}
