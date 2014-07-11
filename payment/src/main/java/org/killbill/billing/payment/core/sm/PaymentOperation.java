/*
 * Copyright 2014 Groupon, Inc
 * Copyright 2014 The Billing Project, LLC
 *
 * Groupon licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.payment.core.sm;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.killbill.automaton.Operation.OperationCallback;
import org.killbill.automaton.OperationException;
import org.killbill.automaton.OperationResult;
import org.killbill.billing.ErrorCode;
import org.killbill.billing.osgi.api.OSGIServiceRegistration;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.TransactionStatus;
import org.killbill.billing.payment.api.TransactionType;
import org.killbill.billing.payment.core.ProcessorBase.WithAccountLockCallback;
import org.killbill.billing.payment.dao.PaymentTransactionModelDao;
import org.killbill.billing.payment.dispatcher.PluginDispatcher;
import org.killbill.billing.payment.plugin.api.PaymentPluginApi;
import org.killbill.billing.payment.plugin.api.PaymentPluginApiException;
import org.killbill.billing.payment.plugin.api.PaymentTransactionInfoPlugin;
import org.killbill.commons.locker.GlobalLocker;
import org.killbill.commons.locker.LockFailedException;

import com.google.common.base.Objects;

import com.google.common.base.Objects;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;

// Encapsulates the payment specific logic
public abstract class PaymentOperation extends OperationCallbackBase implements OperationCallback {

    protected final PaymentAutomatonDAOHelper daoHelper;
    protected PaymentPluginApi plugin;

    protected PaymentOperation(final GlobalLocker locker,
                               final PaymentAutomatonDAOHelper daoHelper,
                               final PluginDispatcher<OperationResult> paymentPluginDispatcher,
                               final PaymentStateContext paymentStateContext) {
        super(locker, paymentPluginDispatcher, paymentStateContext);
        this.daoHelper = daoHelper;
    }


    @Override
    public OperationResult doOperationCallback() throws OperationException {

        try {

            this.plugin = daoHelper.getPaymentProviderPlugin();

            if (paymentStateContext.shouldLockAccountAndDispatch()) {
                return doOperationCallbackWithDispatchAndAccountLock();
            } else {
                return doSimpleOperationCallback();
            }
        } catch (PaymentApiException e) {
            throw new OperationException(e, OperationResult.EXCEPTION);
        }
    }

    @Override
    protected OperationException rewrapExecutionException(final PaymentStateContext paymentStateContext, final ExecutionException e) {
        final Throwable realException = Objects.firstNonNull(e.getCause(), e);
        if (e.getCause() instanceof PaymentApiException) {
            logger.warn("Unsuccessful plugin call for account {}", paymentStateContext.getAccount().getExternalKey(), realException);
            return new OperationException(realException, OperationResult.FAILURE);
        } else if (e.getCause() instanceof LockFailedException) {
            final String format = String.format("Failed to lock account %s", paymentStateContext.getAccount().getExternalKey());
            logger.error(String.format(format), e);
            return new OperationException(realException, OperationResult.FAILURE);
        } else /* if (e instanceof RuntimeException) */ {
            logger.warn("Plugin call threw an exception for account {}", paymentStateContext.getAccount().getExternalKey(), e);
            return new OperationException(realException, OperationResult.EXCEPTION);
        }
    }

    @Override
    protected OperationException wrapTimeoutException(final PaymentStateContext paymentStateContext, final TimeoutException e) {
        logger.error("Plugin call TIMEOUT for account {}: {}", paymentStateContext.getAccount().getExternalKey(), e.getMessage());
        return new OperationException(e, OperationResult.EXCEPTION);
    }

    @Override
    protected OperationException wrapInterruptedException(final PaymentStateContext paymentStateContext, final InterruptedException e) {
        logger.error("Plugin call was interrupted for account {}: {}", paymentStateContext.getAccount().getExternalKey(), e.getMessage());
        return new OperationException(e, OperationResult.EXCEPTION);
    }

    @Override
    protected abstract PaymentTransactionInfoPlugin doCallSpecificOperationCallback() throws PaymentPluginApiException;

    protected Iterable<PaymentTransactionModelDao> getOnLeavingStateExistingTransactionsForType(final TransactionType transactionType) {
        if (paymentStateContext.getOnLeavingStateExistingTransactions() == null || paymentStateContext.getOnLeavingStateExistingTransactions().isEmpty()) {
            return ImmutableList.of();
        }
        return Iterables.filter(paymentStateContext.getOnLeavingStateExistingTransactions(), new Predicate<PaymentTransactionModelDao>() {
            @Override
            public boolean apply(final PaymentTransactionModelDao input) {
                return input.getTransactionStatus() == TransactionStatus.SUCCESS && input.getTransactionType() == transactionType;
            }
        });
    }

    protected BigDecimal getSumAmount(final Iterable<PaymentTransactionModelDao> transactions) {
        BigDecimal result = BigDecimal.ZERO;
        Iterator<PaymentTransactionModelDao> iterator = transactions.iterator();
        while (iterator.hasNext()) {
            result = result.add(iterator.next().getAmount());
        }
        return result;
    }

    private OperationResult doOperationCallbackWithDispatchAndAccountLock() throws OperationException {
        return dispatchWithAccountLockAndTimeout(new WithAccountLockCallback<OperationResult, OperationException>() {
            @Override
            public OperationResult doOperation() throws OperationException {
                return doSimpleOperationCallback();
            }
        });
    }

    private OperationResult doSimpleOperationCallback() throws OperationException {
        try {
            return doOperation();
        } catch (final PaymentApiException e) {
            throw new OperationException(e, OperationResult.FAILURE);
        } catch (RuntimeException e) {
            throw new OperationException(e, OperationResult.EXCEPTION);
        }
    }

    private OperationResult doOperation() throws PaymentApiException {
        try {
            final PaymentTransactionInfoPlugin paymentInfoPlugin = doCallSpecificOperationCallback();

            paymentStateContext.setPaymentInfoPlugin(paymentInfoPlugin);

            return processPaymentInfoPlugin();
        } catch (final PaymentPluginApiException e) {
            throw new PaymentApiException(ErrorCode.PAYMENT_PLUGIN_EXCEPTION, e.getErrorMessage());
        }
    }

    private OperationResult processPaymentInfoPlugin() {
        if (paymentStateContext.getPaymentInfoPlugin() == null) {
            return OperationResult.FAILURE;
        }

        switch (paymentStateContext.getPaymentInfoPlugin().getStatus()) {
            case PROCESSED:
                return OperationResult.SUCCESS;
            case PENDING:
                return OperationResult.PENDING;
            case ERROR:
            case UNDEFINED:
            default:
                return OperationResult.FAILURE;
        }
    }
}
