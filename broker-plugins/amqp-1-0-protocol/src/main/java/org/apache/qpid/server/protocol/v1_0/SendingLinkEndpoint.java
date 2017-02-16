/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */

package org.apache.qpid.server.protocol.v1_0;

import java.security.AccessControlException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.qpid.server.consumer.ConsumerOption;
import org.apache.qpid.server.filter.FilterManager;
import org.apache.qpid.server.filter.JMSSelectorFilter;
import org.apache.qpid.server.filter.SelectorParsingException;
import org.apache.qpid.server.filter.selector.ParseException;
import org.apache.qpid.server.filter.selector.TokenMgrError;
import org.apache.qpid.server.message.MessageInstance;
import org.apache.qpid.server.message.MessageInstanceConsumer;
import org.apache.qpid.server.message.MessageSource;
import org.apache.qpid.server.model.NamedAddressSpace;
import org.apache.qpid.server.model.NotFoundException;
import org.apache.qpid.server.model.Queue;
import org.apache.qpid.server.protocol.v1_0.type.AmqpErrorException;
import org.apache.qpid.server.protocol.v1_0.type.Binary;
import org.apache.qpid.server.protocol.v1_0.type.DeliveryState;
import org.apache.qpid.server.protocol.v1_0.type.Outcome;
import org.apache.qpid.server.protocol.v1_0.type.Symbol;
import org.apache.qpid.server.protocol.v1_0.type.UnsignedInteger;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Accepted;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Filter;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Modified;
import org.apache.qpid.server.protocol.v1_0.type.messaging.NoLocalFilter;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Released;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Source;
import org.apache.qpid.server.protocol.v1_0.type.messaging.StdDistMode;
import org.apache.qpid.server.protocol.v1_0.type.messaging.Target;
import org.apache.qpid.server.protocol.v1_0.type.messaging.TerminusDurability;
import org.apache.qpid.server.protocol.v1_0.type.transport.AmqpError;
import org.apache.qpid.server.protocol.v1_0.type.transport.Attach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Detach;
import org.apache.qpid.server.protocol.v1_0.type.transport.Error;
import org.apache.qpid.server.protocol.v1_0.type.transport.Flow;
import org.apache.qpid.server.protocol.v1_0.type.transport.Role;
import org.apache.qpid.server.protocol.v1_0.type.transport.Transfer;
import org.apache.qpid.server.txn.AutoCommitTransaction;
import org.apache.qpid.server.txn.ServerTransaction;
import org.apache.qpid.server.util.ConnectionScopedRuntimeException;
import org.apache.qpid.server.virtualhost.QueueManagingVirtualHost;

public class SendingLinkEndpoint extends LinkEndpoint<SendingLink_1_0>
{
    private static final Logger LOGGER = LoggerFactory.getLogger(SendingLinkEndpoint.class);

    public static final Symbol PRIORITY = Symbol.valueOf("priority");
    private UnsignedInteger _lastDeliveryId;
    private Binary _lastDeliveryTag;
    private Map<Binary, UnsignedInteger> _unsettledMap = new HashMap<>();
    private Map<Binary, MessageInstance> _unsettledMap2 = new HashMap<>();
    private Binary _transactionId;
    private Integer _priority;
    private final List<Binary> _resumeAcceptedTransfers = new ArrayList<>();
    private final List<MessageInstance> _resumeFullTransfers = new ArrayList<>();
    private volatile boolean _draining = false;
    private final ConcurrentMap<Binary, UnsettledAction> _unsettledActionMap = new ConcurrentHashMap<>();
    private TerminusDurability _durability;
    private SendingDestination _destination;
    private EnumSet<ConsumerOption> _consumerOptions;
    private FilterManager _consumerFilters;
    private ConsumerTarget_1_0 _consumerTarget;
    private MessageInstanceConsumer<ConsumerTarget_1_0> _consumer;

    public SendingLinkEndpoint(final Session_1_0 session, final Attach attach)
    {
        super(session, attach);
        setSendingSettlementMode(attach.getSndSettleMode());
        setReceivingSettlementMode(attach.getRcvSettleMode());
        init();
    }

    @Override
    public void start()
    {
    }

