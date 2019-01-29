/*
 * Copyright 2002-2017 the original author or authors.
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

package org.springframework.transaction.support;

import org.springframework.lang.Nullable;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.SavepointManager;
import org.springframework.util.Assert;

/**
 * Default implementation of the {@link org.springframework.transaction.TransactionStatus}
 * interface, used by {@link AbstractPlatformTransactionManager}. Based on the concept
 * of an underlying "transaction object".
 *
 * <p>Holds all status information that {@link AbstractPlatformTransactionManager}
 * needs internally, including a generic transaction object determined by the
 * concrete transaction manager implementation.
 *
 * <p>Supports delegating savepoint-related methods to a transaction object
 * that implements the {@link SavepointManager} interface.
 *
 * <p><b>NOTE:</b> This is <i>not</i> intended for use with other PlatformTransactionManager
 * implementations, in particular not for mock transaction managers in testing environments.
 * Use the alternative {@link SimpleTransactionStatus} class or a mock for the plain
 * {@link org.springframework.transaction.TransactionStatus} interface instead.
 *
 * @author Juergen Hoeller
 * @since 19.01.2004
 * @see AbstractPlatformTransactionManager
 * @see org.springframework.transaction.SavepointManager
 * @see #getTransaction
 * @see #createSavepoint
 * @see #rollbackToSavepoint
 * @see #releaseSavepoint
 * @see SimpleTransactionStatus
 */
public class DefaultTransactionStatus extends AbstractTransactionStatus {
	/**
	 * 事务对象 DataSourceTransactionObject，不是新建事务时就为空
	 */
	@Nullable
	private final Object transaction;
	/**
	 * 是否新建事务，新建事务时为true
	 */
	private final boolean newTransaction;
	/**
	 * 是否新同步，在新建事务时为true
	 */
	private final boolean newSynchronization;
	/**
	 * 是否只读
	 */
	private final boolean readOnly;

	private final boolean debug;
	/**
	 * 挂起的事务资源，这个用于在执行完当前事务后恢复之前挂起的事务
	 * SuspendedResourcesHolder
	 */
	@Nullable
	private final Object suspendedResources;


	/**
	 * 创建一个默认的事务状态对象
	 * @param transaction 事务对象 DataSourceTransactionObject
	 * @param newTransaction newTransaction 是否新建事务
	 * @param newSynchronization 是否新同步
	 * @param readOnly 是否只读
	 * @param debug
	 * @param suspendedResources 挂起的事务对象
	 */
	public DefaultTransactionStatus(
			@Nullable Object transaction, boolean newTransaction, boolean newSynchronization,
			boolean readOnly, boolean debug, @Nullable Object suspendedResources) {

		this.transaction = transaction;
		this.newTransaction = newTransaction;
		this.newSynchronization = newSynchronization;
		this.readOnly = readOnly;
		this.debug = debug;
		this.suspendedResources = suspendedResources;
	}


	/**
	 * Return the underlying transaction object.
	 * @throws IllegalStateException if no transaction is active
	 */
	public Object getTransaction() {
		Assert.state(this.transaction != null, "No transaction active");
		return this.transaction;
	}

	/**
	 * Return whether there is an actual transaction active.
	 */
	public boolean hasTransaction() {
		return (this.transaction != null);
	}

	@Override
	public boolean isNewTransaction() {
		return (hasTransaction() && this.newTransaction);
	}

	/**
	 * Return if a new transaction synchronization has been opened
	 * for this transaction.
	 */
	public boolean isNewSynchronization() {
		return this.newSynchronization;
	}

	/**
	 * Return if this transaction is defined as read-only transaction.
	 */
	public boolean isReadOnly() {
		return this.readOnly;
	}

	/**
	 * Return whether the progress of this transaction is debugged. This is used
	 * by AbstractPlatformTransactionManager as an optimization, to prevent repeated
	 * calls to logger.isDebug(). Not really intended for client code.
	 */
	public boolean isDebug() {
		return this.debug;
	}

	/**
	 * Return the holder for resources that have been suspended for this transaction,
	 * if any.
	 */
	@Nullable
	public Object getSuspendedResources() {
		return this.suspendedResources;
	}


	//---------------------------------------------------------------------
	// Enable functionality through underlying transaction object
	//---------------------------------------------------------------------

	/**
	 * Determine the rollback-only flag via checking both the transaction object,
	 * provided that the latter implements the {@link SmartTransactionObject} interface.
	 * <p>Will return "true" if the transaction itself has been marked rollback-only
	 * by the transaction coordinator, for example in case of a timeout.
	 * @see SmartTransactionObject#isRollbackOnly
	 */
	@Override
	public boolean isGlobalRollbackOnly() {
		return ((this.transaction instanceof SmartTransactionObject) &&
				((SmartTransactionObject) this.transaction).isRollbackOnly());
	}

	/**
	 * Delegate the flushing to the transaction object,
	 * provided that the latter implements the {@link SmartTransactionObject} interface.
	 */
	@Override
	public void flush() {
		if (this.transaction instanceof SmartTransactionObject) {
			((SmartTransactionObject) this.transaction).flush();
		}
	}

	/**
	 * This implementation exposes the SavepointManager interface
	 * of the underlying transaction object, if any.
	 */
	@Override
	protected SavepointManager getSavepointManager() {
		Object transaction = this.transaction;
		if (!(transaction instanceof SavepointManager)) {
			throw new NestedTransactionNotSupportedException(
				"Transaction object [" + this.transaction + "] does not support savepoints");
		}
		return (SavepointManager) transaction;
	}

	/**
	 * Return whether the underlying transaction implements the
	 * SavepointManager interface.
	 * @see #getTransaction
	 * @see org.springframework.transaction.SavepointManager
	 */
	public boolean isTransactionSavepointManager() {
		return (this.transaction instanceof SavepointManager);
	}

}
