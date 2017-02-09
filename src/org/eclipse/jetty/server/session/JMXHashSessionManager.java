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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.lang.Double;
import java.lang.Exception;
import java.lang.Integer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.JMX;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.annotation.ManagedOperation;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.thread.ScheduledExecutorScheduler;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.statistic.SampleStatistic;

/* ------------------------------------------------------------ */
/** 
 * HashSessionManager
 * 
 * An in-memory implementation of SessionManager.
 * <p>
 * This manager supports saving sessions to disk, either periodically or at shutdown.
 * <p>
 * This manager will create it's own Timer instance to scavenge threads, unless it discovers a shared Timer instance
 * set as the "org.eclipse.jetty.server.session.timer" attribute of the ContextHandler.
 *
 */
@ManagedObject("JMX Hash Session Manager")
public class JMXHashSessionManager extends AbstractSessionManager implements JMXHashSessionManagerMBean
{
    final static Logger LOG = SessionHandler.LOG;

    protected final ConcurrentMap<String,JMXHashedSession> _sessions=new ConcurrentHashMap<String,JMXHashedSession>();
    private Scheduler _timer;
    private Scheduler.Task _task;
    AtomicLong _scavengePeriodMs = new AtomicLong(30000L);
    AtomicLong _savePeriodMs = new AtomicLong(0L); //don't do period saves by default
    private Scheduler.Task _saveTask;
    private AtomicBoolean _sessionsLoaded = new AtomicBoolean(false);
	private AtomicBoolean _retry = new AtomicBoolean(false);
	private JMXConnector jmxc = null;
	AtomicInteger _alertCnt = new AtomicInteger(0);
	
	protected final SampleStatistic _sessionChangeStats = new SampleStatistic();
	protected final SampleStatistic _sessionSaveStats = new SampleStatistic();
	protected final SampleStatistic _sessionSavePeriodStats = new SampleStatistic();
	
	protected AtomicLong _lastReset = new AtomicLong(System.currentTimeMillis());
	
	private final ConcurrentMap<String, Boolean> _removedIds = new ConcurrentHashMap<String, Boolean>();
	
	protected ConcurrentMap<String, Boolean> getRemovedIds()
	{
		return _removedIds;
	}
		
    /* ------------------------------------------------------------ */
    /**
     * @return millis since reset
     */
    @ManagedAttribute("millis since reset")
    public long getMillisSinceReset()
    {
        return System.currentTimeMillis()-_lastReset.longValue();
    }
	
    @ManagedAttribute("average ms of session save times")
    public double getSessionsSavePeriodMean()
    {
        return _sessionSavePeriodStats.getMean();
    }
		
    @ManagedAttribute("max ms of session save times")
    public long getSessionsSavePeriodMax()
    {
        return _sessionSavePeriodStats.getMax();
    }
	
    /* ------------------------------------------------------------ */
    /**
     * @return total size of session save changes
     */
    @ManagedAttribute("total size of session save changes")
    public long getSessionsSaveTotal()
    {
        return _sessionSaveStats.getTotal();
    }
	
    /* ------------------------------------------------------------ */
    /**
     * @return total size of session changes
     */
    @ManagedAttribute("total size of session changes")
    public long getSessionChangeTotal()
    {
        return _sessionChangeStats.getTotal();
    }
	
	@ManagedOperation(value="close jmx connection", impact="ACTION")
    public void jmxClose()
    {
		if (jmxc != null)
		{
			try
			{
				jmxc.close();
			}
			catch (IOException e)
			{
				// nothing necessary
			}
		}
	}
	    
    /* ------------------------------------------------------------ */
    /**
     * Reset statistics values
     */
    @ManagedOperation(value="reset statistics", impact="ACTION")
	@Override
    public void statsReset()
    {
		_lastReset.set(System.currentTimeMillis());
		_sessionChangeStats.reset();
		_sessionSaveStats.reset();
		_sessionSavePeriodStats.reset();
		super.statsReset();
    }
	