    public void doStuff(final SendingDestination destination) throws AmqpErrorException
    {
        _destination = destination;
        final Source source = (Source) getSource();

        EnumSet<ConsumerOption> options = EnumSet.noneOf(ConsumerOption.class);

        boolean noLocal = false;
        JMSSelectorFilter messageFilter = null;

        if(destination instanceof ExchangeDestination)
        {
            options.add(ConsumerOption.ACQUIRES);
            options.add(ConsumerOption.SEES_REQUEUES);
        }
        else if(destination instanceof MessageSourceDestination)
        {
            MessageSource messageSource = _destination.getMessageSource();

            if(messageSource instanceof Queue && ((Queue<?>)messageSource).getAvailableAttributes().contains("topic"))
            {
                source.setDistributionMode(StdDistMode.COPY);
            }

            Map<Symbol,Filter> filters = source.getFilter();

            Map<Symbol,Filter> actualFilters = new HashMap<Symbol,Filter>();

            if(filters != null)
            {
                for(Map.Entry<Symbol,Filter> entry : filters.entrySet())
                {
                    if(entry.getValue() instanceof NoLocalFilter)
                    {
                        actualFilters.put(entry.getKey(), entry.getValue());
                        noLocal = true;
                    }
                    else if(messageFilter == null && entry.getValue() instanceof org.apache.qpid.server.protocol.v1_0.type.messaging.JMSSelectorFilter)
                    {

                        org.apache.qpid.server.protocol.v1_0.type.messaging.JMSSelectorFilter selectorFilter = (org.apache.qpid.server.protocol.v1_0.type.messaging.JMSSelectorFilter) entry.getValue();
                        try
                        {
                            messageFilter = new JMSSelectorFilter(selectorFilter.getValue());

                            actualFilters.put(entry.getKey(), entry.getValue());
                        }
                        catch (ParseException | SelectorParsingException | TokenMgrError e)
                        {
                            Error error = new Error();
                            error.setCondition(AmqpError.INVALID_FIELD);
                            error.setDescription("Invalid JMS Selector: " + selectorFilter.getValue());
                            error.setInfo(Collections.singletonMap(Symbol.valueOf("field"), Symbol.valueOf("filter")));
                            throw new AmqpErrorException(error);
                        }


                    }
                }
            }
            source.setFilter(actualFilters.isEmpty() ? null : actualFilters);

            if(source.getDistributionMode() != StdDistMode.COPY)
            {
                options.add(ConsumerOption.ACQUIRES);
                options.add(ConsumerOption.SEES_REQUEUES);
            }
        }
        else
        {
            throw new ConnectionScopedRuntimeException("Unknown destination type");
        }
        if(noLocal)
        {
            options.add(ConsumerOption.NO_LOCAL);
        }

        FilterManager filters = null;
        if(messageFilter != null)
        {
            filters = new FilterManager();
            filters.add(messageFilter.getName(), messageFilter);
        }
        _consumerOptions = options;
        _consumerFilters = filters;
    }

    void createConsumerTarget() throws AmqpErrorException
    {
        final Source source = (Source) getSource();
        _consumerTarget = new ConsumerTarget_1_0(this,
                                         _destination instanceof ExchangeDestination ? true : source.getDistributionMode() != StdDistMode.COPY);
        try
        {
            final String name;
            if(getTarget() instanceof Target)
            {
                Target target = (Target) getTarget();
                name = target.getAddress() == null ? getName() : target.getAddress();
            }
            else
            {
                name = getName();
            }

            _consumer = _destination.getMessageSource()
                                    .addConsumer(_consumerTarget,
                                                 _consumerFilters,
                                                 Message_1_0.class,
                                                 name,
                                                 _consumerOptions,
                                                 getPriority());
            _consumerTarget.updateNotifyWorkDesired();
        }
        catch (MessageSource.ExistingExclusiveConsumer e)
        {
            String msg = "Cannot add a consumer to the destination as there is already an exclusive consumer";
            throw new AmqpErrorException(new Error(AmqpError.RESOURCE_LOCKED, msg), e);
        }
        catch (MessageSource.ExistingConsumerPreventsExclusive e)
        {
            String msg = "Cannot add an exclusive consumer to the destination as there is already a consumer";
            throw new AmqpErrorException(new Error(AmqpError.RESOURCE_LOCKED, msg), e);
        }
        catch (MessageSource.ConsumerAccessRefused e)
        {
            String msg = "Cannot add an exclusive consumer to the destination as there is an incompatible exclusivity policy";
            throw new AmqpErrorException(new Error(AmqpError.RESOURCE_LOCKED, msg), e);
        }
        catch (MessageSource.QueueDeleted e)
        {
            String msg = "Cannot add a consumer to the destination as the destination has been deleted";
            throw new AmqpErrorException(new Error(AmqpError.RESOURCE_DELETED, msg), e);
        }
    }


