/*
 * Copyright (c) 2004-2007, Identity Theft 911, LLC.  All rights reserved.
 */
package net.sf.hajdbc.distributable;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

/**
 * @author Paul Ferraro
 */
public class DistributableLockAdapter implements Lock
{
	private LockThread thread;
	
	public DistributableLockAdapter(Lock lock)
	{
		this.thread = new LockThread(lock);
		this.thread.setDaemon(true);
	}
	
	/**
	 * @see java.util.concurrent.locks.Lock#lock()
	 */
	@Override
	public void lock()
	{
		LockMethod method = new LockMethod()
		{
			@Override
			public boolean lock(Lock lock)
			{
				lock.lock();
				
				return true;
			}
		};
		
		this.thread.setMethod(method);
		this.thread.start();
		
		while (!this.thread.isReady())
		{
			try
			{
				synchronized (this.thread)
				{
					this.thread.wait();
				}
			}
			catch (InterruptedException e)
			{
				this.thread.interrupt();
			}
		}
	}

	/**
	 * @see java.util.concurrent.locks.Lock#lockInterruptibly()
	 */
	@Override
	public void lockInterruptibly() throws InterruptedException
	{
		LockMethod method = new LockMethod()
		{
			@Override
			public boolean lock(Lock lock) throws InterruptedException
			{
				lock.lockInterruptibly();
				
				return true;
			}
		};
		
		this.thread.setMethod(method);
		this.thread.start();
		
		while (!this.thread.isReady())
		{
			try
			{
				synchronized (this.thread)
				{
					this.thread.wait();
				}
			}
			catch (InterruptedException e)
			{
				this.thread.interrupt();
				
				throw e;
			}
		}
	}

	/**
	 * @see java.util.concurrent.locks.Lock#tryLock()
	 */
	@Override
	public boolean tryLock()
	{
		LockMethod method = new LockMethod()
		{
			@Override
			public boolean lock(Lock lock)
			{
				return lock.tryLock();
			}
		};
		
		this.thread.setMethod(method);
		this.thread.start();
		
		while (!this.thread.isReady())
		{
			try
			{
				synchronized (this.thread)
				{
					this.thread.wait();
				}
			}
			catch (InterruptedException e)
			{
				this.thread.interrupt();
			}
		}

		return this.thread.isLocked();
	}

	/**
	 * @see java.util.concurrent.locks.Lock#tryLock(long, java.util.concurrent.TimeUnit)
	 */
	@Override
	public boolean tryLock(final long time, final TimeUnit unit) throws InterruptedException
	{
		LockMethod method = new LockMethod()
		{
			@Override
			public boolean lock(Lock lock) throws InterruptedException
			{
				return lock.tryLock(time, unit);
			}
		};
		
		this.thread.setMethod(method);
		this.thread.start();
		
		while (!this.thread.isReady())
		{
			try
			{
				synchronized (this.thread)
				{
					this.thread.wait();
				}
			}
			catch (InterruptedException e)
			{
				this.thread.interrupt();
				
				throw e;
			}
		}
		
		return this.thread.isLocked();
	}

	/**
	 * @see java.util.concurrent.locks.Lock#unlock()
	 */
	@Override
	public void unlock()
	{
		if (this.thread.isLocked())
		{
			this.thread.interrupt();
		}
	}

	/**
	 * @see java.util.concurrent.locks.Lock#newCondition()
	 */
	@Override
	public Condition newCondition()
	{
		return this.thread.newCondition();
	}
	
	/**
	 * Thread that locks the specified lock upon starting.
	 * Lock is unlocked by interrupting the running thread.
	 * Caller must call setMethod(...) before starting.
	 */
	private class LockThread extends Thread
	{
		private Lock lock;
		private volatile LockMethod method;
		private volatile boolean ready = false;
		private volatile boolean locked = false;
		
		public LockThread(Lock lock)
		{
			super();
			
			this.lock = lock;
		}
		
		public void setMethod(LockMethod method)
		{
			this.method = method;
		}
		
		public boolean isLocked()
		{
			return this.locked;
		}
		
		public boolean isReady()
		{
			return this.ready;
		}
		
		public Condition newCondition()
		{
			return this.lock.newCondition();
		}
		
		/**
		 * @see java.lang.Runnable#run()
		 */
		@Override
		public void run()
		{
			try
			{
				this.locked = this.method.lock(this.lock);
			}
			catch (InterruptedException e)
			{
				this.interrupt();
			}
			
			this.ready = true;
			
			synchronized (this)
			{
				this.notify();
			}
			
			if (this.locked)
			{
				// Wait for interrupt
				while (!this.isInterrupted())
				{
					try
					{
						synchronized (this)
						{
							this.wait();
						}
					}
					catch (InterruptedException e)
					{
						this.interrupt();
					}
				}
				
				this.lock.unlock();
				this.locked = false;
			}
		}
	}

	private interface LockMethod
	{
		public boolean lock(Lock lock) throws InterruptedException;
	}
}