	@Override
    public void doSessionAttributeListeners(AbstractSession session, String name, Object old, Object value)
    {
		try
		{
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(out);
			oos.writeUTF(name);
			oos.writeObject(value);
			oos.flush();
			oos.close();
			_sessionChangeStats.set(out.toByteArray().length);
		}
		catch (IOException e)
		{
			// _sessionChangeStats will be inaccurate until reset
			// may need to add a variable to warn the user
		}
        super.doSessionAttributeListeners(session, name, old, value);
    }
	
	protected synchronized JMXHashSessionManagerMBean getJMXHashSessionManagerMBean() throws Exception
	{
		//if (System.getSecurityManager() == null)
		//	System.setSecurityManager(new SecurityManager());
		String url = System.getProperty("jmxrmi.url");
		MBeanServerConnection mbsc = null;
		try
		{
			mbsc = jmxc.getMBeanServerConnection();
		}
		catch (Exception e)
		{
			if (_alertCnt.get() < 4 || LOG.isDebugEnabled())
			{
				_alertCnt.incrementAndGet();
				LOG.info("reconnecting to " + url);
			}
			JMXServiceURL jurl = new JMXServiceURL(url);
			jmxc = JMXConnectorFactory.connect(jurl, null);
			mbsc = jmxc.getMBeanServerConnection();
		}

		String query = System.getProperty("jmxrmi.query", "org.eclipse.jetty.server.session:context=*,type=jmxhashsessionmanager,*");
		ObjectName mbeanName = new ObjectName (query);
		Set<ObjectName> mbeanNames = mbsc.queryNames(mbeanName, null);
		
		if (mbeanNames.size() == 0)
			throw new Exception("unable to find matching objectname");

		return JMX.newMXBeanProxy(mbsc, mbeanNames.toArray(new ObjectName[0])[0], JMXHashSessionManagerMBean.class);
	}

	private List<Integer> getSessionSizes()
	{
		List<Integer> sizes = new ArrayList<Integer>();
		Collection<JMXHashedSession> c = _sessions.values();
		for (JMXHashedSession hs : c) 
		{	
			sizes.add(hs.size(hs.getAttributeMap(),new ArrayList<String>()));
		}
		Collections.sort(sizes);
		return sizes;
	}
	
	@ManagedAttribute("returns size of all sessions in bytes")
    public int getSessionSize()
	{
		List<Integer> sizes = getSessionSizes();
		int sum=0;
		for (int size : sizes)
			sum += size;
		return sum;
	}
	
	@ManagedOperation("reset all sessions last saved time")
	public void resetSessionsLastSave()
	{
		LOG.info("reseting sessions last saved to zero");
		for (JMXHashedSession session : _sessions.values())
            session.setLastSaved(0L);
		
		// restarting timer when it has scavenger period
		if (_timer != null && _timer.isRunning() && _retry.get() && (_saveTask == null || _saveTask.cancel())) {
			_retry.set(false);
            _saveTask = _timer.schedule(new Saver(), 1, TimeUnit.MILLISECONDS);
		}
	}
	
	@ManagedOperation("returns specified percentile size of all sessions in bytes")
	public int getSessionSizePerc(@Name("percentile") int perc)
	{
		List<Integer> sizes = getSessionSizes();
		return sizes.size()==0?0:sizes.get(new Double((sizes.size()-1)*perc/100).intValue());
	}
	