    @Override
    protected Map<Symbol, Object> initProperties(final Attach attach)
    {

        Map<Symbol, Object> peerProperties = attach.getProperties();
        if(peerProperties != null)
        {
            Map<Symbol, Object> actualProperties = new HashMap<>();
            if(peerProperties.containsKey(PRIORITY))
            {
                Object value = peerProperties.get(PRIORITY);
                if(value instanceof Number)
                {
                    _priority = ((Number)value).intValue();
                }
                else if(value instanceof String)
                {
                    try
                    {
                        _priority = Integer.parseInt(value.toString());
                    }
                    catch (NumberFormatException e)
                    {
                    }
                }
                if(_priority != null)
                {
                    actualProperties.put(PRIORITY, _priority);
                }
            }
            return actualProperties;
        }
        else
        {

            return Collections.emptyMap();
        }
    }

    private void init()
    {
        setDeliveryCount(UnsignedInteger.valueOf(0));
        setAvailable(UnsignedInteger.valueOf(0));
    }

    @Override
    public Role getRole()
    {
        return Role.SENDER;
    }

    public Integer getPriority()
    {
        return _priority;
    }

    public void setDurability(final TerminusDurability durability)
    {
        _durability = durability;
    }

    public boolean transfer(final Transfer xfr, final boolean decrementCredit)
    {
        Session_1_0 s = getSession();
        xfr.setMessageFormat(UnsignedInteger.ZERO);
        if(decrementCredit)
        {
            setLinkCredit(getLinkCredit().subtract(UnsignedInteger.ONE));
        }

        setDeliveryCount(UnsignedInteger.valueOf((getDeliveryCount().intValue() + 1)));

        xfr.setHandle(getLocalHandle());

        s.sendTransfer(xfr, this, !xfr.getDeliveryTag().equals(_lastDeliveryTag));

        if(!Boolean.TRUE.equals(xfr.getSettled()))
        {
            _unsettledMap.put(xfr.getDeliveryTag(), xfr.getDeliveryId());
        }

        if(Boolean.TRUE.equals(xfr.getMore()))
        {
            _lastDeliveryTag = xfr.getDeliveryTag();
        }
        else
        {
            _lastDeliveryTag = null;
        }

        return true;
    }


    public boolean drained()
    {
        if (_draining)
        {
            setDeliveryCount(getDeliveryCount().add(getLinkCredit()));
            setLinkCredit(UnsignedInteger.ZERO);
            sendFlow();
            _draining = false;
            return true;
        }
        else
        {
            return false;
        }
    }

    @Override
    public void receiveFlow(final Flow flow)
    {
        super.receiveFlow(flow);
        UnsignedInteger t = flow.getDeliveryCount();
        UnsignedInteger c = flow.getLinkCredit();
        setDrain(flow.getDrain());

        Map options;
        if((options = flow.getProperties()) != null)
        {
             _transactionId = (Binary) options.get(Symbol.valueOf("txn-id"));
        }

        if(t == null)
        {
            setLinkCredit(c);
        }
        else
        {
            UnsignedInteger limit = t.add(c);
            if(limit.compareTo(getDeliveryCount())<=0)
            {
                setLinkCredit(UnsignedInteger.valueOf(0));
            }
            else
            {
                setLinkCredit(limit.subtract(getDeliveryCount()));
            }
        }
        flowStateChanged();

    }

