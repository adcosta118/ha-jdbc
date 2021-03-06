/*
 * HA-JDBC: High-Availability JDBC
 * Copyright (C) 2012  Paul Ferraro
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.sf.hajdbc.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.SortedMap;
import java.util.concurrent.locks.Lock;

import net.sf.hajdbc.Database;
import net.sf.hajdbc.DatabaseCluster;
import net.sf.hajdbc.ExceptionType;
import net.sf.hajdbc.durability.Durability;
import net.sf.hajdbc.invocation.InvocationStrategy;
import net.sf.hajdbc.invocation.InvokeOnContextInvocationStrategy;
import net.sf.hajdbc.invocation.Invoker;
import net.sf.hajdbc.tx.TransactionIdentifierFactory;

/**
 * @author Paul Ferraro, André Costa
 * @param <Z>
 * @param <D>
 */
public class LocalTransactionContext<Z, D extends Database<Z>> implements TransactionContext<Z, D>
{
	final Durability<Z, D> durability;
	private final Lock lock;
	private final TransactionIdentifierFactory<? extends Object> transactionIdFactory;
	volatile Object transactionId;
	private D database;
	
	/**
	 * @param cluster
	 */
	public LocalTransactionContext(DatabaseCluster<Z, D> cluster)
	{
		this.lock = cluster.getLockManager().readLock(null);
		this.durability = cluster.getDurability();
		this.transactionIdFactory = cluster.getTransactionIdentifierFactory();
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.sql.TransactionContext#start(net.sf.hajdbc.invocation.InvocationStrategy, java.sql.Connection)
	 */
	@Override
	public InvocationStrategy start(final InvocationStrategy strategy, final Connection connection) throws SQLException
	{
		if (this.transactionId != null)
		{
			return strategy;
		}
		
		if (connection.getAutoCommit())
		{
			return new InvocationStrategy()
			{
				@Override
				public <ZZ, DD extends Database<ZZ>, T, R, E extends Exception> SortedMap<DD, R> invoke(ProxyFactory<ZZ, DD, T, E> proxy, Invoker<ZZ, DD, T, R, E> invoker) throws E
				{
					LocalTransactionContext.this.lock();
					
					try
					{
						InvocationStrategy durabilityStrategy = LocalTransactionContext.this.durability.getInvocationStrategy(strategy, Durability.Phase.COMMIT, LocalTransactionContext.this.transactionId);
						
						return durabilityStrategy.invoke(proxy, invoker);
					}
					finally
					{
						LocalTransactionContext.this.unlock();
					}
				}
			};
		}

		return new InvocationStrategy()
		{
			@SuppressWarnings("unchecked")
			@Override
			public <ZZ, DD extends Database<ZZ>, T, R, E extends Exception> SortedMap<DD, R> invoke(ProxyFactory<ZZ, DD, T, E> proxy, Invoker<ZZ, DD, T, R, E> invoker) throws E
			{
				LocalTransactionContext.this.lock();
				
				try
				{	
					D database = LocalTransactionContext.this.database;
					if(database == null)
					{
						SortedMap<DD,R> resultMap = strategy.invoke(proxy, invoker);
						LocalTransactionContext.this.database = (D) resultMap.firstKey();
						return resultMap;
					}
					else
					{
						return new InvokeOnContextInvocationStrategy<Z, D>(database).invoke(proxy, invoker);
					}
				}
				catch (Throwable e)
				{
					throw proxy.getExceptionFactory().createException(e);
				} 
				finally 
				{
					LocalTransactionContext.this.unlock();
				}
			}
		};
	}
	
	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.sql.TransactionContext#start(net.sf.hajdbc.invocation.Invoker, java.sql.Connection)
	 */
	@Override
	public <T, R> Invoker<Z, D, T, R, SQLException> start(final Invoker<Z, D, T, R, SQLException> invoker, Connection connection) throws SQLException
	{
		if ((this.transactionId == null) || !connection.getAutoCommit()) return invoker;

		return new Invoker<Z, D, T, R, SQLException>()
		{
			@Override
			public R invoke(D database, T object) throws SQLException
			{
				return LocalTransactionContext.this.durability.getInvoker(invoker, Durability.Phase.COMMIT, LocalTransactionContext.this.transactionId, ExceptionType.SQL.<SQLException>getExceptionFactory()).invoke(database, object);
			}
		};
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.sql.TransactionContext#end(net.sf.hajdbc.invocation.InvocationStrategy, net.sf.hajdbc.durability.Durability.Phase)
	 */
	@Override
	public InvocationStrategy end(final InvocationStrategy strategy, final Durability.Phase phase)
	{
		/*
		if (this.transactionId == null)
		{
			return strategy;
		}
		*/

		return new InvocationStrategy()
		{
			@Override
			public <ZZ, DD extends Database<ZZ>, T, R, E extends Exception> SortedMap<DD, R> invoke(ProxyFactory<ZZ, DD, T, E> proxy, Invoker<ZZ, DD, T, R, E> invoker) throws E
			{
				LocalTransactionContext.this.lock();
				try
				{
					D database = LocalTransactionContext.this.database;

					InvocationStrategy usedStrategy = (database == null) ? strategy : new InvokeOnContextInvocationStrategy<Z, D>(database);
					InvocationStrategy durabilityStrategy = LocalTransactionContext.this.durability.getInvocationStrategy(usedStrategy, phase, LocalTransactionContext.this.transactionId);
					return durabilityStrategy.invoke(proxy, invoker);
				}
				finally
				{
					LocalTransactionContext.this.database = null;
					LocalTransactionContext.this.unlock();
				}
			}
		};
	}

	/**
	 * {@inheritDoc}
	 * @see net.sf.hajdbc.sql.TransactionContext#end(net.sf.hajdbc.invocation.Invoker, net.sf.hajdbc.durability.Durability.Phase)
	 */
	@Override
	public <T, R> Invoker<Z, D, T, R, SQLException> end(final Invoker<Z, D, T, R, SQLException> invoker, Durability.Phase phase)
	{
		//LocalTransactionContext.this.database = null;
		
		if (this.transactionId == null) return invoker;

		return this.durability.getInvoker(invoker, phase, this.transactionId, ExceptionType.SQL.<SQLException>getExceptionFactory());
	}

	/**
	 * @see net.sf.hajdbc.sql.TransactionContext#close()
	 */
	@Override
	public void close()
	{
		LocalTransactionContext.this.database = null;
		
		// Tsk, tsk... User neglected to commit/rollback transaction
		if (this.transactionId != null)
		{
			this.unlock();
		}
	}

	void lock()
	{
		this.lock.lock();
		this.transactionId = this.transactionIdFactory.createTransactionIdentifier();
	}
	
	void unlock()
	{
		this.lock.unlock();
		this.transactionId = null;
	}
}