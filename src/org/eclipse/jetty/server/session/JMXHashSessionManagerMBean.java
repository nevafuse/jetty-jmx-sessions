//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.session;

import java.lang.Exception;
		   
public interface JMXHashSessionManagerMBean
{
	public void restoreSession(String clusterId, String nodeId, long creationTime, long accessed, byte[] attributes, String[] deletedKeys) throws Exception; //, boolean invalid, int requests, int maxInactiveInterval
	
	public void removeSessions(String[] clusterIds) throws Exception;
	
	public void resetSessionsLastSave() throws Exception;
}