    @Override
    public void flowStateChanged()
    {
        if(Boolean.TRUE.equals(getDrain()) && getLinkCredit().compareTo(UnsignedInteger.ZERO) > 0)
        {
            _draining = true;
            getConsumerTarget().flush();
        }

        while(!_resumeAcceptedTransfers.isEmpty() && hasCreditToSend())
        {
            Accepted accepted = new Accepted();
            Transfer xfr = new Transfer();
            Binary dt = _resumeAcceptedTransfers.remove(0);
            xfr.setDeliveryTag(dt);
            xfr.setState(accepted);
            xfr.setResume(Boolean.TRUE);
            transfer(xfr, true);
            xfr.dispose();
        }
        if(_resumeAcceptedTransfers.isEmpty())
        {
            getConsumerTarget().flowStateChanged();
        }
    }


    @Override
    protected void remoteDetachedPerformDetach(final Detach detach)
    {
        getConsumerTarget().close();
        //TODO
        // if not durable or close
        if (Boolean.TRUE.equals(detach.getClosed())
            || !(TerminusDurability.UNSETTLED_STATE.equals(_durability) || TerminusDurability.CONFIGURATION.equals(
                _durability)))
        {

            Modified state = new Modified();
            state.setDeliveryFailed(true);

            for(UnsettledAction action : _unsettledActionMap.values())
            {
                action.process(state,Boolean.TRUE);
            }
            _unsettledActionMap.clear();

            close();

            if (getDestination() instanceof ExchangeDestination
               && (_durability == TerminusDurability.CONFIGURATION
                   || _durability == TerminusDurability.UNSETTLED_STATE))
            {
                try
                {
                    if (getSession().getConnection().getAddressSpace() instanceof QueueManagingVirtualHost)
                    {
                        ((QueueManagingVirtualHost) getSession().getConnection().getAddressSpace()).removeSubscriptionQueue(((ExchangeDestination) getDestination()).getQueue().getName());
                    }
                }
                catch (AccessControlException e)
                {
                    LOGGER.error("Error unregistering subscription", e);
                    detach(new Error(AmqpError.NOT_ALLOWED, "Error unregistering subscription"));
                }
                catch (IllegalStateException e)
                {
                    detach(new Error(AmqpError.RESOURCE_LOCKED, e.getMessage()));
                }
                catch (NotFoundException e)
                {
                    detach(new Error(AmqpError.NOT_FOUND, e.getMessage()));
                }
            }
        }
        else if (detach.getError() != null && !getSession().isSyntheticError(detach.getError()))
        {
            try
            {
                getLink().setLinkAttachment(null, null);
            }
            catch (AmqpErrorException e)
            {
                throw new ConnectionScopedRuntimeException(e);
            }
            getConsumerTarget().flowStateChanged();
            detach();
        }
        else
        {
            detach();
            getConsumerTarget().updateNotifyWorkDesired();
        }
    }

    public void addUnsettled(final Binary tag, final UnsettledAction unsettledAction, final MessageInstance queueEntry)
    {
        _unsettledActionMap.put(tag, unsettledAction);
        if(getTransactionId() == null)
        {
            _unsettledMap2.put(tag, queueEntry);
        }

    }

    @Override
    protected void handle(final Binary deliveryTag, final DeliveryState state, final Boolean settled)
    {
        UnsettledAction action = _unsettledActionMap.get(deliveryTag);
        boolean localSettle = false;
        if(action != null)
        {
            localSettle = action.process(state, settled);
            if(localSettle && !Boolean.TRUE.equals(settled))
            {
                updateDisposition(deliveryTag, state, true);
            }
        }
        if(Boolean.TRUE.equals(settled) || localSettle)
        {
            _unsettledActionMap.remove(deliveryTag);
            _unsettledMap.remove(deliveryTag);
            _unsettledMap2.remove(deliveryTag);
        }
    }

    public ServerTransaction getTransaction(Binary transactionId)
    {
        Session_1_0 session = getSession();
        return session == null ? null : session.getTransaction(transactionId);
    }

    public boolean hasCreditToSend()
    {
        UnsignedInteger linkCredit = getLinkCredit();
        return linkCredit != null && (linkCredit.compareTo(UnsignedInteger.valueOf(0)) > 0)
               && getSession().hasCreditToSend();
    }