    /**
     * Scavenger
     *
     */
    protected class Scavenger implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                scavenge();
				_alertCnt.set(0);
            }
            finally
            {
                if (_timer != null && _timer.isRunning()) {
                    _task = _timer.schedule(this, _scavengePeriodMs.longValue(), TimeUnit.MILLISECONDS);
                }
            }
        }
    }
	
	private boolean askForSessions(JMXHashSessionManagerMBean mbeanProxy) throws Exception
	{
		if (_alertCnt.get() < 4 || LOG.isDebugEnabled())
		{
			_alertCnt.incrementAndGet();
			LOG.info("asking for all sessions");
		}
		
		String url = System.getProperty("jmxrmi.self.url", "service:jmx:rmi://localhost:1099/jndi/rmi://localhost:1099/jmxrmi");
		String query = System.getProperty("jmxrmi.query", "org.eclipse.jetty.server.session:context=*,type=jmxhashsessionmanager,*");

		JMXConnector jmxc = null;
		try
		{
			JMXServiceURL jurl = new JMXServiceURL(url);
			jmxc = JMXConnectorFactory.connect(jurl, null);
			MBeanServerConnection mbsc = jmxc.getMBeanServerConnection();
			ObjectName mbeanName = new ObjectName (query);
			Set<ObjectName> mbeanNames = mbsc.queryNames(mbeanName, null);

			if (mbeanNames.size() == 0)
			{
				throw new Exception();
			}
			else
			{
				_sessionsLoaded.set(true);
				mbeanProxy.resetSessionsLastSave();
				return true;
			}
		}
		catch (Exception e)
		{
			if (_alertCnt.get() < 4 || LOG.isDebugEnabled())
			{
				_alertCnt.incrementAndGet();
				LOG.warn("not ready to receive sessions");
			}
			return false;
		}
		finally
		{
			if (jmxc != null)
			{
				try
				{
					jmxc.close();
				}
				catch (IOException e)
				{
					// nothing necessary
				}
			}
		}
	}

    /**
     * Saver
     *
     */
    protected class Saver implements Runnable
    {
        @Override
        public void run()
        {
			long start = 0;
			long periodMs = getSavePeriod();
            try
            {
				start = System.currentTimeMillis();
				JMXHashSessionManagerMBean mbeanProxy = getJMXHashSessionManagerMBean();
				
				if (!_sessionsLoaded.get()) {
					// use scavenger period if not ready
					if (!askForSessions(mbeanProxy))
						periodMs = _scavengePeriodMs.longValue();
				} else {
					saveSessions(mbeanProxy);
				}
				_sessionSavePeriodStats.set(System.currentTimeMillis()-start);
				if (_retry.get())
					_retry.set(false);
            }
            catch (Exception e)
            {       
				if (_alertCnt.get() < 4 || LOG.isDebugEnabled())
				{
					_alertCnt.incrementAndGet();
					LOG.warn("problem reconnecting to jmx", e);
				}
				
				// use scavenger period because there are connection problems
				periodMs = _scavengePeriodMs.longValue();
				_retry.set(true);
				
            } finally {
				if (_timer != null && _timer.isRunning())
                    _saveTask = _timer.schedule(this, periodMs, TimeUnit.MILLISECONDS);
			}
        }        
    }


    /* ------------------------------------------------------------ */
    public JMXHashSessionManager()
    {
        super();
    }
	
    /* ------------------------------------------------------------ */
    /**
     * @see AbstractSessionManager#doStart()
     */
    @Override
    public void doStart() throws Exception
    {	
        //try shared scheduler from Server first
        _timer = getSessionHandler().getServer().getBean(Scheduler.class);
        if (_timer == null)
        {
            //try one passed into the context
            ServletContext context = ContextHandler.getCurrentContext();
            if (context!=null)
                _timer = (Scheduler)context.getAttribute("org.eclipse.jetty.server.session.timer");   
        }    
      
        if (_timer == null)
        {
            //make a scheduler if none useable
            _timer=new ScheduledExecutorScheduler(toString()+"Timer",true);
            addBean(_timer,true);
        }
        else
            addBean(_timer,false);
        
        super.doStart();

        setScavengePeriod(getScavengePeriod());

        setSavePeriod(getSavePeriod());
    }

    /* ------------------------------------------------------------ */
    /**
     * @see AbstractSessionManager#doStop()
     */
    @Override
    public void doStop() throws Exception
    {
        // stop the scavengers
        synchronized(this)
        {
            if (_saveTask!=null)
                _saveTask.cancel();

            _saveTask=null;
            if (_task!=null)
                _task.cancel();
            
            _task=null;
            
            //if we're managing our own timer, remove it
            if (isManaged(_timer))
               removeBean(_timer);

            _timer=null;
        }
       

        // This will callback invalidate sessions - where we decide if we will save
        super.doStop();

        _sessions.clear();
		
		if (jmxc != null)
		{
			try
			{
				jmxc.close();
			}
			catch (IOException e)
			{
				// nothing necessary
			}
		}
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in seconds at which a check is made for sessions to be invalidated.
     */
    public int getScavengePeriod()
    {
        return (int)(_scavengePeriodMs.longValue()/1000);
    }


    /* ------------------------------------------------------------ */
    @Override
    public int getSessions()
    {
        int sessions=super.getSessions();
        if (LOG.isDebugEnabled())
        {
            if (_sessions.size()!=sessions)
                LOG.warn("sessions: "+_sessions.size()+"!="+sessions);
        }
        return sessions;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setMaxInactiveInterval(int seconds)
    {
        super.setMaxInactiveInterval(seconds);
        if (_dftMaxIdleSecs>0&&_scavengePeriodMs.longValue()>_dftMaxIdleSecs*1000L)
            setScavengePeriod((_dftMaxIdleSecs+9)/10);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param seconds the period is milliseconds at which sessions are periodically saved to disk
     */
    public void setSavePeriod (long period)
    {
        if (period < 0)
            period=0;
        _savePeriodMs.set(period);

        if (_timer!=null)
        {
            synchronized (this)
            {
                if (_saveTask!=null)
                    _saveTask.cancel();
                _saveTask = null;
				String url = System.getProperty("jmxrmi.url");
                if (getSavePeriod() > 0 && url != null && url != "") //only save if we have jmx url
                {
                    _saveTask = _timer.schedule(new Saver(),getSavePeriod(),TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the period in seconds at which sessions are periodically saved to disk
     */
    public long getSavePeriod ()
    {
        if (_savePeriodMs.longValue()<=0)
            return 0;

        return _savePeriodMs.longValue();
    }

    /* ------------------------------------------------------------ */
    /**
     * @param seconds the period in seconds at which a check is made for sessions to be invalidated.
     */
    public void setScavengePeriod(int seconds)
    { 
        if (seconds==0)
            seconds=60;

        long old_period=_scavengePeriodMs.longValue();
        long period=seconds*1000L;
        if (period>60000)
            period=60000;
        if (period<1000)
            period=1000;

        _scavengePeriodMs.set(period);
    
        synchronized (this)
        {
            if (_timer!=null && (period!=old_period || _task==null))
            {
                if (_task!=null)
                {
                    _task.cancel();
                    _task = null;
                }

                _task = _timer.schedule(new Scavenger(),_scavengePeriodMs.longValue(), TimeUnit.MILLISECONDS);
            }
        }
    }

    /* -------------------------------------------------------------- */
    /**
     * Find sessions that have timed out and invalidate them. This runs in the
     * SessionScavenger thread.
     */
    protected void scavenge()
    {
        //don't attempt to scavenge if we are shutting down
        if (isStopping() || isStopped())
            return;

        Thread thread=Thread.currentThread();
        ClassLoader old_loader=thread.getContextClassLoader();
        try
        {      
            if (_loader!=null)
                thread.setContextClassLoader(_loader);

            // For each session
            long now=System.currentTimeMillis();
            __log.debug("Scavenging sessions at {}", now); 
            
            for (Iterator<JMXHashedSession> i=_sessions.values().iterator(); i.hasNext();)
            {
                JMXHashedSession session=i.next();
                long idleTime=session.getMaxInactiveInterval()*1000L; 
				//__log.info("id="+session.getId()+", maxInact="+session.getMaxInactiveInterval()+", access="+session.getAccessed());
                if (idleTime>0&&session.getAccessed()+idleTime<now)
                {
                    // Found a stale session, add it to the list
                    try
                    {
						//__log.info("timing out "+session.getId());
                        session.timeout();
                    }
                    catch (Exception e)
                    {
                        __log.warn("Problem scavenging sessions", e);
                    }
                }
            }
        }       
        finally
        {
            thread.setContextClassLoader(old_loader);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void addSession(AbstractSession session)
    {
        if (isRunning())
            _sessions.put(session.getClusterId(),(JMXHashedSession)session);
    }

    /* ------------------------------------------------------------ */
    @Override
    public AbstractSession getSession(String idInCluster)
    {
        Map<String,JMXHashedSession> sessions=_sessions;
        if (sessions==null)
            return null;

        JMXHashedSession session = sessions.get(idInCluster);

        if (session == null)
            return null;

        return session;
    }

    /* ------------------------------------------------------------ */
    @Override
    protected void shutdownSessions() throws Exception
    {   
        // Invalidate all sessions to cause unbind events
        ArrayList<JMXHashedSession> sessions=new ArrayList<JMXHashedSession>(_sessions.values());
        int loop=100;
        while (sessions.size()>0 && loop-->0)
        {
            // If we are called from doStop
            if (isStopping())
            {
                // Then we only save and remove the session from memory- it is not invalidated.
                for (JMXHashedSession session : sessions)
                {
                    _sessions.remove(session.getClusterId());
                }
            }
            else
            {
                for (JMXHashedSession session : sessions)
                    session.invalidate();
            }

            // check that no new sessions were created while we were iterating
            sessions=new ArrayList<JMXHashedSession>(_sessions.values());
        }
    }
    
    
    
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.server.SessionManager#renewSessionId(java.lang.String, java.lang.String, java.lang.String, java.lang.String)
     */
    @Override
    public void renewSessionId(String oldClusterId, String oldNodeId, String newClusterId, String newNodeId)
    {
        try
        {
            Map<String,JMXHashedSession> sessions=_sessions;
            if (sessions == null)
                return;

            JMXHashedSession session = sessions.remove(oldClusterId);
            if (session == null)
                return;

            session.setClusterId(newClusterId); //update ids
            session.setNodeId(newNodeId);
            sessions.put(newClusterId, session);
            
            super.renewSessionId(oldClusterId, oldNodeId, newClusterId, newNodeId);
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /* ------------------------------------------------------------ */
    @Override
    protected AbstractSession newSession(HttpServletRequest request)
    {
        return new JMXHashedSession(this, request);
    }

    /* ------------------------------------------------------------ */
    protected AbstractSession newSession(long created, long accessed, String clusterId)
    {
        return new JMXHashedSession(this, created,accessed, clusterId);
    }

    /* ------------------------------------------------------------ */
	@ManagedOperation("remove hashed session")
	public void removeSessions(@Name("clusterIds") String[] clusterIds)
	{
		if (LOG.isDebugEnabled())
			LOG.debug("removing ids "+Arrays.toString(clusterIds));
		for (String clusterId : clusterIds)
		{
			JMXHashedSession session = (JMXHashedSession) getSession(clusterId);
			if (session != null) {
				try {
					session.invalidate();
					getRemovedIds().remove(clusterId);
				} catch (Exception e) {
					// continue invalidating other sessions
				}
			}
		}
		
		// restarting timer when it has scavenger period
		if (_timer != null && _timer.isRunning() && _retry.get() && (_saveTask == null || _saveTask.cancel())) {
			_retry.set(false);
            _saveTask = _timer.schedule(new Saver(), 1, TimeUnit.MILLISECONDS);
		}
	}
	
    @Override
    protected boolean removeSession(String clusterId)
    {
        return _sessions.remove(clusterId)!=null;
    }
	
	@ManagedOperation("restore hashed session")
	public void restoreSession(@Name("clusterId")String clusterId, @Name("nodeId")String nodeId, @Name("created")long created, @Name("accessed")long accessed, @Name("attributes")byte[] bytes, @Name("deletedKeys")String[] deletedKeys) throws Exception // , @Name("invalid")boolean invalid, @Name("requests")int requests, @Name("maxInactiveInterval")int maxInactiveInterval
	{
		JMXHashedSession session = (JMXHashedSession) getSession(clusterId);
		if (session == null) // || (!invalid && !session.isValid()))
		{
			//Map<String, Object> curAttributes = new HashMap<String, Object>();
			//if (session != null)
			//	curAttributes.putAll(session.getAttributeMap());
			session = (JMXHashedSession) newSession(created, accessed, clusterId);
			//session.getAttributeMap().putAll(curAttributes);
			session.setNodeId(nodeId);
			session.setMaxInactiveInterval(getMaxInactiveInterval());
			addSession(session, true);
			//session.didActivate();
			if (LOG.isDebugEnabled())
				LOG.debug("Adding "+clusterId);
		}
		//else if (invalid)
		//{
		//	if (LOG.isDebugEnabled())
		//		LOG.debug("Invalidating "+clusterId);
		//	session.invalidate();
		//}
		else
		{
			if (LOG.isDebugEnabled())
				LOG.debug("Modifying "+clusterId);
			for (String key : Arrays.asList(deletedKeys))
				session.removeAttribute(key);
			//session.getAttributeMap().keySet().removeAll(Arrays.asList(deletedKeys));
			session.access(accessed);
		
		}
		
		Thread thread=Thread.currentThread();
		ClassLoader old_loader=thread.getContextClassLoader();
		try
		{      
			if (_loader!=null)
				thread.setContextClassLoader(_loader);
			
			ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
			ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(bais);
			@SuppressWarnings("unchecked")
			Map<String,Object> o = (Map<String,Object>)ois.readObject();
			ois.close();
			for (Map.Entry<String, Object> entry : o.entrySet())
			{
				session.setAttribute(entry.getKey(), entry.getValue());
			}
		}
		finally
		{
			thread.setContextClassLoader(old_loader);
		}
		
		session.getChangedKeys().clear();
		
		//session.setRequests(requests);
		session.setLastSaved(accessed);
		
		//addSession(session,false);
		//_sessionsStats.reset(_sessions.size());
		
		// restarting timer when it has scavenger period
		if (_timer != null && _timer.isRunning() && _retry.get() && (_saveTask == null || _saveTask.cancel())) {
			_retry.set(false);
            _saveTask = _timer.schedule(new Saver(), 1, TimeUnit.MILLISECONDS);
		}
	}

    /* ------------------------------------------------------------ */
    public void saveSessions(JMXHashSessionManagerMBean mbeanProxy) throws Exception
    {
		if (getRemovedIds().size() != 0)
		{
			if (LOG.isDebugEnabled())
				LOG.debug("asking to remove session ids "+Arrays.toString(getRemovedIds().keySet().toArray()));
			
			Set<String> removedIds = getRemovedIds().keySet();
			try
			{
				mbeanProxy.removeSessions(removedIds.toArray(new String[removedIds.size()]));		
			}
			catch (Exception e)
			{
				if (_alertCnt.get() < 4 || LOG.isDebugEnabled())
				{
					_alertCnt.incrementAndGet();
					LOG.warn("Problem removing session ids "+Arrays.toString(removedIds.toArray()));
				}
			}
			finally
			{
				getRemovedIds().clear();
			}
		}
		
        for (JMXHashedSession session : _sessions.values())
			if (!session.isSaveFailed() && (session.getChangedKeys().size() != 0 || (session.getLastSaved() < session.getAccessed() && (session.getLastSaved() + _scavengePeriodMs.longValue()) < System.currentTimeMillis()))) {
				try {
					_sessionSaveStats.set(session.save(mbeanProxy));
				}
				catch (Exception e)
				{
					session.saveFailed();
					if (_alertCnt.get() < 4 || LOG.isDebugEnabled())
					{
						_alertCnt.incrementAndGet();
						LOG.warn("Problem saving session " + session.getId(), e);
					}
				}
			}
    }
}
