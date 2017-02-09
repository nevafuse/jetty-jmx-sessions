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

import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class JMXHashedSession extends MemSession
{
    private static final Logger LOG = Log.getLogger(JMXHashedSession.class);
	
	private final ConcurrentMap<String, Boolean> _changedKeys = new ConcurrentHashMap<String, Boolean>();

    private final JMXHashSessionManager _hashSessionManager;

    /** Whether there has already been an attempt to save this session
     * which has failed.  If there has, there will be no more save attempts
     * for this session.  This is to stop the logs being flooded with errors
     * due to serialization failures that are most likely caused by user
     * data stored in the session that is not serializable. */
    private transient AtomicBoolean _saveFailed = new AtomicBoolean(false);
    
    /**
    * Last time session was saved to prevent periodic saves to sessions
    * that have not changed
    */
    private transient AtomicLong _lastSaved = new AtomicLong(0L);

    /* ------------------------------------------------------------- */
    protected JMXHashedSession(JMXHashSessionManager hashSessionManager, HttpServletRequest request)
    {
        super(hashSessionManager,request);
        _hashSessionManager = hashSessionManager;
    }

    /* ------------------------------------------------------------- */
    protected JMXHashedSession(JMXHashSessionManager hashSessionManager, long created, long accessed, String clusterId)
    {
        super(hashSessionManager,created, accessed, clusterId);
        _hashSessionManager = hashSessionManager;
    }

    /* ------------------------------------------------------------- */
    protected void checkValid()
    {
        super.checkValid();
    }
	
	protected ConcurrentMap<String, Boolean> getChangedKeys()
	{
		return _changedKeys;
	}
	
    /* ------------------------------------------------------------ */
    @Override
    public Object doPutOrRemove(String name, Object value)
    {
		_changedKeys.put(name, true);
        return super.doPutOrRemove(name, value);
    }

    /* ------------------------------------------------------------- */
    @Override
    public void setMaxInactiveInterval(int secs)
    {
        super.setMaxInactiveInterval(secs);
        if (getMaxInactiveInterval()>0&&(getMaxInactiveInterval()*1000L/10)<_hashSessionManager._scavengePeriodMs.longValue())
            _hashSessionManager.setScavengePeriod((secs+9)/10);
    }
	
	protected long getLastSaved()
	{
		return _lastSaved.longValue();
	}
	
	protected void setLastSaved(long millis) {
		_lastSaved.set(millis);
	}
	
	/* ------------------------------------------------------------- */
    @Override
    public void invalidate() throws IllegalStateException
    {
		super.invalidate();
		_hashSessionManager.getRemovedIds().put(getClusterId(), true);
    }
    
    int save (JMXHashSessionManagerMBean mbeanProxy)
    throws Exception
    {   
		if (LOG.isDebugEnabled())
			LOG.debug("Saving {}",super.getId());
		
		Map<String, Object> attributes = new HashMap<String, Object>();
		attributes.putAll(getAttributeMap());
		
		List<String> changedKeys = new ArrayList<String>();
		changedKeys.addAll(getChangedKeys().keySet());
		getChangedKeys().clear();
		
		if (_lastSaved.longValue() != 0)
			attributes.keySet().retainAll(changedKeys);
		changedKeys.removeAll(attributes.keySet());
		
		if (LOG.isDebugEnabled())
		{
			LOG.debug("changed attributes "+Arrays.toString(attributes.keySet().toArray()));
			LOG.debug("deleted attributes "+Arrays.toString(changedKeys.toArray()));
		}
			
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		ObjectOutputStream oos = new ObjectOutputStream(baos);
		oos.writeObject(attributes);
		oos.flush();
		byte[] bytes = baos.toByteArray();
		
		long lastSaved = getAccessed();
		if (_lastSaved.longValue() != 0)
			mbeanProxy.restoreSession(getClusterId(),null,0,lastSaved,bytes,changedKeys.toArray(new String[changedKeys.size()])); //,!isValid(),getRequests(),0
		else
			mbeanProxy.restoreSession(getClusterId(),getNodeId(),getCreationTime(),lastSaved,bytes,changedKeys.toArray(new String[changedKeys.size()])); //,!isValid(),getRequests(),getMaxInactiveInterval()
		int size = size(attributes, changedKeys);
		setLastSaved(lastSaved);
		return size;
    }
	
	protected int size(Map<String, Object> attributes, List<String> deletedKeys)
	{
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(out);
			
			oos.writeUTF(getClusterId());
			if (_lastSaved.longValue() != 0)
			{
				oos.writeUTF(getNodeId());
				oos.writeLong(getCreationTime());
				oos.writeInt(getMaxInactiveInterval());
			}
			else
				oos.writeLong(0L);
			oos.writeLong(getAccessed());
			oos.writeBoolean(!isValid());
			oos.writeInt(getRequests());
			//oos.writeInt(hs.getAttributes());
			
			for (String key : attributes.keySet())
			{
				oos.writeUTF(key);
				oos.writeObject(attributes.get(key));
			}
			
			for (String key : deletedKeys)
			{
				oos.writeUTF(key);
			}
			
			oos.flush();
			oos.close();
			
			// adding 2 bytes to match size on disk
			return out.toByteArray().length + 2;
		}
		catch (Exception e)
		{
			// stats will be inaccurate until reset
			// may need to add a variable to warn the user
		}
		return 0;
	}

    /* ------------------------------------------------------------ */
    public boolean isSaveFailed()
    {
        return _saveFailed.get();
    }

    /* ------------------------------------------------------------ */
    public void saveFailed()
    {
        _saveFailed.set(true);
    }
}