    public UnsignedInteger getLastDeliveryId()
    {
        return _lastDeliveryId;
    }

    public void setLastDeliveryId(final UnsignedInteger deliveryId)
    {
        _lastDeliveryId = deliveryId;
    }

    public void updateDisposition(final Binary deliveryTag, DeliveryState state, boolean settled)
    {
        UnsignedInteger deliveryId;
        if (settled && (deliveryId = _unsettledMap.remove(deliveryTag)) != null)
        {
            _unsettledMap2.remove(deliveryTag);
            getSession().updateDisposition(getRole(), deliveryId, deliveryId, state, settled);
        }
    }

    public Binary getTransactionId()
    {
        return _transactionId;
    }

    public void doLinkAttachment(final Session_1_0 session, final MessageInstanceConsumer consumer) throws AmqpErrorException
    {
        if (session != null)
        {
            createConsumerTarget();

            setSession(session);
            _resumeAcceptedTransfers.clear();
            _resumeFullTransfers.clear();
            final NamedAddressSpace addressSpace = getSession().getConnection().getAddressSpace();
            Map<Binary, MessageInstance> unsettledCopy = new HashMap<>(_unsettledMap2);
            Map initialUnsettledMap = getInitialUnsettledMap();

            for (Map.Entry<Binary, MessageInstance> entry : unsettledCopy.entrySet())
            {
                Binary deliveryTag = entry.getKey();
                final MessageInstance queueEntry = entry.getValue();
                if (initialUnsettledMap == null || !initialUnsettledMap.containsKey(deliveryTag))
                {
                    queueEntry.setRedelivered();
                    queueEntry.release(consumer);
                    _unsettledMap2.remove(deliveryTag);
                }
                else if (initialUnsettledMap.get(deliveryTag) instanceof Outcome)
                {
                    Outcome outcome = (Outcome) initialUnsettledMap.get(deliveryTag);

                    if (outcome instanceof Accepted)
                    {
                        AutoCommitTransaction txn = new AutoCommitTransaction(addressSpace.getMessageStore());
                        if (consumer.acquires())
                        {
                            if (queueEntry.acquire() || queueEntry.isAcquired())
                            {
                                txn.dequeue(Collections.singleton(queueEntry),
                                            new ServerTransaction.Action()
                                            {
                                                public void postCommit()
                                                {
                                                    queueEntry.delete();
                                                }

                                                public void onRollback()
                                                {
                                                }
                                            });
                            }
                        }
                    }
                    else if (outcome instanceof Released)
                    {
                        AutoCommitTransaction txn = new AutoCommitTransaction(addressSpace.getMessageStore());
                        if (consumer.acquires())
                        {
                            txn.dequeue(Collections.singleton(queueEntry),
                                        new ServerTransaction.Action()
                                        {
                                            public void postCommit()
                                            {
                                                queueEntry.release(consumer);
                                            }

                                            public void onRollback()
                                            {
                                            }
                                        });
                        }
                    }
                    //_unsettledMap.remove(deliveryTag);
                    initialUnsettledMap.remove(deliveryTag);
                    _resumeAcceptedTransfers.add(deliveryTag);
                }
                else
                {
                    _resumeFullTransfers.add(queueEntry);
                    // exists in receivers map, but not yet got an outcome ... should resend with resume = true
                }
                // TODO - else
            }
        }

        getConsumerTarget().updateNotifyWorkDesired();
    }

    public Map<Binary, MessageInstance> getUnsettledOutcomeMap()
    {
        Map<Binary, MessageInstance> unsettled = new HashMap<>(_unsettledMap2);

        for (Map.Entry<Binary, MessageInstance> entry : unsettled.entrySet())
        {
            entry.setValue(null);
        }

        return unsettled;
    }

    public MessageInstanceConsumer<ConsumerTarget_1_0> getConsumer()
    {
        return _consumer;
    }

    public ConsumerTarget_1_0 getConsumerTarget()
    {
        return _consumerTarget;
    }

    public SendingDestination getDestination()
    {
        return _destination;
    }

    public void setDestination(final SendingDestination destination)
    {
        _destination = destination;
    }
}
